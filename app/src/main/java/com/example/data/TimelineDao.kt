package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {
    @Query("SELECT * FROM timeline_branches ORDER BY isMain DESC, createdAt ASC")
    fun branches(): Flow<List<TimelineBranch>>

    @Query("SELECT * FROM timeline_branches WHERE id = :id LIMIT 1")
    suspend fun branchById(id: Long): TimelineBranch?

    @Query("SELECT * FROM timeline_branches WHERE isMain = 1 LIMIT 1")
    suspend fun mainBranch(): TimelineBranch?

    @Insert
    suspend fun insertBranch(branch: TimelineBranch): Long

    @Query("SELECT * FROM timeline_events WHERE branchId = :branchId AND isArchived = 0 ORDER BY timestamp ASC, id ASC")
    fun events(branchId: Long): Flow<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE branchId = :branchId AND timestamp <= :timestamp ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun nearestEvent(branchId: Long, timestamp: Long): TimelineEvent?

    @Insert
    suspend fun insertEvent(event: TimelineEvent): Long

    @Query("SELECT * FROM timeline_events WHERE id = :id LIMIT 1")
    suspend fun eventById(id: Long): TimelineEvent?

    @Query("UPDATE timeline_events SET isArchived = 1 WHERE id = :id")
    suspend fun archiveEvent(id: Long)

    @Query("UPDATE timeline_events SET isArchived = 0 WHERE id = :id")
    suspend fun unarchiveEvent(id: Long)

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    @Query("DELETE FROM timeline_events WHERE branchId = :branchId")
    suspend fun deleteEventsByBranch(branchId: Long)

    @Query("DELETE FROM timeline_branches WHERE id = :id")
    suspend fun deleteBranch(id: Long)

    @Query("UPDATE timeline_branches SET name = :name WHERE id = :id")
    suspend fun renameBranch(id: Long, name: String)

    @Query("SELECT * FROM timeline_snapshots WHERE branchId = :branchId AND timestamp <= :timestamp ORDER BY timestamp DESC LIMIT 1")
    suspend fun nearestSnapshot(branchId: Long, timestamp: Long): TimelineSnapshot?

    @Insert
    suspend fun insertSnapshot(snapshot: TimelineSnapshot): Long
}
