plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.scooterrent.xyzab"
    minSdk = 24
    targetSdk = 36
    versionCode = 1024
    versionName = "1.2.187-local"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD") ?: "dummy"
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD") ?: "dummy"
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation("androidx.work:work-runtime-ktx:2.9.0")
  implementation("androidx.compose.material:material-icons-extended:1.7.0")
  // ── Excel для экспорта/импорта базы данных ─────────────────────────────
  // FastExcel: лёгкая (~500 КБ) библиотека для записи и чтения .xlsx файлов.
  // Используется BackupManager'ом — кнопки «Eksport» / «Import» на вкладке
  // «Sozlamalar». FastExcel лучше Apache POI подходит для Android, т.к. не
  // тянет за собой тяжёлые XML-зависимости (java.xml.bind и т.п.).
  //
  // ⚠ FastExcel-READER для парсинга .xlsx использует StAX API
  //   (javax.xml.stream.*), которого НЕТ в Android runtime (Android
  //   использует XmlPullParser вместо StAX). Поэтому reader'у нужны:
  //     1. stax-api  — сами интерфейсы javax.xml.stream.*
  //     2. aalto-xml — асинхронная реализация StAX, работающая на Android.
  //   Без них импорт падает с:
  //     NoClassDefFoundError: Lcom/fasterxml/aalto/AsyncXMLInputFactory;
  //     NoClassDefFoundError: Lorg/codehaus/stax2/XMLInputFactory2;
  //     NoClassDefFoundError: Ljavax/xml/stream/XMLInputFactory;
  //   (writer работает и без них — он пишет .xlsx через java.util.zip,
  //   без StAX. Зависимости нужны только для импорта.)
  implementation("org.dhatim:fastexcel:0.18.4")
  implementation("org.dhatim:fastexcel-reader:0.18.4")
  implementation("javax.xml.stream:stax-api:1.0-2")
  implementation("com.fasterxml:aalto-xml:1.3.0")
  // ── CameraX для сканера документов ( Mistral OCR ) ────────────────────────
  // Используется на экране ScannerScreen: preview + захват фото.
  // Версии CameraX стабильны и совместимы с minSdk 24.
  implementation("androidx.camera:camera-core:1.3.4")
  implementation("androidx.camera:camera-camera2:1.3.4")
  implementation("androidx.camera:camera-lifecycle:1.3.4")
  implementation("androidx.camera:camera-view:1.3.4")
  // ── OkHttp для отправки фото в Mistral OCR API ────────────────────────────
  // Mistral OCR принимает multipart/form-data с base64-картинкой; OkHttp —
  // самый лёгкий и проверенный способ делать HTTP-запросы на Android.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  // JSON-парсинг ответов Mistral — используем встроенный org.json (Android SDK),
  // отдельная зависимость не нужна.
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // ── kotlinx-serialization — для JSON-сериализации шаблонов договора ────────
  // Шаблон договора (TemplateContent: 8 реквизитов + текст) хранится в БД
  // как JSON-строка в колонке contentJson таблицы contract_templates.
  // kotlinx-serialization — стандарт де-факто для Kotlin-проектов, плагин
  // уже применён в build.gradle.kts (alias(libs.plugins.kotlin.serialization)).
  implementation(libs.kotlinx.serialization.json)
  // ── PdfBox-Android — open-source PDF редактор (Apache 2.0) ────────────────
  // Используется в PdfEditorScreen для:
  //   • Добавления текстовых аннотаций на страницы PDF
  //   • Сохранения аннотаций в PDF файл
  //   • Поддержки {{placeholders}} в тексте аннотаций (заменяются на реальные
  //     данные при генерации финального PDF для контракта)
  //
  // PdfBox-Android — порт Apache PDFBox на Android. Лицензия Apache 2.0,
  // коммерчески дружелюбная. Активно поддерживается (TomRoush/pdfbox-android).
  // Полноценного open-source WYSIWYG редактора PDF на Android НЕ существует —
  // это лучший вариант для программного API + визуальная обвязка на Compose.
  //
  // ВАЖНО: координата — com.github.TomRoush (JitPack), НЕ com.tom-roush
  // (старая координата на Maven Central, доступна только версия 1.8.10.1).
  // Новые 2.x версии публикуются ТОЛЬКО на JitPack.
  // ── PdfBox-Android 2.0.7.0 (JitPack) — единственная доступная версия ──
  // Pdf_Tools (github.com/Karna14314/Pdf_Tools) использует com.tom-routh:2.0.27.0
  // но эта версия НЕ доступна ни на Maven Central, ни на JitPack (404).
  // JitPack maven-metadata показывает только версии 1.8.9.1, 1.8.10.0, 2.0.7.0.
  // Используем com.github.TomRoush:pdfbox-android:2.0.7.0 — РАБОЧАЯ версия
  // (HTTP 200). API 2.0.x: PDPageContentStream AppendMode enum, setNonStrokingColor(r,g,b).
  // См. Pdf_Tools/app/src/main/java/.../PdfAnnotator.kt:526 — рабочий пример 2.0 API.
  implementation("com.github.TomRoush:pdfbox-android:2.0.7.0")
  // ── Apache POI — для генерации DOCX файлов ──────────────────────────────
  // Используется в DocxContractGenerator для создания .docx из bodyText
  // с {{placeholders}}. Apache POI 5.2.5 — основная open-source библиотека
  // для работы с OOXML (DOCX, XLSX, PPTX) на Java/Android.
  // Уже есть stax-api + aalto-xml (для FastExcel), Apache POI совместим.
  implementation("org.apache.poi:poi:5.2.5")
  implementation("org.apache.poi:poi-ooxml:5.2.5")
  // testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  // ── Room testing — для MigrationTestHelper (тесты миграций БД) ──────────
  // MigrationTestHelper позволяет создать БД на старой версии (напр. v36),
  // запустить миграцию и проверить, что итоговая схема совпадает с entity.
  // Без этого тесты использовали только fresh install и НЕ ловили баги
  // миграций (как было с DEFAULT clauses и необъявленным @Index).
  testImplementation("androidx.room:room-testing:2.7.0")
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
