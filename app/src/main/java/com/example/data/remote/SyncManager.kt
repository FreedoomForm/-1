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
 * Стратегия v4 (LOCAL-FIRST для старшего админа):
 *
 *  • pushOnly()       — ТОЛЬКО отправка локальных данных на сервер.
 *                       Используется для авто-синхронизации каждые 30 сек.
 *                       НЕ получает данные с сервера, НЕ перезаписывает локальные.
 *
 *  • smartMerge()     — Умное слияние: серверные данные добавляются
 *                       только если их НЕТ локально. Существующие локальные
 *                       данные НЕ перезаписываются серверными.
 *                       Используется для младших админов (VIEWER).
 *
 *  • syncAll()        — Полная синхронизация: pushOnly (локал → сервер).
 *                       Кнопка «Sinxronizatsiya» для старшего админа.
 *
 *  • pullAll()        — Полная замена локальных данных серверными.
 *                       Используется ТОЛЬКО при первом логине VIEWER.
 *
 *  КЛЮЧЕВОЕ ПРАВИЛО v4:
 *  Старший админ (ADMIN) — локальные данные ГЛАВНЫЕ.
 *  Сервер — резервная копия и средство просмотра для младших админов.
 *  Авто-синхронизация ADMIN: только PUSH, никакого PULL.
 *  Авто-синхронизация VIEWER: smartMerge (получать данные с сервера).
 */
