package com.example.data

import androidx.room.withTransaction

/** Operational status and repair costs are changed through one audited service. */
class ScooterMaintenanceService(private val db: AppDatabase) {
    suspend fun changeStatus(
        scooterId: Int,
        status: String,
        reason: String,
        repairScenario: String = RepairOrder.SCENARIO_RENTER_REPAIR
    ) = db.withTransaction {
        require(status in setOf(
            Scooter.STATUS_AVAILABLE, Scooter.STATUS_SERVICE, Scooter.STATUS_REPAIR, Scooter.STATUS_RETIRED
        )) { "Unsupported manual lifecycle status" }
        require(reason.isNotBlank()) { "Status change reason is required" }
        val scooter = db.scooterDao().getScooterById(scooterId)
            ?: throw IllegalArgumentException("Scooter #$scooterId does not exist")
        val now = System.currentTimeMillis()
        if (status == Scooter.STATUS_REPAIR) {
            // Repair during an active rental pauses billing instead of forcing
            // the renter to pay for days without a usable scooter.
            val billablePeriods = db.rentPeriodDao().billableForScooter(scooterId)
            billablePeriods.forEach { period ->
                db.rentPeriodDao().update(period.copy(
                    status = RentPeriod.STATUS_SUSPENDED_REPAIR,
                    suspendedAt = now,
                    suspensionReason = reason,
                    updatedAt = now
                ))
            }
            db.repairOrderDao().insert(RepairOrder(
                scooterId = scooterId,
                renterId = billablePeriods.firstOrNull()?.renterId,
                scenario = repairScenario,
                diagnosis = reason,
                documentNote = "Rental billing paused automatically"
            ))
        } else if (status in setOf(Scooter.STATUS_SERVICE, Scooter.STATUS_RETIRED)) {
            val conflicts = db.rentPeriodDao().conflictsForScooter(scooterId, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)
            check(conflicts.none { it.status in setOf(RentPeriod.STATUS_ACTIVE, RentPeriod.STATUS_PARTIALLY_PAID, RentPeriod.STATUS_OVERDUE) }) {
                "Cannot move a scooter with an active rental into service/retirement"
            }
            // Batch 9 (was MEDIUM C1): also block if there are SUSPENDED_REPAIR
            // periods. The conflictsForScooter query filters by status IN
            // (SCHEDULED, ACTIVE, PARTIALLY_PAID, OVERDUE) — it does NOT
            // include SUSPENDED_REPAIR, so a scooter in repair would pass
            // the guard and silently move to SERVICE/RETIRED with its open
            // RepairOrder left dangling. The user must finish or cancel
            // the repair first (via resumeAfterRepair or by closing the
            // order manually).
            val suspended = db.rentPeriodDao().suspendedForScooter(scooterId)
            check(suspended.isEmpty()) {
                "Cannot move a scooter with an open repair into service/retirement — finish or cancel the repair first"
            }
        }
        db.scooterDao().updateLifecycleStatus(scooterId, status)
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now, action = "SCOOTER_STATUS_CHANGED", entityType = "SCOOTER", entityId = scooterId.toString(), reason = reason,
            beforeSnapshot = "status=${scooter.lifecycleStatus}", afterSnapshot = "status=$status"
        ))
        // Batch 8 (was H3): when the status change STARTS a repair, also
        // emit the structured ACTION_REPAIR_START audit event. Previously
        // only FINISH / PAUSE / RESUME had dedicated action codes — a
        // repair that was started but never explicitly finished left a
        // gap in the audit trail, making it impossible to reconstruct
        // the repair timeline from audit_events alone. The generic
        // SCOOTER_STATUS_CHANGED audit above is kept for backward compat
        // with existing audit-log readers.
        if (status == Scooter.STATUS_REPAIR) {
            db.auditEventDao().insert(AuditEvent(
                occurredAt = now,
                action = AuditEvent.ACTION_REPAIR_START,
                entityType = "SCOOTER",
                entityId = scooterId.toString(),
                reason = reason,
                beforeSnapshot = "status=${scooter.lifecycleStatus}",
                afterSnapshot = "status=$status; pausedPeriods=${db.rentPeriodDao().suspendedForScooter(scooterId).size}"
            ))
        }
    }

    /**
     * Returns a repaired scooter to the renter. Every paused period is
     * extended by the actual repair duration (plus any explicit pauses from
     * §8 partial-repair logic), so the paused days are free.
     */
    suspend fun resumeAfterRepair(scooterId: Int, reason: String): Int = db.withTransaction {
        require(reason.isNotBlank()) { "Repair completion note is required" }
        val scooter = db.scooterDao().getScooterById(scooterId)
            ?: throw IllegalArgumentException("Scooter #$scooterId does not exist")
        val now = System.currentTimeMillis()
        // §8: учитываем суммарную длительность паузов ремонта для продления.
        val openOrder = db.repairOrderDao().openForScooter(scooterId).firstOrNull()
        val extraPauseMs = openOrder?.totalPauseMs ?: 0L
        val paused = db.rentPeriodDao().suspendedForScooter(scooterId)
        // Batch 9 (was HIGH B6): if the renter was terminated while the
        // scooter was in repair, the SUSPENDED_REPAIR periods still belong
        // to a terminated renter. Resuming them as ACTIVE/OVERDUE would
        // inflate receivables for a renter who has already been closed out
        // AND set the scooter back to STATUS_RENTED with no active renter.
        // Detect this case up-front: if ANY paused period's renter is
        // isReturned=true, close all paused periods as CLOSED (debt
        // preserved if any) and leave the scooter AVAILABLE.
        val terminatedRenterIds = paused
            .mapNotNull { it.renterId }
            .distinct()
            .mapNotNull { rid -> db.renterDao().getRenterById(rid) }
            .filter { it.isReturned }
            .map { it.id }
            .toSet()
        val hasTerminatedRenter = terminatedRenterIds.isNotEmpty()
        paused.forEach { period ->
            val basePauseMs = (now - (period.suspendedAt ?: now)).coerceAtLeast(0L)
            // §8: добавляем суммарную длительность всех явных паузов ремонта.
            val totalPauseMs = basePauseMs + extraPauseMs
            val newEnd = period.endsAt + totalPauseMs
            val restoredStatus = if (hasTerminatedRenter || period.renterId in terminatedRenterIds) {
                // Batch 9 (B6): renter is terminated — close the period
                // instead of reactivating it. Preserve debt (CLOSED_WITH_DEBT)
                // if the renter still owed money; otherwise CLOSED.
                if (period.paidMinor >= period.effectiveChargeMinor) RentPeriod.STATUS_CLOSED
                else RentPeriod.STATUS_CLOSED_WITH_DEBT
            } else when {
                period.paidMinor >= period.effectiveChargeMinor -> RentPeriod.STATUS_PAID
                period.paidMinor > 0 -> RentPeriod.STATUS_PARTIALLY_PAID
                newEnd <= now -> RentPeriod.STATUS_OVERDUE
                else -> RentPeriod.STATUS_ACTIVE
            }
            db.rentPeriodDao().update(period.copy(
                endsAt = newEnd,
                status = restoredStatus,
                suspendedAt = null,
                suspensionReason = null,
                updatedAt = now
            ))
        }
        // Batch 9 (B6): only set STATUS_RENTED if there is at least one
        // paused period whose renter is NOT terminated. If all paused
        // periods belong to terminated renters, the scooter goes back to
        // AVAILABLE (the renter returned it implicitly via terminate).
        val anyActiveRenter = paused.any { it.renterId !in terminatedRenterIds }
        val newStatus = if (anyActiveRenter && paused.isNotEmpty()) Scooter.STATUS_RENTED else Scooter.STATUS_AVAILABLE
        db.scooterDao().updateLifecycleStatus(scooterId, newStatus)
        db.repairOrderDao().openForScooter(scooterId).forEach { order ->
            db.repairOrderDao().update(order.copy(
                status = RepairOrder.STATUS_COMPLETED,
                closedAt = now,
                documentNote = listOfNotNull(order.documentNote, reason).joinToString(" • ")
            ))
        }
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now, action = AuditEvent.ACTION_REPAIR_FINISH, entityType = "SCOOTER", entityId = scooterId.toString(),
            reason = reason,
            beforeSnapshot = "status=${scooter.lifecycleStatus}; pausedPeriods=${paused.size}",
            afterSnapshot = "status=$newStatus; extraPauseMs=$extraPauseMs; terminatedRenterClosed=${terminatedRenterIds.size}"
        ))
        paused.size
    }

    /**
     * Replaces a broken scooter without resetting the renter's periods, debt
     * or paid coverage. The old unit is retired and all open periods continue
     * on the replacement unit.
     */
    suspend fun replaceScooterForActiveRental(
        oldScooterId: Int,
        newScooterId: Int,
        reason: String
    ): Int = db.withTransaction {
        require(oldScooterId != newScooterId) { "Replacement scooter must differ" }
        require(reason.isNotBlank()) { "Replacement reason is required" }
        val old = db.scooterDao().getScooterById(oldScooterId)
            ?: throw IllegalArgumentException("Old scooter does not exist")
        val replacement = db.scooterDao().getScooterById(newScooterId)
            ?: throw IllegalArgumentException("Replacement scooter does not exist")
        require(replacement.lifecycleStatus == Scooter.STATUS_AVAILABLE) { "Replacement scooter is not available" }
        val currentPeriods = db.rentPeriodDao().currentForScooter(oldScooterId)
        val renterId = currentPeriods.firstOrNull()?.renterId
            ?: throw IllegalStateException("Old scooter has no active or paused rental")
        val renter = db.renterDao().getRenterById(renterId)
            ?: throw IllegalStateException("Active renter not found")
        val now = System.currentTimeMillis()
        db.rentPeriodDao().reassignScooter(oldScooterId, newScooterId, now)
        db.repairOrderDao().insert(RepairOrder(
            scooterId = oldScooterId,
            renterId = renter.id,
            scenario = RepairOrder.SCENARIO_REPLACEMENT,
            status = RepairOrder.STATUS_COMPLETED,
            openedAt = now,
            closedAt = now,
            diagnosis = reason,
            documentNote = "Replaced by ${replacement.name}"
        ))
        // Batch 12 (was HIGH B4): switched from full-entity
        // db.renterDao().updateRenter(renter.copy(scooterId=...,
        // scooterName=...)) to a field-specific UPDATE. The full-entity
        // write clobbered any concurrent field-specific write to balance
        // / debtAmount / lastPaymentTimestamp / isOverdueSmsSent / etc.
        // because the renter snapshot was captured at the top of the
        // transaction and wrote ALL 13 columns back. Now we touch ONLY
        // scooterId and scooterName — the columns this code path mutates.
        db.renterDao().updateScooterAssignment(renter.id, newScooterId, replacement.name)
        db.scooterDao().updateLifecycleStatus(oldScooterId, Scooter.STATUS_RETIRED)
        db.scooterDao().updateLifecycleStatus(newScooterId, if (renter.isReturned) Scooter.STATUS_AVAILABLE else Scooter.STATUS_RENTED)
        db.repairOrderDao().openForScooter(oldScooterId).forEach { order ->
            db.repairOrderDao().update(order.copy(
                status = RepairOrder.STATUS_COMPLETED,
                closedAt = now,
                documentNote = listOfNotNull(order.documentNote, "Replaced by ${replacement.name}: $reason").joinToString(" • ")
            ))
        }
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now, action = "SCOOTER_REPLACED", entityType = "SCOOTER", entityId = oldScooterId.toString(), reason = reason,
            beforeSnapshot = "old=${old.name}; renter=${renter.name}",
            afterSnapshot = "new=${replacement.name}; periods=${currentPeriods.size}"
        ))
        currentPeriods.size
    }

    suspend fun recordRepairExpense(
        scooterId: Int,
        fromCardId: Int,
        amountMinor: Long,
        note: String,
        occurredAt: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Repair expense must be positive" }
        require(note.isNotBlank()) { "Repair description is required" }
        val scooter = db.scooterDao().getScooterById(scooterId)
            ?: throw IllegalArgumentException("Scooter #$scooterId does not exist")
        val card = db.virtualCardDao().getCardById(fromCardId)
            ?: throw IllegalArgumentException("Card #$fromCardId does not exist")
        require(!card.isExternal && !card.isArchived) { "Choose an active business card" }
        val amount = BusinessOperation.fromMinor(amountMinor)
        // Batch 15 (was HIGH A3): replaced the read-modify-write balance
        // check (require(card.balance >= amount) + adjustBalance(-amount))
        // with an atomic conditional UPDATE. Previously, two concurrent
        // recordRepairExpense calls for the same card could both read
        // balance=1000, both pass the require check, and both deduct 700
        // — sending the card to -400 despite both calls individually
        // passing the balance check. The db.withTransaction wrapper
        // didn't help because Room uses BEGIN DEFERRED by default, which
        // doesn't acquire a write lock until the first write. The atomic
        // debitIfSufficient UPDATE serializes at the SQLite row level:
        // the second call sees balance=300 (after the first commit) and
        // the WHERE balance >= :amount clause fails, returning 0 rows.
        val debited = db.virtualCardDao().debitIfSufficient(fromCardId, amount)
        require(debited == 1) { "Insufficient available balance for repair (need $amount on card #$fromCardId)" }
        val cardTxId = db.cardTransactionDao().insertTransaction(CardTransaction(
            timestamp = occurredAt, fromCardId = fromCardId, toCardId = VirtualCard.EXTERNAL_OUT_CARD_ID,
            amount = amount, note = note, type = CardTransaction.TYPE_EXPENSE
        ))
        val operationId = db.businessOperationDao().insert(BusinessOperation(
            occurredAt = occurredAt, type = BusinessOperation.TYPE_REPAIR,
            direction = BusinessOperation.DIRECTION_EXPENSE, amountMinor = amountMinor,
            scooterId = scooterId, fromCardId = fromCardId, toCardId = VirtualCard.EXTERNAL_OUT_CARD_ID,
            cardTransactionId = cardTxId.toInt(), note = note
        ))
        // Batch 9 (was BLOCKER A2): accumulate the expense on the open
        // RepairOrder.actualMinor so ScooterMetricsService.repairMetrics
        // reports a non-zero total repair cost. Previously actualMinor was
        // never written by any code path, so the per-scooter detail screen
        // always showed "Umumiy xarajat: 0 so'm" for every scooter, making
        // the entire repair-cost metric useless. We accumulate (rather than
        // overwrite) because recordRepairExpense can be called multiple
        // times for the same repair (parts + labour billed separately).
        val openOrders = db.repairOrderDao().openForScooter(scooterId)
        if (openOrders.isNotEmpty()) {
            // All open orders for this scooter are part of the same repair
            // session — accumulate on every open order. (In practice there
            // is only one; the loop is defensive.)
            openOrders.forEach { order ->
                db.repairOrderDao().update(order.copy(
                    actualMinor = order.actualMinor + amountMinor,
                    documentNote = listOfNotNull(order.documentNote, "Expense: $note ($amountMinor minor)").joinToString(" • ")
                ))
            }
        } else {
            // No open RepairOrder — the expense was recorded without a
            // repair being started (e.g. preventive maintenance billed to
            // a retired scooter). Synthesize a COMPLETED RepairOrder so
            // the expense is still attributable for metrics.
            db.repairOrderDao().insert(RepairOrder(
                scooterId = scooterId,
                renterId = null,
                scenario = RepairOrder.SCENARIO_OWNER_REPAIR,
                status = RepairOrder.STATUS_COMPLETED,
                openedAt = occurredAt,
                closedAt = occurredAt,
                diagnosis = note,
                actualMinor = amountMinor,
                documentNote = "Synthesised from recordRepairExpense (no open repair order)"
            ))
        }
        db.auditEventDao().insert(AuditEvent(
            occurredAt = occurredAt, action = "SCOOTER_REPAIR_EXPENSE", entityType = "SCOOTER", entityId = scooterId.toString(),
            reason = note, beforeSnapshot = "card=$fromCardId; balance=${card.balance}",
            afterSnapshot = "expense=$amount; operation=$operationId"
        ))
        operationId
    }

    // ── §8: Частичный ремонт — несколько пауз внутри одного RepairOrder ────
    //
    // pauseRepair(): ставит открытый ремонт на паузу. Записывает startMs в
    // lastPausedAt и помечает currentlyPaused=true. RentPeriod остаётся в
    // SUSPENDED_REPAIR до resume — это нормально: биллинг уже приостановлен.
    //
    // resumeRepair(): снимает паузу, добавляет интервал [lastPausedAt, now]
    // в pauseIntervalsJson, увеличивает totalPauseMs на длительность паузы.
    //
    // finishRepair(): при завершении ремонта все паузы уже зафиксированы;
    // итоговое продление контракта = sum(pauseIntervals) + исходная пауза от
    // start до конца ремонта (если ремонт ещё идёт). Это обеспечивает
    // «продление договора по сумме пауз».

    suspend fun pauseRepair(scooterId: Int, reason: String) = db.withTransaction {
        require(reason.isNotBlank()) { "Pause reason is required" }
        val now = System.currentTimeMillis()
        val openOrders = db.repairOrderDao().openForScooter(scooterId)
        require(openOrders.isNotEmpty()) { "No open repair order for scooter #$scooterId" }
        openOrders.forEach { order ->
            require(!order.currentlyPaused) { "Repair is already paused" }
            db.repairOrderDao().update(order.copy(
                currentlyPaused = true,
                lastPausedAt = now,
                documentNote = listOfNotNull(order.documentNote, "Paused: $reason @ $now").joinToString(" • ")
            ))
        }
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now,
            action = AuditEvent.ACTION_REPAIR_PAUSE,
            entityType = "SCOOTER",
            entityId = scooterId.toString(),
            reason = reason
        ))
    }

    suspend fun resumeRepair(scooterId: Int, reason: String) = db.withTransaction {
        require(reason.isNotBlank()) { "Resume reason is required" }
        val now = System.currentTimeMillis()
        val openOrders = db.repairOrderDao().openForScooter(scooterId)
        require(openOrders.isNotEmpty()) { "No open repair order for scooter #$scooterId" }
        openOrders.forEach { order ->
            require(order.currentlyPaused) { "Repair is not paused" }
            val pauseStart = order.lastPausedAt ?: now
            val pauseDuration = (now - pauseStart).coerceAtLeast(0L)
            // Добавляем интервал в pauseIntervalsJson.
            val newIntervalsJson = addPauseInterval(order.pauseIntervalsJson, pauseStart, now)
            db.repairOrderDao().update(order.copy(
                currentlyPaused = false,
                lastPausedAt = null,
                pauseIntervalsJson = newIntervalsJson,
                totalPauseMs = order.totalPauseMs + pauseDuration,
                documentNote = listOfNotNull(order.documentNote, "Resumed: $reason @ $now (pause=${pauseDuration}ms)").joinToString(" • ")
            ))
        }
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now,
            action = AuditEvent.ACTION_REPAIR_RESUME,
            entityType = "SCOOTER",
            entityId = scooterId.toString(),
            reason = reason
        ))
    }

    /** Возвращает суммарную длительность всех паузов ремонта (включая текущую). */
    suspend fun totalPauseMsFor(scooterId: Int): Long {
        val order = db.repairOrderDao().openForScooter(scooterId).firstOrNull() ?: return 0L
        val currentPauseMs = if (order.currentlyPaused) {
            (System.currentTimeMillis() - (order.lastPausedAt ?: System.currentTimeMillis())).coerceAtLeast(0L)
        } else 0L
        return order.totalPauseMs + currentPauseMs
    }

    /**
     * Помощник: добавляет интервал [startMs, endMs] в JSON-строку формата
     * [[start1,end1],[start2,end2],...].
     */
    private fun addPauseInterval(json: String, startMs: Long, endMs: Long): String {
        // Простейший парсер — без org.json, чтобы избежать зависимостей.
        // Формат: [[s1,e1],[s2,e2]]  → добавляем [startMs,endMs].
        val inner = json.trim().removeSurrounding("[", "]").trim()
        val newEntry = "[$startMs,$endMs]"
        return if (inner.isEmpty()) "[$newEntry]" else "[$inner,$newEntry]"
    }
}
