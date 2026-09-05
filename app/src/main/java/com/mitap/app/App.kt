package com.mitap.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// ==========================================================
// MainActivity.kt
// ==========================================================
/**
 * Экран настроек и управления. Намеренно на классических View (без Compose) — меньше
 * зависимостей и рисков сборки. Связывает уже написанные классы:
 * RecordingService, CallRecordingService (через prefs), StorageLocation, YandexAuth.
 *
 * Для реальной авторизации Яндекса заполните YANDEX_CLIENT_ID и секрет (см. handleYandexRedirect).
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private lateinit var folderLabel: TextView

    private val reqPerms = 1
    private val reqFolder = 2

    // TODO: значения из приложения на https://oauth.yandex.ru/client/new
    private val yandexClientId = ""
    private val yandexClientSecret = ""
    private val yandexRedirect = "mitap://yandex/auth"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNeededPermissions()
        handleYandexRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleYandexRedirect(intent)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        fun title(t: String) = TextView(ctx).apply {
            text = t; textSize = 16f; setPadding(0, dp(16), 0, dp(4))
        }
        fun hint(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(2), 0, dp(4))
        }
        fun button(t: String, onClick: () -> Unit) = Button(ctx).apply {
            text = t; setOnClickListener { onClick() }
        }
        fun toggle(t: String, key: String, def: Boolean) = Switch(ctx).apply {
            text = t
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        }

        root.addView(TextView(ctx).apply { text = "Митап"; textSize = 22f })

        // Встреча
        root.addView(title("Встреча"))
        root.addView(button("Начать запись встречи") {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java))
        })
        root.addView(button("Остановить") {
            startService(Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        })

        // Звонки
        root.addView(title("Автозапись звонков"))
        root.addView(toggle("Записывать звонки автоматически", "auto_record_calls", false))
        root.addView(hint(
            "Пишется микрофон: ваша сторона всегда, собеседник — на громкой связи. " +
                "Записывайте только с согласия участников."
        ))

        // Хранилище
        root.addView(title("Папка для записей"))
        folderLabel = TextView(ctx).apply { text = StorageLocation.currentLocationLabel(ctx) }
        root.addView(button("Выбрать папку") {
            startActivityForResult(StorageLocation.pickFolderIntent(), reqFolder)
        })
        root.addView(folderLabel)

        // Яндекс.Диск
        root.addView(title("Яндекс.Диск"))
        root.addView(button("Подключить Яндекс") {
            if (yandexClientId.isBlank()) toast("Укажите yandexClientId в MainActivity")
            else YandexAuth.openLogin(ctx, yandexClientId, yandexRedirect, state = "mitap")
        })
        root.addView(toggle("Сразу загружать записи на Диск", "yandex_auto_upload", false))
        root.addView(EditText(ctx).apply {
            hint = "Папка на Диске, напр. Митап"
            setText(prefs.getString("yandex_folder", "Митап"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("yandex_folder", s?.toString().orEmpty().ifBlank { "Митап" }).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        return ScrollView(ctx).apply { addView(root) }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), reqPerms)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqFolder && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                StorageLocation.persist(this, uri)
                folderLabel.text = StorageLocation.currentLocationLabel(this)
            }
        }
    }

    private fun handleYandexRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "mitap") return
        val code = data.getQueryParameter("code") ?: return
        thread {
            val token = YandexAuth.exchangeCode(yandexClientId, yandexClientSecret, code, yandexRedirect)
            runOnUiThread {
                if (token != null) { YandexAuth.saveToken(this, token); toast("Яндекс подключён") }
                else toast("Не удалось подключить Яндекс")
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}

// ==========================================================
// RecordingService.kt
// ==========================================================
/**
 * Foreground-сервис записи. Главный источник кросс-версионных багов на Android:
 * поведение разбито по уровням API. Подробности и матрица — в COMPATIBILITY.md.
 *
 * Важно по жизненному циклу:
 *  - BUG-C07: с API 31 нельзя стартовать FGS из фона — сервис запускается ТОЛЬКО из видимой Activity.
 *  - BUG-C06: с API 34 при типе microphone разрешение RECORD_AUDIO должно быть выдано ДО
 *    startForeground(), иначе SecurityException. Проверяйте Permissions.granted() перед startService().
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.mitap.app.action.STOP"
    }

    private var capture: AudioCapture? = null

    override fun onCreate() {
        super.onCreate()
        createChannel() // BUG-C01: с API 26 без канала уведомление не покажется и FGS упадёт
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        capture = AudioCapture(applicationContext).also { it.start() }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        // BUG-C02: startForeground с типом доступен с API 29; на API 34 тип обязателен.
        // На API 26–28 типа нет — используем двухаргументный вызов.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        // BUG-C03: с API 31 PendingIntent обязан быть mutable/immutable.
        // FLAG_IMMUTABLE доступен с API 23 — ставим всегда, это корректно для всех наших версий.
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), piFlags
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), piFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Идёт запись встречи")
            .setContentText("Нажмите, чтобы открыть")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись встречи",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Уведомление во время записи разговора" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        capture?.stop()
        capture = null
        // STOP_FOREGROUND_REMOVE доступен с API 24; minSdk 29 — безопасно.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ==========================================================
// CallRecordingService.kt
// ==========================================================
/**
 * Запись во время звонка. Захват — с микрофона (VOICE_COMMUNICATION), т.к. канал разговора
 * недоступен сторонним приложениям (COMPATIBILITY.md, C19). По завершении файл сохраняется
 * в выбранную папку и, если включено, ставится в очередь на выгрузку в Яндекс.Диск.
 */
class CallRecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "call_recording"
        const val NOTIF_ID = 1002
        const val ACTION_START = "com.mitap.app.call.START"
        const val ACTION_STOP = "com.mitap.app.call.STOP"
    }

    private var capture: AudioCapture? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { thread { finishAndUpload(); stopSelf() }; return START_NOT_STICKY }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (capture != null) return
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else
            startForeground(NOTIF_ID, notif)
        // VOICE_COMMUNICATION — лучший доступный источник для разговорного тракта
        capture = AudioCapture(applicationContext, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .also { it.start() }
    }

    private fun finishAndUpload() {
        val temp = capture?.also { it.stop() }?.outputFile
        capture = null
        if (temp != null && temp.exists()) {
            val name = "call_${System.currentTimeMillis()}.wav"
            val uri = StorageLocation.saveRecording(applicationContext, temp, name)
            YandexUploadWorker.enqueueIfEnabled(applicationContext, uri, name)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, CallRecordingService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Запись звонка")
            .setContentText("Пишется микрофон · нажмите, чтобы остановить")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Запись звонка", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        capture?.stop(); capture = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ==========================================================
// PhoneStateReceiver.kt
// ==========================================================
/**
 * Автоопределение звонка без нажатий: ловим смену состояния телефона и запускаем/останавливаем
 * запись. Честно: пишется МИКРОФОН (ваша сторона всегда, собеседник — на громкой связи);
 * канал самого разговора Android сторонним приложениям не отдаёт (см. COMPATIBILITY.md, C19).
 *
 * C07: на Android 12+ старт foreground-сервиса из фонового приёмника может блокироваться —
 * тогда автозапуск работает при активном сервисе-слушателе или при роли call-screening/дозвонщика.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val enabled = context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("auto_record_calls", false)
        if (!enabled) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallRecordingService::class.java)
                        .setAction(CallRecordingService.ACTION_START)
                )
            TelephonyManager.EXTRA_STATE_IDLE ->
                context.startService(
                    Intent(context, CallRecordingService::class.java)
                        .setAction(CallRecordingService.ACTION_STOP)
                )
        }
    }
}

// ==========================================================
// AudioCapture.kt
// ==========================================================
/**
 * Захват PCM 16 кГц mono через AudioRecord и запись в WAV во временный app-private файл.
 * Готовый файл переносится в выбранную папку слоем StorageLocation (после «Стоп»).
 *
 * Параметры настроек:
 *  - sourceId         — выбранный источник (см. AudioSources.captureSources), по умолчанию VOICE_RECOGNITION.
 *  - preferredDeviceId — id физического входа (см. AudioSources.inputDevices) или null (системный выбор).
 *
 * WAV, а не Opus: BUG-C08 — MediaRecorder OPUS/OGG доступен только с API 29; WAV пишется на всех версиях.
 * Эффекты AEC/NS/AGC отключаем: BUG-C11 — они мешают диаризации.
 */
class AudioCapture(
    private val context: Context,
    private val sourceId: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val preferredDeviceId: Int? = null
) {
    private val sampleRate = 16_000
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var scoStarted = false

    lateinit var outputFile: File
        private set

    @SuppressLint("MissingPermission") // RECORD_AUDIO проверяется до старта сервиса (BUG-C06)
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else sampleRate * 2

        val ar = AudioRecord(
            sourceId,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        disableEffects(ar.audioSessionId)
        routeToPreferredDevice(ar)          // BUG-C15/C16
        record = ar

        outputFile = File(context.cacheDir, "meeting_${System.currentTimeMillis()}.wav")

        ar.startRecording()
        running = true
        worker = thread(name = "audio-capture") { writeWav(ar, bufSize) }
    }

    /** BUG-C15/C16: направить запись на выбранный вход; для Bluetooth включить SCO с учётом версии. */
    @Suppress("DEPRECATION")
    private fun routeToPreferredDevice(ar: AudioRecord) {
        val deviceId = preferredDeviceId ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId } ?: return

        if (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.setCommunicationDevice(dev)       // API 31+
                } else {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                scoStarted = true
            }
        }
        runCatching { ar.setPreferredDevice(dev) }        // API 23+
    }

    private fun disableEffects(sessionId: Int) {
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.setEnabled(false) }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.setEnabled(false) }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.setEnabled(false) }
    }

    private fun writeWav(ar: AudioRecord, bufSize: Int) {
        RandomAccessFile(outputFile, "rw").use { raf ->
            writeWavHeader(raf, 0)
            val buffer = ByteArray(bufSize)
            var total = 0L
            while (running) {
                val n = ar.read(buffer, 0, buffer.size)
                if (n > 0) { raf.write(buffer, 0, n); total += n }
            }
            raf.seek(0); writeWavHeader(raf, total)
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Long) {
        val channels = 1; val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val h = ByteArray(44)
        fun str(o: Int, s: String) { for (i in s.indices) h[o + i] = s[i].code.toByte() }
        fun i32(o: Int, v: Int) {
            h[o] = (v and 0xff).toByte(); h[o + 1] = ((v shr 8) and 0xff).toByte()
            h[o + 2] = ((v shr 16) and 0xff).toByte(); h[o + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun i16(o: Int, v: Int) { h[o] = (v and 0xff).toByte(); h[o + 1] = ((v shr 8) and 0xff).toByte() }
        str(0, "RIFF"); i32(4, (36 + dataLen).toInt()); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels)
        i32(24, sampleRate); i32(28, byteRate); i16(32, channels * bits / 8); i16(34, bits)
        str(36, "data"); i32(40, dataLen.toInt())
        raf.write(h)
    }

    @Suppress("DEPRECATION")
    fun stop() {
        running = false
        worker?.join(500)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        if (scoStarted) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
                else { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
            }
            scoStarted = false
        }
    }
}

