package com.example.data.remote

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Проверяет обновления приложения через GitHub Releases API.
 *
 * Ключевые принципы (v3):
 * 1. Сравнение по versionCode (целое число) — НАДЁЖНЕЕ чем versionName (строка)
 * 2. Тег релиза на GitHub должен быть числом = versionCode (например "3")
 *    ЛИБО можно указать versionCode в body релиза: "versionCode:3"
 * 3. Если версия одинаковая или новее — updateInfo = null (НЕ показываем уведомление)
 * 4. Поддержка GitHub Personal Access Token через BuildConfig.GITHUB_API_TOKEN
 *    — поднимает лимит с 60 запросов/час (без токена) до 5000/час (с токеном).
 *    Без токена мобильные операторы с NAT быстро исчерпывают 60-запросный лимит,
 *    и пользователь видит «Versiyalar topilmadi» хотя интернет работает.
 * 5. Дисковый кэш успешных ответов в SharedPreferences на 1 час — устраняет
 *    повторные сетевые запросы при перевкладывании в Settings и перевороте экрана.
 * 6. Различение типов ошибок: rate-limit / network / no-apk / unknown.
 * 7. Одна повторная попытка с коротким backoff для транзиентных сетевых ошибок.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val fileSize: Long,
    val publishDate: String,
    /**
     * Название релиза (release name/title) на GitHub — обычно это заголовок
     * коммита, который был указан при создании релиза. Отображается в списке
     * версий на странице настроек как «название коммита версии».
     * Может быть пустым, если релиз создан без названия — тогда UI покажет
     * только versionName.
     */
    val releaseName: String = ""
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

/**
 * Результат загрузки списка всех релизов.
 *
 * Заменил простой `List<UpdateInfo>` на запечатанный тип, чтобы UI мог
 * показать пользователю **реальную** причину ошибки вместо собирательного
 * «проверьте интернет». Старый API `fetchAllReleases(): List<UpdateInfo>`
 * всё ещё доступен как тонкая обёртка над [fetchAllReleasesDetailed].
 */
sealed class FetchReleasesResult {
    /**
     * Успешный результат. [fromCache] = true если данные пришли с дискового
     * кэша (а не из сети). UI может показать бейдж «кэш».
     */
    data class Success(
        val releases: List<UpdateInfo>,
        val fromCache: Boolean = false
    ) : FetchReleasesResult()

    /**
     * GitHub вернул 403 с указанием на rate limit. Это частая причина
     * ошибки «Versiyalar topilmadi» с мобильной сети — 60 запросов/час
     * с одного IP на NAT-операторе быстро кончаются.
     *
     * [retryAfterSeconds] — сколько секунд осталось до сброса лимита,
     * если GitHub прислал `X-RateLimit-Reset`. -1 если заголовка не было.
     */
    data class RateLimited(
        val retryAfterSeconds: Long = -1
    ) : FetchReleasesResult()

    /** Сетевая ошибка (нет интернета, таймаут, DNS, обрыв соединения). */
    data class NetworkError(val cause: Throwable) : FetchReleasesResult()

    /** Репозиторий или API endpoint вернул 404/410 — неверный REPO_OWNER/REPO_NAME. */
    object NotFound : FetchReleasesResult()

    /** HTTP-ошибка, не классифицированная выше (5xx, 401, неожиданный 4xx). */
    data class HttpError(val code: Int, val body: String) : FetchReleasesResult()

