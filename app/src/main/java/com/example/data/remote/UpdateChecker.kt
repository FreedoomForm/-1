package com.example.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Проверяет обновления приложения через GitHub Releases API.
 *
 * Алгоритм:
 * 1. Запрашивает последний релиз с GitHub
 *    (https://api.github.com/repos/{owner}/{repo}/releases/latest)
 * 2. Сравнивает versionName приложения с тегом релиза
 * 3. Если доступна новая версия — возвращает UpdateInfo с URL для скачивания APK
 *
 * Настройка:
 * - Владелец репозитория и имя задаются в companion object
 * - Нужно создать Release на GitHub и загрузить APK как asset
 */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val fileSize: Long,
    val publishDate: String
)

class UpdateChecker(
    private val context: Context
) {
    /**
     * Проверяет наличие обновления на GitHub.
     * Возвращает UpdateInfo если доступна новая версия, или null если текущая актуальна.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersionName()
            Log.d(TAG, "Current app version: $currentVersion")

            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
            val connection = URL(apiUrl).openConnection()
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000

            val json = connection.getInputStream().bufferedReader().use { it.readText() }
            val release = JSONObject(json)

            val tagName = release.optString("tag_name", "").removePrefix("v")
            val releaseNotes = release.optString("body", "")
            val publishDate = release.optString("published_at", "")

            Log.d(TAG, "Latest GitHub release: $tagName")

            // Ищем APK asset в релизе
            val assets = release.optJSONArray("assets") ?: return@withContext null
            var downloadUrl: String? = null
            var fileSize: Long = 0

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    fileSize = asset.optLong("size", 0)
                    break
                }
            }

            if (downloadUrl == null) {
                Log.w(TAG, "No APK asset found in release $tagName")
                return@withContext null
            }

            // Сравниваем версии
            if (isNewerVersion(currentVersion, tagName)) {
                Log.d(TAG, "Update available: $currentVersion → $tagName")
                UpdateInfo(
                    versionName = tagName,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    fileSize = fileSize,
                    publishDate = publishDate
                )
            } else {
                Log.d(TAG, "App is up to date ($currentVersion >= $tagName)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        }
    }

    /**
     * Получает текущую версию приложения из PackageManager.
     */
    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get version name", e)
            "1.0"
        }
    }

    /**
     * Сравнивает номера версий.
     * Поддерживает форматы: "1.0", "1.2.3", "1.2.3-67", "1.2.67"
     * Если версия содержит суффикс через "-" (например "1.1-67"),
     * он обрабатывается как дополнительный компонент версии.
     * Возвращает true если remote > local
     */
    private fun isNewerVersion(local: String, remote: String): Boolean {
        val normalizedLocal = normalizeVersion(local)
        val normalizedRemote = normalizeVersion(remote)

        Log.d(TAG, "Version comparison: local='$local' → $normalizedLocal, remote='$remote' → $normalizedRemote")

        val localParts = normalizedLocal.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = normalizedRemote.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(localParts.size, remoteParts.size)

        for (i in 0 until maxLen) {
            val l = localParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false // Версии одинаковы
    }

    /**
     * Нормализует строку версии: заменяет "-" на "."
     * Например: "1.1-67" → "1.1.67", "1.2.3" → "1.2.3"
     */
    private fun normalizeVersion(version: String): String {
        return version.replace('-', '.')
    }

    companion object {
        private const val TAG = "UpdateChecker"
        const val REPO_OWNER = "FreedoomForm"
        const val REPO_NAME = "-1"
    }
}
