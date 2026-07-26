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
}
