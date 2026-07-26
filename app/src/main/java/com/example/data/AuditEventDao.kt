package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditEventDao {
    @Insert
    suspend fun insert(event: AuditEvent): Long

    @Query("SELECT * FROM audit_events ORDER BY occurredAt DESC, id DESC")
    fun all(): Flow<List<AuditEvent>>

    @Query("SELECT * FROM audit_events WHERE entityType = :entityType AND entityId = :entityId ORDER BY occurredAt DESC")
    fun forEntity(entityType: String, entityId: String): Flow<List<AuditEvent>>

    @Query("SELECT * FROM audit_events ORDER BY id ASC")
    suspend fun getAllOnce(): List<AuditEvent>

    @Query("DELETE FROM audit_events")
    suspend fun clear()
}
