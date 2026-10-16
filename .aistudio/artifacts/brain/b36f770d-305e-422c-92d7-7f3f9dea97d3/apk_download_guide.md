# Релиз APK на GitHub и Скачивание — Aura Control (v1.2)

В проект добавлен автоматический пайплайн **GitHub Actions** (`.github/workflows/release-apk.yml`), который при отправке кода на GitHub автоматически собирает подписанный APK и публикует его в раздел **Releases** вашего репозитория.

---

## Способ 1: Автоматический релиз на GitHub (`AuraControl-v1.2.apk`)

1. В верхнем меню **Google AI Studio** нажмите кнопку **GitHub (Push to GitHub / Export to GitHub)**.
2. Авторизуйтесь в GitHub и выберите (или создайте) репозиторий для выгрузки проекта.
3. Как только код попадёт в ветку `main` (или `master`), на GitHub автоматически запустится workflow **Build & Release Android APK**:
   * Восстановит ключ подписи `debug.keystore` из `debug.keystore.base64` (с которым уже связан Firebase Auth / Google Sign-In).
   * Соберёт APK через Gradle 9.3.1 (`gradle :app:assembleDebug`).
   * Прикрепит готовый файл **`AuraControl-v1.2.apk`** в раздел **Releases** (`v1.2.0`) на главной странице вашего GitHub-репозитория, а также в **Actions → Artifacts**.
4. *(Опционально)* Чтобы голосовой ассистент в облачной сборке GitHub использовал ваш ключ Gemini без ввода внутри приложения, добавьте секрет `GEMINI_API_KEY` в настройках репозитория (**Settings → Secrets and variables → Actions → New repository secret**).

---

## Способ 2: Прямое скачивание APK из AI Studio (в 1 клик)
1. В правом верхнем углу интерфейса AI Studio откройте меню **Settings (Настройки / Шестерёнка)** или **Download / Export**.
2. Нажмите **Download APK / Generate APK** — готовый `.apk` файл сразу скачается в браузере без ожидания сборки на GitHub.

---

## Параметры сборки
* **Файл релиза на GitHub:** `AuraControl-v1.2.apk` (тег `v1.2.0`)
* **Package Name (`applicationId`):** `com.aistudio.auracontrol.xkvpzr`
* **Версия (`versionName` / `versionCode`):** `1.2` (`3`)
* **Подпись:** `debug.keystore` (SHA-1 привязан к проекту Firebase для работы входа через Google и Firestore)
