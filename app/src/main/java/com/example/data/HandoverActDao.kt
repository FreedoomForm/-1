package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HandoverActDao {
    @Query("SELECT * FROM handover_acts ORDER BY timestamp DESC")
    fun all(): Flow<List<HandoverAct>>

    @Query("SELECT * FROM handover_acts WHERE renterId = :renterId ORDER BY timestamp DESC")
    fun forRenter(renterId: Int): Flow<List<HandoverAct>>

    @Query("SELECT * FROM handover_acts WHERE scooterId = :scooterId ORDER BY timestamp DESC")
    fun forScooter(scooterId: Int): Flow<List<HandoverAct>>

    @Query("SELECT * FROM handover_acts WHERE contractHistoryId = :contractId ORDER BY timestamp ASC")
    suspend fun forContract(contractId: Int): List<HandoverAct>

    @Query("SELECT * FROM handover_acts WHERE scooterId = :scooterId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestForScooter(scooterId: Int): HandoverAct?

    @Insert
    suspend fun insert(act: HandoverAct): Long

    @Query("DELETE FROM handover_acts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM handover_acts WHERE renterId = :renterId")
    suspend fun deleteByRenter(renterId: Int)

    @Query("DELETE FROM handover_acts WHERE scooterId = :scooterId")
    suspend fun deleteByScooter(scooterId: Int)

    @Query("DELETE FROM handover_acts WHERE contractHistoryId = :contractId")
    suspend fun deleteByContract(contractId: Int)

    @Query("DELETE FROM handover_acts")
    suspend fun clear()
}
