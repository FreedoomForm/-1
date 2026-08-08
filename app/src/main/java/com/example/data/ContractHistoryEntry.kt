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
    val additionalInfo: String = "",

    // ── Статус контракта: оплачен / не оплачен ────────────────────────────
    // true  = оплачен (зелёная линия статуса)
    // false = долг (красная линия статуса)
    // Применяется только к записям-контрактам (CREATED, AUTO_RENEW).
    // PAYMENT/TERMINATED/RETURNED — транзакции, для них isPaid не используется.
    val isPaid: Boolean = false,

    // ── Soft-delete (trash mode) ──────────────────────────────────────────
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
) {
    companion object {
        const val TYPE_CREATED = "CREATED"
        const val TYPE_PAYMENT = "PAYMENT"
        const val TYPE_AUTO_RENEW = "AUTO_RENEW"
        const val TYPE_TERMINATED = "TERMINATED"
        const val TYPE_RETURNED = "RETURNED"

        /**
         * Вычисляет ЭФФЕКТИВНЫЙ баланс арендатора на основе списка контрактов
         * и текущего времени [now]. Реализует модель «отель/ночь» для баланса
         * (по требованию пользователя):
         *
         *   • Оплаченный контракт, период которого полностью прошёл
         *     (weekEnd <= now): вклад = 0. Скутер отработал оплату —
         *     мы ничего не должны арендатору, и он нам ничего не должен.
         *   • Оплаченный контракт, период которого ещё идёт или в будущем
         *     (weekEnd > now): вклад = +amount. Это предоплата — мы должны
         *     арендатору услугу, поэтому баланс «плюс».
         *   • Неоплаченный контракт, период которого начался
         *     (weekStart <= now): вклад = −amount. Арендатор должен оплатить —
         *     баланс «минус» (долг). Это включает случай «первый день
         *     последнего неоплаченного контракта = сегодня».
         *   • Неоплаченный контракт, период которого ещё не начался
         *     (weekStart > now): вклад = 0. Платить пока рано —
         *     не увеличиваем долг заранее.
         *
         * Маркеры STOP/RESUME (TYPE_TERMINATED/RETURNED с notes="STOP_MARKER"/
         * "RESUME_MARKER") игнорируются — у них amount = 0 и они не являются
         * «настоящими» контрактами.
         *
         * Записи PAYMENT игнорируются — они учитываются через isPaid контрактов.
         *
         * Записи с isDeleted = true игнорируются (мягко удалённые).
         *
         * Возвращает Double — эффективный баланс. < 0 = долг (статус красный),
         * > 0 = предоплата (статус зелёный), = 0 = все расчёты закрыты.
         */
        fun computeEffectiveBalance(
            contracts: List<ContractHistoryEntry>,
            now: Long = System.currentTimeMillis()
        ): Double {
            return contracts
                .asSequence()
                .filter { !it.isDeleted }
                .filter { it.type == TYPE_CREATED || it.type == TYPE_AUTO_RENEW }
                .sumOf { c ->
                    val amt = c.weeklyPrice.coerceAtLeast(0.0)
                    val ws = c.weekStart ?: return@sumOf 0.0
                    val we = c.weekEnd ?: return@sumOf 0.0
                    when {
                        // Оплаченный + период ещё не закончился (текущий или будущий)
                        // → предоплата, баланс плюс.
                        c.isPaid && we > now -> +amt
                        // Оплаченный + период полностью прошёл → отработано, 0.
                        c.isPaid -> 0.0
                        // Неоплаченный + период начался → долг, баланс минус.
                        !c.isPaid && ws <= now -> -amt
                        // Неоплаченный + период ещё не начался → 0 (платить рано).
                        else -> 0.0
                    }
                }
        }

        /**
         * Единый источник истины для логики «остановлен ли арендатор».
         *
         * Модель: «глобально последний маркер побеждает» (last writer wins).
         *
         * Берём ВСЕ маркеры STOP (TYPE_TERMINATED + notes="STOP_MARKER") и
         * RESUME (TYPE_RETURNED + notes="RESUME_MARKER") и сортируем их по
         * ключу (weekStart, timestamp):
         *   • сначала по дню маркера (weekStart),
         *   • при совпадении дня — по времени создания записи (timestamp).
         *
         * Если последний в этом порядке — STOP → арендатор «остановлен»
         * (в архиве). Функция возвращает этот STOP-маркер.
         * Если последний — RESUME, или маркеров нет, или есть только RESUME →
         * возвращается null (арендатор активен).
         *
         * Эта модель корректно обрабатывает все сценарии пользователя:
         *
         *   1. Первая установка STOP → архив (последний = STOP).
         *   2. Restore-from-archive ставит RESUME@сегодня (timestamp новее
         *      исходного STOP) → последний = RESUME → активен.
         *   3. Повторная установка STOP (вчера/сегодня/завтра) после restore:
         *      новый STOP получает свежий timestamp и становится последним →
         *      архив снова. Это чинит баг, когда будущий STOP не архивировал,
         *      потому что старый RESUME@сегодня «перекрывал» прошлый STOP.
         *   4. Приоритет статусов на один день: если на один день поставить
         *      RESUME потом STOP — STOP побеждает (его timestamp новее, он
         *      последний). Если сначала STOP потом RESUME — RESUME побеждает
         *      (его timestamp новее). Точно по требованию пользователя.
         *
         * Записи с isDeleted = true игнорируются (мягко удалённые).
         *
         * Возвращает «активный» STOP-маркер (последний в глобальном порядке,
         * если он STOP) или null.
         */
        fun activeStopMarker(entries: List<ContractHistoryEntry>): ContractHistoryEntry? {
            // Собираем только живые STOP/RESUME маркеры.
            val markers = entries.asSequence()
                .filter { !it.isDeleted }
                .filter {
                    (it.type == TYPE_TERMINATED && it.notes == "STOP_MARKER") ||
                    (it.type == TYPE_RETURNED && it.notes == "RESUME_MARKER")
                }
                .toList()
            if (markers.isEmpty()) return null
            // Сортируем по (weekStart, timestamp). weekStart nullable —
            // используем 0 для null (маркеры всегда имеют weekStart).
            val last = markers.maxWithOrNull(compareBy(
                { it.weekStart ?: 0L },
                { it.timestamp }
            )) ?: return null
            return if (last.type == TYPE_TERMINATED && last.notes == "STOP_MARKER") {
                last
            } else {
                null
            }
        }

        /**
         * convenience-обёртка над [activeStopMarker]: true, если у арендатора
         * есть «активный» STOP-маркер (последний маркер — STOP), т.е. аренда
         * приостановлена и не возобновлена → арендатор в архиве.
         */
        fun isArchivedByEntries(entries: List<ContractHistoryEntry>): Boolean =
            activeStopMarker(entries) != null
    }
}
