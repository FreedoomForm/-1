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
    private val actionUseCase: com.example.data.RenterActionUseCase
    val rentersList: StateFlow<List<Renter>>

    private var smsSendCounter = 0

    private val _smsResults = MutableSharedFlow<SmsResult>(extraBufferCapacity = 10)
    val smsResults: SharedFlow<SmsResult> = _smsResults

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RenterRepository(
            database.renterDao(),
            database.contractHistoryDao()
        )
        historyRepository = com.example.data.ContractHistoryRepository(
            database.contractHistoryDao()
        )
        transactionRepository = com.example.data.TransactionRepository(database.transactionDao())
        virtualCardRepository = com.example.data.VirtualCardRepository(
            database.virtualCardDao(),
            database.cardTransactionDao(),
            database
        )
        actionUseCase = com.example.data.RenterActionUseCase.fromContext(application)
        rentersList = repository.allRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun startAutoSync() {
        // §4/§5: при старте автоматически создаём неоплаченные контракты
        // для активных арендаторов, у которых последний контракт закончился.
        // Это гарантирует, что FIFO-оплата всегда находит целевой контракт.
        viewModelScope.launch {
            try {
                actionUseCase.autoCreateForAllActiveRenters()
            } catch (_: Exception) {}
        }
    }
    fun stopAutoSync() { /* no-op: local-only mode */ }

    /**
     * Создаёт нового арендатора и один или несколько контрактов в зависимости
     * от выбранной даты начала аренды. Новая логика (по запросу пользователя):
     *
     *   • Если выбранная дата была БОЛЕЕ НЕДЕЛИ назад (now - start > 7 дней):
     *     создаётся ОДИН контракт с МИНУСОВЫМ значением (долг).
     *     balance = -weeklyPrice, isPaid = false. Transaction НЕ создаётся,
     *     на карту ничего не зачисляется (деньги ещё не пришли).
     *
     *   • Если выбранная дата МЕНЕЕ НЕДЕЛИ назад (0 < now - start ≤ 7 дней):
     *     создаётся ОДИН контракт с ПЛЮСОВЫМ значением (предоплата).
     *     balance = 0, isPaid = true. Transaction(+weeklyPrice) создаётся,
     *     на главную карту зачисляется weeklyPrice.
     *
     *   • Если выбранная дата = сегодня или в будущем (start ≥ now):
     *     создаётся НЕСКОЛЬКО контрактов с ПЛЮСОВЫМ значением — по одному
     *     на каждую неделю от today до выбранного дня. Все с isPaid = true,
     *     для каждой создаётся Transaction и зачисление на карту.
     *
     *   • Если при создании передан список групп контрактов (contractGroups),
     *     они имеют приоритет над автоматической логикой выше. Для каждой
     *     группы создаётся отдельный контракт с weekStart = группа.startMs,
     *     weekEnd = группа.endMs. Знак контракта (долг/предоплата) определяется
     *     по той же логике относительно конца этой группы.
     *
     * При удалении контракта (deleteContractWithCascade в ContractHistoryViewModel)
     * каскадно удаляются все связанные Transaction и CardTransaction, а баланс
     * арендатора и главной карты реверсится.
     */
    fun addRenter(
        name: String, phone: String, debt: Double, duration: Int,
        startTimestamp: Long, scooterId: Int?, scooterName: String?, weeklyPrice: Double,
        // PDF-реквизиты арендатора
        passportData: String = "",
        address: String = "",
        pinfl: String = "",
        // ── Группы контрактов (из календаря формы) ────────────────────────
        // Если список не пуст — он имеет приоритет над автогенерацией по
        // выбранной дате. Каждая группа = один контракт с weekStart/weekEnd
        // и isPaid из группы. Список — это List<Triple<startMs, endMs, isPaid>>.
        contractGroups: List<Triple<Long, Long, Boolean>> = emptyList()
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs

            val expiryTime = startTimestamp + duration * dayMs

            val effectiveWeeklyPrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
            val settingsForPricing = SettingsRepository(getApplication())
            val effectiveMonthlyPrice = settingsForPricing.monthlyPrice
                .let { if (it > 0) it else SettingsRepository.DEFAULT_MONTHLY_PRICE }

            // ── Определяем сценарий создания ──────────────────────────────
            //   SCENARIO_OVERDUE   — выбранная дата была более недели назад
            //   SCENARIO_RECENT    — выбранная дата менее недели назад
            //   SCENARIO_FUTURE    — выбранная дата сегодня или в будущем
            //   SCENARIO_GROUPS    — передан явный список групп из календаря
            val diffMs = now - startTimestamp
            val scenario = when {
                contractGroups.isNotEmpty() -> 4 // SCENARIO_GROUPS
                diffMs > weekMs -> 1             // SCENARIO_OVERDUE
                diffMs > 0L -> 2                 // SCENARIO_RECENT
                else -> 3                        // SCENARIO_FUTURE (today или future)
            }

            // ── Вычисляем список контрактов для создания ───────────────────
            // Каждый элемент: Triple<weekStart, weekEnd, isPaid>
            // isPaid = true → предоплата (плюс), balance += weeklyPrice,
            //               создаётся Transaction, depositContractIncome.
            // isPaid = false → долг (минус), balance -= weeklyPrice,
            //                Transaction НЕ создаётся, на карту ничего не падает.
            data class ContractSpec(
                val weekStart: Long,
                val weekEnd: Long,
                val isPaid: Boolean,
                val isScheduled: Boolean = false
            )

            val specs: List<ContractSpec> = when (scenario) {
                4 -> {
                    // Сценарий групп: для каждой группы используем isPaid,
                    // который выбрал пользователь в календаре (кнопки статуса).
                    contractGroups.map { (gs, ge, isPaid) ->
                        ContractSpec(gs, ge, isPaid)
                    }
                }
                1 -> {
                    // Historical start: materialise every elapsed contractual
                    // period immediately, rather than inventing one debt and
                    // letting the hourly worker catch up later.
                    val specsList = mutableListOf<ContractSpec>()
                    var cursor = startTimestamp
                    while (cursor < expiryTime && specsList.size < 104) {
                        val end = minOf(cursor + weekMs, expiryTime)
                        specsList += ContractSpec(cursor, end, isPaid = false)
                        cursor = end
                    }
                    specsList
                }
                2 -> {
                    // Recent start: first period is paid, the remaining agreed
                    // duration is planned and is not charged in advance.
                    val specsList = mutableListOf<ContractSpec>()
                    var cursor = startTimestamp
                    var first = true
                    while (cursor < expiryTime && specsList.size < 104) {
                        val end = minOf(cursor + weekMs, expiryTime)
                        specsList += ContractSpec(cursor, end, isPaid = first, isScheduled = !first)
                        first = false
                        cursor = end
                    }
                    specsList
                }
                3 -> {
                    // Future rental is a reservation, not a cash receipt and
                    // not a debt. Build the real coverage from its start to
                    // its contractual expiry; no interval starts "today".
                    if (startTimestamp > now) {
                        val specsList = mutableListOf<ContractSpec>()
                        var cursor = startTimestamp
                        while (cursor < expiryTime && specsList.size < 104) {
                            val end = minOf(cursor + weekMs, expiryTime)
                            specsList += ContractSpec(cursor, end, isPaid = false, isScheduled = true)
                            cursor = end
                        }
                        specsList
                    } else {
                        // A same-day handover may settle only the first week.
                        // The rest of the agreed duration is scheduled now and
                        // becomes a receivable exactly on each start date.
                        val specsList = mutableListOf<ContractSpec>()
                        var cursor = startTimestamp
                        var first = true
                        while (cursor < expiryTime && specsList.size < 104) {
                            val end = minOf(cursor + weekMs, expiryTime)
                            specsList += ContractSpec(
                                cursor, end,
                                isPaid = first,
                                isScheduled = !first
                            )
                            first = false
                            cursor = end
                        }
                        specsList
                    }
                }
                else -> listOf(ContractSpec(startTimestamp, startTimestamp + weekMs, isPaid = true))
            }

            // ── Начальный баланс ───────────────────────────────────────────
            // A settled period is neither debt nor advance: it contributes 0
            // to the renter balance. Only an unpaid active/historical period
            // is a liability; planned periods are not charged yet.
            val contractsBalance = specs.fold(0.0) { acc, s ->
                val periodDays = ((s.weekEnd - s.weekStart) / dayMs).toInt().coerceAtLeast(1)
                val periodPrice = settingsForPricing.priceForRentalDays(
                    periodDays, effectiveWeeklyPrice, effectiveMonthlyPrice
                )
                acc + if (s.isScheduled || s.isPaid) 0.0 else -periodPrice
            }
            val initialBalance = when {
                debt > 0 -> -debt + contractsBalance
                else -> contractsBalance
            }
            val finalDebt = if (initialBalance < 0) -initialBalance else 0.0

            // A scooter cannot be reserved or rented by two people for
            // overlapping periods. Validate before the renter itself is saved.
            // §4: also block SERVICE/REPAIR/RETIRED scooters explicitly with a
            // user-visible error message instead of silent return.
            if (scooterId != null) {
                val db = AppDatabase.getDatabase(getApplication())
                val scooter = db.scooterDao().getScooterById(scooterId)
                if (scooter == null) {
                    _smsResults.tryEmit(SmsResult(
                        false,
                        "Skuter topilmadi (#$scooterId)",
                        "SCOOTER_NOT_FOUND"
                    ))
                    return@launch
                }
                if (scooter.lifecycleStatus in setOf(
                        Scooter.STATUS_SERVICE, Scooter.STATUS_REPAIR, Scooter.STATUS_RETIRED
                    )) {
                    val statusLabel = when (scooter.lifecycleStatus) {
                        Scooter.STATUS_SERVICE -> "servisda"
                        Scooter.STATUS_REPAIR -> "ta'mirda"
                        Scooter.STATUS_RETIRED -> "ro'yxatdan o'chirilgan"
                        else -> "noyaroqsiz holatda"
                    }
                    _smsResults.tryEmit(SmsResult(
                        false,
                        "Skuter ${scooter.name} hozirda $statusLabel. Ijaraga berib bo'lmaydi.",
                        "SCOOTER_UNAVAILABLE",
                        exceptionMessage = scooter.lifecycleStatus
                    ))
                    Log.w(TAG, "Rental blocked: scooter #$scooterId is ${scooter.lifecycleStatus}")
                    return@launch
                }
                val periodDao = db.rentPeriodDao()
                specs.forEach { spec ->
                    val conflicts = periodDao.conflictsForScooter(scooterId, spec.weekStart, spec.weekEnd)
                    if (conflicts.isNotEmpty()) {
                        _smsResults.tryEmit(SmsResult(
                            false,
                            "Skuter ${scooter.name} tanlangan davrda band. Boshqa sanani tanlang.",
                            "SCOOTER_CONFLICT"
                        ))
                        Log.w(TAG, "Rental blocked: scooter #$scooterId overlaps an existing period")
                        return@launch
                    }
                }
            }

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

            // ── Создание контрактов по specs ───────────────────────────────
            // Первый контракт — TYPE_CREATED, остальные — TYPE_AUTO_RENEW.
            // Для каждого контракта:
            //   • isPaid = true → создаём Transaction(+weeklyPrice) с contractId
            //     и depositContractIncome(+weeklyPrice, contractId) на главную карту.
            //   • isPaid = false → ничего, кроме записи контракта.
            // Это обеспечивает каскадную связь: при удалении контракта
            // (deleteContractWithCascade) автоматически удаляются Transaction
            // и реверсится CardTransaction главной карты.
            val shouldNotifyOverdue = specs.any { !it.isPaid && !it.isScheduled }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
                    specs.forEachIndexed { idx, spec ->
                        val isFirst = idx == 0
                        val contractType = if (isFirst) ContractHistoryEntry.TYPE_CREATED
                                           else ContractHistoryEntry.TYPE_AUTO_RENEW
                        // ── Маркер календаря ─────────────────────────────────
                        // Если контракт создаётся через форму с contractGroups
                        // (т.е. пользователь выбрал периоды в календаре формы),
                        // добавляем подстроку "Kalendar orqali yaratildi" в notes.
                        // Это позволяет deleteContractWithCascade отличить
                        // календарные контракты от созданных через «To'lash»
                        // (applyWeeklyPayment) и применять к ним симметричную
                        // логику удаления (реверс баланса на ±amount).
                        val calendarMarker =
                            if (scenario == 4) "Kalendar orqali yaratildi — " else ""
                        val notes = when {
                            spec.isScheduled -> "${calendarMarker}Rejalashtirilgan ijara davri"
                            spec.isPaid && specs.size > 1 ->
                                "${calendarMarker}${idx + 1}-hafta oldindan to'lov"
                            spec.isPaid ->
                                "${calendarMarker}Yaratildi (oldindan to'langan)"
                            else ->
                                "${calendarMarker}Kechikkan holda yaratildi (qarz)"
                        }

                        // The owner selects how a final partial period is
                        // charged: pro-rata, round-up, or monthly equivalent.
                        val periodDays = ((spec.weekEnd - spec.weekStart) / dayMs).toInt().coerceAtLeast(1)
                        val periodAmount = settingsForPricing.priceForRentalDays(
                            periodDays, effectiveWeeklyPrice, effectiveMonthlyPrice
                        )
                        val contractId = historyRepository.insert(ContractHistoryEntry(
                            renterId = savedRenter.id,
                            timestamp = now,
                            type = contractType,
                            amount = periodAmount,
                            notes = notes,
                            renterName = savedRenter.name,
                            renterPhone = savedRenter.phoneNumber,
                            scooterName = savedRenter.scooterName,
                            weekStart = spec.weekStart,
                            weekEnd = spec.weekEnd,
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
                            isPaid = spec.isPaid
                        )).toInt()

                        // Native billable period: the legacy history record is
                        // retained for documents, while all new payment logic
                        // uses this explicit outstanding amount and status.
                        try {
                            val periodStatus = when {
                                spec.weekStart > now -> com.example.data.RentPeriod.STATUS_SCHEDULED
                                spec.isPaid -> com.example.data.RentPeriod.STATUS_PAID
                                spec.weekEnd <= now -> com.example.data.RentPeriod.STATUS_OVERDUE
                                else -> com.example.data.RentPeriod.STATUS_ACTIVE
                            }
                            AppDatabase.getDatabase(getApplication()).rentPeriodDao().insert(
                                com.example.data.RentPeriod(
                                    contractHistoryId = contractId,
                                    renterId = savedRenter.id,
                                    scooterId = savedRenter.scooterId,
                                    startsAt = spec.weekStart,
                                    endsAt = spec.weekEnd,
                                    chargeMinor = com.example.data.BusinessOperation.toMinor(periodAmount),
                                    paidMinor = if (spec.isPaid) com.example.data.BusinessOperation.toMinor(periodAmount) else 0,
                                    status = periodStatus,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create rent period for contract #$contractId", e)
                            throw e // do not leave a silently incomplete rental
                        }

                        // ── Для оплаченного контракта: создаём Transaction ──
                        // и зачисляем сумму на главную карту (Glavnaya) через
                        // depositContractIncome. Это обеспечивает корректный
                        // каскадный реверс при удалении контракта.
                        if (spec.isPaid && contractId > 0) {
                            try {
                                val wsStr = dateFmt.format(java.util.Date(spec.weekStart))
                                val weStr = dateFmt.format(java.util.Date(spec.weekEnd))
                                val contractLabel = "#$contractId  $wsStr → $weStr"
                                transactionRepository.insert(
                                    com.example.data.Transaction(
                                        contractId = contractId,
                                        renterId = savedRenter.id,
                                        scooterId = savedRenter.scooterId,
                                        timestamp = now,
                                        type = com.example.data.Transaction.TYPE_PAYMENT,
                                        amount = periodAmount,
                                        notes = notes,
                                        renterName = savedRenter.name,
                                        renterPhone = savedRenter.phoneNumber,
                                        scooterName = savedRenter.scooterName ?: "",
                                        contractLabel = contractLabel
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to insert prepaid Transaction for contract #$contractId: ${e.message}")
                            }

                            try {
                                virtualCardRepository.depositContractIncome(
                                    amount = periodAmount,
                                    note = "To'lov: ${savedRenter.name} — #$contractId",
                                    contractId = contractId,
                                    renterId = savedRenter.id,
                                    scooterId = savedRenter.scooterId
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "depositContractIncome failed for new contract #$contractId: ${e.message}")
                            }
                        }
                    }
                    savedRenter.scooterId?.let { id ->
                        val lifecycle = if (startTimestamp > now) Scooter.STATUS_RESERVED else Scooter.STATUS_RENTED
                        AppDatabase.getDatabase(getApplication()).scooterDao().updateLifecycleStatus(id, lifecycle)
                    }
                } catch (e: Exception) { Log.w(TAG, "History save xato", e) }
            }

            // ── Уведомление и SMS только если есть неоплаченные контракты ──
            if (shouldNotifyOverdue) {
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

            // ── Timeline critical action (§9.0) ────────────────────────────
            // Record the renter creation as a major timeline event so it shows
            // up in History with an auto-snapshot. Non-blocking — never fails
            // the renter creation if timeline write fails.
            try {
                val db = com.example.data.AppDatabase.getDatabase(getApplication())
                com.example.data.TimelineService(db).recordCriticalAction(
                    actionType = "RENTER_CREATE",
                    screen = "RENTERS",
                    title = "Yangi arendator: ${savedRenter.name}",
                    entityType = "RENTER",
                    entityId = savedRenter.id.toString(),
                    payloadJson = "{\"name\":\"${savedRenter.name.replace("\"","\\\"")}\",\"phone\":\"${savedRenter.phoneNumber}\"}"
                )
            } catch (_: Exception) {}
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
            // Используем FLOOR, а не CEIL — чтобы не создавать перекрытий с
            // уже существующим CREATED. Если deltaMillis точно кратно 7 дням,
            // FLOOR и CEIL дают одинаковый результат. Если есть «хвост»
            // (< 7 дней), FLOOR его отбрасывает, и пользователь должен сам
            // решать, нужна ли дополнительная неделя (через ручное создание).
            if (newStart < oldStart) {
                val deltaMillis = oldStart - newStart
                val extraWeeks = (deltaMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
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
            // Удаляем AUTO_RENEW по weekStart DESC (не по timestamp!).
            // Это гарантирует, что удаляются именно самые поздние по неделе
            // контракты, а не те, что были созданы последними по времени
            // (что может быть результатом ручных операций).
            // CREATED никогда не удаляется — это первичная запись.
            if (newStart > oldStart) {
                val deltaMillis = newStart - oldStart
                val removedWeeks = (deltaMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
                if (removedWeeks > 0) {
                    val history = historyRepository.getForRenterOnce(existing.id)
                    val autoRenew = history.filter { it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
                        .sortedByDescending { it.weekStart ?: 0L }
                        .take(removedWeeks)
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
            // debtAmount должен быть СТРОГО max(0, -newBalance) — это
            // автоматически синхронизирует поле с реальным балансом.
            // Раньше здесь было max(newDebt, -newBalance.coerceAtMost(0.0)),
            // что приводило к парадоксу: пользователь мог ввести долг 300,
            // а реальный по контрактам — 200, и сохранялось 300 (лишний долг).
            val newDebtAmount = maxOf(0.0, -newBalance)
            val updated = existing.copy(
                name = newName,
                phoneNumber = newPhone,
                debtAmount = newDebtAmount,
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
            var deletedContractsCount = 0
            // ── Каскадное удаление арендатора ──────────────────────────────
            // Полная цепочка:
            //   1. Найти все контракты (ContractHistoryEntry) этого арендатора
            //   2. Для каждого контракта:
            //      a) Найти все CardTransaction с contractId = contract.id
            //         (это TYPE_CONTRACT_INCOME — деньги, упавшие на Glavnaya)
            //      b) Реверснуть баланс главной карты: adjustBalance(toCardId, -amount)
            //      c) Удалить эти CardTransaction
            //   3. Удалить все Transaction арендатора (renterId = id)
            //   4. Удалить все ContractHistoryEntry арендатора (renterId = id)
            //   5. Удалить самого арендатора
            //
            // Без шагов 2-3 деньги «зависнут» на главной карте, а в списке
            // транзакций останутся «осиротевшие» записи без арендатора.
            try {
                val db = AppDatabase.getDatabase(getApplication())
                repository.getById(id)?.let { renter ->
                    com.example.data.TrashService(db).snapshotRenter(renter, "Renter deleted with related records")
                }
                val contracts = historyRepository.getForRenterOnce(id)
                deletedContractsCount = contracts.size
                val trashService = com.example.data.TrashService(db)
                contracts.forEach { trashService.snapshotContract(it, "Removed with renter #$id") }
                for (contract in contracts) {
                    // Реверсим и удаляем CardTransaction для этого контракта
                    val cardTxList = virtualCardRepository.getCardTxForContract(contract.id)
                    for (cardTx in cardTxList) {
                        try {
                            // cardTx.toCardId обычно = MAIN_CARD_ID (1).
                            // Реверс: вычитаем amount из баланса карты-получателя.
                            virtualCardRepository.adjustCardBalance(
                                cardId = cardTx.toCardId,
                                delta = -cardTx.amount
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "deleteRenter: failed to reverse cardTx #${cardTx.id}: ${e.message}")
                        }
                    }
                    if (cardTxList.isNotEmpty()) {
                        virtualCardRepository.deleteCardTxForContract(contract.id)
                    }
                }

                // Удаляем все Transaction арендатора (PAYMENT, PENALTY, REPAIR и т.д.)
                val renterTransactions = transactionRepository.forRenterOnce(id)
                renterTransactions.forEach { trashService.snapshotTransaction(it, "Removed with renter #$id") }
                if (renterTransactions.isNotEmpty()) {
                    transactionRepository.deleteByIds(renterTransactions.map { it.id })
                    Log.d(TAG, "deleteRenter: deleted ${renterTransactions.size} transactions for renter #$id")
                }

                // Удаляем все контракты арендатора
                historyRepository.deleteForRenter(id)
                Log.d(TAG, "deleteRenter: deleted ${contracts.size} contracts for renter #$id")

                // Удаляем самого арендатора
                repository.delete(id)
                Log.d(TAG, "deleteRenter: renter #$id deleted with full cascade")
            } catch (e: Exception) {
                Log.e(TAG, "deleteRenter cascade failed for #$id", e)
                // Fallback: хотя бы удалить арендатора (старое поведение)
                try {
                    historyRepository.deleteForRenter(id)
                    repository.delete(id)
                } catch (_: Exception) {}
            }

            // Обновляем нативные виджеты Android
            try {
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (_: Exception) {}

            // ── Timeline critical action (§9.0) ────────────────────────────
            // Record renter deletion (with cascade count) for history tree.
            try {
                val db = com.example.data.AppDatabase.getDatabase(getApplication())
                com.example.data.TimelineService(db).recordCriticalAction(
                    actionType = "RENTER_DELETE",
                    screen = "RENTERS",
                    title = "Arendator o'chirildi: #$id",
                    entityType = "RENTER",
                    entityId = id.toString(),
                    payloadJson = "{\"cascadeContracts\":$deletedContractsCount}",
                    major = true
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Accepts any amount (including a partial payment or an overpayment) and
     * allocates it FIFO to explicit rent periods. New UI payment forms should
     * use this instead of changing balance/debt directly.
     */
    fun acceptVariablePayment(renterId: Int, amount: Double, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                require(amount > 0.0 && amount.isFinite()) { "Payment must be positive" }
                val db = AppDatabase.getDatabase(getApplication())
                val operator = com.example.data.OperatorSessionRepository(getApplication())
                    .requirePermission(db, com.example.data.AccessPolicy.PAYMENT_ACCEPT)
                com.example.data.RentPeriodAccountingService(db)
                    .acceptPayment(
                        renterId = renterId,
                        amountMinor = com.example.data.BusinessOperation.toMinor(amount),
                        note = note.ifBlank { "Qisman to'lov" },
                        actor = operator.displayName
                    )
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Variable payment failed for renter #$renterId", e)
            }
        }
    }

    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(getApplication())
            val price = SettingsRepository(getApplication()).weeklyPrice
                .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE }
            if (db.rentPeriodDao().openForRenter(renter.id).isNotEmpty()) {
                try {
                    com.example.data.RentPeriodAccountingService(db).acceptPayment(
                        renter.id, com.example.data.BusinessOperation.toMinor(price), "Bitta to'lov"
                    )
                } catch (e: Exception) { Log.e(TAG, "Payment failed", e) }
            } else {
                applyWeeklyPayment(renter, "Bitta to'lov")
            }
        }
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

    fun terminateRenters(renterIds: Set<Int>, forgiveDebt: Boolean = false) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id -> repository.getById(id)?.let { applyTermination(it, weeklyPrice, forgiveDebt) } }
        }
    }

    /**
     * Оплата одной недели. Делегирует в [com.example.data.RenterActionUseCase.payWeekly] —
     * единый источник истины, который также используется action-кнопками
     * системного уведомления (см. NotificationActionReceiver).
     */
    private suspend fun applyWeeklyPayment(renter: Renter, notes: String, weeklyPriceOverride: Double? = null) {
        actionUseCase.payWeekly(renter, notes, weeklyPriceOverride)
    }

    /**
     * Расторжение контракта. Делегирует в [com.example.data.RenterActionUseCase.terminate] —
     * единый источник истины, который также используется action-кнопкой
     * «Kontraktni uzish» системного уведомления.
     */
    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double, forgiveDebt: Boolean = false) {
        actionUseCase.terminate(renter, weeklyPrice, forgiveDebt)
    }

    companion object {
        private const val TAG = "RenterViewModel"
    }
}
    /**
     * Create a repair break period for a renter.
     * During this period, the renter keeps the scooter but doesn't pay.
     * This is used when the scooter needs repairs while still assigned to the renter.
     * 
     * @param renterId The renter ID
     * @param startMs Start of repair period (milliseconds)
     * @param endMs End of repair period (milliseconds)
     * @param reason Optional reason for the repair break
     */
    fun createRepairBreakPeriod(
        renterId: Int,
        startMs: Long,
        endMs: Long,
        reason: String = "Ta'mir davri"
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = com.example.data.AppDatabase.getDatabase(getApplication())
                val renter = db.renterDao().getRenterById(renterId) ?: return@launch
                
                // Create a repair break period with zero charge
                val repairPeriod = com.example.data.RentPeriod(
                    renterId = renterId,
                    scooterId = renter.scooterId,
                    startsAt = startMs,
                    endsAt = endMs,
                    chargeMinor = 0, // No charge during repair
                    paidMinor = 0,
                    status = com.example.data.RentPeriod.STATUS_REPAIR_BREAK,
                    suspensionReason = reason,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                
                db.rentPeriodDao().insert(repairPeriod)
                
                // Create a contract history entry for documentation
                val scooter = renter.scooterId?.let { db.scooterDao().getScooterById(it) }
                db.contractHistoryDao().insert(
                    com.example.data.ContractHistoryEntry(
                        renterId = renterId,
                        timestamp = System.currentTimeMillis(),
                        type = "REPAIR_BREAK",
                        amount = 0.0,
                        notes = reason,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName,
                        weekStart = startMs,
                        weekEnd = endMs,
                        weeklyPrice = 0.0,
                        passportData = renter.passportData,
                        address = renter.address,
                        pinfl = renter.pinfl,
                        vinNumber = scooter?.vinNumber ?: "",
                        engineNumber = scooter?.engineNumber ?: "",
                        scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                        batteryId1 = scooter?.batteryId1 ?: "",
                        batteryId2 = scooter?.batteryId2 ?: "",
                        additionalInfo = scooter?.additionalInfo ?: "",
                        isPaid = true // No payment needed
                    )
                )
                
                // Record in timeline
                try {
                    com.example.data.TimelineService(db).recordCriticalAction(
                        actionType = "REPAIR_BREAK_CREATE",
                        screen = "RENTERS",
                        title = "Ta'mir davri: ${renter.name}",
                        entityType = "RENTER",
                        entityId = renterId.toString(),
                        payloadJson = "{\"renterId\":$renterId,\"startMs\":$startMs,\"endMs\":$endMs,\"reason\":\"$reason\"}"
                    )
                } catch (_: Exception) {}
                
                Log.d(TAG, "Created repair break period for renter #$renterId: $startMs - $endMs")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create repair break period", e)
            }
        }
    }
}
