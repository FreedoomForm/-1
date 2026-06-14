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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Периодически (раз в час) проверяет арендаторов и для тех, у кого
 * наступил срок оплаты, планирует уведомление на 01:00 следующего дня.
 *
 * Также работает в одноразовом режиме (KEY_ONE_TIME=true) — уведомляет
 * сразу. Этот режим используется:
 *   • при создании арендатора с уже просроченной датой (моментальная
 *     отправка по требованию пользователя);
 *   • для запланированных напоминаний на 01:00 (когда таймер сработал).
 */
class PaymentCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val now = System.currentTimeMillis()

            NotificationHelper.createChannel(applicationContext)

            val renterId = inputData.getInt(KEY_RENTER_ID, -1)
            val isOneTime = inputData.getBoolean(KEY_ONE_TIME, false)

            if (isOneTime && renterId != -1) {
                handleOneTimeNotification(db, renterId)
                return@withContext Result.success()
            }

            // Периодический режим: для каждого просроченного арендатора
            // без оплаты планируем уведомление на 01:00 и помечаем флаг,
            // чтобы не дублировать на следующих тиках.
            val activeRenters = db.renterDao().getActiveRenters()
            for (renter in activeRenters) {
                val expiryTime = renter.rentStartDateTimestamp +
                    (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                val isOverdue = now > expiryTime
                val lastPayment = renter.lastPaymentTimestamp ?: 0L
                val paidForCurrentPeriod = lastPayment >= renter.rentStartDateTimestamp

                if (isOverdue && !paidForCurrentPeriod && !renter.isOverdueSmsSent) {
                    scheduleNextOneAmNotification(applicationContext, renter.id)
                    db.renterDao().updateRenter(renter.copy(isOverdueSmsSent = true))
                    Log.d(TAG, "Scheduled 01:00 notification for renter #${renter.id}")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "PaymentCheckWorker failed", e)
            Result.retry()
        }
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

        /**
         * Планирует одноразовый worker, который сработает в ближайшую
         * 01:00 (сегодня, если сейчас до 01:00, иначе — завтра) и
         * отправит уведомление по этому арендатору.
         */
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
