package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Recycle-bin entry. The source is stored as JSON so it can be restored even
 * after the user-facing projection was removed from legacy tables.
 */
@Entity(
    tableName = "deleted_items",
    indices = [Index(value = ["sourceType"]), Index(value = ["deletedAt"])]
)
data class DeletedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,
    val sourceId: String,
    val title: String,
    val snapshotJson: String,
    val deletedAt: Long = System.currentTimeMillis(),
    val deletedBy: String = "Owner",
    val reason: String? = null
) {
    companion object {
        const val TYPE_RENTER = "RENTER"
        const val TYPE_CONTRACT = "CONTRACT"
        const val TYPE_TRANSACTION = "TRANSACTION"
        const val TYPE_CARD = "CARD"
        const val TYPE_HISTORY_BRANCH = "HISTORY_BRANCH"
    }
}
