# Сборка APK — «Митап»

> APK **не собран в этом окружении намеренно**: у песочницы нет Android SDK, а доступ к
> серверам Google (SDK, AndroidX, Gradle) закрыт. Ниже — три рабочих способа получить APK
> там, где это возможно. Проект готов к сборке «как есть».

## Что в проекте
- `app/src/main/java/com/mitap/app/` — код: запись встречи и звонка, источники звука, диаризация-заглушки,
  почта/календарь-интенты, хранилище (SAF), Яндекс.Диск (OAuth + загрузка), `MainActivity` (экран настроек).
- `app/src/main/AndroidManifest.xml`, `res/values/themes.xml` — манифест и тема.
- Gradle-файлы (`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`).
- `.github/workflows/build-apk.yml` — сборка APK в облаке.

Версии: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, compileSdk 35, minSdk 29, targetSdk 35.
Зависимости минимальны (core-ktx, work-runtime-ktx, documentfile) — без Compose, для устойчивой сборки.

---

## Способ 1 — GitHub Actions (без установки чего-либо) ✅ рекомендуется
1. Создайте новый репозиторий на GitHub.
2. Запушьте туда **содержимое папки `android/`** так, чтобы `settings.gradle.kts` и `.github/`
   оказались в корне репозитория.
3. Вкладка **Actions** → сборка стартует сама (или **Run workflow**).
4. Откройте завершённый прогон → **Artifacts** → скачайте `mitap-debug-apk` (это `app-debug.apk`).

## Способ 2 — Android Studio
1. **File → Open** и выберите папку `android/`.
2. Дождитесь Gradle Sync (Studio сам подтянет Gradle 8.9, SDK и зависимости).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. APK появится в `app/build/outputs/apk/debug/app-debug.apk` (Studio покажет ссылку «locate»).

## Способ 3 — командная строка
Нужны JDK 17 и Android SDK (переменная `ANDROID_HOME`).
```bash
cd android
gradle wrapper --gradle-version 8.9   # один раз, если нет ./gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Подписанный release-APK (для распространения)
Debug-APK подписан отладочным ключом (для теста этого достаточно). Для раздачи:
```bash
keytool -genkey -v -keystore mitap.keystore -alias mitap -keyalg RSA -keysize 2048 -validity 10000
```
Добавьте в `app/build.gradle.kts` блок `signingConfigs` и `buildTypes.release.signingConfig`,
затем `./gradlew assembleRelease`.

---

## Перед реальным запуском
- **Яндекс.Диск:** зарегистрируйте приложение на https://oauth.yandex.ru/client/new (scope `cloud_api:disk.write`),
  впишите `yandexClientId`/секрет и redirect `mitap://yandex/auth` в `MainActivity`. Токен переносите в
  Keystore-шифрованное хранилище (см. `COMPATIBILITY.md`, BUG-07).
- **Запись звонков:** пишется микрофон (собеседник — на громкой связи); канал самого разговора Android
  сторонним приложениям не отдаёт. Соблюдайте законы о согласии на запись.
- **Распространение:** авто-рекордеры часто отклоняются в Google Play — рассматривайте прямой APK / RuStore / MDM.
- Это MVP-каркас: UI утилитарный (Compose-версия — в веб-прототипе `App.jsx`). ASR/диаризация/суммаризация
  подключаются отдельными модулями (on-device или облако), см. ТЗ и `COMPATIBILITY.md`.
