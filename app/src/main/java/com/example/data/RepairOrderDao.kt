package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RepairOrderDao {
    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId ORDER BY openedAt DESC")
    fun forScooter(scooterId: Int): Flow<List<RepairOrder>>

    @Query("SELECT * FROM repair_orders WHERE status = 'OPEN' ORDER BY openedAt ASC")
    suspend fun openOrders(): List<RepairOrder>

    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId AND status = 'OPEN' ORDER BY openedAt ASC")
    suspend fun openForScooter(scooterId: Int): List<RepairOrder>

    @Insert
    suspend fun insert(order: RepairOrder): Long

    @Update
    suspend fun update(order: RepairOrder)
}
