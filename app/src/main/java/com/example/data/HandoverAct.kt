package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §4: Акт выдачи/возврата скутера.
 *
 * Записывается каждый раз, когда скутер передаётся арендатору (HANDOVER)
 * или возвращается от арендатора (RETURN). Содержит:
 *   • дату и тип акта
 *   • показания одометра (пробег в км) на момент выдачи/возврата
 *   • комплектацию — свободный текст или список (аккумуляторы, ключи, шлем и т.д.)
 *   • замечания / состояние скутера
 *   • ссылку на контракт (contractHistoryId) и арендатора
 *
 * Это отдельная сущность, чтобы сохранять историю всех передач скутера.
 */
@Entity(
    tableName = "handover_acts",
    indices = [
        Index(value = ["renterId", "actType"]),
        Index(value = ["scooterId", "timestamp"]),
        Index(value = ["contractHistoryId"])
    ]
)
data class HandoverAct(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** HANDOVER (выдача) или RETURN (возврат). */
    val actType: String,
    val renterId: Int,
    val scooterId: Int,
    val contractHistoryId: Int? = null,
    /** Показания одометра в км на момент акта. */
    val mileageKm: Long = 0L,
    /** Комплектация: аккумуляторы, ключи, шлем и т.д. — свободный текст. */
    val equipmentChecklist: String = "",
    /** Замечания о состоянии скутера при передаче/возврате. */
    val conditionNotes: String = "",
    /** Подпись/инициалы принимающего (оператора). */
    val signedBy: String = "LOCAL_SYSTEM"
) {
    companion object {
        const val TYPE_HANDOVER = "HANDOVER"
        const val TYPE_RETURN = "RETURN"
    }
}
