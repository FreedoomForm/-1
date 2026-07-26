package com.example.data

import androidx.room.withTransaction
import org.json.JSONObject

/**
 * Records deterministic app actions and compact render snapshots. It never
 * deletes financial facts; restoration consumers must turn differences into
 * auditable business actions.
 */
class TimelineService(private val db: AppDatabase) {
    suspend fun ensureMainBranch(): TimelineBranch = db.withTransaction {
        db.timelineDao().mainBranch() ?: run {
            val id = db.timelineDao().insertBranch(TimelineBranch(name = "Main", isMain = true))
            db.timelineDao().branchById(id)!!
        }
    }

    suspend fun record(
        branchId: Long,
        actionType: String,
        screen: String,
        title: String,
        entityType: String? = null,
        entityId: String? = null,
        payloadJson: String = "{}",
        major: Boolean = true,
        timestamp: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        val eventId = db.timelineDao().insertEvent(TimelineEvent(
            branchId = branchId, timestamp = timestamp, actionType = actionType,
            screen = screen, title = title, entityType = entityType,
            entityId = entityId, payloadJson = payloadJson, isMajor = major
        ))
        if (major) db.timelineDao().insertSnapshot(TimelineSnapshot(
            branchId = branchId, eventId = eventId, timestamp = timestamp,
            stateJson = renderStateJson()
        ))
        eventId
    }

    suspend fun createBranch(parentBranchId: Long, atTimestamp: Long, name: String): Long = db.withTransaction {
        val fork = db.timelineDao().nearestEvent(parentBranchId, atTimestamp)
        db.timelineDao().insertBranch(TimelineBranch(
            name = name.ifBlank { "Branch ${System.currentTimeMillis()}" },
            parentBranchId = parentBranchId,
            forkEventId = fork?.id
        ))
    }

    suspend fun nearestRenderableState(branchId: Long, timestamp: Long): TimelineSnapshot? =
        db.timelineDao().nearestSnapshot(branchId, timestamp)

    private suspend fun renderStateJson(): String = JSONObject().apply {
        put("renters", db.renterDao().getAllRentersOnce().size)
        put("scooters", db.scooterDao().getAllScootersOnce().size)
        put("periods", db.rentPeriodDao().getAllOnce().size)
        put("operations", db.businessOperationDao().getAllOnce().size)
        put("cards", db.virtualCardDao().getAllCardsOnce().size)
    }.toString()
}
