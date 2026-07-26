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
     * Cancels an operation without destroying audit history. The caller must
     * create the real-world compensating movement separately when required.
     */
    suspend fun reverse(id: Long, note: String): Long = db.withTransaction {
        val original = dao.getById(id) ?: error("Operation #$id not found")
        check(original.status == BusinessOperation.STATUS_ACTIVE) { "Operation is already reversed" }
        dao.markReversed(id)
        dao.insert(original.copy(
            id = 0,
            occurredAt = System.currentTimeMillis(),
            note = note,
            // Reversal audit rows never affect reports. A real refund/expense
            // is recorded separately as its own operation.
            status = BusinessOperation.STATUS_REVERSED,
            reversesOperationId = id,
            createdAt = System.currentTimeMillis()
        ))
    }
}
