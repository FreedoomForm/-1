package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContractHistoryDao {
    @Query("SELECT * FROM contract_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ContractHistoryEntry>>

    @Query("SELECT * FROM contract_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<ContractHistoryEntry>

    @Query("SELECT * FROM contract_history WHERE renterId = :renterId ORDER BY timestamp DESC")
    suspend fun getForRenter(renterId: Int): List<ContractHistoryEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ContractHistoryEntry)

    @Query("DELETE FROM contract_history")
    suspend fun clear()

    @Query("DELETE FROM contract_history")
    suspend fun deleteAll()

    /** Обновляет renterId при смене id арендатора. */
    @Query("UPDATE contract_history SET renterId = :newId WHERE renterId = :oldId")
    suspend fun updateRenterId(oldId: Int, newId: Int)
}
