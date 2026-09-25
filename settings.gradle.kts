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
    // ── JitPack — для PdfBox-Android 2.x (если понадобится в будущем) ──────
    // PdfBox-Android 2.x публикуется только на JitPack под координатой
    // com.github.TomRoush:pdfbox-android. Сейчас мы используем версию
    // 1.8.10.1 с Maven Central (более стабильная, не зависит от
    // доступности JitPack). Оставляем репозиторий на случай если
    // понадобится обновиться до 2.x.
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "My Application"

include(":app")
