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
            val settingsRepo = SettingsRepository(applicationContext)

            // ── Qo'llanma rejimi: avto-yuborish o'chirilgan ───────────────
            // Foydalanuvchi Settingsda "Qo'llanma" rejimini tanlagan bo'lsa,
            // SmsWorker hech narsa yubormaydi — SMS faqat "SMS" tugmasi orqali
            // yuboriladi. Bildirishnomalar va boshqa logika ishlayveradi.
            if (!settingsRepo.smsAutoSendEnabled) {
                Log.d(TAG, "SmsWorker skipped: manual mode is on (smsAutoSendEnabled=false)")
                return@withContext Result.success()
            }

            val db = AppDatabase.getDatabase(applicationContext)
            val repository = RenterRepository(db.renterDao())
            val activeRenters = repository.getActiveRenters()
            val currentTime = System.currentTimeMillis()

            Log.d(TAG, "SmsWorker started: ${activeRenters.size} active renters")

            var sentCount = 0
            var skippedCount = 0

            activeRenters.forEach { renter ->
                if (!settingsRepo.canSendAutoSms(currentTime)) return@forEach
                // Batch 10 (was HIGH B2): removed `renter.isOverdueSmsSent`
                // from this skip condition. The flag was set true after the
                // FIRST successful SMS and only reset by autoRenew / payWeekly
                // / terminate / acceptPayment — meaning once a renter got one
                // overdue SMS, the configurable smsReminderCooldownHours had
                // ZERO effect: no further reminders were sent until the renter
                // paid or the contract renewed (which could be a week later).
                // The cooldown check below at lines 69-74 already throttles
                // repeat SMS; the boolean flag was redundant and overrode it.
                //
                // Batch 12 (was LOW 4.2): also removed the
                // `renter.balance >= 0.0` short-circuit that was sitting here.
                // Renter.balance is @Deprecated — payWeekly still writes it
                // but PaymentCheckWorker.autoRenew no longer does (per its
                // comment). That means a renter whose only activity is
                // autoRenew keeps balance == 0 forever and was silently
                // skipped by SmsWorker even when they had overdue RentPeriods.
                // The overduePeriod check below is the authoritative signal;
                // if it returns null the renter is genuinely not in debt and
                // we skip. No balance short-circuit needed.
                val overduePeriod = db.rentPeriodDao().openForRenter(renter.id)
                    .firstOrNull { it.status == com.example.data.RentPeriod.STATUS_OVERDUE }
                if (overduePeriod == null) {
                    skippedCount++
                    return@forEach
                }
                val cooldownMs = settingsRepo.smsReminderCooldownHours * 60L * 60 * 1000
                val latestSent = db.smsDeliveryDao().latestSuccessfulForRenter(renter.id)
                if (latestSent != null && currentTime - latestSent.timestamp < cooldownMs) {
                    skippedCount++
                    return@forEach
                }
                val daysOverdue = ((currentTime - overduePeriod.endsAt) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                // Batch 12 (was LOW 4.2 follow-up): compute the debt shown in
                // the SMS from the overdue period's outstandingMinor rather
                // than from the deprecated renter.balance. Previously the
                // message showed "{debt}=0" for any renter whose balance
                // hadn't been touched by payWeekly (e.g. a renter whose only
                // activity was autoRenew), even when they had a real overdue
                // period with non-zero outstandingMinor. Now the SMS shows
                // the actual amount owed on the overdue period. We sum across
                // ALL overdue periods for the renter (not just the first) so
                // the message reflects total outstanding debt.
                val debtMinor = db.rentPeriodDao().openForRenter(renter.id)
                    .filter { it.status == com.example.data.RentPeriod.STATUS_OVERDUE }
                    .sumOf { it.outstandingMinor }
                val debt = com.example.data.BusinessOperation.fromMinor(debtMinor)
                val message = settingsRepo.smsTemplate
                    .replace("{name}", renter.name.trim().lowercase())
                    .replace("{days}", daysOverdue.toString())
                    .replace("{debt}", debt.toLong().toString())
                    .replace("{payme}", settingsRepo.paymeLink)
                    .replace("{call}", settingsRepo.callCenter)

                // Batch 10 (was HIGH B1): normalize the phone number before
                // handing it to SmsManager. Previously the raw renter.phoneNumber
                // was passed — if stored as "998901234567" (no +) or
                // "+998 90 123 45 67" (spaces), SmsManager would reject with
                // INVALID_NUMBER / GENERIC_FAILURE and the worker would
                // retry the same bad number indefinitely, wasting battery
                // and creating a stream of FAILED SmsDelivery rows.
                val normalizedPhone = SimHelper.normalizePhoneNumber(renter.phoneNumber)
                if (!SimHelper.isValidUzbekPhone(normalizedPhone)) {
                    db.smsDeliveryDao().insert(com.example.data.SmsDelivery(
                        renterId = renter.id, timestamp = currentTime,
                        status = com.example.data.SmsDelivery.STATUS_FAILED,
                        messagePreview = message.take(160),
                        error = "Invalid Uzbek phone number: ${renter.phoneNumber} → $normalizedPhone"
                    ))
                    Log.w(TAG, "SMS skipped for renter #${renter.id}: invalid phone ${renter.phoneNumber}")
                    skippedCount++
                    return@forEach
                }
                val ok = sendSms(normalizedPhone, message)
                if (ok) {
                    // Batch 10 (was HIGH B4): use the field-specific
                    // updateOverdueSmsFlag DAO method instead of full-entity
                    // @Update. Previously this code did
                    // `repository.update(renter.copy(isOverdueSmsSent = true))`
                    // which wrote ALL columns of the renter — if
                    // PaymentCheckWorker.autoRenew ran between the read at
                    // line 55 and this write, the autoRenew's increment of
                    // rentDurationDays would be silently reverted, causing
                    // double-billing on the next autoRenew. The
                    // field-specific UPDATE touches only isOverdueSmsSent.
                    db.renterDao().updateOverdueSmsFlag(renter.id, true)
                    settingsRepo.recordAutoSmsSent(currentTime)
                    db.smsDeliveryDao().insert(com.example.data.SmsDelivery(
                        renterId = renter.id, timestamp = currentTime,
                        status = com.example.data.SmsDelivery.STATUS_SENT,
                        messagePreview = message.take(160)
                    ))
                    sentCount++
                    Log.d(TAG, "SMS sent for renter #${renter.id} (${renter.name}), $daysOverdue days overdue")
                } else {
                    db.smsDeliveryDao().insert(com.example.data.SmsDelivery(
                        renterId = renter.id, timestamp = currentTime,
                        status = com.example.data.SmsDelivery.STATUS_FAILED,
                        messagePreview = message.take(160),
                        error = "SmsManager send failed; worker will retry on next scheduled run"
                    ))
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
