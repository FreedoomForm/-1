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

    @Query("SELECT * FROM scooters WHERE id = :id LIMIT 1")
    suspend fun getScooterById(id: Int): Scooter?

    @Query("UPDATE scooters SET lifecycleStatus = :status WHERE id = :id")
    suspend fun updateLifecycleStatus(id: Int, status: String): Int

    @Query("SELECT * FROM scooters WHERE nextServiceAt IS NOT NULL AND nextServiceAt <= :now AND lifecycleStatus NOT IN ('RETIRED') ORDER BY nextServiceAt ASC")
    suspend fun serviceDue(now: Long): List<Scooter>

    /**
     * Scooters with nextServiceAt within [now, now + withinMs] window.
     * Used by the 'Upcoming maintenance' UI section (§8). Excludes RETIRED.
     * Ordered by nextServiceAt ascending — most urgent first.
     */
    @Query("SELECT * FROM scooters WHERE nextServiceAt IS NOT NULL AND nextServiceAt <= :nowPlusWindow AND lifecycleStatus NOT IN ('RETIRED') ORDER BY nextServiceAt ASC")
    suspend fun upcomingMaintenance(nowPlusWindow: Long): List<Scooter>

    /** Flow version of upcomingMaintenance for reactive UI updates. */
    @Query("SELECT * FROM scooters WHERE nextServiceAt IS NOT NULL AND nextServiceAt <= :nowPlusWindow AND lifecycleStatus NOT IN ('RETIRED') ORDER BY nextServiceAt ASC")
    fun upcomingMaintenanceFlow(nowPlusWindow: Long): Flow<List<Scooter>>

    @Query("""
        SELECT COUNT(*) FROM scooters
        WHERE id != :excludeId AND (
          (:vin != '' AND lower(vinNumber) = lower(:vin)) OR
          (:engine != '' AND lower(engineNumber) = lower(:engine)) OR
          (:serial != '' AND lower(scooterSerialNumber) = lower(:serial))
        )
    """)
    suspend fun duplicateIdentifierCount(vin: String, engine: String, serial: String, excludeId: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScooter(scooter: Scooter): Long

    @Update
    suspend fun updateScooter(scooter: Scooter)

    suspend fun update(scooter: Scooter) = updateScooter(scooter)

    suspend fun delete(scooter: Scooter) = deleteScooter(scooter)

    @androidx.room.Delete
    suspend fun deleteScooter(scooter: Scooter)

    @Query("DELETE FROM scooters WHERE id = :id")
    suspend fun deleteScooterById(id: Int)

    @Query("DELETE FROM scooters")
    suspend fun deleteAll()
}
