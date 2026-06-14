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
