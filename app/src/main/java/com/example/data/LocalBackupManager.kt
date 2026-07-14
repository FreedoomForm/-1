package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Локальный менеджер авто-резервного копирования.
 *
 * Записывает .xlsx-бэкап в **публичную** папку загрузок устройства, которая
 * переживает удаление приложения. При повторной установке приложение
 * автоматически находит этот бэкап и восстанавливает данные — никаких
 * действий от пользователя не требуется.
 *
 * ── Куда пишется бэкап ────────────────────────────────────────────────────
 *
 * На Android 10+ (API 29+) используется `MediaStore.Downloads` с относительным
 * путём `ScooterRent/scooter_autobackup.xlsx`. Файл физически лежит в
 * `/sdcard/Download/ScooterRent/`. MediaStore — единственный надёжный способ
 * писать в публичные папки на scoped storage без `MANAGE_EXTERNAL_STORAGE`.
 *
 * На Android 9 и ниже (API 24-28) используется прямой `File` по пути
 * `Environment.getExternalStoragePublicDirectory(DOWNLOADS)/ScooterRent/`.
 * Разрешение `WRITE_EXTERNAL_STORAGE` (maxSdkVersion=28) уже объявлено в
 * манифесте.
 *
 * ── Почему не SAF (Storage Access Framework) ──────────────────────────────
 *
 * SAF-разрешения (`takePersistableUriPermission`) привязаны к установке
 * приложения и **удаляются при удалении приложения**. После повторной
 * установки persisted URI больше не действителен, и приложению пришлось бы
 * снова просить пользователя выбрать папку. Это ломает требование
 * «приложение само проверяет папку и восстанавливает данные».
 *
 * MediaStore не имеет этого ограничения: файл, записанный через MediaStore,
 * остаётся в публичной памяти и доступен тому же packageName после
 * переустановки.
 *
 * ── Почему не только Auto Backup for Apps ─────────────────────────────────
 *
 * Auto Backup (Google Cloud) требует Google-аккаунт, интернет, и не работает
 * на устройствах без Google Play Services (китайские OEM). Мы используем его
 * как дополнительный слой (см. `backup_rules.xml`), но основным механизмом
 * является локальный файл в Downloads — он работает офлайн и без Google.
 *
 * ── Поток работы ──────────────────────────────────────────────────────────
 *
 * 1. При запуске приложения (MainActivity) проверяем: если БД пуста (нет
 *    арендаторов) и в Downloads есть бэкап → автоматически восстанавливаем.
 * 2. После каждого изменения данных (добавление/редактирование арендатора,
 *    контракта, транзакции, карты) — debounced-запись нового бэкапа.
 * 3. Пользователь может вручную создать бэкап или восстановиться через
 *    существующий экспорт/импорт в Настройках.
 */
class LocalBackupManager(private val context: Context) {

    /**
     * Записывает бэкап в публичную папку Downloads/ScooterRent/.
     * Использует существующий BackupManager для формирования .xlsx.
     *
     * @return true при успехе, false при ошибке.
     */
    suspend fun writeBackup(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Сначала формируем .xlsx во временный файл в cacheDir.
            // BackupManager.exportToExcel уже умеет писать в любой OutputStream
            // (SAF URI), но для MediaStore нам нужен файл или OutputStream.
            // Используем временный файл, затем копируем в MediaStore/File.
            val tempFile = File(context.cacheDir, "autobackup_${System.currentTimeMillis()}.xlsx")
            val tempUri = Uri.fromFile(tempFile)

            // BackupManager.exportToExcel принимает URI и пишет в него через
            // ContentResolver. Uri.fromFile даёт file:// URI, который
            // ContentResolver умеет открывать на запись как обычный файл.
            val msg = BackupManager.exportToExcel(context, tempUri)
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e(TAG, "Temp backup file is empty or missing: $msg")
                tempFile.delete()
                return@withContext false
            }
            if (msg.startsWith("Xato")) {
                Log.e(TAG, "BackupManager.exportToExcel failed: $msg")
                tempFile.delete()
                return@withContext false
            }

