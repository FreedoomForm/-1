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

    /**
     * Duplicate-name detection (case-insensitive) for new/edit renter.
     * Returns count of renters whose name matches [name] (excluding [excludeId]).
     * Used by RenterViewModel to block creation of duplicate renters.
     */
    @Query("""
        SELECT COUNT(*) FROM renters
        WHERE id != :excludeId AND lower(trim(name)) = lower(trim(:name))
    """)
    suspend fun duplicateNameCount(name: String, excludeId: Int): Int

    /** Same as above but matches by phone — useful for blocking duplicate phones. */
    @Query("""
        SELECT COUNT(*) FROM renters
        WHERE id != :excludeId
          AND :phone != ''
          AND lower(trim(phoneNumber)) = lower(trim(:phone))
    """)
    suspend fun duplicatePhoneCount(phone: String, excludeId: Int): Int

    /** §12: Total renter count — used by migration tests and analytics. */
    @Query("SELECT COUNT(*) FROM renters")
    suspend fun getCount(): Int

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
