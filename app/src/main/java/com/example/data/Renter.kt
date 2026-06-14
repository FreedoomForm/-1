package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "renters")
data class Renter(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    val debtAmount: Double = 0.0,
    val rentDurationDays: Int,
    val rentStartDateTimestamp: Long = System.currentTimeMillis(),
    val isReturned: Boolean = false,
    val isOverdueSmsSent: Boolean = false,
    val scooterId: Int? = null,
    val scooterName: String? = null,
    val lastPaymentTimestamp: Long? = null,
    /** Баланс арендатора: < 0 — должен нам, > 0 — аванс. */
    val balance: Double = 0.0
)
