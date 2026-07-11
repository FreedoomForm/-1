package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import com.example.data.Scooter
import com.example.worker.NotificationHelper
import com.example.worker.PaymentCheckWorker
import com.example.worker.SimHelper
import com.example.worker.SmsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SmsResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null
) {
    val fullDetails: String
        get() = buildString {
            appendLine(message)
            if (errorCode != null) appendLine("Xato kodi: $errorCode")
            if (exceptionClass != null) appendLine("Exception: $exceptionClass")
            if (exceptionMessage != null) appendLine("Tushuntirish: $exceptionMessage")
        }
}

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    private val historyRepository: com.example.data.ContractHistoryRepository
    private val transactionRepository: com.example.data.TransactionRepository
    private val virtualCardRepository: com.example.data.VirtualCardRepository
    val rentersList: StateFlow<List<Renter>>

    private var smsSendCounter = 0

    private val _smsResults = MutableSharedFlow<SmsResult>(extraBufferCapacity = 10)
    val smsResults: SharedFlow<SmsResult> = _smsResults

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RenterRepository(database.renterDao())
        historyRepository = com.example.data.ContractHistoryRepository(
            database.contractHistoryDao()
        )
        transactionRepository = com.example.data.TransactionRepository(database.transactionDao())
        virtualCardRepository = com.example.data.VirtualCardRepository(
            database.virtualCardDao(),
            database.cardTransactionDao()
        )
        rentersList = repository.allRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun startAutoSync() { /* no-op: local-only mode */ }
    fun stopAutoSync() { /* no-op: local-only mode */ }

    /**
     * Создаёт нового арендатора. Одновременно создаёт одну запись CREATED в истории
     * контрактов, а при просрочке на старте — дополнительно N записей AUTO_RENEW
     * (по одной на каждую просроченную неделю), чтобы в истории сразу были видны
     * все недели долга.
     */
    fun addRenter(
        name: String, phone: String, debt: Double, duration: Int,
        startTimestamp: Long, scooterId: Int?, scooterName: String?, weeklyPrice: Double,
        // PDF-реквизиты арендатора
        passportData: String = "",
        address: String = "",
        pinfl: String = ""
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val expiryTime = startTimestamp + duration * 24L * 60 * 60 * 1000
            val isOverdueAtCreation = expiryTime < now
            val effectiveWeeklyPrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE

            // ── Начальный баланс арендатора ──────────────────────────────────
            // Логика:
            //   • Если дата окончания уже в прошлом (isOverdueAtCreation) →
            //     баланс = -(weeklyPrice × (overdueWeeks + 1)). Арендатор сразу
            //     видит красный статус и долг за просроченные недели.
            //     ВНИМАНИЕ: +1 нужен потому, что помимо overdueWeeks штук
            //     AUTO_RENEW-контрактов создаётся ещё 1 базовый CREATED-контракт
            //     (первая неделя), который тоже не оплачен. Раньше здесь было
            //     -(weeklyPrice × overdueWeeks) — это приводило к багу: при
            //     оплате одной недели баланс сразу становился 0, хотя вторая
            //     неделя всё ещё была не оплачена (см. скриншот пользователя).
            //   • Если в форме указан явный долг (debt > 0) → баланс = -debt.
            //     Пользователь сам ввёл сумму долга при создании.
            //   • Иначе (создаётся сегодня, без явного долга) → контракт
            //     создаётся ЗАРАНЕЕ ОПЛАЧЕННЫМ: баланс = 0, isPaid = true.
            //     На странице «Ijarachilar» виден ЗЕЛЁНЫЙ статус (оплачено),
            //     контракт в истории — зелёный. При наступлении следующей
            //     недели авто-продление создаст новый неоплаченный контракт.
            val initialBalance = when {
                isOverdueAtCreation -> {
                    val overdueWeeks = ((now - expiryTime) / (7L * 24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                    -(effectiveWeeklyPrice * (overdueWeeks + 1))
                }
                debt > 0 -> -debt
                else -> 0.0
            }
            val isPrepaidContract = !isOverdueAtCreation && debt <= 0
            val finalDebt = if (initialBalance < 0) -initialBalance else 0.0

            val provisional = Renter(
                name = name, phoneNumber = phone, debtAmount = finalDebt,
                rentDurationDays = duration, rentStartDateTimestamp = startTimestamp,
                scooterId = scooterId, scooterName = scooterName, balance = initialBalance,
                passportData = passportData, address = address, pinfl = pinfl
            )

            val localId = repository.insert(provisional).toInt()
            val savedRenter = provisional.copy(id = localId)

            // ── Подтягиваем реквизиты скутера из БД для PDF-договора ───────
            val scooter: Scooter? = scooterId?.let { fetchScooterById(it) }

            // ── Запись CREATED ──────────────────────────────────────────
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    historyRepository.insert(ContractHistoryEntry(
                        renterId = savedRenter.id,
                        timestamp = now,
                        type = ContractHistoryEntry.TYPE_CREATED,
                        amount = effectiveWeeklyPrice,
                        notes = if (isOverdueAtCreation) "Kechikkan holda yaratildi" else "Yaratildi (oldindan to'langan)",
                        renterName = savedRenter.name,
                        renterPhone = savedRenter.phoneNumber,
                        scooterName = savedRenter.scooterName,
                        weekStart = savedRenter.rentStartDateTimestamp,
                        weekEnd = savedRenter.rentStartDateTimestamp + savedRenter.rentDurationDays * 24L * 60 * 60 * 1000,
                        weeklyPrice = effectiveWeeklyPrice,
                        passportData = savedRenter.passportData,
                        address = savedRenter.address,
                        pinfl = savedRenter.pinfl,
                        vinNumber = scooter?.vinNumber ?: "",
                        engineNumber = scooter?.engineNumber ?: "",
                        scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                        batteryId1 = scooter?.batteryId1 ?: "",
                        batteryId2 = scooter?.batteryId2 ?: "",
                        additionalInfo = scooter?.additionalInfo ?: "",
                        isPaid = isPrepaidContract
                    ))

                    // Если просрочка — создаем N записей AUTO_RENEW
                    if (isOverdueAtCreation) {
                        val overdueWeeks = ((now - expiryTime) / (7L * 24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                        for (i in 1..overdueWeeks) {
                            val weekStart = expiryTime + (i - 1) * 7L * 24 * 60 * 60 * 1000
                            val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000
                            historyRepository.insert(ContractHistoryEntry(
                                renterId = savedRenter.id,
                                timestamp = now,
                                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                                amount = effectiveWeeklyPrice,
                                notes = "$i-hafta avtomatik yangilangan (kechikish)",
                                renterName = savedRenter.name,
                                renterPhone = savedRenter.phoneNumber,
                                scooterName = savedRenter.scooterName,
                                weekStart = weekStart,
                                weekEnd = weekEnd,
                                weeklyPrice = effectiveWeeklyPrice,
                                passportData = savedRenter.passportData,
                                address = savedRenter.address,
                                pinfl = savedRenter.pinfl,
                                vinNumber = scooter?.vinNumber ?: "",
                                engineNumber = scooter?.engineNumber ?: "",
                                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                                batteryId1 = scooter?.batteryId1 ?: "",
                                batteryId2 = scooter?.batteryId2 ?: "",
                                additionalInfo = scooter?.additionalInfo ?: ""
                            ))
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "History save xato", e) }
            }

            if (isOverdueAtCreation) {
                val context = getApplication<Application>()
                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber)
                } catch (e: Exception) { Log.e(TAG, "Notification xato", e) }

                // ── SMS avto-yuborish rejimini tekshirish ─────────────────
                // Agar Settings'da "Qo'llanma" rejimi tanlangan bo'lsa (smsAutoSendEnabled=false),
                // SMS avtomatik yuborilmaydi — faqat bildirishnoma va tarix yozuvi saqlanadi.
                // Foydalanuvchi keyin "SMS" tugmasi orqali qo'lda yuborishi mumkin.
                val settingsRepo = SettingsRepository(context)
                if (settingsRepo.smsAutoSendEnabled) {
                    sendSmsWithFullRetry(context, savedRenter, expiryTime, now, initialBalance)
                } else {
                    Log.d(TAG, "Auto-SMS skipped for renter #${savedRenter.id}: manual mode is on")
                    _smsResults.tryEmit(SmsResult(
                        success = false,
                        message = "SMS avto-yuborish o'chirilgan (qo'llanma rejimi). Mijozga SMS qo'lda yuboring.",
                        errorCode = "SMS_AUTO_DISABLED",
                        exceptionClass = null,
                        exceptionMessage = "smsAutoSendEnabled=false"
                    ))
                }

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.notificationHistoryDao().insert(com.example.data.NotificationHistoryEntity(
                        timestamp = now, renterId = savedRenter.id,
                        title = "To'lov muddati yetdi",
                        message = "Mijoz ${savedRenter.name} (${savedRenter.phoneNumber}) bugun to'lov qilishi kerak"
                    ))
                } catch (e: Exception) { Log.w(TAG, "Notif save xato", e) }

                val wm = WorkManager.getInstance(getApplication())
                wm.enqueueUniquePeriodicWork("PaymentCheckWork", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS).build())
            }
            // Обновляем нативные виджеты Android после создания арендатора
            try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
        }
    }

    /**
     * SMS yuborish — 3 ta usul bilan ketma-ket uriniladi.
     */
    private fun sendSmsWithFullRetry(
        context: android.content.Context,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        initialBalance: Double
    ) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: ruxsat berilmagan", "SMS_PERMISSION_DENIED", "SecurityException", "SEND_SMS ruxsati berilmagan"))
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SmsWorker>().build())
            return
        }

        val rawPhone = renter.phoneNumber
        val phone = SimHelper.normalizePhoneNumber(rawPhone)
        if (phone.isBlank()) {
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: raqam bo'sh", "SMS_EMPTY_PHONE", "IllegalArgumentException", "Telefon raqami kiritilmagan"))
            return
        }

        val settingsRepo = SettingsRepository(context)
        val message = formatSmsMessage(settingsRepo, renter, expiryTime, now, -initialBalance)

        Log.d(TAG, "SMS yuborish boshlandi: to=$phone (${rawPhone}→$phone), ${message.length} chars")

        val attempts = SimHelper.getAllSmsManagers(context)
        val validAttempts = attempts.filter { it.smsManager != null }

        if (validAttempts.isEmpty()) {
            val diag = SimHelper.getDiagnostics(context, rawPhone)
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: SmsManager topilmadi",
                "SMS_MANAGER_NULL", "IllegalStateException", "Hech qanday SmsManager topilmadi.\n${diag.fullReport}"))
            return
        }

        trySmsAttempts(context, renter, phone, message, validAttempts, 0)
    }

    /**
     * Формирует текст SMS с подстановкой всех тегов:
     * {name}, {days}, {debt}, {payme}, {call}.
     *
     * Имя приводится к нижнему регистру первой буквы — так, как в примере пользователя
     * (озодбек).
     */
    fun formatSmsMessage(
        settingsRepo: SettingsRepository,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        debtAmount: Double
    ): String {
        val days = if (now > expiryTime) ((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt() else 0
        // Надёжный источник долга — balance. Если переданный debtAmount = 0,
        // но balance < 0, используем -balance (debtAmount мог рассинхронизироваться).
        val effectiveDebt = if (debtAmount > 0) debtAmount else maxOf(0.0, -renter.balance)
        val debt = effectiveDebt.toBigDecimal().setScale(0, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
        val nameLower = renter.name.trim().lowercase()
        return settingsRepo.smsTemplate
            .replace("{name}", nameLower)
            .replace("{days}", maxOf(1, days).toString())
            .replace("{debt}", debt)
            .replace("{payme}", settingsRepo.paymeLink)
            .replace("{call}", settingsRepo.callCenter)
    }

    private fun trySmsAttempts(
        context: android.content.Context,
        renter: Renter,
        phone: String,
        message: String,
        attempts: List<SimHelper.SmsSendAttempt>,
        currentIndex: Int
    ) {
        if (currentIndex >= attempts.size) {
            val lastManager = attempts.last().smsManager ?: return
            Log.w(TAG, "Barcha PendingIntentli urinishlar xato — fire-and-forget bilan urinilmoqda")
            val sent = SimHelper.sendSmsFireAndForget(lastManager, phone, message)
            if (sent) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
                _smsResults.tryEmit(SmsResult(true, "SMS yuborildi (fire-and-forget): $phone"))
            } else {
                val diag = SimHelper.getDiagnostics(context, phone)
                _smsResults.tryEmit(SmsResult(false,
                    "SMS yuborilmadi: barcha usullar xato (${attempts.size} ta urinish)",
                    "SMS_ALL_METHODS_FAILED", "GENERIC_FAILURE",
                    buildString {
                        appendLine("Ilova ${attempts.size} xil usul bilan urinib ko'rdi — hammasida GENERIC_FAILURE.")
                        appendLine()
                        appendLine("Eng ehtimol sabablar:")
                        appendLine("1) SIM balans YETARLI EMAS — ${diag.networkOperatorName ?: "operator"} balansini tekshiring!")
                        appendLine("2) Operator SMS ni rad etmoqda — operatorga qo'ng'iroq qiling")
                        appendLine("3) Ilova DEFAULT SMS APP emas — Android sozlamalaridan 'Default SMS app' qiling")
                        appendLine()
                        appendLine(diag.fullReport)
                    }))
            }
            return
        }

        val attempt = attempts[currentIndex]
        val smsManager = attempt.smsManager ?: return trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)

        val isLastAttempt = currentIndex == attempts.size - 1

        if (isLastAttempt) {
            val sent = SimHelper.sendSmsFireAndForget(smsManager, phone, message)
            if (sent) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
                _smsResults.tryEmit(SmsResult(true, "SMS yuborildi (${attempt.method}): $phone"))
            } else {
                val diag = SimHelper.getDiagnostics(context, phone)
                _smsResults.tryEmit(SmsResult(false,
                    "SMS yuborilmadi: barcha usullar xato",
                    "SMS_ALL_METHODS_FAILED", "GENERIC_FAILURE",
                    buildString {
                        appendLine("${attempts.size} ta usul bilan urinildi — hammasi GENERIC_FAILURE.")
                        appendLine()
                        appendLine("Sabablar:")
                        appendLine("1) SIM BALANS yetarli emas — ${diag.networkOperatorName ?: "operator"} balansini tekshiring!")
                        appendLine("2) Operator SMS ni rad etmoqda")
                        appendLine("3) Ilova Default SMS app emas")
                        appendLine()
                        appendLine(diag.fullReport)
                    }))
            }
            return
        }

        val actionId = "com.example.SMS_SENT_${++smsSendCounter}_A${attempt.attempt}"
        val sentIntent = PendingIntent.getBroadcast(
            context, smsSendCounter, Intent(actionId),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                val code = resultCode
                Log.d(TAG, "SMS result [${attempt.method}]: code=$code for $phone")

                if (code == android.app.Activity.RESULT_OK) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                    }
                    _smsResults.tryEmit(SmsResult(true, "SMS muvaffaqiyatli yuborildi (${attempt.method}): $phone"))
                } else if (code == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
                    Log.w(TAG, "GENERIC_FAILURE [${attempt.method}] → keyingi usulga o'tilmoqda")
                    _smsResults.tryEmit(SmsResult(true, "SMS usul ${attempt.attempt} xato, keyingi usul sinab ko'rilmoca..."))
                    trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
                } else {
                    val (errorName, _) = smsErrorCodeToText(code)
                    Log.w(TAG, "$errorName [${attempt.method}] → keyingi usulga o'tilmoqda")
                    trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
                }
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(actionId))
        } catch (e: Exception) {
            Log.e(TAG, "Receiver ro'yxatdan o'tkazilmadi", e)
        }

        try {
            SimHelper.sendSmsAuto(smsManager, phone, message, sentIntent, null)
            Log.d(TAG, "SMS ${attempt.attempt}-urinish [${attempt.method}]: → $phone (${message.length} chars)")
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            Log.w(TAG, "SMS ${attempt.attempt}-urinishda exception: ${e.message}")
            trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
        }
    }

    private fun smsErrorCodeToText(code: Int): Pair<String, String> {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE" to "Umumiy xato (kod 1)"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF" to "Radio o'chiq (kod 2)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU" to "PDU xatosi (kod 3)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE" to "Tarmoq yo'q (kod 4)"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED" to "SMS limiti (kod 5)"
            else -> "UNKNOWN_$code" to "Noma'lum ($code)"
        }
    }

    fun updateRenter(renter: Renter) {
        viewModelScope.launch { repository.update(renter) }
    }

    /**
     * Обновление арендатора с автоматической корректировкой контрактов.
     *
     * Если арендодатель изменил дату начала аренды **назад** (старая дата новее новой),
     * то на каждый дополнительный период в 7 дней:
     *   • создаётся одна запись AUTO_RENEW в истории контрактов
     *   • баланс уменьшается на weeklyPrice
     *
     * Если дата сдвинута **вперёд** — наоборот, AUTO_RENEW-записи за эти недели
     * удаляются (последние N), баланс восстанавливается.
     *
     * Если изменилась длительность (без смены даты) — аналогично для новых недель.
     */
    fun updateRenterWithContracts(
        existing: Renter,
        newName: String,
        newPhone: String,
        newDebt: Double,
        newDuration: Int,
        newStartTimestamp: Long,
        newScooterId: Int?,
        newScooterName: String?,
        newIsActive: Boolean,
        weeklyPrice: Double,
        // PDF-реквизиты арендатора
        passportData: String = existing.passportData,
        address: String = existing.address,
        pinfl: String = existing.pinfl
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
            val settingsRepo = SettingsRepository(getApplication())
            val realWeeklyPrice = if (settingsRepo.weeklyPrice > 0) settingsRepo.weeklyPrice else effectivePrice

            val oldStart = existing.rentStartDateTimestamp
            val newStart = newStartTimestamp
            val oldDuration = existing.rentDurationDays
            val newDuration = newDuration

            val oldEnd = oldStart + oldDuration * 24L * 60 * 60 * 1000
            val newEnd = newStart + newDuration * 24L * 60 * 60 * 1000

            val now = System.currentTimeMillis()
            var balanceAdjust = 0.0

            // ── Подтягиваем реквизиты скутера из БД (для новых AUTO_RENEW) ─
            val scooter: Scooter? = newScooterId?.let { fetchScooterById(it) }

            // ── Сдвиг даты назад → дополнительные недели ───────────────────
            if (newStart < oldStart) {
                val deltaMillis = oldStart - newStart
                val extraWeeks = ((deltaMillis + 7L * 24 * 60 * 60 * 1000 - 1) / (7L * 24 * 60 * 60 * 1000)).toInt()
                if (extraWeeks > 0) {
                    for (i in 1..extraWeeks) {
                        val weekStart = newStart + (i - 1) * 7L * 24 * 60 * 60 * 1000
                        val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000
                        historyRepository.insert(ContractHistoryEntry(
                            renterId = existing.id,
                            timestamp = now,
                            type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                            amount = realWeeklyPrice,
                            notes = "$i-hafta (muddat orqaga surildi)",
                            renterName = newName,
                            renterPhone = newPhone,
                            scooterName = newScooterName,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            weeklyPrice = realWeeklyPrice,
                            passportData = passportData,
                            address = address,
                            pinfl = pinfl,
                            vinNumber = scooter?.vinNumber ?: "",
                            engineNumber = scooter?.engineNumber ?: "",
                            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                            batteryId1 = scooter?.batteryId1 ?: "",
                            batteryId2 = scooter?.batteryId2 ?: "",
                            additionalInfo = scooter?.additionalInfo ?: ""
                        ))
                        balanceAdjust -= realWeeklyPrice
                    }
                }
            }

            // ── Сдвиг даты вперёд → возврат баланса за «лишние» недели ──────
            if (newStart > oldStart) {
                val deltaMillis = newStart - oldStart
                val removedWeeks = (deltaMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
                if (removedWeeks > 0) {
                    val history = historyRepository.getForRenterOnce(existing.id)
                    val autoRenew = history.filter { it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
                        .sortedByDescending { it.timestamp }.take(removedWeeks)
                    autoRenew.forEach { entry ->
                        historyRepository.deleteById(entry.id)
                        balanceAdjust += realWeeklyPrice
                    }
                }
            }

            // ── Изменение длительности ─────────────────────────────────────
            if (oldStart == newStart && newDuration > oldDuration) {
                val deltaDays = newDuration - oldDuration
                val extraWeeks = ((deltaDays + 6) / 7).coerceAtLeast(1)
                for (i in 1..extraWeeks) {
                    val weekStart = oldEnd + (i - 1) * 7L * 24 * 60 * 60 * 1000
                    val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000
                    historyRepository.insert(ContractHistoryEntry(
                        renterId = existing.id,
                        timestamp = now,
                        type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                        amount = realWeeklyPrice,
                        notes = "$i-hafta (muddat uzaytirildi)",
                        renterName = newName,
                        renterPhone = newPhone,
                        scooterName = newScooterName,
                        weekStart = weekStart,
                        weekEnd = weekEnd,
                        weeklyPrice = realWeeklyPrice,
                        passportData = passportData,
                        address = address,
                        pinfl = pinfl,
                        vinNumber = scooter?.vinNumber ?: "",
                        engineNumber = scooter?.engineNumber ?: "",
                        scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                        batteryId1 = scooter?.batteryId1 ?: "",
                        batteryId2 = scooter?.batteryId2 ?: "",
                        additionalInfo = scooter?.additionalInfo ?: ""
                    ))
                    balanceAdjust -= realWeeklyPrice
                }
            }

            val newBalance = existing.balance + balanceAdjust
            val updated = existing.copy(
                name = newName,
                phoneNumber = newPhone,
                debtAmount = maxOf(newDebt, -newBalance.coerceAtMost(0.0)),
                rentDurationDays = newDuration,
                rentStartDateTimestamp = newStart,
                scooterId = newScooterId,
                scooterName = newScooterName,
                isReturned = !newIsActive,
                balance = newBalance,
                isOverdueSmsSent = if (newIsActive && newStart != oldStart) false else existing.isOverdueSmsSent,
                passportData = passportData,
                address = address,
                pinfl = pinfl
            )
            repository.update(updated)
        }
    }

    /** Подгружает скутер из БД по его id (для денормализации в ContractHistoryEntry). */
    private suspend fun fetchScooterById(id: Int): Scooter? {
        return AppDatabase.getDatabase(getApplication()).scooterDao().getScooterById(id)
    }

    fun deleteRenter(id: Int) {
        viewModelScope.launch {
            historyRepository.deleteForRenter(id)
            repository.delete(id)
            // Обновляем нативные виджеты Android
            try {
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (_: Exception) {}
        }
    }

    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch { applyWeeklyPayment(renter, "Bitta to'lov") }
    }

    /**
     * Пакетная оплата одной недели для выбранных арендаторов.
     * Баланс каждого увеличивается на weeklyPrice, создаётся запись PAYMENT.
     */
    fun payWeeklyForRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            renterIds.forEach { id ->
                repository.getById(id)?.let { applyWeeklyPayment(it, "Ommaviy to'lov (1 hafta)") }
            }
        }
    }

    fun terminateRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id -> repository.getById(id)?.let { applyTermination(it, weeklyPrice) } }
        }
    }

    private suspend fun applyWeeklyPayment(renter: Renter, notes: String, weeklyPriceOverride: Double? = null) {
        val weeklyPrice = weeklyPriceOverride ?: SettingsRepository(getApplication()).weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val newBalance = renter.balance + effectivePrice
        val now = System.currentTimeMillis()

        // ── Логика статуса контракта ──────────────────────────────────────
        //   balance < 0  → у арендатора долг. Оплата гасит СТАРЫЙ контракт:
        //                  находим самый ранний неоплаченный (isPaid=false) контракт
        //                  и помечаем его isPaid=true (красный → зелёный).
        //                  Дата контракта НЕ меняется — он закрывается как оплаченный.
        //   balance >= 0 → все текущие контракты уже зелёные. Создаём НОВЫЙ
        //                  контракт от weekEnd самого позднего оплаченного контракта
        //                  до +7 дней, сразу с isPaid=true (зелёный).
        //
        // В обоих случаях создаётся запись PAYMENT (транзакция оплаты для истории).
        // Запись PAYMENT не показывается на экране контрактов — только CREATED/AUTO_RENEW.
        if (renter.balance < 0) {
            // ── Гашение долга: помечаем самый ранний неоплаченный контракт ──
            val unpaid = historyRepository.getEarliestUnpaidContract(renter.id)
            if (unpaid != null) {
                historyRepository.update(unpaid.copy(isPaid = true))
            }
            // Запись PAYMENT — для истории транзакций (не показывается на экране контрактов)
            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = unpaid?.weekStart,
                weekEnd = unpaid?.weekEnd,
                weeklyPrice = effectivePrice,
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
            historyRepository.insert(paymentEntry)

            // ── Запись в таблицу transactions (для страницы «Tranzaksiya») ──
            // Чтобы оплата отображалась не только в истории контрактов, но и в
            // общем списке транзакций. Привязка к контракту (contractId) —
            // к оплаченному контракту, если он есть.
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val contractLabel = unpaid?.let { e ->
                val ws = e.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                val we = e.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                "#${e.id}  $ws → $we"
            } ?: ""
            try {
                transactionRepository.insert(
                    com.example.data.Transaction(
                        contractId = unpaid?.id,
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = com.example.data.Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = contractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w("RenterViewModel", "Failed to insert transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту (виртуальная касса) ──
            // Все оплаты контрактов автоматически падают на главную карту
            // (id=1) — это входящий поток денег в фин. систему приложения.
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (qarz yopildi) — $notes"
                )
            } catch (e: Exception) {
                Log.w("RenterViewModel", "depositContractIncome failed: ${e.message}")
            }

            // Обновляем арендатора: баланс растёт, даты аренды НЕ меняются
            val updated = renter.copy(
                debtAmount = maxOf(0.0, -newBalance),
                balance = newBalance,
                lastPaymentTimestamp = now,
                isOverdueSmsSent = false
            )
            repository.update(updated)
        } else {
            // ── Предоплата: создаём новый оплаченный контракт (зелёный) ─────
            val latestPaid = historyRepository.getLatestPaidContract(renter.id)
            val lastWeekEnd = latestPaid?.weekEnd
                ?: (renter.rentStartDateTimestamp + renter.rentDurationDays * 24L * 60 * 60 * 1000)
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs

            // ── НОВАЯ ЛОГИКА: проверка давности последнего контракта ──────
            // Если последний оплаченный контракт закончился БОЛЬШЕ 7 дней назад
            // (т.е. (now - lastWeekEnd) > 7 дней), то новый контракт начинается
            // с ТЕКУЩЕГО дня, а не с lastWeekEnd.
            //
            // Если последний контракт закончился МЕНЬШЕ 7 дней назад (или ещё
            // не закончился — lastWeekEnd в будущем), то новый контракт начинается
            // с lastWeekEnd — это обеспечивает НЕПРЕРЫВНОЕ покрытие без дыр.
            // Например: последний контракт закончился 8 июля, сегодня 10 июля
            // → новый контракт начинается с 8 июля (10 - 8 = 2 дня < 7 дней).
            //
            // Дополнительно: если арендатор был в пассивном состоянии
            // (isReturned = true — скутер возвращён, аренда завершена), то при
            // создании нового контракта он возвращается в активное состояние.
            // Если lastWeekEnd == null (нет оплаченных контрактов) — используем
            // rentStartDate + duration как «последнюю дату», и ту же логику >7 дней.
            val effectiveLastEnd = lastWeekEnd
                ?: (renter.rentStartDateTimestamp + renter.rentDurationDays * dayMs)
            val effectiveGapMs = now - effectiveLastEnd
            val shouldStartFromNow = effectiveGapMs > weekMs

            val baseStart = if (shouldStartFromNow) {
                now
            } else {
                // gap <= 7 days → start from last contract end (continuous coverage).
                // This covers both cases: lastWeekEnd in the past (within 7 days)
                // and lastWeekEnd in the future (pre-paying for next week).
                effectiveLastEnd
            }
            val weekStart = baseStart
            val weekEnd = baseStart + weekMs

            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val scooterVin = scooter?.vinNumber ?: ""
            val scooterEngine = scooter?.engineNumber ?: ""
            val scooterSerial = scooter?.scooterSerialNumber ?: ""
            val scooterBat1 = scooter?.batteryId1 ?: ""
            val scooterBat2 = scooter?.batteryId2 ?: ""
            val scooterExtra = scooter?.additionalInfo ?: ""

            // Описание для нового контракта
            val contractNotes = when {
                renter.isReturned -> "Qayta faollashtirildi (1 hafta to'lov)"
                shouldStartFromNow && lastWeekEnd != null -> "Yangi hafta (eski kontrakt muddati o'tgan)"
                else -> "Oldindan to'lov (keyingi hafta)"
            }

            // Новый контракт-неделя, сразу оплаченный (зелёный)
            val newContract = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW, amount = effectivePrice,
                notes = contractNotes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = weekStart, weekEnd = weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooterVin,
                engineNumber = scooterEngine,
                scooterSerialNumber = scooterSerial,
                batteryId1 = scooterBat1,
                batteryId2 = scooterBat2,
                additionalInfo = scooterExtra,
                isPaid = true
            )
            val newContractId = historyRepository.insert(newContract)

            // Запись PAYMENT — для истории транзакций
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = weekStart, weekEnd = weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooterVin,
                engineNumber = scooterEngine,
                scooterSerialNumber = scooterSerial,
                batteryId1 = scooterBat1,
                batteryId2 = scooterBat2,
                additionalInfo = scooterExtra
            )
            historyRepository.insert(paymentEntry)

            // ── Запись в таблицу transactions (для страницы «Tranzaksiya») ──
            // Чтобы оплата отображалась не только в истории контрактов, но и в
            // общем списке транзакций. Привязка к контракту (contractId) — к
            // только что созданному новому контракту.
            val dateFmtTx = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = dateFmtTx.format(java.util.Date(weekStart))
            val weStr = dateFmtTx.format(java.util.Date(weekEnd))
            val newContractLabel = "#$newContractId  $wsStr → $weStr"
            try {
                transactionRepository.insert(
                    com.example.data.Transaction(
                        contractId = newContractId.toInt(),
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = com.example.data.Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = newContractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w("RenterViewModel", "Failed to insert transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту (виртуальная касса) ──
            // Предоплата тоже падает на главную карту — это входящий поток.
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (oldindan) — $notes"
                )
            } catch (e: Exception) {
                Log.w("RenterViewModel", "depositContractIncome failed: ${e.message}")
            }

            // Обновляем арендатора:
            //   • баланс растёт (предоплата)
            //   • rentStartDateTimestamp = weekStart (новая неделя)
            //   • rentDurationDays = 7 (одна неделя)
            //   • isReturned = false — реактивация, если был в пассивном состоянии
            //   • isOverdueSmsSent = false — сбрасываем флаг просрочки
            val updated = renter.copy(
                debtAmount = maxOf(0.0, -newBalance),
                balance = newBalance,
                rentStartDateTimestamp = weekStart,
                rentDurationDays = 7,
                lastPaymentTimestamp = now,
                isOverdueSmsSent = false,
                isReturned = false  // ← реактивация пассивного арендатора
            )
            repository.update(updated)
            // Обновляем нативные виджеты Android
            try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
        }
    }

    /**
     * Умное расторжение контракта (кнопка «Uzish»).
     *
     * ЛОГИКА (по требованию пользователя):
     *   • Если balance < 0 (арендатор должен) → оплачиваем ОДНУ неоплаченную
     *     неделю: помечаем самый ранний isPaid=false контракт как оплаченный,
     *     создаём PAYMENT-запись, но баланс НЕ растёт и НЕ списывается — мы
     *     просто закрываем один долг. Если неоплаченных контрактов нет, но
     *     баланс всё равно отрицательный (рассинхрон) — баланс обнуляется
     *     до 0.
     *   • Если balance >= 0 (арендатор ничего не должен) → НИЧЕГО не платим,
     *     НЕ списываем с баланса. Баланс остаётся как есть.
     *   • В ОБОИХ случаях: isReturned=true, создаём TERMINATED в истории
     *     контрактов и Transaction TERMINATED в таблице транзакций.
     */
    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double) {
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val now = System.currentTimeMillis()
        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }

        // ── Шаг 1: решение по балансу ────────────────────────────────────
        // balance < 0 → у арендатора долг. Оплачиваем одну неделю.
        // balance >= 0 → ничего не платим, баланс не трогаем.
        val unpaid = if (renter.balance < 0) {
            historyRepository.getEarliestUnpaidContract(renter.id)
        } else null
        var paidContractId: Int? = null
        if (unpaid != null) {
            // Оплачиваем этот контракт (помечаем isPaid=true)
            historyRepository.update(unpaid.copy(isPaid = true))
            paidContractId = unpaid.id

            // Создаём PAYMENT-запись в истории контрактов (как при обычной оплате)
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice,
                notes = "Tugatish vaqtida to'lov (qarz yopildi)",
                renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = unpaid.weekStart, weekEnd = unpaid.weekEnd,
                weeklyPrice = effectivePrice,
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
            historyRepository.insert(paymentEntry)

            // Transaction в таблице транзакций (чтобы оплата появилась в «Tranzaksiya»)
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = unpaid.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
            val weStr = unpaid.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
            val contractLabel = "#${unpaid.id}  $wsStr → $weStr"
            try {
                transactionRepository.insert(
                    com.example.data.Transaction(
                        contractId = unpaid.id,
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = com.example.data.Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = "Tugatish vaqtida to'lov",
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = contractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w("RenterViewModel", "Failed to insert payment transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту (виртуальная касса) ──
            // При расторжении с погашением долга — сумма тоже падает на главную карту.
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (tugatish vaqtida)"
                )
            } catch (e: Exception) {
                Log.w("RenterViewModel", "depositContractIncome failed: ${e.message}")
            }
        }
        // Если unpaid == null — ничего с контрактами и балансом не делаем.

        // ── Шаг 2: переводим арендатора в пассивное состояние ─────────────
        // БАЛАНС НЕ МЕНЯЕТСЯ (раньше здесь было + effectivePrice — это была ошибка).
        val updated = renter.copy(
            isReturned = true,
            lastPaymentTimestamp = now,
            isOverdueSmsSent = false
        )
        repository.update(updated)

        // ── Шаг 3: создаём запись TERMINATED в истории контрактов ──────────
        val entry = ContractHistoryEntry(
            renterId = renter.id, timestamp = now,
            type = ContractHistoryEntry.TYPE_TERMINATED, amount = effectivePrice,
            notes = if (unpaid != null) "Kontrakt tugatildi (qarz yopildi)"
                    else "Kontrakt tugatildi",
            renterName = renter.name, renterPhone = renter.phoneNumber,
            scooterName = renter.scooterName,
            weekStart = renter.rentStartDateTimestamp,
            weekEnd = now,
            weeklyPrice = effectivePrice,
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
        historyRepository.insert(entry)

        // ── Шаг 4: Transaction TERMINATED в таблице транзакций ─────────────
        try {
            transactionRepository.insert(
                com.example.data.Transaction(
                    contractId = paidContractId,
                    renterId = renter.id,
                    scooterId = renter.scooterId,
                    timestamp = now,
                    type = com.example.data.Transaction.TYPE_TERMINATED,
                    amount = effectivePrice,
                    notes = "Kontrakt tugatildi",
                    renterName = renter.name,
                    renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName ?: "",
                    contractLabel = ""
                )
            )
        } catch (e: Exception) {
            Log.w("RenterViewModel", "Failed to insert terminated transaction: ${e.message}")
        }
        // Обновляем нативные виджеты Android
        try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "RenterViewModel"
    }
}