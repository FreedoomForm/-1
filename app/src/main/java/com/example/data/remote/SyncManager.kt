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
 * Стратегия v1:
 *  • pullAll()      — после успешного логина заменяет Room-данные
 *                     данными с сервера (источник истины).
 *  • push*()        — после локального изменения шлёт запись на сервер.
 *                     Возвращает серверный id (для создания).
 *  • syncAll()      — полная двусторонняя синхронизация:
 *                     1) Push — отправляет все локальные данные на сервер
 *                     2) Pull — забирает данные с сервера (источник истины)
 *  • При недоступности сервера — локальные изменения остаются,
 *    но в лог пишется warning. Следующий pull перезатрёт их.
 */
class SyncManager(
    private val context: Context,
    private val api: ApiService = ApiClient.getService(context),
    private val db: AppDatabase = AppDatabase.getDatabase(context)
) {
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
     * Полная синхронизация: push всех локальных данных → pull с сервера.
     *
     * Возвращает SyncResult с деталями — сколько записей отправлено/получено.
     * Используется кнопкой «Sinxronizatsiya» в настройках.
     */
    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        val result = SyncResult()
        try {
            Log.d(TAG, "syncAll: starting push phase")

            // ── 1. Push scooters ──────────────────────────────
            val localScooters = db.scooterDao().getAllScootersOnce()
            for (s in localScooters) {
                try {
                    api.createScooter(s.toCreateDto())
                    result.scootersPushed++
                } catch (e: Exception) {
                    // Maybe already exists — try update
                    try {
                        api.updateScooter(s.id, s.toUpdateDto())
                        result.scootersPushed++
                    } catch (e2: Exception) {
                        Log.w(TAG, "pushScooter failed for #${s.id}", e2)
                        result.scootersFailed++
                    }
                }
            }

            // ── 2. Push renters ──────────────────────────────
            val localRenters = db.renterDao().getAllRentersOnce()
            for (r in localRenters) {
                try {
                    api.createRenter(r.toCreateDto())
                    result.rentersPushed++
                } catch (e: Exception) {
                    try {
                        api.updateRenter(r.id, r.toUpdateDto())
                        result.rentersPushed++
                    } catch (e2: Exception) {
                        Log.w(TAG, "pushRenter failed for #${r.id}", e2)
                        result.rentersFailed++
                    }
                }
            }

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

            Log.d(TAG, "syncAll: push done (scooters=${result.scootersPushed}, " +
                "renters=${result.rentersPushed}, history=${result.historyPushed}, " +
                "notifs=${result.notificationsPushed})")

            // ── 5. Pull — сервер = источник истины ────────────
            Log.d(TAG, "syncAll: starting pull phase")
            val pullOk = pullAll()
            if (pullOk) {
                result.pullSuccess = true
                Log.d(TAG, "syncAll: complete — pull ok")
            } else {
                result.pullSuccess = false
                Log.w(TAG, "syncAll: push ok but pull failed")
            }

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
