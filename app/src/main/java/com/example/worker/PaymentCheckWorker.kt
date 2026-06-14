package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Периодический worker (раз в час) проверяет арендаторов и шлёт локальные
 * уведомления, когда срок оплаты наступил. Также используется как
 * одноразовый worker с задержкой для кнопки «Уведомить через час».
 */
class PaymentCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val now = System.currentTimeMillis()

            // Гарантируем наличие канала уведомлений
            NotificationHelper.createChannel(applicationContext)

            val renterId = inputData.getInt(KEY_RENTER_ID, -1)
            val isOneTime = inputData.getBoolean(KEY_ONE_TIME, false)

            if (isOneTime && renterId != -1) {
                // Одноразовое напоминание по конкретному арендатору
                val renter = db.renterDao().getRenterById(renterId)
                if (renter != null && !renter.isReturned) {
                    val lastPayment = renter.lastPaymentTimestamp ?: 0L
                    // Не спамим, если уже платил в текущем периоде
                    if (lastPayment < renter.rentStartDateTimestamp) {
                        NotificationHelper.postPaymentDueNotification(
                            applicationContext, renter.id, renter.name, renter.phoneNumber
                        )
                    }
                }
                return@withContext Result.success()
            }

            // Периодический режим: проходим по всем активным арендаторам
            val activeRenters = db.renterDao().getActiveRenters()
            for (renter in activeRenters) {
                val expiryTime = renter.rentStartDateTimestamp +
                    (renter.rentDurationDays * 24L * 60 * 60 * 1000)

                // Окно уведомления: 24 часа после наступления срока
                val inDueWindow = now in expiryTime..(expiryTime + 24L * 60 * 60 * 1000)

                // Не уведомляем, если платёж уже был после начала периода
                val lastPayment = renter.lastPaymentTimestamp ?: 0L
                val paidForCurrentPeriod = lastPayment >= renter.rentStartDateTimestamp

                if (inDueWindow && !paidForCurrentPeriod && !renter.isOverdueSmsSent) {
                    NotificationHelper.postPaymentDueNotification(
                        applicationContext, renter.id, renter.name, renter.phoneNumber
                    )
                    // Помечаем, чтобы не спамить на следующих тиках
                    db.renterDao().updateRenter(renter.copy(isOverdueSmsSent = true))
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        const val KEY_RENTER_ID = "renterId"
        const val KEY_ONE_TIME = "isOneTimeReminder"
    }
}
