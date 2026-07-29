package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable delivery log for automated payment reminders. */
@Entity(tableName = "sms_deliveries", indices = [Index(value = ["renterId", "timestamp"]), Index(value = ["status"])])
data class SmsDelivery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val renterId: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String,
    val messagePreview: String,
    val error: String? = null
) {
    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
