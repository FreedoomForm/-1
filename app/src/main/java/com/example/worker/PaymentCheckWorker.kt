package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * • Периодически (раз в час) проверяет арендаторов:
 *     — если срок аренды истёк → автоматически продлевает контракт на 1 неделю
 *       (rentStartDateTimestamp += 7 дней, rentDurationDays += 7),
 *       списывает weeklyPrice с баланса, создаёт запись AUTO_RENEW
 *       с полными денормализованными полями (для PDF и истории).
 *       Баланс уходит в минус → это и есть долг.
 *     — если после продления баланс < 0 (есть долг) И SMS ещё не отправлено →
 *       планируется уведомление на 01:00 следующего дня.
 * • Одноразовый режим (KEY_ONE_TIME=true) — шлёт уведомление
 *   сразу (используется при создании арендатора с просрочкой
 *   и для запланированных 01:00 напоминаний).
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
                val expiryTime = renter.rentStartDateTimestamp +
                    (renter.rentDurationDays * 24L * 60 * 60 * 1000)

                if (now >= expiryTime) {
                    // Всегда продлеваем на 1 неделю и списываем weeklyPrice.
                    // Баланс уходит в минус → это долг. Так появляется новый контракт
                    // в истории и обновляются даты в таблице арендатора.
                    val newBalance = autoRenew(db, settingsRepo, renter, now)

                    if (newBalance < 0.0 && !renter.isOverdueSmsSent) {
                        // Есть долг — планируем уведомление на 01:00.
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

    /**
     * Продлевает контракт арендатора на 1 неделю:
     *   • rentStartDateTimestamp += 7 дней
     *   • rentDurationDays += 7
     *   • balance -= weeklyPrice (уходит в минус = долг)
     *   • debtAmount = max(0, -balance) (синхронизация)
     *   • isOverdueSmsSent = false (новая неделя — можно снова слать SMS)
     *   • Создаёт запись AUTO_RENEW со всеми денормализованными полями.
     *
     * Возвращает новый баланс (для решения о SMS-уведомлении).
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

        val newBalance = renter.balance - weeklyPrice
        val renewed = renter.copy(
            rentStartDateTimestamp = renter.rentStartDateTimestamp + sevenDays,
            rentDurationDays = renter.rentDurationDays + 7,
            balance = newBalance,
            debtAmount = maxOf(0.0, -newBalance),
            isOverdueSmsSent = false
        )
        db.renterDao().updateRenter(renewed)

        // ── Подтягиваем реквизиты скутера для PDF-денормализации ──────────
        val scooter = renter.scooterId?.let { db.scooterDao().getScooterById(it) }

        val weekStart = renewed.rentStartDateTimestamp
        val weekEnd = weekStart + sevenDays
        db.contractHistoryDao().insert(
            ContractHistoryEntry(
                renterId = renter.id,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                amount = weeklyPrice,
                notes = "Avtomatik yangilanish +7 kun",
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = weekStart,
                weekEnd = weekEnd,
                weeklyPrice = weeklyPrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
        )
        Log.d(TAG, "Auto-renewed renter #${renter.id} for 1 week, balance ${renter.balance} → $newBalance")
        return newBalance
    }

    private suspend fun handleOneTimeNotification(
        db: AppDatabase,
        renterId: Int
    ) {
        val renter = db.renterDao().getRenterById(renterId) ?: return
        if (renter.isReturned) return
        val lastPayment = renter.lastPaymentTimestamp ?: 0L
        if (lastPayment < renter.rentStartDateTimestamp) {
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
