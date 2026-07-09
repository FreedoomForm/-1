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

    // ── Реквизиты скутера для PDF-договора и далолатнома ───────────────────
    /** VIN номер скутера. */
    val vinNumber: String = "",
    /** Номер двигателя. */
    val engineNumber: String = "",
    /** Серийный / внутренний ID номер скутера (отдельно от scooterId FK). */
    val scooterSerialNumber: String = "",
    /** ID первого аккумулятора. */
    val batteryId1: String = "",
    /** ID второго аккумулятора. */
    val batteryId2: String = "",
    /** Дополнительная информация (свободный текст). */
    val additionalInfo: String = ""
)
