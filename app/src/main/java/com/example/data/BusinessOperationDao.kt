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

    @Query("UPDATE business_operations SET cardTransactionId = :cardTransactionId WHERE id = :operationId")
    suspend fun markCardTransaction(operationId: Long, cardTransactionId: Int)

    @Query("DELETE FROM business_operations")
    suspend fun clear()
}
