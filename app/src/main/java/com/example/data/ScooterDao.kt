package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScooterDao {
    @Query("SELECT * FROM scooters ORDER BY id ASC")
    fun getAllScooters(): Flow<List<Scooter>>

    @Query("SELECT * FROM scooters ORDER BY id ASC")
    suspend fun getAllScootersOnce(): List<Scooter>

    // ── Trash-mode queries (v36+) ────────────────────────────────────────
    @Query("SELECT * FROM scooters WHERE isDeleted = 0 ORDER BY id ASC")
    fun getLiveScooters(): Flow<List<Scooter>>

    @Query("SELECT * FROM scooters WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashedScooters(): Flow<List<Scooter>>

    @Query("UPDATE scooters SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE scooters SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)

    @Query("DELETE FROM scooters WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM scooters WHERE id = :id LIMIT 1")
    suspend fun getScooterById(id: Int): Scooter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScooter(scooter: Scooter): Long

    @Update
    suspend fun updateScooter(scooter: Scooter)

    suspend fun update(scooter: Scooter) = updateScooter(scooter)

    suspend fun delete(scooter: Scooter) = deleteScooter(scooter)

    @androidx.room.Delete
    suspend fun deleteScooter(scooter: Scooter)

    @Query("DELETE FROM scooters")
    suspend fun deleteAll()
}
