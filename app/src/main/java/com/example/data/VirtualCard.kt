package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Виртуальная финансовая карта.
 *
 * Используется на вкладке «Finansi» для учёта денежных потоков между счетами
 * (касса, банк, личные средства и т.д.).
 *
 * Баланс может быть как положительным (аванс/профицит), так и отрицательным (долг).
 * Цвет фона хранится hex-строкой вида "#FF1A1A1A" — выбирается пользователем из палитры.
 *
 * Две карты с id=1 и id=2 (isDefault=true) создаются автоматически при первом запуске
 * и не могут быть удалены пользователем («главная» и «второстепенная»).
 */
@Entity(tableName = "virtual_cards")
data class VirtualCard(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    /** Баланс в сумах. Может быть отрицательным. */
    val balance: Double = 0.0,
    /** Hex-цвет фона карты, например "#FF1565C0". */
    val colorHex: String,
    /** Необязательное описание / примечание. */
    val info: String? = null,
    /** true для системных карт, которые нельзя удалить (главная + второстепенная). */
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** id «главной» карты — нельзя удалить. */
        const val MAIN_CARD_ID = 1
        /** id «второстепенной» карты — нельзя удалить. */
        const val SECONDARY_CARD_ID = 2

        /** Палитра цветов, доступная при создании карты. */
        val COLOR_PALETTE = listOf(
            "#FF1565C0", // синий
            "#FF2E7D32", // зелёный
            "#FFE65100", // оранжевый
            "#FF6A1B9A", // фиолетовый
            "#FFC62828", // красный
            "#FF424242", // тёмно-серый
            "#FF00838F", // бирюзовый
            "#FF8D6E63"  // коричневый
        )
    }
}
