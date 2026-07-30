package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<NotificationHistoryEntity>

    /**
     * Returns ALL NotificationHistory rows for a given renter. Used by
     * TrashService.snapshotRenter so notification history can be rebuilt
     * after restoration from the recycle bin.
     */
    @Query("SELECT * FROM notification_history WHERE renterId = :renterId ORDER BY timestamp ASC, id ASC")
    suspend fun forRenterOnce(renterId: Int): List<NotificationHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NotificationHistoryEntity)

    @Query("DELETE FROM notification_history")
    suspend fun clear()

    @Query("DELETE FROM notification_history")
    suspend fun deleteAll()

    /** Обновляет renterId при смене id арендатора. */
    @Query("UPDATE notification_history SET renterId = :newId WHERE renterId = :oldId")
    suspend fun updateRenterId(oldId: Int, newId: Int)

    @Query("DELETE FROM notification_history WHERE renterId = :renterId")
    suspend fun deleteByRenter(renterId: Int)
}
