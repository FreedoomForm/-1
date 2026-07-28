package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A billable interval of one rental agreement. Unlike legacy contract history,
 * this record has a precise outstanding amount and an explicit lifecycle.
 */
@Entity(
    tableName = "rent_periods",
    indices = [
        Index(value = ["contractHistoryId"], unique = true),
        Index(value = ["renterId", "status"]),
        Index(value = ["scooterId", "startsAt", "endsAt"])
    ]
)
data class RentPeriod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Legacy contract_history ID during transition; null only for new native periods. */
    val contractHistoryId: Int? = null,
    val renterId: Int,
    val scooterId: Int? = null,
    val startsAt: Long,
    val endsAt: Long,
    val chargeMinor: Long,
    val paidMinor: Long = 0,
    val status: String = STATUS_SCHEDULED,
    /** Non-null while scooter repair pauses this billable period. */
    val suspendedAt: Long? = null,
    val suspensionReason: String? = null,
    /** 
     * For repair periods: the original period ID this repair period was created from.
     * Used to link repair breaks to their parent rental period.
     */
    val parentPeriodId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_SCHEDULED = "SCHEDULED"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PARTIALLY_PAID = "PARTIALLY_PAID"
        const val STATUS_PAID = "PAID"
        const val STATUS_OVERDUE = "OVERDUE"
        /** Scooter is being repaired; no rental charge accrues during pause. */
        const val STATUS_SUSPENDED_REPAIR = "SUSPENDED_REPAIR"
        /**
         * Repair break period - renter keeps the scooter but doesn't pay.
         * Created when user long-taps on calendar dates.
         * The scooter remains assigned to this renter during repair.
         */
        const val STATUS_REPAIR_BREAK = "REPAIR_BREAK"
        const val STATUS_CLOSED = "CLOSED"
        /** Rental is closed and scooter released, but receivable remains collectible. */
        const val STATUS_CLOSED_WITH_DEBT = "CLOSED_WITH_DEBT"
        const val STATUS_CANCELLED = "CANCELLED"
        
        /** All statuses where renter doesn't pay but keeps the scooter */
        val NON_BILLABLE_STATUSES = setOf(STATUS_SUSPENDED_REPAIR, STATUS_REPAIR_BREAK, STATUS_CANCELLED)
        
        /** All statuses where period is active/ongoing */
        val ACTIVE_STATUSES = setOf(STATUS_ACTIVE, STATUS_PARTIALLY_PAID, STATUS_OVERDUE, STATUS_REPAIR_BREAK)
    }

    val outstandingMinor: Long get() = (chargeMinor - paidMinor).coerceAtLeast(0)
    
    /** True if this period is a repair break where no payment is required */
    val isRepairBreak: Boolean get() = status == STATUS_REPAIR_BREAK
    
    /** True if this is a non-billable period (repair, suspended, cancelled) */
    val isNonBillable: Boolean get() = status in NON_BILLABLE_STATUSES
}
