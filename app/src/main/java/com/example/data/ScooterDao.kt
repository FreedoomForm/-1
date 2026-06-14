package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScooterDao {
    @Query("SELECT * FROM scooters ORDER BY id ASC")
    fun getAllScooters(): Flow<List<Scooter>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScooter(scooter: Scooter)

    @Update
    suspend fun updateScooter(scooter: Scooter)

    @Delete
    suspend fun deleteScooter(scooter: Scooter)
}
