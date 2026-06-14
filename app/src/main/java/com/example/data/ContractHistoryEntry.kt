package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись в истории контракта арендатора.
 * Создаётся при: создании арендатора, оплате, продлении, разрыве контракта.
 */
@Entity(tableName = "contract_history")
data class ContractHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val renterId: Int,
    val timestamp: Long = System.currentTimeMillis(),
    /** CREATED / PAYMENT / AUTO_RENEW / TERMINATED / RETURNED */
    val type: String,
    val amount: Double = 0.0,
    val notes: String? = null
) {
    companion object {
        const val TYPE_CREATED = "CREATED"
        const val TYPE_PAYMENT = "PAYMENT"
        const val TYPE_AUTO_RENEW = "AUTO_RENEW"
        const val TYPE_TERMINATED = "TERMINATED"
        const val TYPE_RETURNED = "RETURNED"
    }
}
