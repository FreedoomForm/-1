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

    /**
     * Used by TrashService.restore TYPE_RENTER path to rewrite the renterId
     * inside dependent TYPE_CONTRACT / TYPE_TRANSACTION snapshots so they
     * point to the newly-restored renter's id. Without this rewrite, the
     * renter-existence check in those restore paths would fail and the
     * dependent items would stay stuck in the recycle bin forever.
     */
    @Query("UPDATE deleted_items SET snapshotJson = :snapshotJson WHERE id = :id")
    suspend fun updateSnapshot(id: Long, snapshotJson: String)

    /** Returns ALL deleted items regardless of type, used by the renter-restore
     *  snapshot-rewrite pass. Ordered by id ASC for deterministic processing. */
    @Query("SELECT * FROM deleted_items ORDER BY id ASC")
    suspend fun getAllOnceInclTrash(): List<DeletedItem>

    @Query("DELETE FROM deleted_items WHERE id = :id")
    suspend fun purge(id: Long)
}
