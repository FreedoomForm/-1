package com.example.data

import android.content.Context
import android.util.Log
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Единый источник истины (single source of truth) для действий:
 *   • [payWeekly]      — оплата одной недели арендатором.
 *   • [terminate]      — расторжение контракта арендатора.
 *
 * Этот use-case используется ВСЕМП, кто инициирует эти действия:
 *   • [com.example.ui.RenterViewModel] — кнопки «To'lash» и «Uzish» в UI.
 *   • [com.example.worker.NotificationActionReceiver] — action-кнопки
 *     «To'lov qabul qilindi» и «Kontraktni uzish» в системном уведомлении.
 *
 * Раньше логика дублировалась между ViewModel и NotificationActionReceiver,
 * что приводило к рассинхрону: кнопка в UI создавала Transaction,
 * зачисляла деньги на главную карту и обновляла баланс, а кнопка в
 * уведомлении делала только часть операций. Теперь обе точки входа
 * гарантированно выполняют ОДНУ И ТУ ЖЕ последовательность операций.
 *
 * Логика действий:
 *
 * **payWeekly**:
 *   • balance < 0 → гасим самый ранний неоплаченный контракт (isPaid=true),
 *     создаём ContractHistoryEntry(PAYMENT) + Transaction(PAYMENT),
 *     зачисляем сумму на главную карту (depositContractIncome).
 *     Если после гашения баланс ≥ 0 — арендатор снова активен.
 *   • balance ≥ 0 → создаём новый оплаченный контракт AUTO_RENEW от конца
 *     последнего оплаченного контракта (+7 дней), либо от now, если последний
 *     контракт закончился больше недели назад. Создаём PAYMENT-запись +
 *     Transaction(PAYMENT) + depositContractIncome.
 *
 * **terminate**:
 *   • Если balance < 0 и есть неоплаченный контракт → оплачиваем его
 *     (как в payWeekly для долга), создаём PAYMENT-запись + Transaction(PAYMENT)
 *     + depositContractIncome. Баланс растёт на weeklyPrice.
 *   • Если balance < 0, но неоплаченных контрактов нет (рассинхрон) →
 *     обнуляем баланс до 0 (долг «прощается» при закрытии договора).
 *   • Если balance ≥ 0 → ничего не платим.
 *   • В обоих случаях: isReturned=true, ContractHistoryEntry(TERMINATED) +
 *     Transaction(TERMINATED).
 */