    /**
     * API вернул 200, но ни в одном релизе нет .apk-asset. Обычно означает,
     * что CI не успел загрузить APK, либо репозиторий не содержит APK-релизов.
     */
    object NoApkInReleases : FetchReleasesResult()
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
            applyDefaultHeaders(connection)
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "GitHub API returned HTTP $responseCode: $errorBody")
                return@withContext Pair(UpdateCheckResult.ERROR, null)
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(json)

            val tagName = release.optString("tag_name", "").removePrefix("v")
            val releaseName = release.optString("name", "")
            val releaseNotes = release.optString("body", "")
            val publishDate = release.optString("published_at", "")

            val remoteVersionCode = parseVersionCode(tagName, releaseNotes)

            Log.d(TAG, "Latest GitHub release: tag=$tagName, remoteVersionCode=$remoteVersionCode")

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

            if (remoteVersionCode != null) {
                if (currentVersionCode >= remoteVersionCode) {
                    Log.d(TAG, "App is up to date (versionCode: $currentVersionCode >= $remoteVersionCode)")
                    return@withContext Pair(UpdateCheckResult.UP_TO_DATE, null)
                }
            } else {
                if (!isNewerVersion(currentVersionName, tagName)) {
                    Log.d(TAG, "App is up to date (versionName: $currentVersionName >= $tagName)")
                    return@withContext Pair(UpdateCheckResult.UP_TO_DATE, null)
                }
            }

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
                    publishDate = publishDate,
                    releaseName = releaseName
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
     * «конфликт папок», когда старый файл ещё занят предыдущей установкой.
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

            if (!partFile.renameTo(targetFile)) {
                partFile.inputStream().use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                try { partFile.delete() } catch (_: Exception) {}
            }

            Log.d(TAG, "APK downloaded to ${targetFile.name}: ${targetFile.length()} bytes")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download APK to ${targetFile.name}", e)
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
        tagName.toIntOrNull()?.let { return it }

        val regex = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        regex.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

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
        return false
    }

    /**
     * Нормализует строку версии: заменяет "-" на "."
     */
    private fun normalizeVersion(version: String): String {
        return version.replace('-', '.')
    }

    // ─────────────────────────────────────────────────────────────────────
    //  НОВОЕ (v3): запечённый результат вместо List<UpdateInfo>
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Тонкая обёртка над [fetchAllReleasesDetailed] для обратной совместимости
     * с кодом, который ждёт `List<UpdateInfo>`. Возвращает пустой список при
     * любой ошибке.
     *
     * НОВЫЙ код должен вызывать [fetchAllReleasesDetailed] напрямую, чтобы
     * получить дифференцированную ошибку.
     */
    suspend fun fetchAllReleases(): List<UpdateInfo> {
        return when (val r = fetchAllReleasesDetailed()) {
            is FetchReleasesResult.Success -> r.releases
            else -> emptyList()
        }
    }

    /**
     * Получает СПИСОК всех релизов из GitHub Releases (не только последний).
     *
     * Возвращает [FetchReleasesResult], различающий:
     *  - [FetchReleasesResult.Success] — успешный ответ (возможно из кэша)
     *  - [FetchReleasesResult.RateLimited] — 403 + rate limit (частая причина
     *    ошибки на мобильной сети)
     *  - [FetchReleasesResult.NetworkError] — нет интернета / таймаут
     *  - [FetchReleasesResult.NotFound] — 404 (неверный REPO_OWNER/REPO_NAME)
     *  - [FetchReleasesResult.HttpError] — прочие HTTP-ошибки
     *  - [FetchReleasesResult.NoApkInReleases] — 200, но ни один релиз не
     *    содержит .apk-asset
     *
     * Алгоритм:
     * 1. Если есть свежий кэш (< 1 часа) — возвращаем его с флагом fromCache=true.
     * 2. Иначе делаем сетевой запрос (с токеном если задан).
     * 3. При сетевой ошибке делаем 1 retry с 1.5-секундной паузой.
     * 4. При 403-rate-limit — не делаем retry (бесполезно), возвращаем RateLimited.
     * 5. При успехе сохраняем в кэш.
     * 6. Если сетевой запрос провалился И есть устаревший кэш (< 24 часов) —
     *    возвращаем устаревший кэш с fromCache=true (лучше старые данные,
     *    чем никаких).
     */
    suspend fun fetchAllReleasesDetailed(): FetchReleasesResult = withContext(Dispatchers.IO) {
        // ── 1. Проверяем свежий кэш (TTL = 1 час) ─────────────────────────
        val cachedNow = readCache(maxAgeMs = CACHE_TTL_MS)
        if (cachedNow != null) {
            Log.d(TAG, "fetchAllReleases: returning fresh cache (${cachedNow.size} releases)")
            return@withContext FetchReleasesResult.Success(cachedNow, fromCache = true)
        }

        // ── 2. Сетевой запрос с одной повторной попыткой ──────────────────
        val result = fetchAllReleasesFromNetworkWithRetry()

        // ── 3. Сохраняем в кэш при успехе ─────────────────────────────────
        if (result is FetchReleasesResult.Success && !result.fromCache) {
            writeCache(result.releases)
        }

        // ── 4. Fallback на устаревший кэш при сетевой ошибке ─────────────
        if (result is FetchReleasesResult.NetworkError ||
            result is FetchReleasesResult.RateLimited ||
            result is FetchReleasesResult.HttpError
        ) {
            val staleCache = readCache(maxAgeMs = STALE_CACHE_TTL_MS)
            if (staleCache != null) {
                Log.w(TAG, "fetchAllReleases: network failed ($result), returning stale cache (${staleCache.size} releases)")
                return@withContext FetchReleasesResult.Success(staleCache, fromCache = true)
            }
        }

        result
    }

    /**
     * Делает один сетевой запрос; при транзиентной сетевой ошибке (timeout,
     * UnknownHostException, SocketException) — одна повторная попытка с
     * короткой паузой. При 4xx не ретраит (бесполезно — код ошибки тот же).
     */
    private suspend fun fetchAllReleasesFromNetworkWithRetry(): FetchReleasesResult {
        val first = tryFetchAllReleasesOnce()
        if (first !is FetchReleasesResult.NetworkError) return first

        Log.w(TAG, "fetchAllReleases: first attempt failed with NetworkError (${first.cause.javaClass.simpleName}), retrying in 1500ms")
        delay(1500)
        return tryFetchAllReleasesOnce()
    }

    /**
     * Один сетевой запрос к GitHub Releases API. Не ретраит сам.
     */
    private fun tryFetchAllReleasesOnce(): FetchReleasesResult {
        return try {
            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=50"
            val connection = URL(apiUrl).openConnection() as java.net.HttpURLConnection
            applyDefaultHeaders(connection)
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "GitHub API returned HTTP $responseCode: $errorBody")

                // 403 — почти всегда rate limit
                if (responseCode == 403) {
                    val resetEpochSec = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: -1L
                    val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: -1L
                    val retryAfterSec = when {
                        retryAfter > 0 -> retryAfter
                        resetEpochSec > 0 -> (resetEpochSec - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                        else -> -1L
                    }
                    return FetchReleasesResult.RateLimited(retryAfterSec)
                }
                if (responseCode == 404 || responseCode == 410) {
                    return FetchReleasesResult.NotFound
                }
                return FetchReleasesResult.HttpError(responseCode, errorBody)
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(json)
            val result = mutableListOf<UpdateInfo>()

            for (i in 0 until releases.length()) {
                try {
                    val release = releases.getJSONObject(i)
                    val tagName = release.optString("tag_name", "").removePrefix("v")
                    val releaseName = release.optString("name", "")
                    val releaseNotes = release.optString("body", "")
                    val publishDate = release.optString("published_at", "")

                    val assets = release.optJSONArray("assets") ?: continue
                    var downloadUrl: String? = null
                    var fileSize: Long = 0
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            fileSize = asset.optLong("size", 0)
                            break
                        }
                    }
                    if (downloadUrl == null) continue

                    val versionCode = parseVersionCode(tagName, releaseNotes)
                        ?: tagName.split(".").lastOrNull()?.toIntOrNull()
                        ?: (i + 1)

                    result.add(
                        UpdateInfo(
                            versionName = tagName,
                            versionCode = versionCode,
                            downloadUrl = downloadUrl,
                            releaseNotes = releaseNotes,
                            fileSize = fileSize,
                            publishDate = publishDate,
                            releaseName = releaseName
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse release at index $i", e)
                }
            }

            if (result.isEmpty()) {
                // Могло быть 0 релизов вообще, ИЛИ релизы есть, но ни в одном
                // нет .apk. Различаем по длине массива.
                if (releases.length() > 0) {
                    return FetchReleasesResult.NoApkInReleases
                }
                // Релизов совсем нет — это не ошибка, просто пустой список.
                // Возвращаем Success с пустым списком (UI покажет «нет версий»).
            }

            // Сортируем по убыванию versionCode (новые версии сверху)
            FetchReleasesResult.Success(result.sortedByDescending { it.versionCode }, fromCache = false)
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "UnknownHostException (no DNS / no internet)", e)
            FetchReleasesResult.NetworkError(e)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "SocketTimeoutException", e)
            FetchReleasesResult.NetworkError(e)
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "ConnectException", e)
            FetchReleasesResult.NetworkError(e)
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "SSLException", e)
            FetchReleasesResult.NetworkError(e)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "IOException (network-related)", e)
            FetchReleasesResult.NetworkError(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching releases", e)
            FetchReleasesResult.NetworkError(e)
        }
    }

    /**
     * Применяет заголовки по умолчанию к GitHub HTTP-запросу:
     *  - Accept: application/vnd.github.v3+json
     *  - User-Agent: обязателен для GitHub API (иначе 403)
     *  - Authorization: Bearer <token> если задан BuildConfig.GITHUB_API_TOKEN
     *    и он не пустой. Поднимает лимит с 60 → 5000 запросов/час.
     */
    private fun applyDefaultHeaders(connection: java.net.HttpURLConnection) {
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "ScooterRent-App-Update-Checker")
        val token = githubToken()
        if (token.isNotEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
            Log.d(TAG, "Using GitHub API token (length=${token.length})")
        } else {
            Log.d(TAG, "No GitHub API token — using unauthenticated request (60/hour limit)")
        }
    }

    /**
     * Читает токен из BuildConfig.GITHUB_API_TOKEN (внедряется secrets-gradle-plugin
     * из .env). Возвращает пустую строку если токен не задан.
     */
    private fun githubToken(): String {
        return try {
            // BuildConfig генерируется в пакете com.example — обращаемся через рефлексию,
            // чтобы не плодить compile-time зависимость на классе, который может
            // отсутствовать в unit-тестах. В рантайме Android-сборки класс всегда есть.
            val clazz = Class.forName("com.example.BuildConfig")
            val field = clazz.getField("GITHUB_API_TOKEN")
            val raw = (field.get(null) as? String)?.trim().orEmpty()
            // Отбрасываем placeholder и пустую строку — в этих случаях токена реально нет,
            // и запрос должен идти без Authorization header (unauthenticated, 60 req/hour).
            // Без этой проверки приложение отправило бы placeholder как Bearer-токен,
            // и GitHub API вернул бы 401 Unauthorized.
            if (raw.isEmpty() ||
                raw.equals("PLACEHOLDER_REPLACE_VIA_CI_SECRET", ignoreCase = true) ||
                raw.startsWith("MY_GITHUB") ||
                raw.startsWith("YOUR_GITHUB")
            ) {
                ""
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.w(TAG, "BuildConfig.GITHUB_API_TOKEN not available", e)
            ""
        }
    }

    // ── Дисковый кэш ──────────────────────────────────────────────────────

    /**
     * Читает кэш из SharedPreferences если он свежее [maxAgeMs].
     * Возвращает null если кэша нет, он устарел, или не парсится.
     */
    private fun readCache(maxAgeMs: Long): List<UpdateInfo>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedAt = prefs.getLong(KEY_CACHE_SAVED_AT, 0L)
            if (savedAt == 0L) return null
            val ageMs = System.currentTimeMillis() - savedAt
            if (ageMs > maxAgeMs) return null

            val json = prefs.getString(KEY_CACHE_RELEASES, null) ?: return null
            val arr = JSONArray(json)
            val result = mutableListOf<UpdateInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    UpdateInfo(
                        versionName = o.optString("versionName"),
                        versionCode = o.optInt("versionCode"),
                        downloadUrl = o.optString("downloadUrl"),
                        releaseNotes = o.optString("releaseNotes"),
                        fileSize = o.optLong("fileSize"),
                        publishDate = o.optString("publishDate"),
                        releaseName = o.optString("releaseName")
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read releases cache", e)
            null
        }
    }

    /**
     * Сохраняет список релизов в SharedPreferences. Дешёвая операция —
     * ~50 релизов × ~500 байт = ~25 КБ.
     */
    private fun writeCache(releases: List<UpdateInfo>) {
        try {
            val arr = JSONArray()
            for (r in releases) {
                val o = JSONObject()
                o.put("versionName", r.versionName)
                o.put("versionCode", r.versionCode)
                o.put("downloadUrl", r.downloadUrl)
                o.put("releaseNotes", r.releaseNotes)
                o.put("fileSize", r.fileSize)
                o.put("publishDate", r.publishDate)
                o.put("releaseName", r.releaseName)
                arr.put(o)
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putString(KEY_CACHE_RELEASES, arr.toString())
                putLong(KEY_CACHE_SAVED_AT, System.currentTimeMillis())
            }
            Log.d(TAG, "Cached ${releases.size} releases to SharedPreferences")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write releases cache", e)
        }
    }

    /**
     * Очищает кэш. Вызывается когда пользователь нажимает «Qayta urinish»
     * (Retry) — мы хотим гарантированно сделать свежий запрос.
     */
    fun clearCache() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                remove(KEY_CACHE_RELEASES)
                remove(KEY_CACHE_SAVED_AT)
            }
            Log.d(TAG, "Releases cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear releases cache", e)
        }
    }

    /**
     * Возвращает человекочитаемое описание для пользователя на узбекском.
     * Используется UI-слоем для отображения конкретной причины ошибки.
     */
    fun userFacingMessage(result: FetchReleasesResult): String {
        return when (result) {
            is FetchReleasesResult.Success -> ""
            is FetchReleasesResult.RateLimited -> {
                if (result.retryAfterSeconds > 0) {
                    val mins = (result.retryAfterSeconds + 59) / 60
                    "GitHub API so'rovlari chegarasi tugadi. ~${mins} daqiqadan so'ng qayta urinib ko'ring."
                } else {
                    "GitHub API so'rovlari chegarasi tugadi. Iltimos keyinroq urinib ko'ring."
                }
            }
            is FetchReleasesResult.NetworkError ->
                "Internetga ulanib bo'lmadi. Tarmoq aloqasini tekshiring."
            FetchReleasesResult.NotFound ->
                "GitHub repozitoriyasi topilmadi. Iltimos dasturchi bilan bog'laning."
            is FetchReleasesResult.HttpError ->
                "GitHub serveri xato qaytardi (HTTP ${result.code}). Iltimos keyinroq urinib ko'ring."
            FetchReleasesResult.NoApkInReleases ->
                "Relizlarda APK fayl topilmadi. Iltimos dasturchi bilan bog'laning."
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        const val REPO_OWNER = "FreedoomForm"
        const val REPO_NAME = "-1"  // Да, репозиторий реально называется "-1"

        private const val PREFS_NAME = "scooter_rent_update_cache"
        private const val KEY_CACHE_RELEASES = "releases_json"
        private const val KEY_CACHE_SAVED_AT = "releases_saved_at_ms"

        /** Свежий кэш: 1 час. В пределах этого окна возвращаем кэш без сети. */
        private const val CACHE_TTL_MS = 60L * 60 * 1000

        /** Устаревший кэш: 24 часа. Используется как fallback при сетевой ошибке. */
        private const val STALE_CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
