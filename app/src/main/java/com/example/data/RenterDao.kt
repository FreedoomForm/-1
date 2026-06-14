package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RenterDao {
    @Query("SELECT * FROM renters ORDER BY isReturned ASC, rentStartDateTimestamp DESC")
    fun getAllRenters(): Flow<List<Renter>>

    @Query("SELECT * FROM renters WHERE isReturned = 0")
    suspend fun getActiveRenters(): List<Renter>

    @Query("SELECT * FROM renters WHERE id = :id LIMIT 1")
    suspend fun getRenterById(id: Int): Renter?

    /** Возвращает сгенерированный rowId — нужно для немедленного уведомления при создании. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRenter(renter: Renter): Long

    @Update
    suspend fun updateRenter(renter: Renter)

    @Query("DELETE FROM renters WHERE id = :id")
    suspend fun deleteRenter(id: Int)
}
