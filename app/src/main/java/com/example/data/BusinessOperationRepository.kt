package com.example.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Centralised append-only accounting journal. */
class BusinessOperationRepository(private val db: AppDatabase) {
    private val dao = db.businessOperationDao()
    val active: Flow<List<BusinessOperation>> = dao.getActive()

    suspend fun record(operation: BusinessOperation): Long {
        require(operation.amountMinor > 0) { "Operation amount must be positive" }
        return dao.insert(operation)
    }

    /**
     * Creates a true compensating journal entry. The original is retained and
     * remains visible; together, the two active entries net to zero in cash,
     * profit and ledger projections. No financial history is silently erased.
     */
    suspend fun reverse(id: Long, note: String): Long = db.withTransaction {
        require(note.isNotBlank()) { "Reversal reason is required" }
        val original = dao.getById(id) ?: error("Operation #$id not found")
        check(original.status == BusinessOperation.STATUS_ACTIVE) { "Operation is not active" }
        val (direction, fromCard, toCard) = when (original.direction) {
            BusinessOperation.DIRECTION_INCOME -> Triple(
                BusinessOperation.DIRECTION_EXPENSE, original.toCardId, CardTransaction.EXTERNAL_SOURCE_ID
            )
            BusinessOperation.DIRECTION_EXPENSE -> Triple(
                BusinessOperation.DIRECTION_INCOME, CardTransaction.EXTERNAL_SOURCE_ID, original.fromCardId
            )
            BusinessOperation.DIRECTION_TRANSFER -> Triple(
                BusinessOperation.DIRECTION_TRANSFER, original.toCardId, original.fromCardId
            )
            // Liability reversal is recorded as an adjustment and is excluded
            // from cash/profit while remaining fully auditable.
            else -> Triple(BusinessOperation.DIRECTION_LIABILITY, original.toCardId, original.fromCardId)
        }
        dao.insert(BusinessOperation(
            occurredAt = System.currentTimeMillis(),
            type = BusinessOperation.TYPE_REVERSAL,
            direction = direction,
            amountMinor = original.amountMinor,
            renterId = original.renterId,
            scooterId = original.scooterId,
            contractId = original.contractId,
            fromCardId = fromCard,
            toCardId = toCard,
            note = note,
            reversesOperationId = original.id
        ))
    }

    // ── §5: deposit / discount / refund / debt-forgive high-level helpers ────
    // These wrap the raw BusinessOperation with audit + card balance updates
    // so callers don't have to re-implement the accounting plumbing.

    /**
     * Records a security deposit received from a renter. Money lands on
     * [toCardId] (typically main card). Direction = LIABILITY — the deposit
     * is NOT revenue; it must be returned or withheld later.
     */
    suspend fun recordDepositReceived(
        renterId: Int,
        amountMinor: Long,
        toCardId: Int,
        note: String,
        actor: String = "LOCAL_SYSTEM"
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Deposit must be positive" }
        require(note.isNotBlank()) { "Deposit description is required" }
        val amount = BusinessOperation.fromMinor(amountMinor)
        val card = db.virtualCardDao().getCardById(toCardId)
            ?: error("Card #$toCardId not found")
        require(!card.isArchived) { "Cannot deposit to an archived card" }
        db.virtualCardDao().adjustBalance(toCardId, +amount)
        val cardTxId = db.cardTransactionDao().insertTransaction(CardTransaction(
            fromCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            toCardId = toCardId, amount = amount, note = note,
            type = CardTransaction.TYPE_CONTRACT_INCOME
        ))
        val opId = dao.insert(BusinessOperation(
            type = BusinessOperation.TYPE_DEPOSIT_RECEIVED,
            direction = BusinessOperation.DIRECTION_LIABILITY,
            amountMinor = amountMinor, renterId = renterId,
            fromCardId = CardTransaction.EXTERNAL_SOURCE_ID, toCardId = toCardId,
            cardTransactionId = cardTxId.toInt(), note = note
        ))
        db.auditEventDao().insert(AuditEvent(
            actor = actor, action = "DEPOSIT_RECEIVED",
            entityType = "RENTER", entityId = renterId.toString(),
            reason = note, beforeSnapshot = "card=$toCardId; balance=${card.balance}",
            afterSnapshot = "+$amount; operation=$opId"
        ))
        opId
    }

