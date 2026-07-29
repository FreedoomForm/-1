package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Structured repair work order; complements financial repair expense entries. */
@Entity(
    tableName = "repair_orders",
    indices = [Index(value = ["scooterId", "status"]), Index(value = ["openedAt"])]
)
data class RepairOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scooterId: Int,
    val renterId: Int? = null,
    /** RENTER_REPAIR / OWNER_REPAIR / REPLACEMENT / RETIREMENT. */
    val scenario: String,
    val status: String = STATUS_OPEN,
    val openedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val diagnosis: String,
    val performer: String? = null,
    /** Free-text list of parts / batteries replaced. */
    val partsUsed: String? = null,
    val estimatedMinor: Long = 0,
    val actualMinor: Long = 0,
    val documentNote: String? = null,
    // ── §8: частичный ремонт — несколько пауз внутри одного order ──────
    /** JSON-массив пар [startMs, endMs] — история пауз ремонта. */
    val pauseIntervalsJson: String = "[]",
    /** Суммарная длительность всех пауз в ms — на эту величину продлевается контракт. */
    val totalPauseMs: Long = 0L,
    /** true если ремонт сейчас на паузе (pause start without resume). */
    val currentlyPaused: Boolean = false,
    /** Timestamp последней паузы (или null если ремонт не на паузе). */
    val lastPausedAt: Long? = null
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val SCENARIO_RENTER_REPAIR = "RENTER_REPAIR"
        const val SCENARIO_OWNER_REPAIR = "OWNER_REPAIR"
        const val SCENARIO_REPLACEMENT = "REPLACEMENT"
        const val SCENARIO_RETIREMENT = "RETIREMENT"
    }
}
