plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
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
    versionCode = 107
    versionName = "1.2.107"

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
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
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
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