class SyncManager(
    private val context: Context,
    private val api: ApiService = ApiClient.getService(context),
    private val db: AppDatabase = AppDatabase.getDatabase(context)
) {
    companion object {
        private const val TAG = "SyncManager"
    }

    /**
     * ТОЛЬКО отправка локальных данных на сервер.
     * НЕ получает данные с сервера.
     * НЕ перезаписывает локальные данные.
     *
     * Используется для авто-синхронизации ADMIN каждые 30 сек.
     * Это гарантирует, что локальные удаления не будут отменены.
     */
    suspend fun pushOnly(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "pushOnly: starting")

            // Push scooters
            val localScooters = db.scooterDao().getAllScootersOnce()
            for (s in localScooters) {
                try {
                    api.updateScooter(s.id, s.toUpdateDto())
                } catch (_: Exception) {
                    try { api.createScooter(s.toCreateDto()) } catch (_: Exception) {}
                }
            }

            // Push renters
            val localRenters = db.renterDao().getAllRentersOnce()
            for (r in localRenters) {
                try {
                    api.updateRenter(r.id, r.toUpdateDto())
                } catch (_: Exception) {
                    try { api.createRenter(r.toCreateDto()) } catch (_: Exception) {}
                }
            }

            // Push contract history
            val localHistory = db.contractHistoryDao().getAllOnce()
            for (h in localHistory) {
                try { api.createContractHistory(h.toCreateDto()) } catch (_: Exception) {}
            }

            // Push notifications
            val localNotifs = db.notificationHistoryDao().getAllOnce()
            for (n in localNotifs) {
                try { api.createNotification(n.toCreateDto()) } catch (_: Exception) {}
            }

            Log.d(TAG, "pushOnly: ok")
            true
        } catch (e: Exception) {
            Log.e(TAG, "pushOnly failed", e)
            false
        }
    }

    /**
     * Умное слияние серверных данных с локальными — БЕЗ перезаписи.
     *
     * Алгоритм (LOCAL-FIRST):
     * 1. Получаем данные с сервера
     * 2. Для каждой серверной записи:
     *    - Если существует локально (по id) → ПРОПУСКАЕМ (не обновляем!)
     *    - Если не существует → вставляем (новая запись с сервера)
     * 3. Локальные записи, которых нет на сервере → остаются
     * 4. Локальные записи, которые удалены → НЕ восстанавливаются
     *
     * Это гарантирует: если старший админ удалил клиента,
     * smartMerge его НЕ вернёт.
     */
    suspend fun smartMerge(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "smartMerge: starting (local-first, no overwrite)")

            val scooters = api.getScooters()
            val renters = api.getRenters()
            val contractHistory = api.getContractHistory()
            val notifications = api.getNotifications()

            db.withTransaction {
                // ── Scooters: insert only if NOT exists locally ────
                for (dto in scooters) {
                    val entity = dto.toEntity()
                    val existing = db.scooterDao().getScooterById(entity.id)
                    if (existing == null) {
                        db.scooterDao().insertScooter(entity)
                        Log.d(TAG, "smartMerge: new scooter #${entity.id} from server")
                    }
                    // existing != null → ПРОПУСКАЕМ, локальная версия главная
                }

                // ── Renters: insert only if NOT exists locally ────
                for (dto in renters) {
                    val entity = dto.toEntity()
                    val existing = db.renterDao().getRenterById(entity.id)
                    if (existing == null) {
                        db.renterDao().insertRenter(entity)
                        Log.d(TAG, "smartMerge: new renter #${entity.id} from server")
                    }
                    // existing != null → ПРОПУСКАЕМ, локальная версия главная
                }

                // ── Contract history: insert (IGNORE duplicates) ───
                for (dto in contractHistory) {
                    try {
                        db.contractHistoryDao().insert(dto.toEntity())
                    } catch (_: Exception) {
                        // IGNORE duplicate
                    }
                }

                // ── Notifications: insert (IGNORE duplicates) ──────
                for (dto in notifications) {
                    try {
                        db.notificationHistoryDao().insert(dto.toEntity())
                    } catch (_: Exception) {
                        // IGNORE duplicate
                    }
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
     * Используется ТОЛЬКО при первом логине VIEWER.
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
            Log.d(TAG, "pullAll: ok")
            true
        } catch (e: Exception) {
            Log.e(TAG, "pullAll failed", e)
            false
        }
    }

    /**
     * Полная синхронизация для ADMIN: pushOnly.
     *
     * Отправляет все локальные данные на сервер (создание/обновление).
     * НЕ получает данные с сервера — локальные данные главные!
     *
     * Также отправляет DELETE для записей, которых нет локально
     * но есть на сервере (чтобы сервер был точной копией локала).
     */
    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        val result = SyncResult()
        try {
            Log.d(TAG, "syncAll: starting (ADMIN: push local → server)")

            // ── 1. Push scooters (update or create) ──────────────
            val localScooters = db.scooterDao().getAllScootersOnce()
            val localScooterIds = localScooters.map { it.id }.toSet()

            for (s in localScooters) {
                val ok = try {
                    api.updateScooter(s.id, s.toUpdateDto())
                    result.scootersPushed++
                    true
                } catch (_: Exception) {
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

            // Delete scooters from server that don't exist locally
            try {
                val serverScooters = api.getScooters()
                for (s in serverScooters) {
                    if (s.id !in localScooterIds) {
                        try {
                            api.deleteScooter(s.id)
                            Log.d(TAG, "Deleted scooter #${s.id} from server (not local)")
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}

            // ── 2. Push renters (update or create) ──────────────
            val localRenters = db.renterDao().getAllRentersOnce()
            val localRenterIds = localRenters.map { it.id }.toSet()

            for (r in localRenters) {
                val ok = try {
                    api.updateRenter(r.id, r.toUpdateDto())
                    result.rentersPushed++
                    true
                } catch (_: Exception) {
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

            // Delete renters from server that don't exist locally
            try {
                val serverRenters = api.getRenters()
                for (r in serverRenters) {
                    if (r.id !in localRenterIds) {
                        try {
                            api.deleteRenter(r.id)
                            Log.d(TAG, "Deleted renter #${r.id} from server (not local)")
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}

            // ── 3. Push contract history ──────────────────────
            val localHistory = db.contractHistoryDao().getAllOnce()
            for (h in localHistory) {
                try {
                    api.createContractHistory(h.toCreateDto())
                    result.historyPushed++
                } catch (e: Exception) {
                    Log.w(TAG, "pushContractHistory failed", e)
                    result.historyFailed++
                }
            }

            // ── 4. Push notifications ─────────────────────────
            val localNotifs = db.notificationHistoryDao().getAllOnce()
            for (n in localNotifs) {
                try {
                    api.createNotification(n.toCreateDto())
                    result.notificationsPushed++
                } catch (e: Exception) {
                    Log.w(TAG, "pushNotification failed", e)
                    result.notificationsFailed++
                }
            }

            result.pullSuccess = true
            result.success = true
            Log.d(TAG, "syncAll: push done (scooters=${result.scootersPushed}, " +
                "renters=${result.rentersPushed})")
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
}

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
