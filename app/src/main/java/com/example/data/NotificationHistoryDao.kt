package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<NotificationHistoryEntity>

    @Insert
    suspend fun insert(entity: NotificationHistoryEntity)

    @Query("DELETE FROM notification_history")
    suspend fun clear()

    @Query("DELETE FROM notification_history")
    suspend fun deleteAll()
}
