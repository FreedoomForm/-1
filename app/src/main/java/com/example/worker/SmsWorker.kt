package com.example.worker

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
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
 * Для отправки требуется разрешение android.permission.SEND_SMS.
 *
 * Расчёт долга и просрочки:
 *  • unpaidDays — суммарное количество дней из ВСЕХ неоплаченных
 *    контрактов (CREATED, AUTO_RENEW с isPaid=false). Это точная
 *    цифра: если у арендатора 2 неоплаченных контракта по 7 дней,
 *    unpaidDays = 14.
 *  • unpaidCount — количество неоплаченных контрактов (для плейсхолдера
 *    {unpaidCount} в шаблоне).
 *  • debt = unpaidDays × dailyPrice — реальная сумма долга, основанная
 *    на дневной ставке, а не на -balance (который может быть
 *    рассинхронизирован при ручных правках).
 *  • days (legacy) — старый плейсхолдер, равен max(1, unpaidDays).
 *    Оставлен для совместимости со старыми пользовательскими шаблонами.
 */
class SmsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val settingsRepo = SettingsRepository(applicationContext)

            if (!settingsRepo.smsAutoSendEnabled) {
                Log.d(TAG, "SmsWorker skipped: manual mode is on (smsAutoSendEnabled=false)")
                return@withContext Result.success()
            }

            val db = AppDatabase.getDatabase(applicationContext)
            val repository = RenterRepository(db.renterDao())
            val activeRenters = repository.getActiveRenters()
            val contractDao = db.contractHistoryDao()

            Log.d(TAG, "SmsWorker started: ${activeRenters.size} active renters, template='${settingsRepo.smsTemplate.take(40)}...'")

            var sentCount = 0
            var skippedCount = 0

            activeRenters.forEach { renter ->
                if (renter.isOverdueSmsSent) {
                    skippedCount++
                    return@forEach
                }

                // ── Считаем неоплаченные контракты ──────────────────────
                // Запрашиваем все неоплаченные контракты (CREATED/AUTO_RENEW
                // с isPaid=false) для этого арендатора. Каждый контракт имеет
                // weekStart/weekEnd — по ним считаем суммарное число дней.
                val unpaidContracts = contractDao.getUnpaidContractsForRenter(renter.id)
                if (unpaidContracts.isEmpty()) {
                    // Нет неоплаченных контрактов — SMS не нужен
                    return@forEach
                }

                val unpaidDays = unpaidContracts.sumOf { c ->
                    val ws = c.weekStart ?: return@sumOf 0L
                    val we = c.weekEnd ?: return@sumOf 0L
                    val dayMs = 24L * 60 * 60 * 1000
                    // Округляем вверх: если контракт длится 7 дней (полная
                    // неделя), получим 7. Если частичный день — тоже 1.
                    val diff = we - ws
                    if (diff <= 0) 1L else ((diff + dayMs - 1) / dayMs)
                }.toInt().coerceAtLeast(1)

                val unpaidCount = unpaidContracts.size

                // Долг = unpaidDays × dailyPrice (а НЕ -balance!)
                val dailyPrice = settingsRepo.dailyPrice.let {
                    if (it > 0) it else SettingsRepository.DEFAULT_DAILY_PRICE
                }
                val debt = unpaidDays * dailyPrice

                // ── Подставляем плейсхолдеры в шаблон ────────────────────
                val message = settingsRepo.smsTemplate
                    .replace("{name}", renter.name.trim().lowercase())
                    .replace("{unpaidDays}", unpaidDays.toString())
                    .replace("{unpaidCount}", unpaidCount.toString())
                    .replace("{days}", unpaidDays.toString())  // legacy alias
                    .replace("{debt}", debt.toLong().toString())
                    .replace("{payme}", settingsRepo.paymeLink)
                    .replace("{call}", settingsRepo.callCenter)

                val ok = sendSms(renter.phoneNumber, message)
                if (ok) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                    sentCount++
                    Log.d(TAG, "SMS sent for renter #${renter.id} (${renter.name}), " +
                        "unpaidDays=$unpaidDays, unpaidCount=$unpaidCount, debt=$debt")
                } else {
                    Log.w(TAG, "SMS failed for renter #${renter.id} (${renter.name})")
                }
            }

            Log.d(TAG, "SmsWorker finished: sent=$sentCount skipped=$skippedCount")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SmsWorker failed", e)
            Result.retry()
        }
    }

    private fun sendSms(phone: String, message: String): Boolean {
        return try {
            val smsManager = getSmsManager(applicationContext)
                ?: throw IllegalStateException("SmsManager is null")
            SimHelper.sendSmsAuto(smsManager, phone, message, null, null)
            Log.d(TAG, "SmsManager.sendSmsAuto OK to $phone (${message.length} chars)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: SEND_SMS permission not granted", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed for $phone", e)
            false
        }
    }

    private fun getSmsManager(context: Context): SmsManager? {
        return SimHelper.getSmsManagerForSim(context)
    }

    companion object {
        private const val TAG = "SmsWorker"
    }
}
