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
 *
 * ЛОГИКА:
 *   • id=1 «Glavnaya» (главная) — на неё автоматически поступают все платежи
 *     из контрактов (applyWeeklyPayment → Glavnaya.balance += weeklyPrice).
 *   • Остальные карты — «карманы» для расходов (ремонт, штрафы, налоги и т.д.).
 *     Пользователь вручную переводит деньги с Glavnaya на расходные карты
 *     через зону транзакции на вкладке Finansi.
 *
 * ВНЕШНИЕ КАРТЫ (kind = EXTERNAL_IN / EXTERNAL_OUT):
 *   • id=3 «Tashqidan» (kind=EXTERNAL_IN) — бесконечный источник «из вне».
 *     Перевод С этой карты на любую обычную = внесение наличности из внешнего
 *     источника (банк, инкассация, личные средства). Баланс не меняется (∞),
 *     при переводе обязательно описание (note) — для чего внесена сумма.
 *   • id=4 «Tashqiga» (kind=EXTERNAL_OUT) — бесконечный сток «вне».
 *     Перевод С любой обычной карты НА эту = вывод денег во вне (снятие
 *     наличными, оплата поставщику, налоги вне системы). Баланс не меняется (∞),
 *     note также обязателен.
 *   • В [VirtualCardRepository.transfer] вызовы adjustBalance для внешних карт
 *     пропускаются — их баланс концептуально бесконечен и не учитывается.
 *   • В отчётах (ReportsScreen) и итогах внешние карты исключаются.
 */
@Entity(tableName = "virtual_cards")
data class VirtualCard(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    /** Баланс в сумах. Может быть отрицательным. Для внешних карт не используется (∞). */
    val balance: Double = 0.0,
    /** Hex-цвет фона карты, например "#FF1565C0". */
    val colorHex: String,
    /** Необязательное описание / примечание. */
    val info: String? = null,
    /** true для системных карт, которые нельзя удалить (главная + второстепенная + внешние). */
    val isDefault: Boolean = false,
    /**
     * Тип карты:
     *   • [KIND_REGULAR] — обычная пользовательская/системная карта (по умолчанию).
     *   • [KIND_EXTERNAL_IN] — «Из вне», бесконечный источник входящих средств.
     *   • [KIND_EXTERNAL_OUT] — «Вне», бесконечный сток для исходящих средств.
     *
     * Добавлено в миграции 13→14; у всех существующих карт значение 'REGULAR'.
     */
    val kind: String = KIND_REGULAR,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** id «главной» карты — нельзя удалить. На неё падают все оплаты контрактов. */
        const val MAIN_CARD_ID = 1
        /** id «второстепенной» карты — нельзя удалить. */
        const val SECONDARY_CARD_ID = 2
        /** id карты «Из вне» — бесконечный источник. Нельзя удалить. */
        const val EXTERNAL_IN_CARD_ID = 3
        /** id карты «Вне» — бесконечный сток. Нельзя удалить. */
        const val EXTERNAL_OUT_CARD_ID = 4

        /** Обычная карта (пользовательская или системная Glavnaya/Vtorostepennaya). */
        const val KIND_REGULAR = "REGULAR"
        /** Внешняя карта «Из вне» — бесконечный источник входящих средств. */
        const val KIND_EXTERNAL_IN = "EXTERNAL_IN"
        /** Внешняя карта «Вне» — бесконечный сток для исходящих средств. */
        const val KIND_EXTERNAL_OUT = "EXTERNAL_OUT"

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

        /** true, если карта с этим id — внешняя (бесконечный баланс). */
        fun isExternalId(cardId: Int): Boolean =
            cardId == EXTERNAL_IN_CARD_ID || cardId == EXTERNAL_OUT_CARD_ID
    }
}

/** true для внешних карт (Tashqidan / Tashqiga). */
val VirtualCard.isExternal: Boolean
    get() = kind == VirtualCard.KIND_EXTERNAL_IN || kind == VirtualCard.KIND_EXTERNAL_OUT

/** true для карты «Из вне» (бесконечный источник). */
val VirtualCard.isExternalIn: Boolean
    get() = kind == VirtualCard.KIND_EXTERNAL_IN

/** true для карты «Вне» (бесконечный сток). */
val VirtualCard.isExternalOut: Boolean
    get() = kind == VirtualCard.KIND_EXTERNAL_OUT
