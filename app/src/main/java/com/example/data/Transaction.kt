package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись о транзакции — отдельная сущность для страницы «Tranzaksiya».
 *
 * В отличие от [ContractHistoryEntry], которая хранит ВСЕ события по арендатору
 * (создание, оплаты, продления, расторжения, возвраты), эта сущность нужна
 * для ручного учёта произвольных транзакций: доплаты, штрафы, возвраты
 * залогов, ремонты и т.д. — того, что администратор хочет записать отдельно.
 *
 * Транзакция МОЖЕТ быть связана с контрактом (contractId) — тогда при выборе
 * контракта в диалоге создания все поля (renter, scooter) автозаполняются.
 * Но может быть и самостоятельной (contractId = null) — например, продажа
 * расходника, не привязанная к конкретному контракту.
 *
 * Все поля денормализованы (renterName, renterPhone, scooterName,
 * contractLabel) — для стабильности отображения даже после удаления
 * исходного арендатора/скутера/контракта.
 *
 * @property type один из TYPE_PAYMENT / TYPE_TERMINATED / TYPE_RETURNED /
 *           TYPE_PENALTY / TYPE_REPAIR / TYPE_CUSTOM. Влияет на цвет
 *           статус-линии строки в таблице.
 */
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** Связанный контракт (null для самостоятельной транзакции). */
    val contractId: Int? = null,
    /** Связанный арендатор (для быстрого JOIN'а и фильтрации). */
    val renterId: Int,
    val timestamp: Long = System.currentTimeMillis(),
    /** TYPE_PAYMENT / TYPE_TERMINATED / TYPE_RETURNED / TYPE_PENALTY /
     *  TYPE_REPAIR / TYPE_CUSTOM. */
    val type: String,
    val amount: Double = 0.0,
    val notes: String? = null,

    // ── Денормализованные поля для отображения ───────────────────────────
    val renterName: String = "",
    val renterPhone: String = "",
    val scooterName: String = "",
    /** Человекочитаемая подпись контракта, напр. "#123  01.07 → 08.07". */
    val contractLabel: String = ""
) {
    companion object {
        const val TYPE_PAYMENT    = "PAYMENT"
        const val TYPE_TERMINATED = "TERMINATED"
        const val TYPE_RETURNED   = "RETURNED"
        const val TYPE_PENALTY    = "PENALTY"
        const val TYPE_REPAIR     = "REPAIR"
        const val TYPE_CUSTOM     = "CUSTOM"
    }
}