// ==========================================================
// AudioSources.kt
// ==========================================================
/**
 * Перечисление доступных источников звука на текущем устройстве — для меню «Источник звука».
 *
 * Что показываем:
 *  1) Логические источники записи (MediaRecorder.AudioSource) с учётом доступности на устройстве.
 *  2) Физические входы (встроенный микрофон, гарнитура, Bluetooth, USB) через AudioManager.
 *
 * Честно про «все системные каналы»: канал самого телефонного звонка
 * (VOICE_CALL/VOICE_UPLINK/VOICE_DOWNLINK) и системный микс (REMOTE_SUBMIX) Android
 * НЕ отдаёт сторонним приложениям — они помечены available=false с причиной.
 */
object AudioSources {

    data class SourceOption(
        val id: Int,          // значение MediaRecorder.AudioSource
        val key: String,
        val label: String,
        val available: Boolean,
        val reason: String? = null,
        val recommended: Boolean = false
    )

    data class InputDevice(
        val id: Int,          // AudioDeviceInfo.getId()
        val type: Int,
        val label: String
    )

    /** Логические источники записи с вычисленной доступностью. */
    fun captureSources(context: Context): List<SourceOption> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sdk = Build.VERSION.SDK_INT
        val unprocessed =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        fun opt(id: Int, key: String, label: String, available: Boolean = true,
                reason: String? = null, recommended: Boolean = false) =
            SourceOption(id, key, label, available, reason, recommended)

