package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись о контракте / событии в истории арендатора.
 *
 * Создаётся при:
 *  • CREATED     — первичном создании арендатора (одна запись на весь срок аренды)
 *  • PAYMENT     — поступлении оплаты (баланс += amount)
 *  • AUTO_RENEW  — автоматическом продлении на 1 неделю (баланс -= amount, появляются N контрактов)
 *  • TERMINATED  — досрочном расторжении
 *  • RETURNED    — возврате скутера
 *
 * Поля `renterName`, `renterPhone`, `scooterName`, `weekStart`, `weekEnd` и все
 * `passport*` / `address` / `pinfl` / `vin*` / `engine*` / `battery*` поля
 * денормализованы специально для генерации PDF-документа по контракту —
 * даже если арендатор/скутер будет удалён, PDF всё равно можно сгенерировать
 * корректно без пустых полей.
 */
@Entity(tableName = "contract_history")
data class ContractHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val renterId: Int,
    val timestamp: Long = System.currentTimeMillis(),
    /** CREATED / PAYMENT / AUTO_RENEW / TERMINATED / RETURNED */
    val type: String,
    val amount: Double = 0.0,
    val notes: String? = null,

    // ── Денормализованные поля для PDF ────────────────────────────────────
    val renterName: String = "",
    val renterPhone: String = "",
    val scooterName: String? = null,
    /** Начало недели (для AUTO_RENEW) или дата начала аренды (для CREATED). */
    val weekStart: Long? = null,
    /** Конец недели (для AUTO_RENEW) или дата окончания аренды (для CREATED). */
    val weekEnd: Long? = null,
    /** Использованная недельная ставка на момент создания записи. */
    val weeklyPrice: Double = 0.0,

    // ── Реквизиты арендатора (для PDF) ────────────────────────────────────
    val passportData: String = "",
    val address: String = "",
    val pinfl: String = "",

    // ── Реквизиты скутера (для PDF) ───────────────────────────────────────
    val vinNumber: String = "",
    val engineNumber: String = "",
    val scooterSerialNumber: String = "",
    val batteryId1: String = "",
    val batteryId2: String = "",
    val additionalInfo: String = ""
) {
    companion object {
        const val TYPE_CREATED = "CREATED"
        const val TYPE_PAYMENT = "PAYMENT"
        const val TYPE_AUTO_RENEW = "AUTO_RENEW"
        const val TYPE_TERMINATED = "TERMINATED"
        const val TYPE_RETURNED = "RETURNED"
    }
}
