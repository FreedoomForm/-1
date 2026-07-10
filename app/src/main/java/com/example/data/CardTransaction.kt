package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Транзакция перевода между двумя виртуальными картами.
 *
 * При создании транзакции:
 *   • с карты-источника (fromCardId) списывается amount
 *   • на карту-назначения (toCardId) зачисляется amount
 *
 * Если тот же перевод нужно сделать в обратном направлении, пользователь
 * нажимает кнопку разворота стрелки в UI — тогда меняются местами fromCardId
 * и toCardId и создаётся новая транзакция (а не редактируется старая).
 *
 * type = CARD_TRANSFER для переводов между картами.
 * Зарезервированы: INCOME / EXPENSE на случай будущих расширений
 * (например, внешние поступления или траты без второй карты).
 */
@Entity(tableName = "card_transactions")
data class CardTransaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val fromCardId: Int,
    val toCardId: Int,
    val amount: Double,
    val note: String? = null,
    val type: String = TYPE_CARD_TRANSFER
) {
    companion object {
        const val TYPE_CARD_TRANSFER = "CARD_TRANSFER"
        const val TYPE_INCOME = "INCOME"
        const val TYPE_EXPENSE = "EXPENSE"
    }
}
