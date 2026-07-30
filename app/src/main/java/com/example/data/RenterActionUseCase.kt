package com.example.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Единый источник истины (single source of truth) для действий:
 *   • [payWeekly]      — оплата одной недели арендатором.
 *   • [terminate]      — расторжение контракта арендатора.
 *
 * ВАЖНЫЕ ИЗМЕНЕНИЯ:
 * - Убрано дублирование depositContractIncome: теперь деньги зачисляются
 *   ТОЛЬКО через RentPeriodAccountingService.acceptPayment или один раз
 *   в payWeekly, но не оба.
 * - Добавлена проверка существующих периодов перед созданием новых.
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
     * Оплата одной недели.
     * 
     * ИСПРАВЛЕНО: depositContractIncome вызывается только ОДИН раз,
     * убрано дублирование записи платежа.
     */
    suspend fun payWeekly(
        renter: Renter,
        notes: String,
        weeklyPriceOverride: Double? = null
    ) = db_for(context).withTransaction {
        _payWeeklyInternal(renter, notes, weeklyPriceOverride)
    }

    private suspend fun _payWeeklyInternal(
        renter: Renter,
        notes: String,
        weeklyPriceOverride: Double?
    ) {
        val weeklyPrice = weeklyPriceOverride ?: settingsRepository.weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val now = System.currentTimeMillis()
        val db = AppDatabase.getDatabase(context)

        // Автосоздание неоплаченного контракта если нужно
        autoCreateUnpaidForRenter(renter, effectivePrice)

        // Вычисляем баланс по формуле: paid − turnover
        val contracts = historyRepository.contractsForRenterOnce(renter.id)
        val turnover = contracts.sumOf { it.amount }
        val paidTotal = contracts.filter { it.isPaid }.sumOf { it.amount }
        val computedBalance = paidTotal - turnover
        val newBalance = computedBalance + effectivePrice

        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }

        if (computedBalance < 0) {
            // ── Гашение долга ──
            val unpaid = historyRepository.getEarliestUnpaidContract(renter.id)
            if (unpaid != null) {
                historyRepository.update(unpaid.copy(isPaid = true))
                db.rentPeriodDao().byContractHistoryId(unpaid.id)?.let { period ->
                    db.rentPeriodDao().update(period.copy(
                        paidMinor = period.chargeMinor,
                        status = RentPeriod.STATUS_PAID,
                        updatedAt = now
                    ))
                }
            }

            // Запись PAYMENT для истории
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = unpaid?.weekStart, weekEnd = unpaid?.weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            historyRepository.insert(paymentEntry)

            // Запись Transaction
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val contractLabel = unpaid?.let { e ->
                val ws = e.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                val we = e.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                "#${e.id}  $ws → $we"
            } ?: ""

            // Batch 10 (was HIGH B3): removed try/catch around
            // transactionRepository.insert and depositContractIncome.
            // Previously a failure in depositContractIncome was silently
            // swallowed and the renter's balance was still updated to
            // newBalance — leaving the card short of the deposited amount
            // and triggering AccountingIntegrityService discrepancies.
            // Now the whole payWeekly is wrapped in db.withTransaction
            // (see payWeekly wrapper above), so any failure rolls back
            // ALL writes — contract history, transaction, card deposit,
            // renter balance — leaving the DB in its pre-payment state.
            transactionRepository.insert(Transaction(
                contractId = unpaid?.id, renterId = renter.id, scooterId = renter.scooterId,
                timestamp = now, type = Transaction.TYPE_PAYMENT, amount = effectivePrice,
                notes = notes, renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: "", contractLabel = contractLabel
            ))

            // Зачисление на карту — ТОЛЬКО ОДИН РАЗ
            virtualCardRepository.depositContractIncome(
                amount = effectivePrice,
                note = "To'lov: ${renter.name} (qarz yopildi) — $notes",
                contractId = unpaid?.id, renterId = renter.id, scooterId = renter.scooterId
            )

            // Batch 12 (was HIGH B4): switched from full-entity
            // renterRepository.update(renter.copy(...)) to field-specific
            // UPDATE queries. The full-entity write clobbered any concurrent
            // field-specific write to rentDurationDays / scooterId /
            // scooterName / passportData / address / pinfl because the
            // read-modify-write captured the renter snapshot at the top of
            // payWeekly and wrote ALL 13 columns back. Now we touch ONLY
            // the 5 columns this branch mutates.
            db.renterDao().updateBalanceAndDebt(
                renter.id,
                balance = newBalance,
                debtAmount = maxOf(0.0, -newBalance)
            )
            db.renterDao().updateLastPaymentTimestamp(renter.id, now)
            db.renterDao().updateOverdueSmsFlag(renter.id, false)
            if (newBalance >= 0 && renter.isReturned) {
                db.renterDao().updateReturnedFlag(renter.id, false)
            }
        } else {
            // ── Предоплата: создаём новый оплаченный контракт ──
            val latestPaid = historyRepository.getLatestPaidContract(renter.id)
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs

            val lastWeekEnd: Long? = latestPaid?.weekEnd
            val weekStart: Long
            val weekEnd: Long

            if (lastWeekEnd == null || lastWeekEnd < now - weekMs) {
                weekStart = now
                weekEnd = now + weekMs
            } else {
                weekStart = lastWeekEnd
                weekEnd = lastWeekEnd + weekMs
            }

            val newContract = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW, amount = effectivePrice,
                notes = notes, renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName, weekStart = weekStart, weekEnd = weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = true
            )
            val newContractId = historyRepository.insert(newContract)

            db.rentPeriodDao().insert(RentPeriod(
                contractHistoryId = newContractId.toInt(),
                renterId = renter.id, scooterId = renter.scooterId,
                startsAt = weekStart, endsAt = weekEnd,
                chargeMinor = BusinessOperation.toMinor(effectivePrice),
                paidMinor = BusinessOperation.toMinor(effectivePrice),
                status = RentPeriod.STATUS_PAID,
                createdAt = now, updatedAt = now
            ))

            // Batch 10 (was HIGH B5): auto-create a HandoverAct when a new
            // contract is created via prepayment. Previously HandoverAct was
            // only created via the manual "Akt saqlash" dialog button in
            // ContractHistoryScreens — the handover_acts table was effectively
            // always empty unless the user explicitly created acts. Now every
            // new prepayment contract gets a structured HANDOVER act tied to
            // the new contractHistoryId, so the per-contract handover history
            // is populated automatically. The user can still edit fields
            // (mileage, equipment, condition) via the manual dialog later.
            renter.scooterId?.let { sid ->
                db.handoverActDao().insert(HandoverAct(
                    timestamp = now,
                    actType = HandoverAct.TYPE_HANDOVER,
                    renterId = renter.id,
                    scooterId = sid,
                    contractHistoryId = newContractId.toInt(),
                    conditionNotes = "Auto-created by prepayment: $notes",
                    signedBy = "LOCAL_SYSTEM"
                ))
            }

            // Запись PAYMENT
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = weekStart, weekEnd = weekEnd, weeklyPrice = effectivePrice,
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            historyRepository.insert(paymentEntry)

            // Transaction запись
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = dateFmt.format(java.util.Date(weekStart))
            val weStr = dateFmt.format(java.util.Date(weekEnd))
            val newContractLabel = "#$newContractId  $wsStr → $weStr"

            // Batch 10 (B3): removed try/catch — see debt-payoff branch above
            // for the rationale. The whole payWeekly is now transactional.
            transactionRepository.insert(Transaction(
                contractId = newContractId.toInt(), renterId = renter.id, scooterId = renter.scooterId,
                timestamp = now, type = Transaction.TYPE_PAYMENT, amount = effectivePrice,
                notes = notes, renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: "", contractLabel = newContractLabel
            ))

            // Зачисление на карту — ТОЛЬКО ОДИН РАЗ
            virtualCardRepository.depositContractIncome(
                amount = effectivePrice,
                note = "To'lov: ${renter.name} (oldindan) — $notes",
                contractId = newContractId.toInt(), renterId = renter.id, scooterId = renter.scooterId
            )

            // Batch 12 (was HIGH B4): same field-specific UPDATE pattern
            // as the debt-payoff branch above. The prepayment branch
            // unconditionally sets isReturned=false (the renter is
            // continuing) in addition to balance/debt/timestamp/SMS flag.
            db.renterDao().updateBalanceAndDebt(
                renter.id,
                balance = newBalance,
                debtAmount = maxOf(0.0, -newBalance)
            )
            db.renterDao().updateLastPaymentTimestamp(renter.id, now)
            db.renterDao().updateOverdueSmsFlag(renter.id, false)
            if (renter.isReturned) {
                db.renterDao().updateReturnedFlag(renter.id, false)
            }
        }

        try { WidgetUpdater.updateAll(context) } catch (_: Exception) {}

        try {
            TimelineService(db).recordCriticalAction(
                actionType = "PAYMENT_ACCEPTED",
                screen = "FINANCE",
                title = "To'lov: ${renter.name} — $effectivePrice so'm",
                entityType = "PAYMENT",
                entityId = renter.id.toString(),
                payloadJson = "{\"renterId\":${renter.id},\"amount\":$effectivePrice,\"balanceBefore\":${renter.balance},\"balanceAfter\":$newBalance}"
            )
        } catch (_: Exception) {}
    }

    private fun db_for(context: Context): AppDatabase = AppDatabase.getDatabase(context)

    /**
     * Auto-create missing unpaid contracts for an active renter.
     * 
     * ИСПРАВЛЕНО: Добавлена проверка существующих периодов по датам,
     * чтобы избежать дублирования при повторных вызовах.
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
        if (latestEnd > now) return

        val weeklyPrice = weeklyPriceOverride ?: settingsRepository.weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE

        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
        val db = AppDatabase.getDatabase(context)
        
        // Получаем существующие периоды для проверки дубликатов
        val existingPeriods = db.rentPeriodDao().getAllForRenter(renter.id)
        
        val missedMs = now - latestEnd
        val missedWeeks = ((missedMs + weekMs - 1) / weekMs).toInt().coerceIn(1, 52)
        
        var periodStart = latestEnd
        var createdContracts = 0
        
        for (i in 0 until missedWeeks) {
            val periodEnd = periodStart + weekMs
            
            // Проверяем, нет ли уже периода с такими же датами
            val duplicateExists = existingPeriods.any { existing ->
                existing.startsAt == periodStart && existing.endsAt == periodEnd
            }
            
            if (!duplicateExists && (periodEnd <= now || i == 0)) {
                val newContract = ContractHistoryEntry(
                    renterId = renter.id, timestamp = now,
                    type = ContractHistoryEntry.TYPE_AUTO_RENEW, amount = effectivePrice,
                    notes = "Avtomatik yaratildi (${i + 1}/$missedWeeks hafta)",
                    renterName = renter.name, renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName,
                    weekStart = periodStart, weekEnd = periodEnd, weeklyPrice = effectivePrice,
                    passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                    vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                    batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                    additionalInfo = scooter?.additionalInfo ?: "",
                    isPaid = false
                )
                val newContractId = historyRepository.insert(newContract)
                
                db.rentPeriodDao().insert(RentPeriod(
                    contractHistoryId = newContractId.toInt(),
                    renterId = renter.id, scooterId = renter.scooterId,
                    startsAt = periodStart, endsAt = periodEnd,
                    chargeMinor = BusinessOperation.toMinor(effectivePrice),
                    paidMinor = 0L,
                    status = if (periodEnd <= now) RentPeriod.STATUS_OVERDUE else RentPeriod.STATUS_ACTIVE,
                    createdAt = now, updatedAt = now
                ))
                
                createdContracts++
            }
            periodStart = periodEnd
            if (periodEnd > now && i > 0) break
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
                    payloadJson = "{\"renterId\":${renter.id},\"createdContracts\":$createdContracts}"
                )
            } catch (_: Exception) {}
        }
    }

    suspend fun autoCreateForAllActiveRenters() {
        val active = renterRepository.getActiveRenters()
        active.forEach { autoCreateUnpaidForRenter(it) }
    }

    /**
     * Расторжение контракта.
     */
    suspend fun terminate(renter: Renter, weeklyPrice: Double, forgiveDebt: Boolean = false) =
        db_for(context).withTransaction {
            _terminateInternal(renter, weeklyPrice, forgiveDebt)
        }

    private suspend fun _terminateInternal(renter: Renter, weeklyPrice: Double, forgiveDebt: Boolean) {
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val now = System.currentTimeMillis()
        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
        val db = AppDatabase.getDatabase(context)

        val periodDao = db.rentPeriodDao()
        val closeableDebtPeriods = periodDao.openForRenter(renter.id)
        val forgivenMinor = if (forgiveDebt) closeableDebtPeriods.sumOf { it.outstandingMinor } else 0L
        
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
            
            if (forgiveDebt && period.contractHistoryId != null) {
                db.contractHistoryDao().getById(period.contractHistoryId)?.let { contract ->
                    historyRepository.update(contract.copy(isPaid = true))
                }
            }
        }
        
        val finalBalance = if (forgiveDebt) renter.balance.coerceAtLeast(0.0) else renter.balance

        // Batch 12 (was HIGH B4): switched from full-entity
        // renterRepository.update(renter.copy(...)) to field-specific
        // UPDATE queries. The full-entity write clobbered any concurrent
        // field-specific write to rentDurationDays / scooterId /
        // scooterName / passportData / address / pinfl because the renter
        // snapshot was captured at the top of _terminateInternal (before
        // the scooter-lookup loop) and wrote ALL 13 columns back. Now we
        // touch ONLY the 5 columns terminate actually mutates.
        db.renterDao().updateReturnedFlag(renter.id, true)
        db.renterDao().updateBalanceAndDebt(
            renter.id,
            balance = finalBalance,
            debtAmount = maxOf(0.0, -finalBalance)
        )
        db.renterDao().updateLastPaymentTimestamp(renter.id, now)
        db.renterDao().updateOverdueSmsFlag(renter.id, false)
        
        if (forgivenMinor > 0) {
            db.businessOperationDao().insert(BusinessOperation(
                occurredAt = now,
                type = BusinessOperation.TYPE_DEBT_FORGIVEN,
                direction = BusinessOperation.DIRECTION_LIABILITY,
                amountMinor = forgivenMinor,
                renterId = renter.id, scooterId = renter.scooterId,
                note = "Debt forgiven on rental termination"
            ))
        }
        
        periodDao.closeOpenForRenter(renter.id, now)
        periodDao.cancelScheduledForRenter(renter.id, now)
        // Batch 9 (was MEDIUM C2): don't blindly overwrite STATUS_REPAIR
        // with STATUS_AVAILABLE. If the scooter is currently being repaired,
        // setting it to AVAILABLE would (a) make it rentable even though
        // it's broken, (b) leave the open RepairOrder dangling with no
        // resolution. Only transition to AVAILABLE if the scooter is in a
        // RENTED/RESERVED-like state. REPAIR / SERVICE / RETIRED are left
        // alone — the user must explicitly finish or cancel the repair.
        renter.scooterId?.let { sid ->
            val scooter = db.scooterDao().getScooterById(sid)
            if (scooter != null && scooter.lifecycleStatus == Scooter.STATUS_RENTED) {
                db.scooterDao().updateLifecycleStatus(sid, Scooter.STATUS_AVAILABLE)
            }
            // If scooter is STATUS_REPAIR/SERVICE/RETIRED, leave it — the
            // user will resolve the repair lifecycle separately. We also
            // close any SUSPENDED_REPAIR periods tied to this renter so
            // they don't get reactivated by a later resumeAfterRepair.
            if (scooter != null && scooter.lifecycleStatus == Scooter.STATUS_REPAIR) {
                db.rentPeriodDao().suspendedForScooter(sid)
                    .filter { it.renterId == renter.id }
                    .forEach { period ->
                        val closedStatus = if (period.paidMinor >= period.effectiveChargeMinor)
                            RentPeriod.STATUS_CLOSED else RentPeriod.STATUS_CLOSED_WITH_DEBT
                        db.rentPeriodDao().update(period.copy(
                            status = closedStatus,
                            suspendedAt = null,
                            suspensionReason = "Renter terminated during repair",
                            updatedAt = now
                        ))
                    }
            }
        }

        val entry = ContractHistoryEntry(
            renterId = renter.id, timestamp = now,
            type = ContractHistoryEntry.TYPE_TERMINATED,
            amount = 0.0,
            notes = when {
                forgiveDebt && forgivenMinor > 0 -> "Kontrakt tugatildi (qarz kechirildi: ${BusinessOperation.fromMinor(forgivenMinor)} UZS)"
                closeableDebtPeriods.any { it.outstandingMinor > 0 } -> "Kontrakt tugatildi (qarz mavjud)"
                else -> "Kontrakt tugatildi"
            },
            renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
            weekStart = renter.rentStartDateTimestamp, weekEnd = now, weeklyPrice = effectivePrice,
            passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
            vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
            batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
            additionalInfo = scooter?.additionalInfo ?: ""
        )
        historyRepository.insert(entry)

        // Batch 10 (was HIGH B5): auto-create a HandoverAct TYPE_RETURN
        // when a contract is terminated. Complements the TYPE_HANDOVER act
        // created by the prepayment branch of payWeekly — together they
        // give a complete handover/return history per scooter without
        // requiring the user to manually create acts.
        renter.scooterId?.let { sid ->
            db.handoverActDao().insert(HandoverAct(
                timestamp = now,
                actType = HandoverAct.TYPE_RETURN,
                renterId = renter.id,
                scooterId = sid,
                contractHistoryId = null, // terminate doesn't create a new contract
                conditionNotes = "Auto-created by terminate: ${entry.notes}",
                signedBy = "LOCAL_SYSTEM"
            ))
        }

        db.auditEventDao().insert(AuditEvent(
            occurredAt = now,
            action = AuditEvent.ACTION_RENT_TERMINATED,
            entityType = "RENTER",
            entityId = renter.id.toString(),
            reason = entry.notes,
            beforeSnapshot = "balance=${renter.balance}; returned=${renter.isReturned}",
            afterSnapshot = "balance=$finalBalance; returned=true; forgivenMinor=$forgivenMinor"
        ))

        try {
            transactionRepository.insert(Transaction(
                contractId = null, renterId = renter.id, scooterId = renter.scooterId,
                timestamp = now, type = Transaction.TYPE_TERMINATED, amount = 0.0,
                notes = entry.notes, renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: "", contractLabel = ""
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert terminated transaction: ${e.message}")
        }

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

        try { WidgetUpdater.updateAll(context) } catch (_: Exception) {}
    }
}