            // Копируем tempFile в публичную папку Downloads/ScooterRent/
            val success = copyToPublicDownload(tempFile)
            tempFile.delete()
            success
        } catch (e: Exception) {
            Log.e(TAG, "writeBackup failed", e)
            false
        }
    }

    /**
     * Проверяет, существует ли бэкап в публичной папке Downloads/ScooterRent/.
     */
    suspend fun hasBackup(): Boolean = withContext(Dispatchers.IO) {
        findBackupUri() != null
    }

    /**
     * Восстанавливает данные из последнего бэкапа.
     * @return сообщение о результате (для Toast), либо null если бэкап не найден.
     */
    suspend fun restoreBackup(): String? = withContext(Dispatchers.IO) {
        try {
            val uri = findBackupUri() ?: run {
                Log.d(TAG, "No backup found in Downloads/ScooterRent/")
                return@withContext null
            }

            // BackupManager.importFromExcel принимает URI и читает через
            // ContentResolver. Для file:// URI это работает напрямую.
            // Для MediaStore content:// URI тоже работает.
            val msg = BackupManager.importFromExcel(context, uri)
            Log.d(TAG, "Restore result: $msg")
            msg
        } catch (e: Exception) {
            Log.e(TAG, "restoreBackup failed", e)
            "Xato: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ── Внутренние методы ──────────────────────────────────────────────────

    /**
     * Копирует файл в публичную папку Downloads/ScooterRent/scooter_autobackup.xlsx.
     * На API 29+ использует MediaStore, на API 24-28 — прямой File.
     */
    private fun copyToPublicDownload(tempFile: File): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyToPublicDownloadViaMediaStore(tempFile)
        } else {
            copyToPublicDownloadViaFile(tempFile)
        }
    }

    /**
     * API 29+: запись через MediaStore.Downloads.
     *
     * Файл записывается с relative path "ScooterRent/" внутри Downloads.
     * Если файл с таким именем уже существует — он перезаписывается
     * (удаляем старый по displayName + relative path, затем вставляем новый).
     */
    private fun copyToPublicDownloadViaMediaStore(tempFile: File): Boolean {
        val resolver = context.contentResolver
        val displayName = BACKUP_FILENAME

        // Удаляем старый бэкап с тем же именем, если есть — иначе MediaStore
        // создаст файл с суффиксом (1), (2) и т.д., и мы потеряем единый файл.
        deleteExistingBackupMediaStore()

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE_XLSX)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH_DOWNLOADS)
            // IS_PENDING сигнализирует, что файл в процессе записи —
            // другие приложения не увидят его до завершения.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values)
            ?: run {
                Log.e(TAG, "MediaStore.insert returned null — cannot create backup file")
                return false
            }

        return try {
            resolver.openOutputStream(itemUri, "w")?.use { out ->
                tempFile.inputStream().use { inp ->
                    inp.copyTo(out)
                    out.flush()
                }
            } ?: run {
                Log.e(TAG, "Cannot open OutputStream for $itemUri")
                resolver.delete(itemUri, null, null)
                return false
            }

            // Помечаем файл как готовый — снимаем IS_PENDING.
            val updateValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, updateValues, null, null)
            Log.d(TAG, "Backup written to MediaStore: $itemUri (${tempFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write backup to MediaStore", e)
            try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
            false
        }
    }

    /**
     * Удаляет существующий бэкап из MediaStore (по displayName + relative path).
     */
    private fun deleteExistingBackupMediaStore() {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(BACKUP_FILENAME)
            resolver.delete(collection, selection, selectionArgs)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete existing backup via MediaStore: ${e.message}")
        }
    }

    /**
     * API 24-28: прямая запись через File в
     * Environment.getExternalStoragePublicDirectory(DOWNLOADS)/ScooterRent/.
     */
    private fun copyToPublicDownloadViaFile(tempFile: File): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val backupDir = File(downloadsDir, BACKUP_FOLDER)
            if (!backupDir.exists()) backupDir.mkdirs()
            val targetFile = File(backupDir, BACKUP_FILENAME)

            tempFile.inputStream().use { inp ->
                targetFile.outputStream().use { out ->
                    inp.copyTo(out)
                    out.flush()
                }
            }
            Log.d(TAG, "Backup written to ${targetFile.absolutePath} (${tempFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write backup via File API", e)
            false
        }
    }

    /**
     * Находит URI последнего бэкапа в Downloads/ScooterRent/.
     * @return URI или null, если бэкап не найден.
     */
    private fun findBackupUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findBackupUriViaMediaStore()
        } else {
            findBackupUriViaFile()
        }
    }

    /**
     * API 29+: поиск через MediaStore.
     */
    private fun findBackupUriViaMediaStore(): Uri? {
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(BACKUP_FILENAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idColumn)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    Log.d(TAG, "Found backup in MediaStore: $uri")
                    uri
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find backup via MediaStore", e)
            null
        }
    }

    /**
     * API 24-28: поиск через прямой File.
     * Возвращает file:// URI или null.
     */
    private fun findBackupUriViaFile(): Uri? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val backupFile = File(downloadsDir, "$BACKUP_FOLDER/$BACKUP_FILENAME")
            if (backupFile.exists() && backupFile.length() > 0) {
                Log.d(TAG, "Found backup at ${backupFile.absolutePath}")
                Uri.fromFile(backupFile)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find backup via File API", e)
            null
        }
    }

    companion object {
        private const val TAG = "LocalBackupManager"
        private const val BACKUP_FOLDER = "ScooterRent"
        private const val BACKUP_FILENAME = "scooter_autobackup.xlsx"
        private const val MIME_TYPE_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val RELATIVE_PATH_DOWNLOADS = "Download/ScooterRent/"
    }
}
