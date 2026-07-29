package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "renters")
data class Renter(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    /**
     * @deprecated Используется только для обратной совместимости со старыми
     * проекциями UI. Новая логика (§5) вычисляет долг как
     * `turnover − paid_total` из истории контрактов (см. ContractHistoryDao).
     * Не записывайте новые значения в это поле — используйте ContractHistoryEntry.
     */
    @Deprecated("Используй вычисление из ContractHistoryDao", ReplaceWith("0.0"))
    val debtAmount: Double = 0.0,
    val rentDurationDays: Int,
    val rentStartDateTimestamp: Long = System.currentTimeMillis(),
    val isReturned: Boolean = false,
    val isOverdueSmsSent: Boolean = false,
    val scooterId: Int? = null,
    val scooterName: String? = null,
    val lastPaymentTimestamp: Long? = null,
    /**
     * @deprecated Баланс теперь вычисляется по формуле `paid − turnover`
     * (см. ContractHistoryDao.getComputedBalance). Поле остаётся только
     * для совместимости со старым UI; новые оплаты НЕ должны его обновлять
     * напрямую — оно обновляется автоматически при изменении isPaid
     * на контрактах.
     */
    @Deprecated("Используй ContractHistoryDao.getComputedBalance", ReplaceWith("0.0"))
    val balance: Double = 0.0,

    // ── Реквизиты арендатора для PDF-договора ─────────────────────────────
    /** Паспорт: серия, номер, дата выдачи (свободная строка). */
    val passportData: String = "",
    /** Адрес проживания. */
    val address: String = "",
    /** ЖШШИР / ПИНФЛ. */
    val pinfl: String = ""

    // Примечание: реквизиты скутера (VIN, двигатель, ID, аккумы, доп. инфо)
    // хранятся на самой сущности Scooter и подтягиваются в ContractHistoryEntry
    // при создании контракта. Это правильно: они описывают скутер, а не арендатора.
)
