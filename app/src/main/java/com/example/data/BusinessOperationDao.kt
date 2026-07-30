package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessOperationDao {
    @Query("SELECT * FROM business_operations WHERE status = 'ACTIVE' ORDER BY occurredAt DESC, id DESC")
    fun getActive(): Flow<List<BusinessOperation>>

    @Query("SELECT * FROM business_operations WHERE status = 'ACTIVE' AND occurredAt >= :from AND occurredAt < :until ORDER BY occurredAt DESC")
    suspend fun getActiveInRange(from: Long, until: Long): List<BusinessOperation>

    @Query("SELECT * FROM business_operations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BusinessOperation?

    @Query("SELECT * FROM business_operations ORDER BY id ASC")
    suspend fun getAllOnce(): List<BusinessOperation>

    @Insert
    suspend fun insert(operation: BusinessOperation): Long

    @Query("UPDATE business_operations SET status = 'REVERSED' WHERE id = :id AND status = 'ACTIVE'")
    suspend fun markReversed(id: Long): Int

    @androidx.room.Update
    suspend fun update(operation: BusinessOperation)

    @Query("SELECT COUNT(*) FROM business_operations WHERE cardTransactionId = :cardTransactionId")
    suspend fun countForCardTransaction(cardTransactionId: Int): Int

    @Query("SELECT * FROM business_operations WHERE cardTransactionId = :cardTransactionId AND status = 'ACTIVE' LIMIT 1")
    suspend fun getByCardTransactionId(cardTransactionId: Int): BusinessOperation?

    @Query("SELECT * FROM business_operations WHERE legacyTransactionId = :legacyId AND status = 'ACTIVE' LIMIT 1")
    suspend fun getByLegacyTransactionId(legacyId: Int): BusinessOperation?

    /**
     * Returns ALL operations (ACTIVE and REVERSED) for a given legacy
     * transaction id. Used by TrashService.snapshotTransaction so the
     * full financial context can be restored from the recycle bin.
     */
    @Query("SELECT * FROM business_operations WHERE legacyTransactionId = :legacyId ORDER BY id ASC")
    suspend fun getAllByLegacyTransactionId(legacyId: Int): List<BusinessOperation>

    /**
     * Returns ALL operations (ACTIVE and REVERSED) for a given contract.
     * Used by TrashService.snapshotContract so the financial history of
     * a contract can be rebuilt after restoration from the recycle bin.
     */
    @Query("SELECT * FROM business_operations WHERE contractId = :contractId ORDER BY id ASC")
    suspend fun getAllByContract(contractId: Int): List<BusinessOperation>

    /**
     * Returns ALL operations (ACTIVE and REVERSED) for a given scooter.
     * Used by TrashService.snapshotScooter so the repair-cost history of
     * a scooter can be rebuilt after restoration from the recycle bin.
     */
    @Query("SELECT * FROM business_operations WHERE scooterId = :scooterId ORDER BY id ASC")
    suspend fun getAllByScooter(scooterId: Int): List<BusinessOperation>

    /**
     * Returns ALL operations (ACTIVE and REVERSED) for a given renter.
     * Used by TrashService.snapshotRenter so the renter's full financial
     * history can be rebuilt after restoration from the recycle bin.
     */
    @Query("SELECT * FROM business_operations WHERE renterId = :renterId ORDER BY id ASC")
    suspend fun getAllByRenter(renterId: Int): List<BusinessOperation>

    @Query("UPDATE business_operations SET cardTransactionId = :cardTransactionId WHERE id = :operationId")
    suspend fun markCardTransaction(operationId: Long, cardTransactionId: Int)

    @Query("UPDATE business_operations SET status = 'REVERSED' WHERE renterId = :renterId AND status = 'ACTIVE'")
    suspend fun markReversedByRenter(renterId: Int): Int

    @Query("UPDATE business_operations SET status = 'REVERSED' WHERE scooterId = :scooterId AND status = 'ACTIVE'")
    suspend fun markReversedByScooter(scooterId: Int): Int

    @Query("UPDATE business_operations SET status = 'REVERSED' WHERE contractId = :contractId AND status = 'ACTIVE'")
    suspend fun markReversedByContract(contractId: Int): Int

    @Query("UPDATE business_operations SET status = 'REVERSED' WHERE legacyTransactionId = :legacyId AND status = 'ACTIVE'")
    suspend fun markReversedByLegacyTransactionId(legacyId: Int): Int

    @Query("UPDATE business_operations SET status = 'REVERSED' WHERE cardTransactionId = :cardTxId AND status = 'ACTIVE'")
    suspend fun markReversedByCardTransactionId(cardTxId: Int): Int

    /**
     * Cross-snapshot BO deduplication lookup (Batch 7 — fixes BLOCKER B1).
     *
     * The same `BusinessOperation` row can be captured by TWO independent
     * snapshots during cascade trash:
     *   - `snapshotContract` captures it via `contractId`
     *   - `snapshotTransaction` captures it via `legacyTransactionId`
     *
     * Without dedup, restoring both snapshots would insert two identical
     * ACTIVE rows → income/expense reports silently double-count the
     * operation's `amountMinor`.
     *
     * Solution: before inserting a restored BO, query by the fingerprint
     * (`occurredAt + amountMinor + direction + type + renterId + scooterId`).
     * If a matching ACTIVE op already exists, the restore path skips the
     * insert and reuses the existing id for `operationIdMap` (so
     * `PaymentAllocation` rows still link correctly).
     *
     * `renterId` / `scooterId` use `(x IS :y OR (x IS NULL AND :y IS NULL))`
     * so a NULL parameter matches a NULL column (Room binds Kotlin `null`
     * as SQL `NULL`). Without this form, `renterId = NULL` would never
     * match (SQL `=` semantics return NULL, not TRUE, for NULL operands).
     */
    @Query("""
        SELECT * FROM business_operations
        WHERE status = 'ACTIVE'
          AND occurredAt = :occurredAt
          AND amountMinor = :amountMinor
          AND direction = :direction
          AND type = :type
          AND (renterId IS :renterId OR (renterId IS NULL AND :renterId IS NULL))
          AND (scooterId IS :scooterId OR (scooterId IS NULL AND :scooterId IS NULL))
        LIMIT 1
    """)
    suspend fun findActiveByFingerprint(
        occurredAt: Long,
        amountMinor: Long,
        direction: String,
        type: String,
        renterId: Int?,
        scooterId: Int?
    ): BusinessOperation?

    @Query("DELETE FROM business_operations")
    suspend fun clear()
}
