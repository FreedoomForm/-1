package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable link between a received payment and the rental period it settled. */
@Entity(
    tableName = "payment_allocations",
    indices = [Index(value = ["operationId"]), Index(value = ["rentPeriodId"])]
)
data class PaymentAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: Long,
    val rentPeriodId: Long,
    val amountMinor: Long,
    val createdAt: Long = System.currentTimeMillis()
)
