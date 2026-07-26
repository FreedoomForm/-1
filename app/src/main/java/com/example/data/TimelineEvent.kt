package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One renderable action/timestamp in a timeline branch. */
@Entity(
    tableName = "timeline_events",
    indices = [Index(value = ["branchId", "timestamp"]), Index(value = ["entityType", "entityId"])]
)
data class TimelineEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val branchId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: String,
    val screen: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val title: String,
    val payloadJson: String = "{}",
    val isMajor: Boolean = true,
    val isArchived: Boolean = false
)

/** Compact deterministic state used to render a historical application frame. */
@Entity(tableName = "timeline_snapshots", indices = [Index(value = ["branchId", "timestamp"]), Index(value = ["eventId"])])
data class TimelineSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val branchId: Long,
    val eventId: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val stateJson: String
)
