package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Неизменяемый универсальный журнал хозяйственных операций.
 *
 * Это единственный формат для новых денежных движений. Сумма хранится в
 * тийинах (1/100 сума) как Long, поэтому расчёты не зависят от Double.
 * Операция не удаляется и не редактируется: исправление оформляется новой
 * сторнирующей операцией со ссылкой [reversesOperationId].
 */
@Entity(
    tableName = "business_operations",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["renterId"]),
        Index(value = ["contractId"]),
        Index(value = ["cardTransactionId"]),
        Index(value = ["status"])
    ]
)
data class BusinessOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long = System.currentTimeMillis(),
    /** RENT_PAYMENT, EXPENSE, PENALTY, DEPOSIT, REFUND, TRANSFER, ADJUSTMENT. */
    val type: String,
    /** INCOME / EXPENSE / TRANSFER / LIABILITY. */
    val direction: String,
    /** Всегда положительная сумма в тийинах. Знак задаётся direction. */
    val amountMinor: Long,
    val renterId: Int? = null,
    val scooterId: Int? = null,
    val contractId: Int? = null,
    val fromCardId: Int? = null,
    val toCardId: Int? = null,
    val cardTransactionId: Int? = null,
    val legacyTransactionId: Int? = null,
    val note: String? = null,
    /** ACTIVE / REVERSED. */
    val status: String = STATUS_ACTIVE,
    /** ID исходной операции, если данная операция является сторно. */
    val reversesOperationId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_REVERSED = "REVERSED"
        const val TYPE_RENT_PAYMENT = "RENT_PAYMENT"
        const val TYPE_TRANSFER = "TRANSFER"
        const val TYPE_EXPENSE = "EXPENSE"
        const val TYPE_PENALTY = "PENALTY"
        const val TYPE_DEPOSIT = "DEPOSIT"
        const val TYPE_REFUND = "REFUND"
        const val TYPE_ADJUSTMENT = "ADJUSTMENT"
        const val DIRECTION_INCOME = "INCOME"
        const val DIRECTION_EXPENSE = "EXPENSE"
        const val DIRECTION_TRANSFER = "TRANSFER"
        const val DIRECTION_LIABILITY = "LIABILITY"

        fun toMinor(amount: Double): Long =
            amount.toBigDecimal().movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()

        fun fromMinor(amountMinor: Long): Double = amountMinor / 100.0
    }
}
