package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletedItemDao {
    @Query("SELECT * FROM deleted_items ORDER BY deletedAt DESC, id DESC")
    fun all(): Flow<List<DeletedItem>>

    @Query("SELECT * FROM deleted_items WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): DeletedItem?

    @Insert
    suspend fun insert(item: DeletedItem): Long

    @Query("DELETE FROM deleted_items WHERE id = :id")
    suspend fun purge(id: Long)
}
