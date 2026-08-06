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
    val balance: Double = 0.0,

    // ── Реквизиты арендатора для PDF-договора ─────────────────────────────
    /** Паспорт: серия, номер, дата выдачи (свободная строка). */
    val passportData: String = "",
    /** Адрес проживания. */
    val address: String = "",
    /** ЖШШИР / ПИНФЛ. */
    val pinfl: String = "",

    // ── Режим авто-продления контракта ────────────────────────────────────
    // "MANUAL" — система НЕ создаёт контракты автоматически при окончании
    //            последнего контракта по дате. Пользователь создаёт сам.
    // "AUTO"   — система автоматически создаёт новый контракт (AUTO_RENEW)
    //            при наступлении дня окончания последнего контракта.
    //
    // Добавлено по запросу пользователя: в форме создания/редактирования
    // арендатора добавлен переключатель «Статус» (Manual/Auto). При Manual
    // PaymentCheckWorker пропускает арендатора, при Auto — продлевает как
    // раньше. Значение по умолчанию — "AUTO" (автоматическое создание).
    val autoRenewMode: String = RenterAutoRenewMode.AUTO,

    // Примечание: реквизиты скутера (VIN, двигатель, ID, аккумы, доп. инфо)
    // хранятся на самой сущности Scooter и подтягиваются в ContractHistoryEntry
    // при создании контракта. Это правильно: они описывают скутер, а не арендатора.
)

/** Возможные значения [Renter.autoRenewMode]. */
object RenterAutoRenewMode {
    const val MANUAL = "MANUAL"
    const val AUTO = "AUTO"
}
