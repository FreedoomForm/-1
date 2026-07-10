package com.example.data.remote

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Проверяет обновления приложения через GitHub Releases API.
 *
 * Ключевые принципы (v2):
 * 1. Сравнение по versionCode (целое число) — НАДЁЖНЕЕ чем versionName (строка)
 * 2. Тег релиза на GitHub должен быть числом = versionCode (например "3")
 *    ЛИБО можно указать versionCode в body релиза: "versionCode:3"
 * 3. Если версия одинаковая или новее — updateInfo = null (НЕ показываем уведомление)
 * 4. Любая ошибка API → null (НЕ показываем уведомление)
 * 5. Скачивание APK прямо в приложение — без браузера
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val fileSize: Long,
    val publishDate: String
)

/**
 * Результат проверки обновлений — различает «обновление доступно»,
 * «приложение актуально» и «ошибка».
 */
enum class UpdateCheckResult {
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    ERROR
}

class UpdateChecker(
    private val context: Context
) {
    /**
     * Проверяет наличие обновления на GitHub.
     * Возвращает Pair(result, updateInfo):
     *   result = UPDATE_AVAILABLE → updateInfo != null
     *   result = UP_TO_DATE → updateInfo = null
     *   result = ERROR → updateInfo = null
     */
    suspend fun checkForUpdate(): Pair<UpdateCheckResult, UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val currentVersionCode = getCurrentVersionCode()
            val currentVersionName = getCurrentVersionName()
            Log.d(TAG, "Current: versionCode=$currentVersionCode, versionName=$currentVersionName")

            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
            val connection = URL(apiUrl).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            // GitHub API требует User-Agent, иначе возвращает 403
            connection.setRequestProperty("User-Agent", "ScooterRent-App-Update-Checker")
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "GitHub API returned HTTP $responseCode: $errorBody")
                if (responseCode == 403 && errorBody.contains("rate limit", ignoreCase = true)) {
                    Log.w(TAG, "GitHub API rate limit exceeded — update check skipped")
                }
                return@withContext Pair(UpdateCheckResult.ERROR, null)
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(json)

            val tagName = release.optString("tag_name", "").removePrefix("v")
            val releaseNotes = release.optString("body", "")
            val publishDate = release.optString("published_at", "")

            // Определяем versionCode из тега или из тела релиза
            val remoteVersionCode = parseVersionCode(tagName, releaseNotes)

            Log.d(TAG, "Latest GitHub release: tag=$tagName, remoteVersionCode=$remoteVersionCode")

            // Ищем APK asset в релизе
            val assets = release.optJSONArray("assets") ?: return@withContext Pair(UpdateCheckResult.ERROR, null)
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
                return@withContext Pair(UpdateCheckResult.ERROR, null)
            }

            // Главное сравнение: versionCode
            if (remoteVersionCode != null) {
                if (currentVersionCode >= remoteVersionCode) {
                    Log.d(TAG, "App is up to date (versionCode: $currentVersionCode >= $remoteVersionCode)")
                    return@withContext Pair(UpdateCheckResult.UP_TO_DATE, null)
                }
            } else {
                // Fallback: сравнение по versionName
                if (!isNewerVersion(currentVersionName, tagName)) {
                    Log.d(TAG, "App is up to date (versionName: $currentVersionName >= $tagName)")
                    return@withContext Pair(UpdateCheckResult.UP_TO_DATE, null)
                }
            }

            // Новая версия доступна
            val effectiveVersionCode = remoteVersionCode ?: (currentVersionCode + 1)
            Log.d(TAG, "Update available: $currentVersionName → $tagName (code: $currentVersionCode → $effectiveVersionCode)")
            Pair(
                UpdateCheckResult.UPDATE_AVAILABLE,
                UpdateInfo(
                    versionName = tagName,
                    versionCode = effectiveVersionCode,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    fileSize = fileSize,
                    publishDate = publishDate
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            Pair(UpdateCheckResult.ERROR, null)
        }
    }

    /**
     * Скачивает APK файл в кэш приложения.
     * Возвращает File на скачанный APK или null при ошибке.
     * @param onProgress колбэк с прогрессом (0.0 .. 1.0)
     *
     * ВНИМАНИЕ: этот метод использует фиксированное имя `update.apk`.
     * Для новой установки предпочтительнее использовать [downloadApkTo],
     * который принимает уникальное имя файла — это устраняет конфликт
     * файлов при повторных загрузках.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val apkFile = File(context.cacheDir, "update.apk")
        downloadApkTo(downloadUrl, apkFile, onProgress)
    }

    /**
     * Скачивает APK в указанный файл. Используется InAppUpdateManager-ом
     * с уникальным именем (`update_<timestamp>.apk`) — это устраняет
     * «конфликт папок», когда старый файл ещё занят предыдущей сессией
     * PackageInstaller.
     *
     * Метод атомарен: пишет во временный `.part` файл, затем переименовывает.
     * Если загрузка прерывается — временный файл удаляется, частичный APK
     * никогда не останется в кэше.
     */
    suspend fun downloadApkTo(
        downloadUrl: String,
        targetFile: File,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        try {
            // Удаляем старые файлы если есть
            if (targetFile.exists()) {
                try { targetFile.delete() } catch (_: Exception) {}
            }
            if (partFile.exists()) {
                try { partFile.delete() } catch (_: Exception) {}
            }

            val connection = URL(downloadUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            // User-Agent помогает избежать блокировки со стороны GitHub CDN
            connection.setRequestProperty("User-Agent", "ScooterRent-App-Update-Checker")
            connection.connect()

            val fileSize = connection.contentLength.toLong()

            connection.getInputStream().buffered().use { input ->
                partFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (fileSize > 0) {
                            onProgress(totalRead.toFloat() / fileSize.toFloat())
                        }
                    }
                }
            }

            // Атомарный rename: .part → финальное имя
            if (!partFile.renameTo(targetFile)) {
                // Если rename не удался (например, кросс-точки монтирования),
                // копируем вручную и удаляем part.
                partFile.inputStream().use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                try { partFile.delete() } catch (_: Exception) {}
            }

            Log.d(TAG, "APK downloaded to ${targetFile.name}: ${targetFile.length()} bytes")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download APK to ${targetFile.name}", e)
            // Чистим частичный файл при ошибке
            try { if (partFile.exists()) partFile.delete() } catch (_: Exception) {}
            try { if (targetFile.exists()) targetFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Получает текущий versionCode приложения.
     */
    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get version code", e)
            1
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
     * Парсит versionCode из тега релиза или тела релиза.
     * Поддерживаемые форматы:
     *   - Тег = число (например "3") → 3
     *   - Тег = "1.2.77" → извлекаем последнюю часть как versionCode (77)
     *   - В теле: "versionCode:3" или "versionCode=3" или "versionCode: 3"
     */
    private fun parseVersionCode(tagName: String, body: String): Int? {
        // Попробуем распарсить тег как целое число (например "3")
        tagName.toIntOrNull()?.let { return it }

        // Попробуем найти versionCode в теле релиза (приоритет!)
        val regex = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        regex.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Попробуем извлечь versionCode из последней части тега (например "1.2.77" → 77)
        val parts = tagName.split(".")
        if (parts.size >= 3) {
            parts.last().toIntOrNull()?.let { return it }
        }

        return null
    }

    /**
     * Сравнивает номера версий.
     * Поддерживает форматы: "1.0", "1.2.3", "1.2.3-67", "1.2.67"
     * Возвращает true если remote > local
     */
    private fun isNewerVersion(local: String, remote: String): Boolean {
        val normalizedLocal = normalizeVersion(local)
        val normalizedRemote = normalizeVersion(remote)

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
     */
    private fun normalizeVersion(version: String): String {
        return version.replace('-', '.')
    }

    companion object {
        private const val TAG = "UpdateChecker"
        const val REPO_OWNER = "FreedoomForm"
        const val REPO_NAME = "-1" // ⚠️ Замените на имя вашего GitHub репозитория!
    }
}
