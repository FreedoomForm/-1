package com.example.data.remote

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.NotificationHistoryEntity
import com.example.data.Renter
import com.example.data.Scooter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Синхронизация Room ↔ API.
 *
 * Стратегия v3 (исправленная — данные больше не исчезают):
 *  • smartMerge()     — умное слияние: серверные данные обновляют локальные,
 *                       локальные изменения отправляются на сервер.
 *                       НЕ удаляет локальные данные.
 *  • pullAll()        — полное обновление при первом логине
 *                       (заменяет Room-данные данными с сервера).
 *  • push*()          — после локального изменения шлёт запись на сервер.
 *  • syncAll()        — полная синхронизация (SmartMerge → Push),
 *                       используется кнопкой «Sinxronizatsiya».
 *
 *  КЛЮЧЕВОЕ ИЗМЕНЕНИЕ v3:
 *  Авто-синхронизация теперь использует smartMerge() вместо pullAll().
 *  smartMerge() НЕ удаляет локальные данные — он обновляет существующие
 *  и добавляет новые с сервера. Это устраняет проблему, когда арендатор
 *  создавался локально, а затем исчезал из-за pullAll().
 */
class SyncManager(
    private val context: Context,
    private val api: ApiService = ApiClient.getService(context),
    private val db: AppDatabase = AppDatabase.getDatabase(context)
) {
    /**
     * Умное слияние серверных данных с локальными.
     * НЕ удаляет локальные данные — обновляет существующие и добавляет новые.
     *
     * Алгоритм:
     * 1. Получаем данные с сервера
     * 2. Для каждой серверной записи:
     *    - Если существует локально (по id) → обновляем
     *    - Если не существует → вставляем
     * 3. Локальные записи, которых нет на сервере — оставляем (они будут
     *    отправлены при следующем push)
     */
    suspend fun smartMerge(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "smartMerge: starting")

            val scooters = api.getScooters()
            val renters = api.getRenters()
            val contractHistory = api.getContractHistory()
            val notifications = api.getNotifications()

            db.withTransaction {
                // ── Scooters: upsert ──────────────────────────────
                for (dto in scooters) {
                    val entity = dto.toEntity()
                    val existing = db.scooterDao().getScooterById(entity.id)
                    if (existing != null) {
                        db.scooterDao().updateScooter(entity)
                    } else {
                        db.scooterDao().insertScooter(entity)
                    }
                }

                // ── Renters: upsert ──────────────────────────────
                for (dto in renters) {
                    val entity = dto.toEntity()
                    val existing = db.renterDao().getRenterById(entity.id)
                    if (existing != null) {
                        db.renterDao().updateRenter(entity)
                    } else {
                        db.renterDao().insertRenter(entity)
                    }
                }

                // ── Contract history: insert (IGNORE duplicates) ───
                for (dto in contractHistory) {
                    db.contractHistoryDao().insert(dto.toEntity())
                }

                // ── Notifications: insert (IGNORE duplicates) ──────
                for (dto in notifications) {
                    db.notificationHistoryDao().insert(dto.toEntity())
                }
            }

            Log.d(TAG, "smartMerge: ok (scooters=${scooters.size}, " +
                "renters=${renters.size}, history=${contractHistory.size}, " +
                "notifications=${notifications.size})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "smartMerge failed", e)
            false
        }
    }

    /**
     * Полная замена локальных данных серверными (деструктивная).
     * Используется ТОЛЬКО при первом логине — не при авто-синхронизации!
     */
    suspend fun pullAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "pullAll: starting")
            val scooters = api.getScooters()
            val renters = api.getRenters()
            val contractHistory = api.getContractHistory()
            val notifications = api.getNotifications()

            db.withTransaction {
                db.scooterDao().deleteAll()
                db.renterDao().deleteAll()
                db.contractHistoryDao().deleteAll()
                db.notificationHistoryDao().deleteAll()

                scooters.forEach { db.scooterDao().insertScooter(it.toEntity()) }
                renters.forEach { db.renterDao().insertRenter(it.toEntity()) }
                contractHistory.forEach { db.contractHistoryDao().insert(it.toEntity()) }
                notifications.forEach { db.notificationHistoryDao().insert(it.toEntity()) }
            }
            Log.d(TAG, "pullAll: ok (scooters=${scooters.size}, " +
                "renters=${renters.size}, history=${contractHistory.size}, " +
                "notifications=${notifications.size})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "pullAll failed", e)
            false
        }
    }

    /**
     * Полная синхронизация (v3): SmartMerge → Push.
     *
     * Сначала сливаем серверные данные с локальными (не удаляя!),
     * затем отправляем все локальные изменения.
     */
    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        val result = SyncResult()
        try {
            // ── 1. SmartMerge — сливаем без удаления ────────────
            Log.d(TAG, "syncAll: starting merge phase")
            val mergeOk = smartMerge()
            if (mergeOk) {
                result.pullSuccess = true
                Log.d(TAG, "syncAll: merge ok")
            } else {
                result.pullSuccess = false
                Log.w(TAG, "syncAll: merge failed, trying push anyway")
            }

            Log.d(TAG, "syncAll: starting push phase")

            // ── 2. Push scooters (update existing) ──────────────
            val localScooters = db.scooterDao().getAllScootersOnce()
            for (s in localScooters) {
                val ok = try {
                    api.updateScooter(s.id, s.toUpdateDto())
                    result.scootersPushed++
                    true
                } catch (e: Exception) {
                    // Maybe doesn't exist on server yet — try create
                    try {
                        api.createScooter(s.toCreateDto())
                        result.scootersPushed++
                        true
                    } catch (e2: Exception) {
                        Log.w(TAG, "pushScooter failed for #${s.id}", e2)
                        result.scootersFailed++
                        false
                    }
                }
            }

            // ── 3. Push renters (update existing) ──────────────
            val localRenters = db.renterDao().getAllRentersOnce()
            for (r in localRenters) {
                val ok = try {
                    api.updateRenter(r.id, r.toUpdateDto())
                    result.rentersPushed++
                    true
                } catch (e: Exception) {
                    // Maybe doesn't exist on server yet — try create
                    try {
                        api.createRenter(r.toCreateDto())
                        result.rentersPushed++
                        true
                    } catch (e2: Exception) {
                        Log.w(TAG, "pushRenter failed for #${r.id}", e2)
                        result.rentersFailed++
                        false
                    }
                }
            }

            // ── 4. Push contract history ──────────────────────
            val localHistory = db.contractHistoryDao().getAllOnce()
            for (h in localHistory) {
                val ok = try {
                    api.createContractHistory(h.toCreateDto())
                    result.historyPushed++
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "pushContractHistory failed", e)
                    result.historyFailed++
                    false
                }
            }

            // ── 5. Push notifications ─────────────────────────
            val localNotifs = db.notificationHistoryDao().getAllOnce()
            for (n in localNotifs) {
                val ok = try {
                    api.createNotification(n.toCreateDto())
                    result.notificationsPushed++
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "pushNotification failed", e)
                    result.notificationsFailed++
                    false
                }
            }

            Log.d(TAG, "syncAll: push done (scooters=${result.scootersPushed}, " +
                "renters=${result.rentersPushed}, history=${result.historyPushed}, " +
                "notifs=${result.notificationsPushed})")

            result.success = true
        } catch (e: Exception) {
            Log.e(TAG, "syncAll failed", e)
            result.success = false
            result.errorMessage = e.message ?: "Noma'lum xato"
        }
        result
    }

    suspend fun pushRenter(r: Renter): Int? = withContext(Dispatchers.IO) {
        try { api.createRenter(r.toCreateDto()).id }
        catch (e: Exception) { Log.w(TAG, "pushRenter failed", e); null }
    }

    suspend fun pushRenterUpdate(r: Renter): Boolean = withContext(Dispatchers.IO) {
        try {
            api.updateRenter(r.id, r.toUpdateDto())
            true
        } catch (e: Exception) {
            Log.w(TAG, "pushRenterUpdate failed for #${r.id}", e); false
        }
    }

    suspend fun pushRenterDelete(id: Int): Boolean = withContext(Dispatchers.IO) {
        try { api.deleteRenter(id); true }
        catch (e: Exception) { Log.w(TAG, "pushRenterDelete failed for #$id", e); false }
    }

    suspend fun pushScooter(s: Scooter): Int? = withContext(Dispatchers.IO) {
        try { api.createScooter(s.toCreateDto()).id }
        catch (e: Exception) { Log.w(TAG, "pushScooter failed", e); null }
    }

    suspend fun pushScooterUpdate(s: Scooter): Boolean = withContext(Dispatchers.IO) {
        try { api.updateScooter(s.id, s.toUpdateDto()); true }
        catch (e: Exception) {
            Log.w(TAG, "pushScooterUpdate failed for #${s.id}", e); false
        }
    }

    suspend fun pushScooterDelete(id: Int): Boolean = withContext(Dispatchers.IO) {
        try { api.deleteScooter(id); true }
        catch (e: Exception) { Log.w(TAG, "pushScooterDelete failed for #$id", e); false }
    }

    suspend fun pushContractHistory(entry: ContractHistoryEntry): Boolean =
        withContext(Dispatchers.IO) {
            try { api.createContractHistory(entry.toCreateDto()); true }
            catch (e: Exception) {
                Log.w(TAG, "pushContractHistory failed", e); false
            }
        }

    suspend fun pushNotification(entry: NotificationHistoryEntity): Boolean =
        withContext(Dispatchers.IO) {
            try { api.createNotification(entry.toCreateDto()); true }
            catch (e: Exception) {
                Log.w(TAG, "pushNotification failed", e); false
            }
        }

    companion object {
        private const val TAG = "SyncManager"
    }
}

/**
 * Результат полной синхронизации.
 * Используется для отображения в UI (диалог с деталями).
 */
data class SyncResult(
    var success: Boolean = false,
    var scootersPushed: Int = 0,
    var scootersFailed: Int = 0,
    var rentersPushed: Int = 0,
    var rentersFailed: Int = 0,
    var historyPushed: Int = 0,
    var historyFailed: Int = 0,
    var notificationsPushed: Int = 0,
    var notificationsFailed: Int = 0,
    var pullSuccess: Boolean = false,
    var errorMessage: String? = null
) {
    val totalPushed: Int get() = scootersPushed + rentersPushed + historyPushed + notificationsPushed
    val totalFailed: Int get() = scootersFailed + rentersFailed + historyFailed + notificationsFailed
    val isFullSuccess: Boolean get() = success && totalFailed == 0 && pullSuccess
}
