package com.mitap.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

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
