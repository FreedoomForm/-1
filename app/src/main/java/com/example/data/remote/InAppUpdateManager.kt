package com.example.data.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Менеджер обновлений внутри приложения.
 *
 * Скачивает APK из GitHub Releases и устанавливает его
 * через стандартный системный установщик (Intent.ACTION_VIEW + FileProvider).
 *
 * Процесс:
 * 1. downloadApk() — скачивает APK в кэш с прогрессом
 * 2. installApk() — запускает системный установщик через ACTION_VIEW
 * 3. Пользователь подтверждает установку в системном диалоге
 * 4. Приложение обновляется и перезапускается
 *
 * Требования:
 * - Android 8+: REQUEST_INSTALL_PACKAGES permission
 * - APK должен быть подписан ТЕМ ЖЕ ключом что и текущее приложение
 *
 * ── ВАЖНОЕ ИЗМЕНЕНИЕ ────────────────────────────────────────────────────
 * Ранее использовался PackageInstaller API + runtime BroadcastReceiver.
 * Этот подход нестабилен:
 *   - BroadcastReceiver регистрируется в рантайме — на Android 14+ и на
 *     некоторых OEM-сборках (Xiaomi, Huawei, Samsung) broadcast не доходит.
 *   - PendingIntent + STATUS_PENDING_USER_ACTION имеет тонкие баги на
 *     разных версиях Android — система не всегда добавляет EXTRA_INTENT.
 *   - PackageInstaller.Session может "зависать" при ошибках, блокируя
 *     следующие установки того же packageName.
 * Стандартный и надёжный путь — Intent.ACTION_VIEW + FileProvider.
 * Системный установщик сам показывает диалог подтверждения и обрабатывает
 * все ошибки. Этот подход работает на всех версиях Android и всех OEM.
 */
sealed class InAppUpdateState {
    object Idle : InAppUpdateState()
    data class Downloading(val progress: Float) : InAppUpdateState()
    data class ReadyToInstall(val apkFile: File) : InAppUpdateState()
    object Installing : InAppUpdateState()
    data class Error(val message: String) : InAppUpdateState()
    object Installed : InAppUpdateState()
}

class InAppUpdateManager(private val context: Context) {

    private val _state = MutableStateFlow<InAppUpdateState>(InAppUpdateState.Idle)
    val state: StateFlow<InAppUpdateState> = _state

    /**
     * Скачивает APK и автоматически запускает установку.
     */
    suspend fun downloadAndInstall(updateInfo: UpdateInfo) {
        _state.value = InAppUpdateState.Downloading(0f)

        // Очистка старого состояния ПЕРЕД новой установкой
        cleanupQuietly()

        val checker = UpdateChecker(context)
        // Уникальное имя файла — никакой конкуренции за `update.apk`.
        val apkFile = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
        if (apkFile.exists()) {
            try { apkFile.delete() } catch (_: Exception) {}
        }

        val apkFileDownloaded = checker.downloadApkTo(
            downloadUrl = updateInfo.downloadUrl,
            targetFile = apkFile
        ) { progress ->
            _state.value = InAppUpdateState.Downloading(progress)
        }

        if (apkFileDownloaded == null) {
            _state.value = InAppUpdateState.Error("APK yuklab bo'lmadi. Internet aloqasini tekshiring.")
            cleanupQuietly()
            return
        }

        // Проверяем, что APK действительно скачался и имеет разумный размер
        if (apkFileDownloaded.length() < 1024 * 100) { // < 100KB — явно не валидный APK
            _state.value = InAppUpdateState.Error(
                "Yuklangan fayl hajmi juda kichik (${apkFileDownloaded.length()} bayt). " +
                "Ehtimol GitHub'da APK hali yuklanmagan yoki internet aloqasi uzildi."
            )
            cleanupQuietly()
            return
        }

        _state.value = InAppUpdateState.ReadyToInstall(apkFileDownloaded)
        installApk(apkFileDownloaded)
    }

