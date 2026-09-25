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
    // ── JitPack — оставлен на случай если понадобится PdfBox-Android 2.x ──
    // PdfBox-Android 2.x публикуется только на JitPack под координатой
    // com.github.TomRoush:pdfbox-android. Сейчас мы не используем PdfBox
    // (annotation rendering реализован через android.graphics.Canvas).
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "My Application"

include(":app")
