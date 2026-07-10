package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Получатель action-кнопок из уведомлений. Работает даже когда
 * приложение закрыто.
 *
 * После любой успешной обработки уведомление автоматически исчезает
 * из шторки (NotificationManagerCompat.cancel).
 *
 * Поддерживает два действия:
 *  • ACTION_PAYMENT_RECEIVED   — «To'lov qabul qilindi»:
 *      увеличивает balance на weeklyPrice (баланс ≥ 0 — предоплата, < 0 —
 *      гашение долга). Создаёт запись PAYMENT. При предоплате создаёт
 *      новый оплаченный контракт AUTO_RENEW. Использует ту же логику, что
 *      и RenterViewModel.applyWeeklyPayment() — это гарантирует
 *      корректное состояние баланса и истории контрактов.
 *  • ACTION_TERMINATE_CONTRACT — «Kontraktni uzish»:
 *      помечает арендатора как вернувшего скутер (isReturned = true),
 *      создаёт запись TERMINATED в истории. Баланс НЕ меняется —
 *      скутер просто возвращается на базу.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: action=$action renterId=$renterId")

        if (renterId == -1) {
            Log.w(TAG, "No renterId in extras, ignoring")
            return
        }

        when (action) {
            ACTION_PAYMENT_RECEIVED -> handlePaymentReceived(context, renterId)
            ACTION_TERMINATE_CONTRACT -> handleTerminateContract(context, renterId)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    /**
     * «To'lov qabul qilindi»: обрабатываем оплату одной недели.
     *
     * Логика полностью совпадает с RenterViewModel.applyWeeklyPayment():
     *   • Если balance < 0 (есть долг) — гасим самый ранний неоплаченный
     *     контракт: помечаем isPaid = true. Баланс растёт на weeklyPrice.
     *     Если после этого баланс ≥ 0 — арендатор снова активен.
     *   • Если balance ≥ 0 (предоплата) — создаём новый оплаченный контракт
     *     AUTO_RENEW. Применяется логика «>7 дней с последнего контракта»:
     *     если последний контракт закончился больше недели назад, новый
     *     начинается с сегодняшнего дня. Арендатор возвращается в активное
     *     состояние (isReturned = false).
     *
     * В обоих случаях создаётся запись PAYMENT для истории транзакций.
     */
    private fun handlePaymentReceived(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter = db.renterDao().getRenterById(renterId)
                if (renter == null) {
                    Log.w(TAG, "Renter #$renterId not found, ignoring payment")
                    return@launch
                }

                val weeklyPrice = SettingsRepository(context).weeklyPrice
                    .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE }
                val newBalance = renter.balance + weeklyPrice
                val now = System.currentTimeMillis()
                val dayMs = 24L * 60 * 60 * 1000
                val weekMs = 7L * dayMs

                // Подтягиваем скутер для денормализованных полей
                val scooter: Scooter? = renter.scooterId?.let {
                    db.scooterDao().getScooterById(it)
                }
                val sVin = scooter?.vinNumber ?: ""
                val sEngine = scooter?.engineNumber ?: ""
                val sSerial = scooter?.scooterSerialNumber ?: ""
                val sBat1 = scooter?.batteryId1 ?: ""
                val sBat2 = scooter?.batteryId2 ?: ""
                val sExtra = scooter?.additionalInfo ?: ""

                if (renter.balance < 0) {
                    // ── Гашение долга: помечаем самый ранний неоплаченный контракт ──
                    val unpaid = db.contractHistoryDao().getEarliestUnpaidContract(renter.id)
                    if (unpaid != null) {
                        db.contractHistoryDao().update(unpaid.copy(isPaid = true))
                    }
                    db.contractHistoryDao().insert(
                        ContractHistoryEntry(
                            renterId = renter.id, timestamp = now,
                            type = ContractHistoryEntry.TYPE_PAYMENT, amount = weeklyPrice,
                            notes = "To'lov qabul qilindi (bildirishnoma)",
                            renterName = renter.name, renterPhone = renter.phoneNumber,
                            scooterName = renter.scooterName,
                            weekStart = unpaid?.weekStart,
                            weekEnd = unpaid?.weekEnd,
                            weeklyPrice = weeklyPrice,
                            passportData = renter.passportData,
                            address = renter.address,
                            pinfl = renter.pinfl,
                            vinNumber = sVin, engineNumber = sEngine,
                            scooterSerialNumber = sSerial,
                            batteryId1 = sBat1, batteryId2 = sBat2,
                            additionalInfo = sExtra
                        )
                    )
                    val updated = renter.copy(
                        balance = newBalance,
                        debtAmount = maxOf(0.0, -newBalance),
                        lastPaymentTimestamp = now,
                        isOverdueSmsSent = false,
                        // Если после гашения баланс стал ≥ 0 — возвращаем в активное
                        isReturned = if (newBalance >= 0) false else renter.isReturned
                    )
                    db.renterDao().updateRenter(updated)
                    Log.d(TAG, "Payment (debt): renter #$renterId " +
                        "balance ${renter.balance} → $newBalance, " +
                        "unpaid contract ${unpaid?.id} marked paid")
                } else {
                    // ── Предоплата: создаём новый оплаченный контракт ──
                    val latestPaid = db.contractHistoryDao().getLatestPaidContract(renter.id)
                    val lastWeekEnd = latestPaid?.weekEnd
                        ?: (renter.rentStartDateTimestamp + renter.rentDurationDays * dayMs)
                    val effectiveLastEnd = lastWeekEnd
                        ?: (renter.rentStartDateTimestamp + renter.rentDurationDays * dayMs)
                    val effectiveGapMs = now - effectiveLastEnd
                    val shouldStartFromNow = effectiveGapMs > weekMs

                    val baseStart = if (shouldStartFromNow) {
                        now
                    } else {
                        // gap <= 7 days → start from last contract end (continuous coverage).
                        effectiveLastEnd
                    }
                    val weekStart = baseStart
                    val weekEnd = baseStart + weekMs

                    val contractNotes = when {
                        renter.isReturned -> "Qayta faollashtirildi (bildirishnoma)"
                        shouldStartFromNow && lastWeekEnd != null ->
                            "Yangi hafta (eski kontrakt muddati o'tgan)"
                        else -> "Oldindan to'lov (bildirishnoma)"
                    }

                    db.contractHistoryDao().insert(
                        ContractHistoryEntry(
                            renterId = renter.id, timestamp = now,
                            type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                            amount = weeklyPrice, notes = contractNotes,
                            renterName = renter.name, renterPhone = renter.phoneNumber,
                            scooterName = renter.scooterName,
                            weekStart = weekStart, weekEnd = weekEnd,
                            weeklyPrice = weeklyPrice,
                            passportData = renter.passportData,
                            address = renter.address, pinfl = renter.pinfl,
                            vinNumber = sVin, engineNumber = sEngine,
                            scooterSerialNumber = sSerial,
                            batteryId1 = sBat1, batteryId2 = sBat2,
                            additionalInfo = sExtra,
                            isPaid = true
                        )
                    )
                    db.contractHistoryDao().insert(
                        ContractHistoryEntry(
                            renterId = renter.id, timestamp = now,
                            type = ContractHistoryEntry.TYPE_PAYMENT, amount = weeklyPrice,
                            notes = "To'lov qabul qilindi (bildirishnoma)",
                            renterName = renter.name, renterPhone = renter.phoneNumber,
                            scooterName = renter.scooterName,
                            weekStart = weekStart, weekEnd = weekEnd,
                            weeklyPrice = weeklyPrice,
                            passportData = renter.passportData,
                            address = renter.address, pinfl = renter.pinfl,
                            vinNumber = sVin, engineNumber = sEngine,
                            scooterSerialNumber = sSerial,
                            batteryId1 = sBat1, batteryId2 = sBat2,
                            additionalInfo = sExtra
                        )
                    )
                    val updated = renter.copy(
                        balance = newBalance,
                        debtAmount = maxOf(0.0, -newBalance),
                        rentStartDateTimestamp = weekStart,
                        rentDurationDays = 7,
                        lastPaymentTimestamp = now,
                        isOverdueSmsSent = false,
                        isReturned = false  // реактивация
                    )
                    db.renterDao().updateRenter(updated)
                    Log.d(TAG, "Payment (prepay): renter #$renterId " +
                        "balance ${renter.balance} → $newBalance, " +
                        "new contract $weekStart..$weekEnd")
                }

                NotificationManagerCompat.from(context).cancel(renterId)
                Log.d(TAG, "Notification #$renterId dismissed after payment")
            } catch (e: Exception) {
                Log.e(TAG, "handlePaymentReceived failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * «Kontraktni uzish»: помечает арендатора как вернувшего скутер
     * (isReturned = true) и создаёт запись TERMINATED в истории.
     *
     * ЛОГИКА (по требованию пользователя, совпадает с RenterViewModel.applyTermination):
     *   • Если balance < 0 → оплачиваем ОДНУ неоплаченную неделю (помечаем
     *     isPaid=true у самого раннего неоплаченного контракта), создаём
     *     PAYMENT-запись. Баланс НЕ меняется (закрываем долг, а не добавляем
     *     новую неделю).
     *   • Если balance >= 0 → НИЧЕГО не платим, баланс не трогаем.
     *   • В обоих случаях isReturned=true, TERMINATED в истории + Transaction.
     */
    private fun handleTerminateContract(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter = db.renterDao().getRenterById(renterId)
                if (renter == null) {
                    Log.w(TAG, "Renter #$renterId not found, ignoring terminate")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val weeklyPrice = SettingsRepository(context).weeklyPrice
                    .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE }
                val scooter: Scooter? = renter.scooterId?.let {
                    db.scooterDao().getScooterById(it)
                }
                val sVin = scooter?.vinNumber ?: ""
                val sEngine = scooter?.engineNumber ?: ""
                val sSerial = scooter?.scooterSerialNumber ?: ""
                val sBat1 = scooter?.batteryId1 ?: ""
                val sBat2 = scooter?.batteryId2 ?: ""
                val sExtra = scooter?.additionalInfo ?: ""

                // ── Шаг 1: решение по балансу ────────────────────────────
                // balance < 0 → оплачиваем одну неоплаченную неделю.
                // balance >= 0 → ничего не платим.
                var paidContractId: Int? = null
                val notesTerminated: String
                if (renter.balance < 0) {
                    val unpaid = db.contractHistoryDao().getEarliestUnpaidContract(renter.id)
                    if (unpaid != null) {
                        db.contractHistoryDao().update(unpaid.copy(isPaid = true))
                        paidContractId = unpaid.id
                        // PAYMENT-запись (как при обычной оплате, но в контексте terminate)
                        db.contractHistoryDao().insert(
                            ContractHistoryEntry(
                                renterId = renter.id, timestamp = now,
                                type = ContractHistoryEntry.TYPE_PAYMENT,
                                amount = weeklyPrice,
                                notes = "Tugatish vaqtida to'lov (bildirishnoma)",
                                renterName = renter.name, renterPhone = renter.phoneNumber,
                                scooterName = renter.scooterName,
                                weekStart = unpaid.weekStart, weekEnd = unpaid.weekEnd,
                                weeklyPrice = weeklyPrice,
                                passportData = renter.passportData,
                                address = renter.address, pinfl = renter.pinfl,
                                vinNumber = sVin, engineNumber = sEngine,
                                scooterSerialNumber = sSerial,
                                batteryId1 = sBat1, batteryId2 = sBat2,
                                additionalInfo = sExtra
                            )
                        )
                        notesTerminated = "Kontrakt tugatildi (qarz yopildi, bildirishnoma)"
                    } else {
                        notesTerminated = "Kontrakt tugatildi (bildirishnoma)"
                    }
                } else {
                    notesTerminated = "Kontrakt tugatildi (bildirishnoma)"
                }

                // ── Шаг 2: создаём TERMINATED-запись ────────────────────
                db.contractHistoryDao().insert(
                    ContractHistoryEntry(
                        renterId = renter.id, timestamp = now,
                        type = ContractHistoryEntry.TYPE_TERMINATED,
                        amount = weeklyPrice,
                        notes = notesTerminated,
                        renterName = renter.name, renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName,
                        weekStart = renter.rentStartDateTimestamp,
                        weekEnd = now,
                        weeklyPrice = weeklyPrice,
                        passportData = renter.passportData,
                        address = renter.address, pinfl = renter.pinfl,
                        vinNumber = sVin, engineNumber = sEngine,
                        scooterSerialNumber = sSerial,
                        batteryId1 = sBat1, batteryId2 = sBat2,
                        additionalInfo = sExtra
                    )
                )

                // ── Шаг 3: помечаем арендатора как вернувшего ───────────
                // Баланс НЕ меняется (balance < 0 → долг остаётся в истории,
                // balance >= 0 → аванс остаётся, как требовал пользователь).
                val updated = renter.copy(
                    isReturned = true,
                    lastPaymentTimestamp = now,
                    isOverdueSmsSent = false
                )
                db.renterDao().updateRenter(updated)
                NotificationManagerCompat.from(context).cancel(renterId)
                Log.d(TAG, "Terminate: renter #$renterId marked returned, " +
                    "balance ${renter.balance} (unpaid contract paid: $paidContractId)")
            } catch (e: Exception) {
                Log.e(TAG, "handleTerminateContract failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_PAYMENT_RECEIVED = "com.example.ACTION_PAYMENT_RECEIVED"
        const val ACTION_TERMINATE_CONTRACT = "com.example.ACTION_TERMINATE_CONTRACT"
        const val EXTRA_RENTER_ID = "renterId"

        @Suppress("unused")
        fun scheduleOneHourReminder(context: Context, renterId: Int) {
            val work = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
                .setInitialDelay(1, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        PaymentCheckWorker.KEY_RENTER_ID to renterId,
                        PaymentCheckWorker.KEY_ONE_TIME to true
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder_$renterId",
                ExistingWorkPolicy.REPLACE,
                work
            )
        }
    }
}
