#!/usr/bin/env bash
# Локальный скрипт для сборки debug APK (используется вне GitHub Actions).
# Запускать из корня проекта (-1).

set -euo pipefail

echo "================================================="
echo "  Scooter Rent — локальная сборка debug APK"
echo "================================================="

# 1. Проверяем, что есть Gradle и keytool
command -v gradle >/dev/null 2>&1 || { echo "❌ gradle не найден. Установите Gradle 9.1."; exit 1; }
command -v keytool >/dev/null 2>&1 || { echo "❌ keytool не найден. Установите JDK 17."; exit 1; }

# 2. Генерируем wrapper, если его нет
if [ ! -x ./gradlew ]; then
  echo "→ gradlew отсутствует, генерирую..."
  gradle wrapper --gradle-version 9.1 --distribution-type bin
  chmod +x gradlew
fi

# 3. Создаём debug.keystore, если его нет
if [ ! -f debug.keystore ]; then
  echo "→ debug.keystore отсутствует, генерирую..."
  keytool -genkeypair \
    -keystore debug.keystore \
    -alias androiddebugkey \
    -storepass android \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

# 4. Готовим .env (нужен для плагина secrets)
if [ ! -f .env ] && [ -f .env.example ]; then
  echo "→ .env отсутствует, копирую .env.example..."
  cp .env.example .env
fi

# 5. Собираем APK
echo "→ Запускаю ./gradlew assembleDebug..."
./gradlew assembleDebug --stacktrace

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  echo ""
  echo "✅ Готово! APK: $(realpath "$APK_PATH")"
  echo "📦 Размер: $(du -h "$APK_PATH" | cut -f1)"
else
  echo "❌ APK не найден в $APK_PATH"
  exit 1
fi
