package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Exact tийин representation of legacy REAL fields. Legacy columns stay only
 * for transition/UI compatibility; reports and new operations use Long money.
 */
@Entity(
    tableName = "legacy_money_amounts",
    indices = [Index(value = ["entityType", "entityId", "field"], unique = true)]
)
data class LegacyMoneyAmount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val field: String,
    val amountMinor: Long,
    val migratedAt: Long = System.currentTimeMillis()
)
