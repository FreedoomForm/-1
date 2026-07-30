package com.example.worker

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.BusinessOperation
import com.example.data.ContractHistoryEntry
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Периодически проверяет арендаторов и автоматически создаёт просроченные контракты.
 * 
 * ИСПРАВЛЕНО: Убран двойной учёт долга. Теперь balance НЕ уменьшается напрямую,
 * долг учитывается ТОЛЬКО через RentPeriod.outstandingMinor.
 */
class PaymentCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val settingsRepo = SettingsRepository(applicationContext)
            val now = System.currentTimeMillis()

            NotificationHelper.createChannel(applicationContext)

            val renterId = inputData.getInt(KEY_RENTER_ID, -1)
            val isOneTime = inputData.getBoolean(KEY_ONE_TIME, false)

            if (isOneTime && renterId != -1) {
                handleOneTimeNotification(db, renterId)
                return@withContext Result.success()
            }

            val activeRenters = db.renterDao().getActiveRenters()
            for (renter in activeRenters) {
                val currentRenter = activateScheduledPeriods(db, renter, now)
                val expiryTime = currentRenter.rentStartDateTimestamp +
                    (currentRenter.rentDurationDays * 24L * 60 * 60 * 1000)

                if (now >= expiryTime) {
                    var current = currentRenter
                    var renewedPeriods = 0
                    val maxCatchUpPeriods = 104
                    
                    db.withTransaction {
                        while (
                            now >= current.rentStartDateTimestamp +
                                current.rentDurationDays * 24L * 60 * 60 * 1000 &&
                            renewedPeriods < maxCatchUpPeriods
                        ) {
                            autoRenew(db, settingsRepo, current, now)
                            current = db.renterDao().getRenterById(current.id) ?: break
                            renewedPeriods++
                        }
                    }
                    
                    if (renewedPeriods == maxCatchUpPeriods) {
                        Log.e(TAG, "Auto-renew cap reached for renter #${renter.id}")
                    }
                    
                    // Проверяем долг через RentPeriod, а не balance
                    val hasDebt = db.rentPeriodDao().openForRenter(current.id)
                        .any { it.outstandingMinor > 0 }
                    
                    if (hasDebt && !currentRenter.isOverdueSmsSent) {
                        scheduleNextOneAmNotification(applicationContext, renter.id)
                        Log.d(TAG, "Scheduled 01:00 notification for renter #${renter.id}")
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "PaymentCheckWorker failed", e)
            Result.retry()
        }
    }

    private suspend fun activateScheduledPeriods(
        db: AppDatabase,
        renter: com.example.data.Renter,
        now: Long
    ): com.example.data.Renter {
        val due = db.rentPeriodDao().scheduledDueForRenter(renter.id, now)
        if (due.isEmpty()) return renter
        
        due.forEach { period ->
            val status = if (period.endsAt <= now) com.example.data.RentPeriod.STATUS_OVERDUE
                         else com.example.data.RentPeriod.STATUS_ACTIVE
            db.rentPeriodDao().update(period.copy(status = status, updatedAt = now))
        }
        
        renter.scooterId?.let { db.scooterDao().updateLifecycleStatus(it, com.example.data.Scooter.STATUS_RENTED) }
        return renter
    }

    /**
     * Продлевает контракт арендатора.
     * 
     * ИСПРАВЛЕНО: НЕ меняем renter.balance напрямую!
     * Долг теперь учитывается только через RentPeriod.outstandingMinor.
     * Это устраняет двойной учёт: balance + RentPeriod.
     */
    private suspend fun autoRenew(
        db: AppDatabase,
        settingsRepo: SettingsRepository,
        renter: com.example.data.Renter,
        now: Long
    ): Double {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val weeklyPrice = settingsRepo.weeklyPrice.let {
            if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE
        }

        // ИСПРАВЛЕНО: НЕ меняем balance напрямую, только увеличиваем срок
        // Batch 12 (was HIGH B4): switched from full-entity @Update
        // (renter.copy(...) + updateRenter) to two field-specific UPDATE
        // queries. The full-entity write clobbered any concurrent
        // field-specific write (e.g. a concurrent SmsWorker setting
        // isOverdueSmsSent=true, or a payWeekly updating balance) because
        // the read-modify-write here captured the renter snapshot at
        // autoRenew entry and wrote ALL 13 columns back. Now we touch
        // ONLY rentDurationDays and isOverdueSmsSent — the columns this
        // code path actually mutates.
        db.renterDao().updateRentDurationDays(renter.id, renter.rentDurationDays + 7)
        db.renterDao().updateOverdueSmsFlag(renter.id, false)

        val scooter = renter.scooterId?.let { db.scooterDao().getScooterById(it) }

        val newWeekStart = renter.rentStartDateTimestamp +
            renter.rentDurationDays * 24L * 60 * 60 * 1000
        val newWeekEnd = newWeekStart + sevenDays
        
        val contractId = db.contractHistoryDao().insert(
            ContractHistoryEntry(
                renterId = renter.id,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                amount = weeklyPrice,
                notes = "Avtomatik yangilanish +7 kun",
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = newWeekStart,
                weekEnd = newWeekEnd,
                weeklyPrice = weeklyPrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = false
            )
        )
        
        db.rentPeriodDao().insert(com.example.data.RentPeriod(
            contractHistoryId = contractId.toInt(),
            renterId = renter.id,
            scooterId = renter.scooterId,
            startsAt = newWeekStart,
            endsAt = newWeekEnd,
            chargeMinor = BusinessOperation.toMinor(weeklyPrice),
            paidMinor = 0,
            status = if (newWeekEnd <= now) com.example.data.RentPeriod.STATUS_OVERDUE
                else com.example.data.RentPeriod.STATUS_ACTIVE,
            createdAt = now,
            updatedAt = now
        ))
        
        Log.d(TAG, "Auto-renewed renter #${renter.id} for 1 week")
        return renter.balance  // Возвращаем исходный баланс
    }

    private suspend fun handleOneTimeNotification(
        db: AppDatabase,
        renterId: Int
    ) {
        val renter = db.renterDao().getRenterById(renterId) ?: return
        if (renter.isReturned) return
        
        // Проверяем долг через RentPeriod
        val hasOverduePeriod = db.rentPeriodDao().openForRenter(renter.id)
            .any { it.status == com.example.data.RentPeriod.STATUS_OVERDUE }
            
        if (hasOverduePeriod) {
            NotificationHelper.postPaymentDueNotification(
                applicationContext, renter.id, renter.name, renter.phoneNumber
            )
        }
    }

    companion object {
        private const val TAG = "PaymentCheckWorker"
        const val KEY_RENTER_ID = "renterId"
        const val KEY_ONE_TIME = "isOneTimeReminder"

        fun scheduleNextOneAmNotification(context: Context, renterId: Int) {
            val nextOneAm = nextOneAmMillis()
            val delay = (nextOneAm - System.currentTimeMillis()).coerceAtLeast(0L)
            val work = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_RENTER_ID to renterId,
                        KEY_ONE_TIME to true
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scheduled_1am_$renterId",
                ExistingWorkPolicy.REPLACE,
                work
            )
            Log.d(TAG, "Scheduled 01:00 notification for renter #$renterId in ${delay / 1000}s")
        }

        private fun nextOneAmMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 1)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }
}
