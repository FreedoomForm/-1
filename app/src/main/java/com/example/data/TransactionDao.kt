package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<Transaction>

    // ── Trash-mode queries (v36+) ────────────────────────────────────────
    @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getLiveTransactions(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashedTransactions(): Flow<List<Transaction>>

    @Query("UPDATE transactions SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isDeleted = 1, deletedAt = :now WHERE id IN (:ids)")
    suspend fun moveToTrashBatch(ids: List<Int>, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)

    @Query("UPDATE transactions SET isDeleted = 0, deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreFromTrashBatch(ids: List<Int>)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Transaction?

    @Query("SELECT * FROM transactions WHERE renterId = :renterId ORDER BY timestamp DESC")
    fun getForRenter(renterId: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE renterId = :renterId ORDER BY timestamp DESC")
    suspend fun getForRenterOnce(renterId: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE scooterId = :scooterId ORDER BY timestamp DESC")
    fun getForScooter(scooterId: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE contractId = :contractId ORDER BY timestamp DESC")
    fun getForContract(contractId: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE contractId = :contractId ORDER BY timestamp DESC")
    suspend fun getForContractOnce(contractId: Int): List<Transaction>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM transactions WHERE contractId = :contractId")
    suspend fun deleteForContract(contractId: Int)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}
