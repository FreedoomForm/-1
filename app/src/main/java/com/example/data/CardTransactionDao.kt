package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardTransactionDao {
    @Query("SELECT * FROM card_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<CardTransaction>

    // ── Trash-mode queries (v36+) ────────────────────────────────────────
    @Query("SELECT * FROM card_transactions WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getLiveTransactions(): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashedTransactions(): Flow<List<CardTransaction>>

    @Query("UPDATE card_transactions SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE card_transactions SET isDeleted = 1, deletedAt = :now WHERE contractId = :contractId")
    suspend fun moveToTrashForContract(contractId: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE card_transactions SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)

    @Query("UPDATE card_transactions SET isDeleted = 0, deletedAt = NULL WHERE contractId = :contractId")
    suspend fun restoreFromTrashForContract(contractId: Int)

    @Query("SELECT * FROM card_transactions WHERE fromCardId = :cardId OR toCardId = :cardId ORDER BY timestamp DESC")
    fun getTransactionsForCard(cardId: Int): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions WHERE type = :type ORDER BY timestamp DESC")
    fun getTransactionsByType(type: String): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions WHERE contractId = :contractId ORDER BY timestamp DESC")
    suspend fun getForContractOnce(contractId: Int): List<CardTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: CardTransaction): Long

    @Update
    suspend fun updateTransaction(tx: CardTransaction)

    @Query("DELETE FROM card_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Int)

    @Query("DELETE FROM card_transactions WHERE contractId = :contractId")
    suspend fun deleteForContract(contractId: Int)

    @Query("DELETE FROM card_transactions")
    suspend fun deleteAll()
}