        return listOf(
            opt(MediaRecorder.AudioSource.MIC, "MIC", "Микрофон"),
            opt(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION",
                "Распознавание речи", recommended = true),
            opt(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION",
                "Голосовая связь (с эхоподавлением)"),
            opt(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER", "Камера/видеозапись"),
            // BUG-C17: UNPROCESSED (API 24+) только если устройство сообщает о поддержке
            opt(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", "Необработанный звук",
                available = unprocessed,
                reason = if (!unprocessed) "Устройство не поддерживает" else null),
            // BUG-C17: VOICE_PERFORMANCE — API 29+
            opt(if (sdk >= Build.VERSION_CODES.Q) MediaRecorder.AudioSource.VOICE_PERFORMANCE else 10,
                "VOICE_PERFORMANCE", "Караоке/выступление",
                available = sdk >= Build.VERSION_CODES.Q,
                reason = if (sdk < Build.VERSION_CODES.Q) "Требуется Android 10+" else null),
            // Недоступно сторонним приложениям:
            opt(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", "Аудио телефонного звонка",
                available = false, reason = "Android не отдаёт сторонним приложениям"),
            opt(MediaRecorder.AudioSource.VOICE_UPLINK, "VOICE_UPLINK", "Исходящий канал звонка",
                available = false, reason = "Только системным приложениям"),
            opt(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", "Входящий канал звонка",
                available = false, reason = "Только системным приложениям"),
            opt(MediaRecorder.AudioSource.REMOTE_SUBMIX, "REMOTE_SUBMIX", "Системный микс",
                available = false, reason = "Только системным приложениям")
        )
    }

    /** Физические входные устройства (BUG-C15: AudioManager.getDevices, API 23+). */
    fun inputDevices(context: Context): List<InputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY } // канал звонка недоступен
            .map { InputDevice(it.id, it.type, labelForDevice(it)) }
    }

    private fun labelForDevice(d: AudioDeviceInfo): String {
        val base = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Встроенный микрофон"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Проводная гарнитура"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth-гарнитура"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-устройство"
            else -> "Аудиовход"
        }
        // productName доступен с API 30; на 12+ для BT-имени нужен BLUETOOTH_CONNECT
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.productName?.toString() else null
        return if (!name.isNullOrBlank() && name != base) "$base · $name" else base
    }
}

// ==========================================================
// StorageLocation.kt
// ==========================================================
/**
 * Папка для сохранения записей. По умолчанию — приватная папка приложения; пользователь может
 * выбрать любую папку через системный диалог (SAF).
 *
 * BUG-C18: на Android 10/11+ писать в общее хранилище по файловому пути нельзя (scoped storage).
 * Совместимый способ — Storage Access Framework: ACTION_OPEN_DOCUMENT_TREE + takePersistableUriPermission,
 * запись через DocumentFile/ContentResolver. Разрешений на хранилище НЕ требует.
 */
object StorageLocation {

    private const val PREFS = "storage"
    private const val KEY_TREE = "tree_uri"

    /** Интент для меню «Выбрать папку». Результат обрабатывается в Activity и передаётся в persist(). */
    fun pickFolderIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

    /** Сохранить выбор папки (вызывать из onActivityResult/registerForActivityResult). */
    fun persist(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs(context).edit().putString(KEY_TREE, treeUri.toString()).apply()
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY_TREE).apply()

    private fun savedTree(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)

    /** Человекочитаемое имя текущей папки для показа в настройках. */
    fun currentLocationLabel(context: Context): String {
        val uri = savedTree(context) ?: return "По умолчанию (память приложения)"
        val doc = DocumentFile.fromTreeUri(context, uri)
        return doc?.name ?: "Выбранная папка"
    }

