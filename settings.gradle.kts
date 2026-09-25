pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // ── JitPack — для PdfBox-Android (com.github.TomRoush:pdfbox-android) ──
    // PdfBox-Android 2.x публикуется только на JitPack, на Maven Central
    // доступна только старая версия 1.8.10.1. Нам нужен 2.0.27.0 (новее API,
    // работает с Android 9+). Без этого репозитория сборка падает с
    // 'Could not find com.tom-routh:pdfbox-android:2.0.27.0'.
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "My Application"

include(":app")
