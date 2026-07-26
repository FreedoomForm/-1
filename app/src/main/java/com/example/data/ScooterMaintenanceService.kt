package com.example.data

import androidx.room.withTransaction

/** Operational status and repair costs are changed through one audited service. */
class ScooterMaintenanceService(private val db: AppDatabase) {
    suspend fun changeStatus(scooterId: Int, status: String, reason: String) = db.withTransaction {
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
            db.rentPeriodDao().billableForScooter(scooterId).forEach { period ->
                db.rentPeriodDao().update(period.copy(
                    status = RentPeriod.STATUS_SUSPENDED_REPAIR,
                    suspendedAt = now,
                    suspensionReason = reason,
                    updatedAt = now
                ))
            }
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
     * extended by the actual repair duration, so the paused days are free.
     */
    suspend fun resumeAfterRepair(scooterId: Int, reason: String): Int = db.withTransaction {
        require(reason.isNotBlank()) { "Repair completion note is required" }
        val scooter = db.scooterDao().getScooterById(scooterId)
            ?: throw IllegalArgumentException("Scooter #$scooterId does not exist")
        val now = System.currentTimeMillis()
        val paused = db.rentPeriodDao().suspendedForScooter(scooterId)
        paused.forEach { period ->
            val pauseMs = (now - (period.suspendedAt ?: now)).coerceAtLeast(0L)
            val newEnd = period.endsAt + pauseMs
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
        db.auditEventDao().insert(AuditEvent(
            occurredAt = now, action = "SCOOTER_REPAIR_RESUMED", entityType = "SCOOTER", entityId = scooterId.toString(),
            reason = reason,
            beforeSnapshot = "status=${scooter.lifecycleStatus}; pausedPeriods=${paused.size}",
            afterSnapshot = "status=${if (paused.isNotEmpty()) Scooter.STATUS_RENTED else Scooter.STATUS_AVAILABLE}"
        ))
        paused.size
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
}