class RenterActionUseCase(
    private val context: Context,
    private val renterRepository: RenterRepository,
    private val historyRepository: ContractHistoryRepository,
    private val transactionRepository: TransactionRepository,
    private val virtualCardRepository: VirtualCardRepository,
    private val settingsRepository: SettingsRepository,
    private val scooterDao: ScooterDao
) {

    companion object {
        private const val TAG = "RenterActionUseCase"

        /** Создаёт use-case из контекста приложения (б防空 способ). */
        fun fromContext(context: Context): RenterActionUseCase {
            val db = AppDatabase.getDatabase(context)
            return RenterActionUseCase(
                context = context.applicationContext,
                renterRepository = RenterRepository(db.renterDao()),
                historyRepository = ContractHistoryRepository(db.contractHistoryDao()),
                transactionRepository = TransactionRepository(db.transactionDao()),
                virtualCardRepository = VirtualCardRepository(
                    db.virtualCardDao(),
                    db.cardTransactionDao(),
                    db
                ),
                settingsRepository = SettingsRepository(context),
                scooterDao = db.scooterDao()
            )
        }
    }

    private suspend fun fetchScooterById(id: Int): Scooter? =
        withContext(Dispatchers.IO) { scooterDao.getScooterById(id) }

    /**
     * Оплата одной недели. Вызывается:
     *   • кнопкой «To'lash» в UI (RenterViewModel.payWeeklyForRenters);
     *   • action-кнопкой «To'lov qabul qilindi» в системном уведомлении.
     *
     * @param renter снимок арендатора на момент вызова.
     * @param notes описание платежа (для истории и Transaction.notes).
     * @param weeklyPriceOverride если задано — используется вместо settings.weeklyPrice.
     */
    suspend fun payWeekly(
        renter: Renter,
        notes: String,
        weeklyPriceOverride: Double? = null
    ) {
        val weeklyPrice = weeklyPriceOverride ?: settingsRepository.weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val now = System.currentTimeMillis()

        // ── §5: сначала автосоздание неоплаченного контракта, если активный
        // арендатор прошёл конец последнего контракта. Это гарантирует что
        // FIFO-оплата всегда находит целевой контракт (старый или свежесозданный).
        autoCreateUnpaidForRenter(renter, effectivePrice)

        // ── §5: вычисляем баланс по новой формуле: paid − turnover ──────
        // Это замена старой логики `renter.balance < 0`. Источник истины —
        // история контрактов (CREATED + AUTO_RENEW), а не хранимое поле balance.
        val turnover = historyRepository
            .contractsForRenterOnce(renter.id)
            .sumOf { it.amount }
        val paidTotal = historyRepository
            .contractsForRenterOnce(renter.id)
            .filter { it.isPaid }
            .sumOf { it.amount }
        val computedBalance = paidTotal - turnover
        val newBalance = computedBalance + effectivePrice

        if (computedBalance < 0) {
            // ── Гашение долга: помечаем самый ранний неоплаченный контракт ──
            val unpaid = historyRepository.getEarliestUnpaidContract(renter.id)
            if (unpaid != null) {
                historyRepository.update(unpaid.copy(isPaid = true))
                AppDatabase.getDatabase(context).rentPeriodDao().byContractHistoryId(unpaid.id)?.let { period ->
                    AppDatabase.getDatabase(context).rentPeriodDao().update(period.copy(
                        paidMinor = period.chargeMinor,
                        status = RentPeriod.STATUS_PAID,
                        updatedAt = now
                    ))
                }
            }
            // Запись PAYMENT — для истории контрактов (не показывается на экране контрактов)
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
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val contractLabel = unpaid?.let { e ->
                val ws = e.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                val we = e.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                "#${e.id}  $ws → $we"
            } ?: ""
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = unpaid?.id,
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = contractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту (виртуальная касса) ──
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (qarz yopildi) — $notes",
                    contractId = unpaid?.id,
                    renterId = renter.id,
                    scooterId = renter.scooterId
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }

            val updated = renter.copy(
                debtAmount = maxOf(0.0, -newBalance),
                balance = newBalance,
                lastPaymentTimestamp = now,
                isOverdueSmsSent = false,
                // Если после гашения баланс стал ≥ 0 — возвращаем в активное
                isReturned = if (newBalance >= 0) false else renter.isReturned
            )
            renterRepository.update(updated)
        } else {
            // ── Предоплата: создаём новый оплаченный контракт ──────────────
            val latestPaid = historyRepository.getLatestPaidContract(renter.id)
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs

            val lastWeekEnd: Long? = latestPaid?.weekEnd

            // Если последний оплаченный контракт закончился БОЛЬШЕ 7 дней назад
            // → новый контракт начинается с today. Иначе — с lastWeekEnd
            // (непрерывное покрытие без дыр). Fallback при null = end первой недели.
            val effectiveLastEnd = lastWeekEnd
                ?: (renter.rentStartDateTimestamp + weekMs)
            val effectiveGapMs = now - effectiveLastEnd
            val shouldStartFromNow = effectiveGapMs > weekMs

            val baseStart = if (shouldStartFromNow) now else effectiveLastEnd
            val weekStart = baseStart
            val weekEnd = baseStart + weekMs

            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val scooterVin = scooter?.vinNumber ?: ""
            val scooterEngine = scooter?.engineNumber ?: ""
            val scooterSerial = scooter?.scooterSerialNumber ?: ""
            val scooterBat1 = scooter?.batteryId1 ?: ""
            val scooterBat2 = scooter?.batteryId2 ?: ""
            val scooterExtra = scooter?.additionalInfo ?: ""

            val contractNotes = when {
                renter.isReturned -> "Qayta faollashtirildi (1 hafta to'lov)"
                shouldStartFromNow && lastWeekEnd != null ->
                    "Yangi hafta (eski kontrakt muddati o'tgan)"
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
            AppDatabase.getDatabase(context).rentPeriodDao().insert(RentPeriod(
                contractHistoryId = newContractId.toInt(),
                renterId = renter.id,
                scooterId = renter.scooterId,
                startsAt = weekStart,
                endsAt = weekEnd,
                chargeMinor = BusinessOperation.toMinor(effectivePrice),
                paidMinor = BusinessOperation.toMinor(effectivePrice),
                status = RentPeriod.STATUS_PAID,
                createdAt = now,
                updatedAt = now
            ))

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

            // ── Запись в таблицу transactions ──
            val dateFmtTx = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = dateFmtTx.format(java.util.Date(weekStart))
            val weStr = dateFmtTx.format(java.util.Date(weekEnd))
            val newContractLabel = "#$newContractId  $wsStr → $weStr"
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = newContractId.toInt(),
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = newContractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту ──
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (oldindan) — $notes",
                    contractId = newContractId.toInt(),
                    renterId = renter.id,
                    scooterId = renter.scooterId
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }

            // rentStartDateTimestamp и rentDurationDays НЕ МЕНЯЕМ — это
            // первоначальные условия аренды (см. комментарий в RenterViewModel).
            val updated = renter.copy(
                debtAmount = maxOf(0.0, -newBalance),
                balance = newBalance,
                lastPaymentTimestamp = now,
                isOverdueSmsSent = false,
                isReturned = false  // ← реактивация пассивного арендатора
            )
            renterRepository.update(updated)
        }

        // Обновляем нативные виджеты Android
        try { WidgetUpdater.updateAll(context) } catch (_: Exception) {}

        // §9.0: таймкод критического действия — PAYMENT_ACCEPTED.
        try {
            com.example.data.TimelineService(
                AppDatabase.getDatabase(context)
            ).recordCriticalAction(
                actionType = "PAYMENT_ACCEPTED",
                screen = "FINANCE",
                title = "To'lov: ${renter.name} — $effectivePrice so'm",
                entityType = "PAYMENT",
                entityId = renter.id.toString(),
                payloadJson = "{\"renterId\":${renter.id},\"amount\":$effectivePrice,\"balanceBefore\":${renter.balance},\"balanceAfter\":$newBalance,\"computedBalanceBefore\":$computedBalance}"
            )
        } catch (_: Exception) {}
    }

    /**
     * §4/§5: Автоматически создаёт неоплаченный контракт на следующую неделю,
     * если активный арендатор прошёл конец последнего контракта и у него ещё
     * нет контракта, покрывающего сегодняшний день.
     *
     * Логика:
     *   1. Если renter.isReturned = true → пропускаем (пассивный арендатор).
     *   2. Берём все контракты арендатора (CREATED + AUTO_RENEW).
     *   3. Находим последний weekEnd.
     *   4. Если weekEnd > now → контракт ещё действует, ничего не создаём.
     *   5. Если weekEnd ≤ now → создаём новый контракт с weekStart = weekEnd
     *      и weekEnd = weekEnd + 7 дней, isPaid = false.
     *
     * Это гарантирует что у активного арендатора всегда есть «текущий»
     * контракт, который можно оплатить через FIFO (pay oldest unpaid).
     */
    /**
     * Auto-create ALL missing unpaid contracts for an active renter.
     * If the renter's last contract ended more than one week ago,
     * this creates multiple contracts to cover the entire missed period.
     */
    suspend fun autoCreateUnpaidForRenter(
        renter: Renter,
        weeklyPriceOverride: Double? = null
    ) {
        if (renter.isReturned) return
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val weekMs = 7L * dayMs

        val contracts = historyRepository.contractsForRenterOnce(renter.id)
        if (contracts.isEmpty()) return

        val latestEnd = contracts.maxOfOrNull { it.weekEnd ?: 0L } ?: return
        if (latestEnd > now) return  // есть действующий контракт

        val weeklyPrice = weeklyPriceOverride ?: settingsRepository.weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE

        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
        val db = AppDatabase.getDatabase(context)
        
        // Calculate how many weeks are missing
        val missedMs = now - latestEnd
        val missedWeeks = ((missedMs + weekMs - 1) / weekMs).toInt().coerceIn(1, 52) // Max 1 year
        
        var periodStart = latestEnd
        var createdContracts = 0
        
        for (i in 0 until missedWeeks) {
            val periodEnd = periodStart + weekMs
            if (periodEnd <= now || i == 0) { // Always create at least the first one
                val newContract = ContractHistoryEntry(
                    renterId = renter.id, timestamp = now,
                    type = ContractHistoryEntry.TYPE_AUTO_RENEW, amount = effectivePrice,
                    notes = "Avtomatik yaratildi (${i + 1}/${missedWeeks} hafta)",
                    renterName = renter.name, renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName,
                    weekStart = periodStart, weekEnd = periodEnd,
                    weeklyPrice = effectivePrice,
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
                val newContractId = historyRepository.insert(newContract)
                
                // Create RentPeriod
                db.rentPeriodDao().insert(RentPeriod(
                    contractHistoryId = newContractId.toInt(),
                    renterId = renter.id,
                    scooterId = renter.scooterId,
                    startsAt = periodStart,
                    endsAt = periodEnd,
                    chargeMinor = BusinessOperation.toMinor(effectivePrice),
                    paidMinor = 0L,
                    status = if (periodEnd <= now) RentPeriod.STATUS_OVERDUE else RentPeriod.STATUS_ACTIVE,
                    createdAt = now,
                    updatedAt = now
                ))
                
                createdContracts++
                periodStart = periodEnd
            } else {
                break // Don't create future periods
            }
        }
        
        if (createdContracts > 0) {
            db.auditEventDao().insert(AuditEvent(
                occurredAt = now,
                action = AuditEvent.ACTION_RENT_RENEWED,
                entityType = "RENTER",
                entityId = renter.id.toString(),
                reason = "Auto-create $createdContracts unpaid contract(s)",
                beforeSnapshot = "latestEnd=$latestEnd",
                afterSnapshot = "createdContracts=$createdContracts; missedWeeks=$missedWeeks"
            ))
            
            try {
                TimelineService(db).recordCriticalAction(
                    actionType = "CONTRACT_CREATE_AUTO_BATCH",
                    screen = "RENTERS",
                    title = "Auto-kontraktlar: ${renter.name} ($createdContracts ta)",
                    entityType = "RENTER",
                    entityId = renter.id.toString(),
                    payloadJson = "{\"renterId\":${renter.id},\"createdContracts\":$createdContracts,\"missedWeeks\":$missedWeeks}"
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * §4/§5: Запускает [autoCreateUnpaidForRenter] для всех активных арендаторов.
     * Используется:
     *   • из MainActivity LaunchedEffect — один раз при старте приложения;
     *   • из SmsWorker — перед отправкой напоминаний о долге;
     *   • из RenterViewModel.refresh() — при ручном обновлении списка.
     */
    suspend fun autoCreateForAllActiveRenters() {
        val active = renterRepository.getActiveRenters()
        active.forEach { autoCreateUnpaidForRenter(it) }
    }

    /**
     * Расторжение контракта. Вызывается:
     *   • кнопкой «Uzish» в UI (RenterViewModel.terminateRenters);
     *   • action-кнопкой «Kontraktni uzish» в системном уведомлении.
     *
     * @param renter снимок арендатора на момент вызова.
     * @param weeklyPrice недельная цена (берётся из settings).
     */

    /**
     * Расторжение контракта.
     * 
     * @param forgiveDebt If true, all outstanding debt is forgiven and marked as CLOSED.
     *                    If false, debt remains collectible as CLOSED_WITH_DEBT.
     */
    suspend fun terminate(renter: Renter, weeklyPrice: Double, forgiveDebt: Boolean = false) {
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val now = System.currentTimeMillis()
        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
        val db = AppDatabase.getDatabase(context)

        // ── Step 1: Handle outstanding debt periods ──────────────────────────
        val periodDao = db.rentPeriodDao()
        val closeableDebtPeriods = periodDao.openForRenter(renter.id)
        val forgivenMinor = if (forgiveDebt) closeableDebtPeriods.sumOf { it.outstandingMinor } else 0L
        
        // Mark periods as closed (with or without debt)
        closeableDebtPeriods.forEach { period ->
            val newStatus = when {
                forgiveDebt -> RentPeriod.STATUS_CLOSED
                period.outstandingMinor > 0 -> RentPeriod.STATUS_CLOSED_WITH_DEBT
                else -> RentPeriod.STATUS_CLOSED
            }
            val newPaidMinor = if (forgiveDebt) period.chargeMinor else period.paidMinor
            periodDao.update(period.copy(
                paidMinor = newPaidMinor,
                status = newStatus,
                updatedAt = now
            ))
            
            // Also update legacy contract history isPaid flag
            if (forgiveDebt && period.contractHistoryId != null) {
                db.contractHistoryDao().getById(period.contractHistoryId)?.let { contract ->
                    historyRepository.update(contract.copy(isPaid = true))
                }
            }
        }
        
        // Calculate final balance
        val finalBalance = if (forgiveDebt) {
            renter.balance.coerceAtLeast(0.0)
        } else {
            renter.balance
        }

        // ── Step 2: Update renter to returned state ──────────────────────────
        val updated = renter.copy(
            isReturned = true,
            balance = finalBalance,
            debtAmount = maxOf(0.0, -finalBalance),
            lastPaymentTimestamp = now,
            isOverdueSmsSent = false
        )
        renterRepository.update(updated)
        
        // Record debt forgiveness operation if applicable
        if (forgivenMinor > 0) {
            db.businessOperationDao().insert(BusinessOperation(
                occurredAt = now,
                type = BusinessOperation.TYPE_DEBT_FORGIVEN,
                direction = BusinessOperation.DIRECTION_LIABILITY,
                amountMinor = forgivenMinor,
                renterId = renter.id,
                scooterId = renter.scooterId,
                note = "Debt forgiven on rental termination"
            ))
        }
        
        // Close/cancel remaining periods and release scooter
        periodDao.closeOpenForRenter(renter.id, now)
        periodDao.cancelScheduledForRenter(renter.id, now)
        renter.scooterId?.let { db.scooterDao().updateLifecycleStatus(it, Scooter.STATUS_AVAILABLE) }

        // ── Step 3: Create TERMINATED history entry ──────────────────────────
        val entry = ContractHistoryEntry(
            renterId = renter.id, timestamp = now,
            type = ContractHistoryEntry.TYPE_TERMINATED, 
            amount = 0.0, // Termination is not a payment
            notes = when {
                forgiveDebt && forgivenMinor > 0 -> "Kontrakt tugatildi (qarz kechirildi: ${BusinessOperation.fromMinor(forgivenMinor)} UZS)"
                closeableDebtPeriods.any { it.outstandingMinor > 0 } -> "Kontrakt tugatildi (qarz mavjud)"
                else -> "Kontrakt tugatildi"
            }, 
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
        
        // Audit event
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now,
            action = AuditEvent.ACTION_RENT_TERMINATED,
            entityType = "RENTER",
            entityId = renter.id.toString(),
            reason = entry.notes,
            beforeSnapshot = "balance=${renter.balance}; returned=${renter.isReturned}",
            afterSnapshot = "balance=$finalBalance; returned=true; forgivenMinor=$forgivenMinor"
        ))

        // ── Step 4: Create Transaction TERMINATED ────────────────────────────
        try {
            transactionRepository.insert(
                Transaction(
                    contractId = null,
                    renterId = renter.id,
                    scooterId = renter.scooterId,
                    timestamp = now,
                    type = Transaction.TYPE_TERMINATED,
                    amount = 0.0,
                    notes = entry.notes,
                    renterName = renter.name,
                    renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName ?: "",
                    contractLabel = ""
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert terminated transaction: ${e.message}")
        }

        // ── Step 5: Timeline recording ───────────────────────────────────────
        try {
            TimelineService(db).recordCriticalAction(
                actionType = "RENTAL_TERMINATED",
                screen = "RENTERS",
                title = "Tugatildi: ${renter.name}",
                entityType = "RENTER",
                entityId = renter.id.toString(),
                payloadJson = "{\"renterId\":${renter.id},\"forgivenMinor\":$forgivenMinor,\"forgiveDebt\":$forgiveDebt}"
            )
        } catch (_: Exception) {}

        // Update widget
        try {
            com.example.widget.WidgetUpdater.updateAll(context)
        } catch (_: Exception) {}
    }
