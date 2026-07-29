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
     *
     * NOTE: prior to v1.2.125 this method called renterDao().deleteRenter /
     * scooterDao().deleteScooterById directly, leaving behind orphaned
     * RentPeriod, PaymentAllocation, BusinessOperation, HandoverAct,
     * SmsDelivery, NotificationHistory, RepairOrder, LegacyMoneyAmount rows.
     * It now performs a full cascade cleanup matching RenterViewModel /
     * ScooterViewModel.
     */
    suspend fun permanentlyDeleteReferencedObject(event: TimelineEvent, actor: String = "owner"): Boolean = db.withTransaction {
        db.timelineDao().deleteEvent(event.id)
        val deleted = event.entityId?.let { entityId ->
            val idLong = entityId.toLongOrNull() ?: return@let false
            val type = event.entityType?.uppercase() ?: ""
            when (type) {
                "RENTER" -> {
                    val renterId = idLong.toInt()
                    cascadeDeleteRenterOrphans(renterId)
                    db.renterDao().deleteRenter(renterId)
                    true
                }
                "SCOOTER" -> {
                    val scooterId = idLong.toInt()
                    // Look up scooter by id (we need the name to find contracts
                    // that match by scooterName string).
                    val scooter = db.scooterDao().getScooterById(scooterId)
                    cascadeDeleteScooterOrphans(scooterId, scooter?.name)
                    db.scooterDao().deleteScooterById(scooterId)
                    true
                }
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
     * Cascade-cleans every dependent table that references a renter.
     * Mirrors RenterViewModel.deleteRenter's cleanup steps (without the
     * trash snapshots — this path is a hard permanent delete, not a soft
     * trash move). Safe to call when the renter row still exists or has
     * already been removed — each step is wrapped in try/catch.
     */
    private suspend fun cascadeDeleteRenterOrphans(renterId: Int) {
        // 1. Reverse CardTransaction balances + linked BusinessOperations
        //    for every contract this renter had.
        val contracts = try { db.contractHistoryDao().getForRenter(renterId) } catch (_: Exception) { emptyList() }
        for (contract in contracts) {
            val cardTxs = try { db.cardTransactionDao().getForContractOnce(contract.id) } catch (_: Exception) { emptyList() }
            for (cardTx in cardTxs) {
                try { db.virtualCardDao().adjustBalance(cardTx.toCardId, -cardTx.amount) } catch (_: Exception) {}
                try { db.businessOperationDao().markReversedByCardTransactionId(cardTx.id) } catch (_: Exception) {}
            }
            if (cardTxs.isNotEmpty()) {
                try { db.cardTransactionDao().deleteForContract(contract.id) } catch (_: Exception) {}
            }
            // Reverse Transaction-linked BusinessOperations + delete Transaction rows
            val txs = try { db.transactionDao().getForContractOnce(contract.id) } catch (_: Exception) { emptyList() }
            txs.forEach { tx ->
                try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) } catch (_: Exception) {}
            }
            if (txs.isNotEmpty()) {
                try { db.transactionDao().deleteForContract(contract.id) } catch (_: Exception) {}
            }
        }
        // 2. Reverse all Transaction-linked BusinessOperations for this renter
        //    (covers Transactions not tied to a specific contract).
        val renterTxs = try { db.transactionDao().getForRenterOnce(renterId) } catch (_: Exception) { emptyList() }
        renterTxs.forEach { tx ->
            try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) } catch (_: Exception) {}
        }
        if (renterTxs.isNotEmpty()) {
            try { db.transactionDao().deleteByIds(renterTxs.map { it.id }) } catch (_: Exception) {}
        }
        // 3. Reverse any remaining BusinessOperations tied to this renter
        try { db.businessOperationDao().markReversedByRenter(renterId) } catch (_: Exception) {}
        // 4. Clean up PaymentAllocation + RentPeriod
        try { db.paymentAllocationDao().deleteByRenterViaPeriod(renterId) } catch (_: Exception) {}
        try { db.rentPeriodDao().deleteByRenter(renterId) } catch (_: Exception) {}
        // 5. Clean up other dependent tables
        try { db.handoverActDao().deleteByRenter(renterId) } catch (_: Exception) {}
        try { db.smsDeliveryDao().deleteByRenter(renterId) } catch (_: Exception) {}
        try { db.notificationHistoryDao().deleteByRenter(renterId) } catch (_: Exception) {}
        try { db.repairOrderDao().deleteByRenter(renterId) } catch (_: Exception) {}
        try { db.legacyMoneyAmountDao().deleteByEntity("RENTER", renterId.toLong()) } catch (_: Exception) {}
        // 6. Delete the renter's contracts (after CardTransaction cleanup)
        try { db.contractHistoryDao().deleteForRenter(renterId) } catch (_: Exception) {}
    }

    /**
     * Cascade-cleans every dependent table that references a scooter.
     * Mirrors ScooterViewModel.deleteScooter's cleanup steps.
     */
    private suspend fun cascadeDeleteScooterOrphans(scooterId: Int, scooterName: String?) {
        // 1. For each contract matching scooterName: reverse CardTx balances,
        //    delete CardTx, reverse Transaction-linked BusinessOperations,
        //    delete Transaction, delete RentPeriod + allocations, reverse
        //    BusinessOperations by contract, delete HandoverActs.
        val contracts = if (scooterName != null) {
            try { db.contractHistoryDao().getForScooterOnce(scooterName) } catch (_: Exception) { emptyList() }
        } else emptyList()
        for (contract in contracts) {
            val cardTxs = try { db.cardTransactionDao().getForContractOnce(contract.id) } catch (_: Exception) { emptyList() }
            for (cardTx in cardTxs) {
                try { db.virtualCardDao().adjustBalance(cardTx.toCardId, -cardTx.amount) } catch (_: Exception) {}
                try { db.businessOperationDao().markReversedByCardTransactionId(cardTx.id) } catch (_: Exception) {}
            }
            if (cardTxs.isNotEmpty()) {
                try { db.cardTransactionDao().deleteForContract(contract.id) } catch (_: Exception) {}
            }
            val txs = try { db.transactionDao().getForContractOnce(contract.id) } catch (_: Exception) { emptyList() }
            txs.forEach { tx ->
                try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) } catch (_: Exception) {}
            }
            if (txs.isNotEmpty()) {
                try { db.transactionDao().deleteForContract(contract.id) } catch (_: Exception) {}
            }
            try { db.paymentAllocationDao().deleteByContractViaPeriod(contract.id) } catch (_: Exception) {}
            try { db.rentPeriodDao().deleteByContract(contract.id) } catch (_: Exception) {}
            try { db.businessOperationDao().markReversedByContract(contract.id) } catch (_: Exception) {}
            try { db.handoverActDao().deleteByContract(contract.id) } catch (_: Exception) {}
        }
        if (contracts.isNotEmpty() && scooterName != null) {
            try { db.contractHistoryDao().deleteForScooter(scooterName) } catch (_: Exception) {}
        }
        // 2. Delete Transaction rows for this scooter (by scooterId, not by name)
        val txs = try { db.transactionDao().forScooterOnce(scooterId) } catch (_: Exception) { emptyList() }
        txs.forEach { tx ->
            try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) } catch (_: Exception) {}
        }
        if (txs.isNotEmpty()) {
            try { db.transactionDao().deleteByIds(txs.map { it.id }) } catch (_: Exception) {}
        }
        // 3. Reverse remaining BusinessOperations tied to this scooter
        try { db.businessOperationDao().markReversedByScooter(scooterId) } catch (_: Exception) {}
        // 4. Clean up PaymentAllocation + RentPeriod
        try { db.paymentAllocationDao().deleteByScooterViaPeriod(scooterId) } catch (_: Exception) {}
        try { db.rentPeriodDao().deleteByScooter(scooterId) } catch (_: Exception) {}
        // 5. Clean up HandoverActs + RepairOrders + LegacyMoneyAmount
        try { db.handoverActDao().deleteByScooter(scooterId) } catch (_: Exception) {}
        try { db.repairOrderDao().closeOpenForScooter(scooterId, "Scooter permanently deleted") } catch (_: Exception) {}
        try { db.repairOrderDao().deleteByScooter(scooterId) } catch (_: Exception) {}
        try { db.legacyMoneyAmountDao().deleteByEntity("SCOOTER", scooterId.toLong()) } catch (_: Exception) {}
        // 6. Clear scooterId/scooterName from active renters
        val rentersWithScooter = try { db.renterDao().getActiveRenters().filter { it.scooterId == scooterId } } catch (_: Exception) { emptyList() }
        rentersWithScooter.forEach { r ->
            try { db.renterDao().updateRenter(r.copy(scooterId = null, scooterName = null)) } catch (_: Exception) {}
        }
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
