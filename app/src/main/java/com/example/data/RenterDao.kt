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

    // ── Trash-mode queries (v36+) ────────────────────────────────────────
    /** Только активные арендаторы (isDeleted = 0) — для обычного режима. */
    @Query("SELECT * FROM renters WHERE isDeleted = 0 ORDER BY isReturned ASC, rentStartDateTimestamp DESC")
    fun getLiveRenters(): Flow<List<Renter>>

    /** Только удалённые в корзину (isDeleted = 1) — для trash mode. */
    @Query("SELECT * FROM renters WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashedRenters(): Flow<List<Renter>>

    /** Soft-delete: помечает арендатора как удалённого (isDeleted=1, deletedAt=now). */
    @Query("UPDATE renters SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    /** Восстановление из корзины: isDeleted=0, deletedAt=NULL. */
    @Query("UPDATE renters SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)

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
