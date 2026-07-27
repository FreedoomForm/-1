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
        }
        db.scooterDao().updateLifecycleStatus(scooterId, status)
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now, action = "SCOOTER_STATUS_CHANGED", entityType = "SCOOTER", entityId = scooterId.toString(), reason = reason,
            beforeSnapshot = "status=${scooter.lifecycleStatus}", afterSnapshot = "status=$status"
        ))
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
        paused.forEach { period ->
            val basePauseMs = (now - (period.suspendedAt ?: now)).coerceAtLeast(0L)
            // §8: добавляем суммарную длительность всех явных паузов ремонта.
            val totalPauseMs = basePauseMs + extraPauseMs
            val newEnd = period.endsAt + totalPauseMs
            val restoredStatus = when {
                period.paidMinor >= period.chargeMinor -> RentPeriod.STATUS_PAID
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
        db.scooterDao().updateLifecycleStatus(
            scooterId,
            if (paused.isNotEmpty()) Scooter.STATUS_RENTED else Scooter.STATUS_AVAILABLE
        )
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
            afterSnapshot = "status=${if (paused.isNotEmpty()) Scooter.STATUS_RENTED else Scooter.STATUS_AVAILABLE}; extraPauseMs=$extraPauseMs"
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
        db.renterDao().updateRenter(renter.copy(scooterId = newScooterId, scooterName = replacement.name))
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
        require(card.balance + 0.005 >= amount) { "Insufficient available balance for repair" }
        db.virtualCardDao().adjustBalance(fromCardId, -amount)
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
