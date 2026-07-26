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
        const val ACTION_CONTRACT_STATUS_CHANGED = "CONTRACT_STATUS_CHANGED"
    }
}
