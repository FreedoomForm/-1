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

    /**
     * Скачивает APK и автоматически запускает установку.
     */
    suspend fun downloadAndInstall(updateInfo: UpdateInfo) {
        _state.value = InAppUpdateState.Downloading(0f)

        val checker = UpdateChecker(context)
        val apkFile = checker.downloadApk(updateInfo.downloadUrl) { progress ->
            _state.value = InAppUpdateState.Downloading(progress)
        }

        if (apkFile == null) {
            _state.value = InAppUpdateState.Error("APK yuklab bo'lmadi. Internet aloqasini tekshiring.")
            return
        }

        _state.value = InAppUpdateState.ReadyToInstall(apkFile)
        installApk(apkFile)
    }

    /**
     * Возвращает versionCode УСТАНОВЛЕННОГО приложения.
     */
    private fun getInstalledVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get installed version code", e)
            0
        }
    }

    /**
     * Извлекает versionCode из APK файла (не устанавливая его).
     * Возвращает null если не удалось прочитать.
     */
    private fun getApkVersionCode(apkFile: File): Int? {
        return try {
            // Файл должен быть читаемым для getPackageArchiveInfo
            apkFile.setReadable(true, false)
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode
                }
            } else {
                Log.w(TAG, "getPackageArchiveInfo returned null for ${apkFile.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read APK versionCode", e)
            null
        }
    }

    /**
     * Устанавливает APK через PackageInstaller API.
     * Требуется REQUEST_INSTALL_PACKAGES permission на Android 8+.
     */
    fun installApk(apkFile: File) {
        try {
            // ── Pre-check: сравниваем versionCode скачанного APK с установленным ──
            // Если APK не новее — PackageInstaller вернёт STATUS_FAILURE_INVALID
            // с невнятным сообщением. Ловим это заранее и показываем понятный текст.
            val installedVc = getInstalledVersionCode()
            val apkVc = getApkVersionCode(apkFile)
            Log.d(TAG, "Version pre-check: installed=$installedVc, apk=$apkVc")
            if (apkVc != null && apkVc <= installedVc) {
                _state.value = InAppUpdateState.Error(
                    "Yangi versiya eskiroq yoki teng joriyga (installed=$installedVc, apk=$apkVc). " +
                    "Iltimos, kutubxonasidan yangiroq relizni kuting."
                )
                Log.e(TAG, "Aborting install: APK versionCode ($apkVc) <= installed ($installedVc)")
                return
            }
            if (apkVc == null) {
                Log.w(TAG, "Could not read APK versionCode — proceeding with install attempt anyway")
            }

            // Регистрируем receiver для результата установки
            registerInstallReceiver()

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Записываем APK в сессию
            apkFile.inputStream().use { input ->
                val out = session.openWrite("package", 0, apkFile.length())
                input.copyTo(out)
                out.flush()
                session.fsync(out)
                out.close()
            }

            // Создаём Intent для получения результата установки
            val intent = Intent(ACTION_INSTALL_RESULT)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                INSTALL_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Коммитим сессию — запуск установки
            session.commit(pendingIntent.intentSender)
            _state.value = InAppUpdateState.Installing

            Log.d(TAG, "PackageInstaller session committed")
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed", e)
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
                                cleanup()
                            }
                            PackageInstaller.STATUS_FAILURE,
                            PackageInstaller.STATUS_FAILURE_ABORTED,
                            PackageInstaller.STATUS_FAILURE_BLOCKED,
                            PackageInstaller.STATUS_FAILURE_CONFLICT,
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                            PackageInstaller.STATUS_FAILURE_INVALID,
                            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                                Log.e(TAG, "Installation failed: status=$status, message=$message")
                                val hint = when (status) {
                                    PackageInstaller.STATUS_FAILURE_INVALID ->
                                        " (APK versiyasi eski yoki imzo mos emas)"
                                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                                        " (mavjud paket bilan konflikt — avval eski versiyani o'chiring)"
                                    PackageInstaller.STATUS_FAILURE_STORAGE ->
                                        " (yetarli joy yo'q)"
                                    PackageInstaller.STATUS_FAILURE_BLOCKED ->
                                        " (tizim blokladi — sozlamalardan ruxsat bering)"
                                    else -> ""
                                }
                                _state.value = InAppUpdateState.Error(
                                    "O'rnatish muvaffaqiyatsiz [status=$status]$hint: $message"
                                )
                                cleanup()
                            }
                            else -> {
                                Log.w(TAG, "Unknown install status: $status")
                                _state.value = InAppUpdateState.Error("Noma'lum xato [status=$status]: $message")
                                cleanup()
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
     * Очистка: удаляем скачанный APK и разрегистрируем receiver.
     */
    fun cleanup() {
        unregisterInstallReceiver()
        // Удаляем скачанный APK
        val apkFile = File(context.cacheDir, "update.apk")
        if (apkFile.exists()) {
            try { apkFile.delete() } catch (_: Exception) {}
        }
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
