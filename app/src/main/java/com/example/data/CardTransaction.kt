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
 * type = CARD_TRANSFER — ручной перевод между картами (с вкладки Finansi).
 * type = CONTRACT_INCOME — автоматическое зачисление платежа по контракту
 *   на главную карту (fromCardId = 0 = «внешний источник», toCardId = MAIN_CARD_ID).
 *   В этом случае fromCardId может быть 0 (внешний доход) — отображается как
 *   «Контракт» в UI вместо имени карты.
 */
@Entity(tableName = "card_transactions")
data class CardTransaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** Карта-источник. 0 = внешний источник (для CONTRACT_INCOME). */
    val fromCardId: Int,
    /** Карта-назначение. */
    val toCardId: Int,
    val amount: Double,
    val note: String? = null,
    val type: String = TYPE_CARD_TRANSFER
) {
    companion object {
        const val TYPE_CARD_TRANSFER = "CARD_TRANSFER"
        const val TYPE_CONTRACT_INCOME = "CONTRACT_INCOME"
        const val TYPE_EXPENSE = "EXPENSE"
        /** ID «внешнего источника» — используется в fromCardId для CONTRACT_INCOME. */
        const val EXTERNAL_SOURCE_ID = 0
    }
}
