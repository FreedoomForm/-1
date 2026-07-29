package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named path through the application's recoverable state timeline. */
@Entity(tableName = "timeline_branches", indices = [Index(value = ["parentBranchId"]), Index(value = ["createdAt"])])
data class TimelineBranch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentBranchId: Long? = null,
    val forkEventId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isMain: Boolean = false
)