    /**
     * Перенести готовый WAV в выбранную папку (или в app-private, если папка не выбрана).
     * Возвращает Uri результата.
     */
    fun saveRecording(context: Context, temp: File, displayName: String): Uri {
        val tree = savedTree(context)
        if (tree == null) {
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val dst = File(dir, displayName)
            temp.copyTo(dst, overwrite = true)
            temp.delete()
            return Uri.fromFile(dst)
        }
        val folder = DocumentFile.fromTreeUri(context, tree)
            ?: return Uri.fromFile(temp) // папка недоступна — оставляем во временной
        val target = folder.createFile("audio/x-wav", displayName)
            ?: return Uri.fromFile(temp)
        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            temp.inputStream().use { it.copyTo(out) }
        }
        temp.delete()
        return target.uri
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ==========================================================
// IntentHelpers.kt
// ==========================================================
/**
 * Отправка письма и создание события календаря системными интентами.
 * BUG-C04: resolveActivity() работает только благодаря <queries> в манифесте (Android 11+).
 */
object IntentHelpers {

    /** Письмо в почтовый клиент. ACTION_SENDTO+mailto показывает только e-mail приложения. */
    fun email(context: Context, to: String, subject: String, body: String) {
        val sendTo = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (sendTo.resolveActivity(context.packageManager) != null) {
            context.startActivity(sendTo)
            return
        }
        // Резерв: общий шэринг текста, если e-mail клиента нет
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(send, "Отправить пост-мит"))
    }

    /**
     * Открыть системный редактор события (ACTION_INSERT). BUG-C09: WRITE_CALENDAR не требуется.
     * begin/end — epoch ms. Для события «на весь день»: end = begin и allDay = true.
     */
    fun calendarEvent(
        context: Context,
        title: String,
        beginMillis: Long,
        endMillis: Long,
        description: String = "",
        allDay: Boolean = false
    ) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            if (allDay) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
        // Календаря нет — на уровне UI показать сообщение (здесь молча пропускаем).
    }
}

// ==========================================================
// Permissions.kt
// ==========================================================
/**
 * Runtime-разрешения различаются по версиям — запрашиваем ровно то, что нужно на данном API.
 */
object Permissions {

    /** Разрешения, которые нужно запросить ДО старта записи. */
    fun requiredForRecording(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        // BUG-C05: POST_NOTIFICATIONS — runtime только с API 33 (Android 13).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // BUG-C06: FOREGROUND_SERVICE_MICROPHONE (API 34) — normal-разрешение, в рантайме
        // не запрашивается, но RECORD_AUDIO обязан быть выдан до startForeground().
        // READ_CONTACTS запрашиваем отдельно — по месту, перед выбором участников.
    }.toTypedArray()

    fun granted(context: Context): Boolean =
        requiredForRecording().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}

// ==========================================================
// YandexAuth.kt
// ==========================================================
/**
 * OAuth 2.0 авторизация Яндекса (обязательна для доступа к Диску).
 * Регистрация приложения: https://oauth.yandex.ru/client/new, разрешение cloud_api:disk.write.
 * Для мобильного рекомендуется Authorization Code (+ PKCE) с редиректом на custom scheme,
 * например mitap://yandex/auth.
 *
 * BUG-07: токен хранить в Keystore-шифрованном виде (здесь SharedPreferences — только как заглушка).
 */
object YandexAuth {
    private const val AUTHORIZE = "https://oauth.yandex.ru/authorize"
    private const val TOKEN = "https://oauth.yandex.ru/token"
    private const val PREFS = "settings"
    private const val KEY_TOKEN = "yandex_token"

    /** URL страницы входа (code flow). */
    fun authorizeUrl(clientId: String, redirectUri: String, state: String): String =
        Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", "cloud_api:disk.write cloud_api:disk.read")
            .appendQueryParameter("state", state)
            .build().toString()

