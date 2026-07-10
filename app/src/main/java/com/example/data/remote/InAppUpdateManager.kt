package com.example.data.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Менеджер обновлений внутри приложения.
 *
 * Скачивает APK из GitHub Releases и устанавливает его
 * через PackageInstaller API — без браузера, без переходов.
 *
 * Процесс:
 * 1. downloadApk() — скачивает APK в кэш с прогрессом
 * 2. installApk() — запускает установку через PackageInstaller
 * 3. Пользователь подтверждает установку (один тап)
 * 4. Приложение обновляется и перезапускается
 *
 * Требования:
 * - Android 8+: REQUEST_INSTALL_PACKAGES permission
 * - APK должен быть подписан ТЕМ ЖЕ ключом что и текущее приложение
 *
 * ── ВАЖНЫЕ ИСПРАВЛЕНИЯ (конфликт папок / сессий) ──────────────────────────
 * Ранее при повторной установке или ошибке возникал «конфликт папок»:
 *   - PackageInstaller.Session не закрывался при ошибке → оставался
 *     висящий session, блокирующий новые установки того же packageName.
 *   - Файл `update.apk` переживал перезапуск и мог быть занят старой
 *     сессией, из-за чего новая запись в session.openWrite() падала с
 *     IOException / IllegalStateException.
 *   - BroadcastReceiver регистрировался повторно без проверки.
 *
 * Решение:
 *   1. Каждая установка использует уникальный APK-файл
 *      (`update_<timestamp>.apk`) — никакой конкуренции за один файл.
 *   2. Перед созданием новой сессии все старые сессии этого приложения
 *      принудительно abandon()-ятся (getMySessions()).
 *   3. Сессия хранится в поле и abandon()-ится в cleanup() / при ошибке.
 *   4. Файл APK удаляется ТОЛЬКО после успешной установки или при cleanup,
 *      не посреди записи.
 *   5. Receiver регистрируется через флаг RECEIVER_NOT_EXPORTED на API 33+
 *      и всегда оборачивается в try/catch.
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

    private var installReceiver: BroadcastReceiver? = null
    /** Активная сессия PackageInstaller — abandon()-ится при cleanup. */
    private var activeSession: PackageInstaller.Session? = null
    private var activeSessionId: Int = -1

    /**
     * Скачивает APK и автоматически запускает установку.
     */
    suspend fun downloadAndInstall(updateInfo: UpdateInfo) {
        _state.value = InAppUpdateState.Downloading(0f)

        // ── Очистка старого состояния ПЕРЕД новой установкой ───────────
        // Это устраняет «конфликт папок»: abandon старых сессий + удаление
        // старых APK-файлов, которые могли остаться от прошлой неудачной
        // установки.
        cleanupQuietly()

        val checker = UpdateChecker(context)
        // Уникальное имя файла — никакой конкуренции за `update.apk`.
        val apkFile = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
        // На всякий случай — если файл с таким именем чудом существует.
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

        _state.value = InAppUpdateState.ReadyToInstall(apkFileDownloaded)
        installApk(apkFileDownloaded)
    }

    /**
     * Устанавливает APK через PackageInstaller API.
     * Требуется REQUEST_INSTALL_PACKAGES permission на Android 8+.
     */
    fun installApk(apkFile: File) {
        var session: PackageInstaller.Session? = null
        try {
            // Регистрируем receiver для результата установки
            registerInstallReceiver()

            // ── abandon всех старых сессий ПЕРЕД созданием новой ────────
            // Это критично: PackageInstaller отказывается создавать новую
            // сессию, если уже есть активная с тем же packageName — отсюда
            // и «конфликт папок» при повторной установке.
            abandonAllMySessions()

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            // Указываем packageName явно — снижает риск конфликтов.
            params.setAppPackageName(context.packageName)

            val sessionId = packageInstaller.createSession(params)
            activeSessionId = sessionId
            session = packageInstaller.openSession(sessionId)
            activeSession = session

            // Записываем APK в сессию
            apkFile.inputStream().use { input ->
                val out = session.openWrite("package", 0, apkFile.length())
                try {
                    input.copyTo(out)
                    out.flush()
                    session.fsync(out)
                } finally {
                    out.close()
                }
            }

            // Создаём Intent для получения результата установки
            val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                INSTALL_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Коммитим сессию — запуск установки
            session.commit(pendingIntent.intentSender)
            // После commit сессия закрывается автоматически — не держим ссылку.
            activeSession = null
            _state.value = InAppUpdateState.Installing

            Log.d(TAG, "PackageInstaller session $sessionId committed")
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed", e)
            // abandon сессии при ошибке — иначе она зависнет и заблокирует
            // следующие установки.
            try { session?.abandon() } catch (_: Exception) {}
            try {
                if (activeSessionId != -1) {
                    context.packageManager.packageInstaller.abandonSession(activeSessionId)
                }
            } catch (_: Exception) {}
            activeSession = null
            activeSessionId = -1
            _state.value = InAppUpdateState.Error(
                "O'rnatish boshlanmadi: ${e.message}. Ilova sozlamalaridan \"Noma'lum manbalardan o'rnatish\" ruxsatini bering."
            )
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
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Принудительно abandon все активные сессии этого приложения.
     * Безопасно вызывать перед созданием новой сессии — устраняет конфликты.
     */
    private fun abandonAllMySessions() {
        try {
            val installer = context.packageManager.packageInstaller
            val mySessions = installer.mySessions
            for (s in mySessions) {
                try {
                    installer.abandonSession(s.sessionId)
                    Log.d(TAG, "Abandoned stale session ${s.sessionId}")
                } catch (e: Exception) {
                    // Сессия может быть уже закрыта — не критично.
                    Log.w(TAG, "Could not abandon session ${s.sessionId}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandonAllMySessions failed", e)
        }
    }

    private fun registerInstallReceiver() {
        unregisterInstallReceiver()

        installReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_INSTALL_RESULT -> {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

                        when (status) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                // Система запрашивает подтверждение пользователя
                                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                                }
                                if (confirmIntent != null) {
                                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(confirmIntent)
                                    Log.d(TAG, "User confirmation required — launched install confirm dialog")
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS -> {
                                Log.d(TAG, "Installation successful")
                                _state.value = InAppUpdateState.Installed
                                cleanupQuietly()
                            }
                            PackageInstaller.STATUS_FAILURE,
                            PackageInstaller.STATUS_FAILURE_ABORTED,
                            PackageInstaller.STATUS_FAILURE_BLOCKED,
                            PackageInstaller.STATUS_FAILURE_CONFLICT,
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                            PackageInstaller.STATUS_FAILURE_INVALID,
                            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                                Log.e(TAG, "Installation failed: status=$status, message=$message")
                                val friendlyMessage = when (status) {
                                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                                        "Konflikt: avvalgi o'rnatish bajarilmayapti. Iltimos ilovani qayta ishga tushiring va qayta urinib ko'ring."
                                    PackageInstaller.STATUS_FAILURE_STORAGE ->
                                        "Xotira yetarli emas — bo'sh joy oching va qayta urinib ko'ring."
                                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                        "APK ushbu qurilma bilan mos emas (imzo yoki versiya farqi)."
                                    PackageInstaller.STATUS_FAILURE_BLOCKED ->
                                        "O'rnatish bloklandi. Sozlamalar → Noma'lum manbalar ruxsatini tekshiring."
                                    PackageInstaller.STATUS_FAILURE_ABORTED ->
                                        "O'rnatish bekor qilindi."
                                    else -> "O'rnatish muvaffaqiyatsiz: $message"
                                }
                                _state.value = InAppUpdateState.Error(friendlyMessage)
                                cleanupQuietly()
                            }
                            else -> {
                                Log.w(TAG, "Unknown install status: $status")
                                _state.value = InAppUpdateState.Error("Noma'lum xato: $message")
                                cleanupQuietly()
                            }
                        }
                    }
                }
            }
        }

        try {
            val filter = IntentFilter(ACTION_INSTALL_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(installReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register install receiver", e)
        }
    }

    private fun unregisterInstallReceiver() {
        installReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        installReceiver = null
    }

    /**
     * Очистка: abandon сессии, разрегистрация receiver, удаление ВСЕХ
     * старых APK-файлов из кэша (update_*.apk). Полная очистка устраняет
     * «конфликт папок» при следующей установке.
     */
    fun cleanup() {
        cleanupQuietly()
        _state.value = InAppUpdateState.Idle
    }

    /**
     * Тихая очистка — не сбрасывает state. Безопасно вызывать из любого места.
     */
    private fun cleanupQuietly() {
        unregisterInstallReceiver()

        // abandon активной сессии, если ещё жива
        try {
            activeSession?.abandon()
        } catch (_: Exception) {}
        activeSession = null
        try {
            if (activeSessionId != -1) {
                context.packageManager.packageInstaller.abandonSession(activeSessionId)
            }
        } catch (_: Exception) {}
        activeSessionId = -1

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

    companion object {
        private const val TAG = "InAppUpdateManager"
        private const val ACTION_INSTALL_RESULT = "com.example.INSTALL_UPDATE_RESULT"
        private const val INSTALL_REQUEST_CODE = 54321
    }
}
