package com.example.worker

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Шлёт SMS просроченным арендаторам.
 *
 * Запускается двумя способами:
 *  • Периодически — раз в 4 часа (MainActivity регистрирует
 *    «OverdueSmsWork» при старте приложения).
 *  • Одноразово — сразу после создания арендатора с просроченной
 *    датой (RenterViewModel.addRenter ставит задачу в очередь).
 *
 * Для отправки требуется разрешение android.permission.SEND_SMS,
 * которое автоматически запрашивается при первом запуске приложения.
 * На эмуляторах Android SMS-отправка не работает вовсе.
 */
class SmsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = RenterRepository(db.renterDao())
            val settingsRepo = SettingsRepository(applicationContext)
            val activeRenters = repository.getActiveRenters()
            val currentTime = System.currentTimeMillis()

            Log.d(TAG, "SmsWorker started: ${activeRenters.size} active renters")

            var sentCount = 0
            var skippedCount = 0

            activeRenters.forEach { renter ->
                val elapsedMillis = currentTime - renter.rentStartDateTimestamp
                val elapsedDays = (elapsedMillis / (1000 * 60 * 60 * 24)).toInt()

                if (renter.isOverdueSmsSent) {
                    skippedCount++
                    return@forEach
                }

                if (elapsedDays > renter.rentDurationDays) {
                    val daysOverdue = elapsedDays - renter.rentDurationDays
                    // Долг = -balance (если balance < 0). debtAmount может рассинхронизироваться,
                    // поэтому всегда вычисляем из balance — это источник истины.
                    val debt = maxOf(0.0, -renter.balance)
                    val message = settingsRepo.smsTemplate
                        .replace("{name}", renter.name.trim().lowercase())
                        .replace("{days}", maxOf(1, daysOverdue).toString())
                        .replace("{debt}", debt.toLong().toString())
                        .replace("{payme}", settingsRepo.paymeLink)
                        .replace("{call}", settingsRepo.callCenter)

                    val ok = sendSms(renter.phoneNumber, message)
                    if (ok) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                        sentCount++
                        Log.d(TAG, "SMS sent for renter #${renter.id} (${renter.name}), " +
                            "$daysOverdue days overdue")
                    } else {
                        Log.w(TAG, "SMS failed for renter #${renter.id} (${renter.name})")
                    }
                }
            }

            Log.d(TAG, "SmsWorker finished: sent=$sentCount skipped=$skippedCount")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SmsWorker failed", e)
            Result.retry()
        }
    }

    /**
     * Возвращает true, если SMS успешно отправлено (или поставлено в очередь
     * системой). Возвращает false при любой ошибке — чтобы в логах был виден
     * конкретный renter, который не удалось оповестить.
     */
    private fun sendSms(phone: String, message: String): Boolean {
        return try {
            val smsManager = getSmsManager(applicationContext)
                ?: throw IllegalStateException("SmsManager is null")
            SimHelper.sendSmsAuto(smsManager, phone, message, null, null)
            Log.d(TAG, "SmsManager.sendSmsAuto OK to $phone (${message.length} chars)")
            true
        } catch (e: SecurityException) {
            // Нет разрешения SEND_SMS — пользователь не дал
            Log.e(TAG, "SecurityException: SEND_SMS permission not granted", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed for $phone", e)
            false
        }
    }

    /**
     * SmsManager ni dual-SIM qo'llab-quvvatlash bilan olish.
     *
     * SimHelper orqali:
     * 1. Saqlangan SIM subscription ID tekshiriladi
     * 2. Agar tanlanmagan bo'lsa, birinchi faol SIM tanlanadi
     * 3. Oxirgi chora: getDefault() (GENERIC_FAILURE xavfi bor!)
     */
    private fun getSmsManager(context: Context): SmsManager? {
        return SimHelper.getSmsManagerForSim(context)
    }

    companion object {
        private const val TAG = "SmsWorker"
    }
}
