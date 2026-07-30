package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable local audit trail for sensitive business actions. */
@Entity(
    tableName = "audit_events",
    indices = [Index(value = ["occurredAt"]), Index(value = ["entityType", "entityId"])]
)
data class AuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long = System.currentTimeMillis(),
    /** Future multi-user versions replace LOCAL_SYSTEM with the authenticated user ID. */
    val actor: String = "LOCAL_SYSTEM",
    val action: String,
    val entityType: String,
    val entityId: String,
    val reason: String? = null,
    val beforeSnapshot: String? = null,
    val afterSnapshot: String? = null
) {
    companion object {
        const val ACTION_PAYMENT_ACCEPTED = "PAYMENT_ACCEPTED"
        const val ACTION_CARD_ARCHIVED = "CARD_ARCHIVED"
        const val ACTION_CARD_TRANSACTION_REVERSED = "CARD_TRANSACTION_REVERSED"
        const val ACTION_RENT_TERMINATED = "RENT_TERMINATED"
        const val ACTION_RENT_RENEWED = "RENT_RENEWED"
        const val ACTION_CONTRACT_STATUS_CHANGED = "CONTRACT_STATUS_CHANGED"
        const val ACTION_RESTORE = "TIMELINE_RESTORE"
        const val ACTION_RENTER_CREATE = "RENTER_CREATE"
        const val ACTION_RENTER_UPDATE = "RENTER_UPDATE"
        const val ACTION_RENTER_DELETE = "RENTER_DELETE"
        const val ACTION_SCOOTER_CREATE = "SCOOTER_CREATE"
        const val ACTION_SCOOTER_UPDATE = "SCOOTER_UPDATE"
        const val ACTION_SCOOTER_DELETE = "SCOOTER_DELETE"
        const val ACTION_REPAIR_START = "REPAIR_START"
        const val ACTION_REPAIR_FINISH = "REPAIR_FINISH"
        const val ACTION_REPAIR_PAUSE = "REPAIR_PAUSE"
        const val ACTION_REPAIR_RESUME = "REPAIR_RESUME"
        const val ACTION_CONTRACT_CREATE = "CONTRACT_CREATE"
        const val ACTION_CONTRACT_UPDATE = "CONTRACT_UPDATE"
        const val ACTION_CONTRACT_DELETE = "CONTRACT_DELETE"
        const val ACTION_TRANSACTION_CREATE = "TRANSACTION_CREATE"
        const val ACTION_CARD_CREATE = "CARD_CREATE"
        const val ACTION_CARD_UPDATE = "CARD_UPDATE"
        const val ACTION_TRASH_RESTORE = "TRASH_RESTORE"
        const val ACTION_TRASH_PURGE = "TRASH_PURGE"

        // ── Batch 8 — audit-trail completeness ───────────────────────────
        // Previously these events were either unaudited or used a misleading
        // action code (e.g. updateCard reused ACTION_CARD_TRANSACTION_REVERSED
        // for a non-reversal balance edit). Each new constant closes one
        // silent-mutation gap found during the Batch 7→8 audit.
        /** Card was unarchived (reopened) by the user. */
        const val ACTION_CARD_UNARCHIVED = "CARD_UNARCHIVED"
        /** Lightweight FK-consistency sweep ran on DB open and repaired
         *  orphan rows (dangling FKs nulled, fully-orphan dependents deleted). */
        const val ACTION_ORPHAN_SWEEP = "ORPHAN_SWEEP"
        /** A restore path silently nulled out a stale card FK on a restored
         *  BusinessOperation because the referenced card was still in trash.
         *  Surfaces the previously-invisible data adjustment. */
        const val ACTION_TRASH_FK_NULLIFIED = "TRASH_FK_NULLIFIED"
    }
}
