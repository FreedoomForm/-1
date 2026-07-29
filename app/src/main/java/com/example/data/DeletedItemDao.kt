package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletedItemDao {
    @Query("SELECT * FROM deleted_items ORDER BY deletedAt DESC, id DESC")
    fun all(): Flow<List<DeletedItem>>

    /** Used by BackupManager to export the recycle bin verbatim. */
    @Query("SELECT * FROM deleted_items ORDER BY id ASC")
    suspend fun getAllOnce(): List<DeletedItem>

    /** Used by BackupManager to truncate before re-import. */
    @Query("DELETE FROM deleted_items")
    suspend fun deleteAll()

    @Query("SELECT * FROM deleted_items WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): DeletedItem?

    @Insert
    suspend fun insert(item: DeletedItem): Long

    @Query("UPDATE deleted_items SET reason = :reason WHERE id = :id")
    suspend fun updateReason(id: Long, reason: String?)

    @Query("DELETE FROM deleted_items WHERE id = :id")
    suspend fun purge(id: Long)
}
