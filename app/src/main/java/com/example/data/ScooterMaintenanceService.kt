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
        if (status in setOf(Scooter.STATUS_SERVICE, Scooter.STATUS_REPAIR, Scooter.STATUS_RETIRED)) {
            val conflicts = db.rentPeriodDao().conflictsForScooter(scooterId, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)
            check(conflicts.none { it.status in setOf(RentPeriod.STATUS_ACTIVE, RentPeriod.STATUS_PARTIALLY_PAID, RentPeriod.STATUS_OVERDUE) }) {
                "Cannot move a scooter with an active rental into maintenance"
            }
        }
        db.scooterDao().updateLifecycleStatus(scooterId, status)
        db.auditEventDao().insert(AuditEvent(
            action = "SCOOTER_STATUS_CHANGED", entityType = "SCOOTER", entityId = scooterId.toString(), reason = reason,
            beforeSnapshot = "status=${scooter.lifecycleStatus}", afterSnapshot = "status=$status"
        ))
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
