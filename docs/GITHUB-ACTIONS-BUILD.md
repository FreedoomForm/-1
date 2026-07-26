# Сборка Debug APK через GitHub Actions

Этот workflow автоматически собирает debug-APK приложения **Scooter Rent**
из репозитория [FreedoomForm/-1](https://github.com/FreedoomForm/-1).

---

## 🚀 Что делает workflow

1. Клонирует код из ветки `main`
2. Ставит **JDK 17** (минимум для AGP 9.1.1)
3. Ставит **Gradle 9.5.1** с кэшированием сборок (latest stable; AGP 9.1.1 совместим с Gradle 9.x — конкретно версия 9.1 пропущена в релизах Gradle, поэтому берём ближайшую существующую)
4. Устанавливает **Android SDK** (API 36 + build-tools 36.0.0)
5. **Генерирует `gradlew`** (в репозитории его нет — это особенность AI Studio шаблона)
6. Копирует `.env.example → .env` (нужно для плагина `secrets`)
7. Создаёт **debug.keystore** (на него ссылается `debugConfig` в `app/build.gradle.kts`)
8. Запускает `./gradlew assembleDebug`
9. Публикует собранный APK как **artifact** на странице Actions

---

## 📥 Как получить готовый APK

### Вариант 1: Через GitHub Actions (рекомендуется)

1. Закоммитьте файл `.github/workflows/build-debug-apk.yml` в репозиторий и
   отправьте в ветку `main`:

   ```bash
   git clone https://github.com/FreedoomForm/-1.git
   cd -1
   mkdir -p .github/workflows
   cp /path/to/build-debug-apk.yml .github/workflows/
   git add .github/workflows/build-debug-apk.yml
   git commit -m "ci: add debug APK build workflow"
   git push origin main
   ```

2. Откройте вкладку **Actions** вашего репозитория — запустится job
   `Build Debug APK`.
3. После завершения (≈ 5–10 минут при первой сборке) прокрутите вниз до
   секции **Artifacts** и скачайте **`scooter-rent-debug-apk`**.
4. Внутри будет файл `app-debug.apk` — установите его на устройство Android
   (через ADB: `adb install app-debug.apk` или просто откройте файл на телефоне).

> ⚠️ APK подписан **стандартным debug-ключом Android** (`androiddebugkey`),
> поэтому устанавливается без дополнительных действий, но **не пригоден
> для публикации в Google Play**.

### Вариант 2: Ручной запуск

1. На странице Actions выберите workflow **Build Debug APK**
2. Справа нажмите **Run workflow**
3. По желанию включите чекбокс «Запустить юнит-тесты»
4. Скачайте APK из artifacts, когда job завершится

---

## 🔧 Что нужно знать

| Параметр | Значение |
|---|---|
| `compileSdk` | 36 (Android 16) |
| `targetSdk` | 36 |
| `minSdk` | 24 (Android 7.0) |
| `applicationId` | `com.aistudio.scooterrent.xyzab` |
| AGP | 9.1.1 |
| Kotlin | 2.2.10 |
| Gradle | 9.5.1 (latest stable; 9.1 был пропущен в релизах) |
| JDK | 17 |
| Время первой сборки | ~5–10 мин |
| Время повторных | ~2–3 мин (Gradle Build Cache) |

---

## 🛠️ Локальная сборка (без GitHub Actions)

В репозитории нет `gradlew` — это шаблон Google AI Studio. Чтобы собрать
проект на своей машине, нужно сгенерировать wrapper один раз:

```bash
# 1. Поставьте Gradle 9.5.1 (например, через SDKMAN)
sdk install gradle 9.5.1

# 2. Сгенерируйте wrapper
cd /path/to/-1
gradle wrapper --gradle-version 9.5.1 --distribution-type bin

# 3. Создайте debug.keystore (если его нет)
keytool -genkeypair -keystore debug.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

# 4. Скопируйте .env.example в .env
cp .env.example .env

# 5. Соберите APK
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
Можно также использовать готовый скрипт:

```bash
./scripts/build-local.sh
```

---

## 🐛 Частые проблемы

### «License for package … not accepted»
Добавьте в шаг `setup-android` параметр:
```yaml
accept-android-sdk-licenses: true
```

### `keystore … was tampered with, or password was incorrect`
Убедитесь, что пароли в `app/build.gradle.kts` (`storePassword = "android"`,
`keyPassword = "android"`) совпадают с теми, которыми вы создали keystore.

### `SDK location not found`
Создайте `local.properties` в корне с указанием пути к Android SDK:
```
sdk.dir=/path/to/Android/sdk
```

### Не генерируется `gradlew` или ошибка `Gradle version X does not exist`
Версия **Gradle 9.1 была пропущена** в релизах Gradle (сразу после 9.0 идёт 9.4). Используйте **9.5.1** (latest stable). AGP 9.1.1 совместим со всем семейством Gradle 9.x.

---

## 🚀 Сборка release-APK (для публикации)

Для подписанной release-сборки нужно:

1. Сгенерировать свой keystore:
   ```bash
   keytool -genkeypair -v -keystore my-upload-key.jks \
     -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Добавить в **Settings → Secrets and variables → Actions** репозитория:
   - `KEYSTORE_BASE64` — содержимое `.jks`, закодированное в base64
   - `STORE_PASSWORD` — пароль от keystore
   - `KEY_PASSWORD` — пароль от ключа `upload`

3. Использовать расширенный workflow (вариант для опытных пользователей).

---

## 📂 Структура файлов в этом репозитории

```
scooter-rent-build/
├── .github/
│   └── workflows/
│       └── build-debug-apk.yml    ← основной workflow
├── docs/
│   └── GITHUB-ACTIONS-BUILD.md    ← вы сейчас читаете
└── scripts/
    └── build-local.sh             ← скрипт для локальной сборки
```