    /**
     * Запускает системный установщик APK через Intent.ACTION_VIEW + FileProvider.
     *
     * Это стандартный, надёжный способ установки APK на всех версиях Android.
     * Системный установщик сам показывает диалог подтверждения и обрабатывает
     * все ошибки (несовпадение подписи, нехватка места, и т.д.).
     *
     * Требуется REQUEST_INSTALL_PACKAGES permission на Android 8+.
     */
    fun installApk(apkFile: File) {
        try {
            // ── Предпроверка: APK versionCode должен быть > текущего ────────
            // Если APK имеет тот же или меньший versionCode, Android откажется
            // устанавливать его поверх существующего приложения с ошибкой
            // INSTALL_FAILED_VERSION_DOWNGRADE. Проверяем заранее.
            val installedVersionCode = getInstalledVersionCode()
            val apkVersionCode = getApkVersionCode(apkFile)
            Log.d(TAG, "Pre-install check: installed=$installedVersionCode, apk=$apkVersionCode, file=${apkFile.name} (${apkFile.length()} bytes)")
            if (apkVersionCode != null && installedVersionCode != null && apkVersionCode <= installedVersionCode) {
                _state.value = InAppUpdateState.Error(
                    "Yangi versiya topildi, lekin APK fayl versionCode ($apkVersionCode) joriy versiyadan kichik yoki teng ($installedVersionCode). " +
                    "Iltimos, GitHub'dagi so'nggi release-ni qayta yuklang yoki dasturchi bilan bog'laning."
                )
                cleanupQuietly()
                return
            }

            // ── Проверка разрешения на установку из неизвестных источников ──
            if (!canInstallFromUnknownSources()) {
                _state.value = InAppUpdateState.Error(
                    "Ilova sozlamalaridan \"Noma'lum manbalardan o'rnatish\" ruxsatini bering."
                )
                openInstallPermissionSettings()
                return
            }

            // ── Делаем файл читаемым для системы ────────────────────────────
            // Файлы в cacheDir приватны — FileProvider выдаст URI с правом
            // чтения, но на всякий случай делаем файл world-readable.
            apkFile.setReadable(true, false)

            // ── Получаем URI через FileProvider ─────────────────────────────
            // content://com.aistudio.scooterrent.xyzab.fileprovider/cache/update_xxx.apk
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = try {
                FileProvider.getUriForFile(context, authority, apkFile)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "FileProvider.getUriForFile failed — authority=$authority", e)
                _state.value = InAppUpdateState.Error(
                    "Fayl provayder sozlanmadi: ${e.message}. Iltimos dasturchi bilan bog'laning."
                )
                cleanupQuietly()
                return
            }

            // ── Создаём Intent для запуска системного установщика ───────────
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // На некоторых устройствах нужны дополнительные флаги
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }

            // ── Проверяем, что есть приложение для обработки Intent'а ────────
            val packageManager = context.packageManager
            val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (activities.isNullOrEmpty()) {
                _state.value = InAppUpdateState.Error(
                    "Tizimda APK o'rnatuvchi topilmadi. Bu g'ayrioddiy holat — qurilma dasturchisi bilan bog'laning."
                )
                cleanupQuietly()
                return
            }

            // ── Даём разрешение на чтение URI всем подходящим приложениям ────
            // Это страховка — FLAG_GRANT_READ_URI_PERMISSION обычно достаточно,
            // но на некоторых OEM-сборках нужно явно выдать разрешение.
            for (resolveInfo in activities) {
                val packageName = resolveInfo.activityInfo.packageName
                try {
                    context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot grant URI permission to $packageName: ${e.message}")
                }
            }

            _state.value = InAppUpdateState.Installing
            Log.d(TAG, "Launching system installer for ${apkFile.name} (${apkFile.length()} bytes), URI=$uri")

            context.startActivity(intent)
            // После startActivity системный установщик берёт управление на себя.
            // Пользователь видит системный диалог "Установить?" и подтверждает.
            // При успешной установке приложение перезапускается автоматически.
            // При отмене — пользователь возвращается в приложение, state остаётся
            // "Installing" — пользователь может нажать "Yopish" (Close) чтобы сбросить.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            _state.value = InAppUpdateState.Error(
                "O'rnatishni boshlab bo'lmadi: ${e.message}. " +
                "Ilova sozlamalaridan \"Noma'lum manbalardan o'rnatish\" ruxsatini bering va qayta urinib ko'ring."
            )
            cleanupQuietly()
        }
    }

    /**
     * Проверяет, есть ли разрешение на установку из неизвестных источников.
     */
    fun canInstallFromUnknownSources(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // На Android < 8 разрешение не требуется
        }
    }

    /**
     * Открывает настройки для предоставления разрешения на установку.
     */
    fun openInstallPermissionSettings() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: открываем настройки приложения
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot open install permission settings", e2)
            }
        }
    }

    /**
     * Очистка: удаляет все старые APK-файлы из кэша (update_*.apk).
     * Полная очистка устраняет конфликты при следующей установке.
     */
    fun cleanup() {
        cleanupQuietly()
        _state.value = InAppUpdateState.Idle
    }

    /**
     * Тихая очистка — не сбрасывает state. Безопасно вызывать из любого места.
     */
    private fun cleanupQuietly() {
        // Удаляем ВСЕ update_*.apk из cacheDir — никаких остатков
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles { f ->
                f.name.startsWith("update_") && f.name.endsWith(".apk")
            }?.forEach { f ->
                try { f.delete() } catch (_: Exception) {}
            }
            // Также удаляем старый файл "update.apk" от прежних версий менеджера
            val legacy = File(cacheDir, "update.apk")
            if (legacy.exists()) {
                try { legacy.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun reset() {
        _state.value = InAppUpdateState.Idle
    }

    /**
     * Возвращает versionCode установленного приложения или null при ошибке.
     */
    private fun getInstalledVersionCode(): Int? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get installed versionCode", e)
            null
        }
    }

    /**
     * Читает versionCode из APK-файла через PackageManager.getPackageArchiveInfo.
     * Возвращает null, если файл не является валидным APK или чтение не удалось.
     */
    private fun getApkVersionCode(apkFile: File): Int? {
        return try {
            apkFile.setReadable(true, false)
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode?.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.versionCode
            }
            Log.d(TAG, "APK versionCode from archive: $code, package: ${packageInfo?.packageName}")
            code
        } catch (e: Exception) {
            Log.w(TAG, "Could not read APK versionCode", e)
            null
        }
    }

    companion object {
        private const val TAG = "InAppUpdateManager"
    }
}
