package com.example.data

import androidx.room.withTransaction
import org.json.JSONObject

/**
 * Records deterministic app actions and compact render snapshots. It never
 * deletes financial facts; restoration consumers must turn differences into
 * auditable business actions.
 *
 * Per PLAN_UNIVERSAL_ACCOUNTING §9.0:
 *  - Auto-snapshot after every major event (already in `record()`).
 *  - Safe restore: pick timestamp → nearest snapshot → record a RESTORE event
 *    referencing the snapshot. Financial facts are NOT erased; the restore is
 *    itself an auditable timeline event.
 *  - Critical action timcodes: addRenter/updateRenter/deleteRenter,
 *    addScooter/updateScooter/deleteScooter, contract create/update/delete,
 *    card create/transfer/delete, payment, repair start/finish, restore.
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

    /**
     * Unarchives a single timeline event AND records a RESTORE audit event
     * so the financial trail stays intact. Used by the "Вернуть объект"
     * secondary button next to the branch name on the history screen.
     */
    suspend fun unarchiveEvent(event: TimelineEvent, actor: String = "owner"): Long? = db.withTransaction {
        db.timelineDao().unarchiveEvent(event.id)
        val restoreEventId = db.timelineDao().insertEvent(TimelineEvent(
            branchId = event.branchId,
            timestamp = System.currentTimeMillis(),
            actionType = "RESTORE_OBJECT",
            screen = "HISTORY",
            title = "Restore object: ${event.title}",
            entityType = event.entityType,
            entityId = event.entityId,
            payloadJson = JSONObject().apply {
                put("sourceEventId", event.id)
                put("actor", actor)
            }.toString(),
            isMajor = true
        ))
        db.auditEventDao().insert(AuditEvent(
            occurredAt = System.currentTimeMillis(),
            actor = actor,
            action = AuditEvent.ACTION_RESTORE,
            entityType = event.entityType ?: "TIMELINE_EVENT",
            entityId = event.entityId ?: event.id.toString(),
            reason = "Manual unarchive from history tree",
            beforeSnapshot = "archived",
            afterSnapshot = "restored"
        ))
        restoreEventId
    }

    /**
     * Permanently deletes a timeline event AND — if the event references a
     * business entity (renter, scooter, contract, card, transaction) whose
     * actionType is a DELETE-type — attempts to permanently delete that
     * entity too. Used by the universal 🗑 button when a deletion-group
     * timecode is selected on the history screen.
     *
     * Returns true if the entity was deleted, false if only the event was.
     */
    suspend fun permanentlyDeleteReferencedObject(event: TimelineEvent, actor: String = "owner"): Boolean = db.withTransaction {
        db.timelineDao().deleteEvent(event.id)
        val deleted = event.entityId?.let { entityId ->
            val idLong = entityId.toLongOrNull() ?: return@let false
            val type = event.entityType?.uppercase() ?: ""
            when (type) {
                "RENTER" -> { db.renterDao().deleteById(idLong.toInt()); true }
                "SCOOTER" -> { db.scooterDao().deleteScooterById(idLong.toInt()); true }
                else -> false
            }
        } ?: false
        db.auditEventDao().insert(AuditEvent(
            occurredAt = System.currentTimeMillis(),
            actor = actor,
            action = "DELETE_TIMELINE_EVENT",
            entityType = event.entityType ?: "TIMELINE_EVENT",
            entityId = event.entityId ?: event.id.toString(),
            reason = "Permanent delete from history tree",
            beforeSnapshot = "event=${event.id}",
            afterSnapshot = if (deleted) "event+entity deleted" else "event deleted only"
        ))
        deleted
    }

    /**
     * Renames an existing branch. Used by the universal ✎ button when a
     * block from a non-main branch is selected on the history tree.
     */
    suspend fun renameBranch(branchId: Long, newName: String) {
        if (newName.isNotBlank()) db.timelineDao().renameBranch(branchId, newName)
    }

    /**
     * Permanently deletes a branch and all its events. The Main branch
     * (isMain = true) cannot be deleted — this method will throw.
     * Used by the universal 🗑 button when a block from a non-main branch
     * is selected on the history tree.
     */
    suspend fun deleteBranch(branchId: Long, actor: String = "owner") = db.withTransaction {
        val branch = db.timelineDao().branchById(branchId) ?: return@withTransaction
        require(!branch.isMain) { "Cannot delete the Main branch" }
        // Cascade: delete the branch's events first, then the branch row.
        db.timelineDao().deleteEventsByBranch(branchId)
        db.timelineDao().deleteBranch(branchId)
        db.auditEventDao().insert(AuditEvent(
            occurredAt = System.currentTimeMillis(),
            actor = actor,
            action = "DELETE_TIMELINE_BRANCH",
            entityType = "TIMELINE_BRANCH",
            entityId = branchId.toString(),
            reason = "Manual branch delete from history tree",
            beforeSnapshot = "branch=$branchId",
            afterSnapshot = "deleted"
        ))
    }

    /**
     * Safe restore: never erases financial facts. Records a RESTORE event
     * referencing the nearest snapshot before/at [targetTimestamp]. The
     * actual business state is not mutated here — the consumer reads the
     * snapshot's stateJson to render the historical frame. Financial
     * reconciliation (if any) must happen via storno operations.
     *
     * Returns the RESTORE event id, or null if no snapshot found.
     */
    suspend fun restoreToSnapshot(
        branchId: Long,
        targetTimestamp: Long,
        reason: String,
        actor: String = "owner"
    ): Long? = db.withTransaction {
        val snapshot = db.timelineDao().nearestSnapshot(branchId, targetTimestamp) ?: return@withTransaction null
        val restoreEventId = db.timelineDao().insertEvent(TimelineEvent(
            branchId = branchId,
            timestamp = System.currentTimeMillis(),
            actionType = "RESTORE",
            screen = "HISTORY",
            title = "Restore to ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(targetTimestamp))}",
            entityType = "TIMELINE_SNAPSHOT",
            entityId = snapshot.id.toString(),
            payloadJson = JSONObject().apply {
                put("targetTimestamp", targetTimestamp)
                put("snapshotId", snapshot.id)
                put("snapshotTimestamp", snapshot.timestamp)
                put("reason", reason)
                put("actor", actor)
                put("snapshotState", snapshot.stateJson)
            }.toString(),
            isMajor = true
        ))
        // Snapshot of the post-restore state so user can navigate back here too.
        db.timelineDao().insertSnapshot(TimelineSnapshot(
            branchId = branchId,
            eventId = restoreEventId,
            timestamp = System.currentTimeMillis(),
            stateJson = renderStateJson()
        ))
        // Audit trail entry.
        db.auditEventDao().insert(AuditEvent(
            occurredAt = System.currentTimeMillis(),
            actor = actor,
            action = AuditEvent.ACTION_RESTORE,    // see AuditEvent for action constants
            entityType = "TIMELINE_SNAPSHOT",
            entityId = snapshot.id.toString(),
            reason = reason,
            beforeSnapshot = "current",
            afterSnapshot = "snapshot=${snapshot.id}@${snapshot.timestamp}"
        ))
        restoreEventId
    }

    /**
     * Records a critical-action timcode (renter/scooter/contract/transaction/
     * card/payment/repair/restore create/update/delete). Per §9.0.
     * Convenience wrapper so callers don't need to look up the main branch
     * each time. Skipped silently if the main branch doesn't exist yet.
     */
    suspend fun recordCriticalAction(
        actionType: String,
        screen: String,
        title: String,
        entityType: String,
        entityId: String,
        payloadJson: String = "{}",
        major: Boolean = true
    ): Long? = db.withTransaction {
        val main = db.timelineDao().mainBranch() ?: return@withTransaction null
        record(
            branchId = main.id,
            actionType = actionType,
            screen = screen,
            title = title,
            entityType = entityType,
            entityId = entityId,
            payloadJson = payloadJson,
            major = major
        )
    }

    private suspend fun renderStateJson(): String = JSONObject().apply {
        put("renters", db.renterDao().getAllRentersOnce().size)
        put("scooters", db.scooterDao().getAllScootersOnce().size)
        put("periods", db.rentPeriodDao().getAllOnce().size)
        put("operations", db.businessOperationDao().getAllOnce().size)
        put("cards", db.virtualCardDao().getAllCardsOnce().size)
    }.toString()
}