    fun openLogin(context: Context, clientId: String, redirectUri: String, state: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl(clientId, redirectUri, state)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Обмен кода на токен (вызывать в фоне). Возвращает access_token или null. */
    fun exchangeCode(clientId: String, clientSecret: String, code: String, redirectUri: String): String? {
        val body = "grant_type=authorization_code&code=$code" +
            "&client_id=$clientId&client_secret=$clientSecret" +
            "&redirect_uri=${Uri.encode(redirectUri)}"
        val conn = (URL(TOKEN).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return runCatching {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val json = conn.inputStream.bufferedReader().readText()
            // В продакшене — нормальный JSON-парсер вместо regex
            Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        }.getOrNull().also { conn.disconnect() }
    }

    fun saveToken(context: Context, token: String) =
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)
    fun isConnected(context: Context): Boolean = !token(context).isNullOrBlank()
    fun signOut(context: Context) = prefs(context).edit().remove(KEY_TOKEN).apply()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ==========================================================
// YandexDisk.kt
// ==========================================================
/**
 * Минимальный клиент Яндекс.Диска (REST). Авторизация — заголовок "Authorization: OAuth <token>".
 * Загрузка: 1) создать папку (идемпотентно) 2) получить upload-href 3) PUT файла на href.
 * Хост API — cloud-api.yandex.net; всё по HTTPS (кросс-версионно безопасно).
 */
object YandexDisk {
    private const val BASE = "https://cloud-api.yandex.net/v1/disk"

    /** Создать папку. 201 — создана, 409 — уже существует; оба варианта считаем успехом. */
    fun ensureFolder(token: String, path: String) {
        val conn = (URL("$BASE/resources?path=${Uri.encode(path)}").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "OAuth $token")
        }
        runCatching { conn.responseCode }
        conn.disconnect()
    }

    /** Загрузить локальный файл. remotePath — напр. "/Митап/call_123.wav". true при успехе. */
    fun upload(token: String, local: File, remotePath: String, overwrite: Boolean = true): Boolean {
        val hrefUrl = "$BASE/resources/upload?path=${Uri.encode(remotePath)}&overwrite=$overwrite"
        val c1 = (URL(hrefUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "OAuth $token")
        }
        val href = runCatching {
            val json = c1.inputStream.bufferedReader().readText()
            Regex("\"href\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        }.getOrNull()
        c1.disconnect()
        if (href.isNullOrBlank()) return false

        val c2 = (URL(href).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
        }
        return runCatching {
            local.inputStream().use { input -> c2.outputStream.use { input.copyTo(it) } }
            c2.responseCode in 200..299
        }.getOrDefault(false).also { c2.disconnect() }
    }
}

// ==========================================================
// YandexUploadWorker.kt
// ==========================================================
/**
 * Фоновая выгрузка записи на Яндекс.Диск. WorkManager переживает Doze/перезапуск и ждёт сеть.
 */
class YandexUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val token = YandexAuth.token(applicationContext) ?: return Result.failure()
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val folder = inputData.getString(KEY_FOLDER) ?: "Митап"
        val name = inputData.getString(KEY_NAME) ?: "recording.wav"

        val file = fileFromUri(path) ?: return Result.failure()
        YandexDisk.ensureFolder(token, "/$folder")
        val ok = YandexDisk.upload(token, file, "/$folder/$name")
        return if (ok) Result.success() else Result.retry()
    }

    // app-private файлы приходят как file://; для SAF-URI выгружать через ContentResolver (опущено).
    private fun fileFromUri(path: String): File? {
        val uri = Uri.parse(path)
        return if (uri.scheme == "file") uri.path?.let(::File) else null
    }

    companion object {
        const val KEY_PATH = "path"
        const val KEY_FOLDER = "folder"
        const val KEY_NAME = "name"

        /** Поставить выгрузку в очередь, если включена автозагрузка и есть авторизация. */
        fun enqueueIfEnabled(context: Context, fileUri: Uri, name: String) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("yandex_auto_upload", false)) return
            if (!YandexAuth.isConnected(context)) return
            val folder = prefs.getString("yandex_folder", "Митап") ?: "Митап"

            val data = workDataOf(
                KEY_PATH to fileUri.toString(),
                KEY_FOLDER to folder,
                KEY_NAME to name
            )
            val request = OneTimeWorkRequestBuilder<YandexUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
