package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scooters")
data class Scooter(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    /** Документированный номер (из техпаспорта / гос. регистрации). */
    val documentedNumber: String? = null,

    // ── Реквизиты скутера для PDF-договора и далолатнома ───────────────────
    /** VIN номер скутера. */
    val vinNumber: String = "",
    /** Номер двигателя. */
    val engineNumber: String = "",
    /** Серийный / внутренний ID номер скутера (отдельно от scooterId PK). */
    val scooterSerialNumber: String = "",
    /** ID первого аккумулятора. */
    val batteryId1: String = "",
    /** ID второго аккумулятора. */
    val batteryId2: String = "",
    /** Дополнительная информация (свободный текст). */
    val additionalInfo: String = "",
    /** AVAILABLE / RESERVED / RENTED / SERVICE / REPAIR / RETIRED. */
    val lifecycleStatus: String = STATUS_AVAILABLE,
    val lastServiceAt: Long? = null,
    val nextServiceAt: Long? = null,
    /** §4: текущий пробег скутера в км (обновляется при акте возврата). */
    val mileageKm: Long = 0L
) {
    companion object {
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_RESERVED = "RESERVED"
        const val STATUS_RENTED = "RENTED"
        const val STATUS_SERVICE = "SERVICE"
        const val STATUS_REPAIR = "REPAIR"
        const val STATUS_RETIRED = "RETIRED"
    }
}
