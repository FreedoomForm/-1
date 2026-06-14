package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContractHistoryDao {
    @Query("SELECT * FROM contract_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ContractHistoryEntry>>

    @Query("SELECT * FROM contract_history WHERE renterId = :renterId ORDER BY timestamp DESC")
    suspend fun getForRenter(renterId: Int): List<ContractHistoryEntry>

    @Insert
    suspend fun insert(entry: ContractHistoryEntry)

    @Query("DELETE FROM contract_history")
    suspend fun clear()
}
