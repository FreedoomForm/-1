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

    @Query("SELECT * FROM renters ORDER BY isReturned ASC, rentStartDateTimestamp DESC")
    suspend fun getAllRentersOnce(): List<Renter>

    @Query("SELECT * FROM renters WHERE isReturned = 0")
    suspend fun getActiveRenters(): List<Renter>

    @Query("SELECT * FROM renters WHERE id = :id LIMIT 1")
    suspend fun getRenterById(id: Int): Renter?

    /** Возвращает сгенерированный rowId — нужно для немедленного уведомления при создании. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRenter(renter: Renter): Long

    suspend fun insert(renter: Renter): Long = insertRenter(renter)

    @Update
    suspend fun updateRenter(renter: Renter)

    suspend fun update(renter: Renter) = updateRenter(renter)

    @Query("DELETE FROM renters WHERE id = :id")
    suspend fun deleteRenter(id: Int)

    suspend fun delete(id: Int) = deleteRenter(id)

    @Query("DELETE FROM renters")
    suspend fun deleteAll()

    /** Обновляет id арендатора (для замены локального id на серверный). */
    @Query("UPDATE renters SET id = :newId WHERE id = :oldId")
    suspend fun updateRenterId(oldId: Int, newId: Int)
}