    /**
     * Returns a previously-held deposit to the renter. Money leaves
     * [fromCardId] (typically main card). The matching DEPOSIT_RECEIVED
     * operation should be reversed separately if full audit symmetry is
     * required; this method only records the cash movement.
     */
    suspend fun recordDepositRefunded(
        renterId: Int,
        amountMinor: Long,
        fromCardId: Int,
        note: String,
        actor: String = "LOCAL_SYSTEM"
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Refund must be positive" }
        require(note.isNotBlank()) { "Refund reason is required" }
        val amount = BusinessOperation.fromMinor(amountMinor)
        val card = db.virtualCardDao().getCardById(fromCardId)
            ?: error("Card #$fromCardId not found")
        require(!card.isArchived) { "Cannot refund from an archived card" }
        require(card.balance + 0.005 >= amount) { "Insufficient available balance for refund" }
        db.virtualCardDao().adjustBalance(fromCardId, -amount)
        val cardTxId = db.cardTransactionDao().insertTransaction(CardTransaction(
            fromCardId = fromCardId,
            toCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            amount = amount, note = note,
            type = CardTransaction.TYPE_EXPENSE
        ))
        val opId = dao.insert(BusinessOperation(
            type = BusinessOperation.TYPE_DEPOSIT_REFUNDED,
            direction = BusinessOperation.DIRECTION_EXPENSE,
            amountMinor = amountMinor, renterId = renterId,
            fromCardId = fromCardId, toCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            cardTransactionId = cardTxId.toInt(), note = note
        ))
        db.auditEventDao().insert(AuditEvent(
            actor = actor, action = "DEPOSIT_REFUNDED",
            entityType = "RENTER", entityId = renterId.toString(),
            reason = note, beforeSnapshot = "card=$fromCardId; balance=${card.balance}",
            afterSnapshot = "-$amount; operation=$opId"
        ))
        opId
    }

    /**
     * Records a discount applied to a renter's debt. Direction = LIABILITY
     * (reduces receivable, not cash). The [contractId] is optional — discounts
     * can apply at renter level too.
     */
    suspend fun recordDiscount(
        renterId: Int,
        amountMinor: Long,
        contractId: Int? = null,
        note: String,
        actor: String = "LOCAL_SYSTEM"
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Discount must be positive" }
        require(note.isNotBlank()) { "Discount reason is required" }
        val opId = dao.insert(BusinessOperation(
            type = BusinessOperation.TYPE_DISCOUNT,
            direction = BusinessOperation.DIRECTION_LIABILITY,
            amountMinor = amountMinor, renterId = renterId, contractId = contractId,
            note = note
        ))
        db.auditEventDao().insert(AuditEvent(
            actor = actor, action = "DISCOUNT_GRANTED",
            entityType = "RENTER", entityId = renterId.toString(),
            reason = note, beforeSnapshot = "discount=0",
            afterSnapshot = "discount=${BusinessOperation.fromMinor(amountMinor)}; operation=$opId"
        ))
        opId
    }

    /**
     * Forgives (writes off) a renter's debt. Direction = LIABILITY.
     * The debt disappears from receivables but the audit trail preserves
     * who forgave it and why. No cash movement.
     */
    suspend fun forgiveDebt(
        renterId: Int,
        amountMinor: Long,
        note: String,
        actor: String = "LOCAL_SYSTEM"
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Forgiven amount must be positive" }
        require(note.isNotBlank()) { "Forgiveness reason is required" }
        val opId = dao.insert(BusinessOperation(
            type = BusinessOperation.TYPE_DEBT_FORGIVEN,
            direction = BusinessOperation.DIRECTION_LIABILITY,
            amountMinor = amountMinor, renterId = renterId,
            note = note
        ))
        db.auditEventDao().insert(AuditEvent(
            actor = actor, action = "DEBT_FORGIVEN",
            entityType = "RENTER", entityId = renterId.toString(),
            reason = note, beforeSnapshot = "debt=${BusinessOperation.fromMinor(amountMinor)}",
            afterSnapshot = "debt=0; operation=$opId"
        ))
        opId
    }

    /**
     * Records a generic refund (overpayment return, duplicate payment return,
     * etc.). Money leaves [fromCardId].
     */
    suspend fun recordRefund(
        renterId: Int,
        amountMinor: Long,
        fromCardId: Int,
        note: String,
        actor: String = "LOCAL_SYSTEM"
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Refund must be positive" }
        require(note.isNotBlank()) { "Refund reason is required" }
        val amount = BusinessOperation.fromMinor(amountMinor)
        val card = db.virtualCardDao().getCardById(fromCardId)
            ?: error("Card #$fromCardId not found")
        require(!card.isArchived) { "Cannot refund from an archived card" }
        require(card.balance + 0.005 >= amount) { "Insufficient available balance for refund" }
        db.virtualCardDao().adjustBalance(fromCardId, -amount)
        val cardTxId = db.cardTransactionDao().insertTransaction(CardTransaction(
            fromCardId = fromCardId,
            toCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            amount = amount, note = note,
            type = CardTransaction.TYPE_EXPENSE
        ))
        val opId = dao.insert(BusinessOperation(
            type = BusinessOperation.TYPE_REFUND,
            direction = BusinessOperation.DIRECTION_EXPENSE,
            amountMinor = amountMinor, renterId = renterId,
            fromCardId = fromCardId, toCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            cardTransactionId = cardTxId.toInt(), note = note
        ))
        db.auditEventDao().insert(AuditEvent(
            actor = actor, action = "REFUND_ISSUED",
            entityType = "RENTER", entityId = renterId.toString(),
            reason = note, beforeSnapshot = "card=$fromCardId; balance=${card.balance}",
            afterSnapshot = "-$amount; operation=$opId"
        ))
        opId
    }
}
