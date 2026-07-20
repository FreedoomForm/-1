package com.example.data

import android.content.Context
import android.util.Log
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Единый источник истины (single source of truth) для действий:
 *   • [payWeekly]      — оплата одной недели арендатором.
 *   • [terminate]      — расторжение контракта арендатора.
 *
 * Этот use-case используется ВСЕМП, кто инициирует эти действия:
 *   • [com.example.ui.RenterViewModel] — кнопки «To'lash» и «Uzish» в UI.
 *   • [com.example.worker.NotificationActionReceiver] — action-кнопки
 *     «To'lov qabul qilindi» и «Kontraktni uzish» в системном уведомлении.
 *
 * Раньше логика дублировалась между ViewModel и NotificationActionReceiver,
 * что приводило к рассинхрону: кнопка в UI создавала Transaction,
 * зачисляла деньги на главную карту и обновляла баланс, а кнопка в
 * уведомлении делала только часть операций. Теперь обе точки входа
 * гарантированно выполняют ОДНУ И ТУ ЖЕ последовательность операций.
 *
 * Логика действий:
 *
 * **payWeekly**:
 *   • balance < 0 → гасим самый ранний неоплаченный контракт (isPaid=true),
 *     создаём ContractHistoryEntry(PAYMENT) + Transaction(PAYMENT),
 *     зачисляем сумму на главную карту (depositContractIncome).
 *     Если после гашения баланс ≥ 0 — арендатор снова активен.
 *   • balance ≥ 0 → создаём новый оплаченный контракт AUTO_RENEW от конца
 *     последнего оплаченного контракта (+7 дней), либо от now, если последний
 *     контракт закончился больше недели назад. Создаём PAYMENT-запись +
 *     Transaction(PAYMENT) + depositContractIncome.
 *
 * **terminate**:
 *   • Если balance < 0 и есть неоплаченный контракт → оплачиваем его
 *     (как в payWeekly для долга), создаём PAYMENT-запись + Transaction(PAYMENT)
 *     + depositContractIncome. Баланс растёт на weeklyPrice.
 *   • Если balance < 0, но неоплаченных контрактов нет (рассинхрон) →
 *     обнуляем баланс до 0 (долг «прощается» при закрытии договора).
 *   • Если balance ≥ 0 → ничего не платим.
 *   • В обоих случаях: isReturned=true, ContractHistoryEntry(TERMINATED) +
 *     Transaction(TERMINATED).
 */
class RenterActionUseCase(
    private val context: Context,
    private val renterRepository: RenterRepository,
    private val historyRepository: ContractHistoryRepository,
    private val transactionRepository: TransactionRepository,
    private val virtualCardRepository: VirtualCardRepository,
    private val settingsRepository: SettingsRepository,
    private val scooterDao: ScooterDao
) {

    companion object {
        private const val TAG = "RenterActionUseCase"

        /** Создаёт use-case из контекста приложения (б防空 способ). */
        fun fromContext(context: Context): RenterActionUseCase {
            val db = AppDatabase.getDatabase(context)
            return RenterActionUseCase(
                context = context.applicationContext,
                renterRepository = RenterRepository(db.renterDao()),
                historyRepository = ContractHistoryRepository(db.contractHistoryDao()),
                transactionRepository = TransactionRepository(db.transactionDao()),
                virtualCardRepository = VirtualCardRepository(
                    db.virtualCardDao(),
                    db.cardTransactionDao()
                ),
                settingsRepository = SettingsRepository(context),
                scooterDao = db.scooterDao()
            )
        }
    }

    private suspend fun fetchScooterById(id: Int): Scooter? =
        withContext(Dispatchers.IO) { scooterDao.getScooterById(id) }

    /**
     * Оплата одной недели. Вызывается:
     *   • кнопкой «To'lash» в UI (RenterViewModel.payWeeklyForRenters);
     *   • action-кнопкой «To'lov qabul qilindi» в системном уведомлении.
     *
     * @param renter снимок арендатора на момент вызова.
     * @param notes описание платежа (для истории и Transaction.notes).
     * @param weeklyPriceOverride если задано — используется вместо settings.weeklyPrice.
     */
    suspend fun payWeekly(
        renter: Renter,
        notes: String,
        weeklyPriceOverride: Double? = null
    ) {
        val weeklyPrice = weeklyPriceOverride ?: settingsRepository.weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val newBalance = renter.balance + effectivePrice
        val now = System.currentTimeMillis()

        if (renter.balance < 0) {
            // ── Гашение долга: помечаем самый ранний неоплаченный контракт ──
            val unpaid = historyRepository.getEarliestUnpaidContract(renter.id)
            if (unpaid != null) {
                historyRepository.update(unpaid.copy(isPaid = true))
            }
            // Запись PAYMENT — для истории контрактов (не показывается на экране контрактов)
            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = unpaid?.weekStart,
                weekEnd = unpaid?.weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            historyRepository.insert(paymentEntry)

            // ── Запись в таблицу transactions (для страницы «Tranzaksiya») ──
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val contractLabel = unpaid?.let { e ->
                val ws = e.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                val we = e.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
                "#${e.id}  $ws → $we"
            } ?: ""
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = unpaid?.id,
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = contractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту (виртуальная касса) ──
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (qarz yopildi) — $notes",
                    contractId = unpaid?.id
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }

            val updated = renter.copy(
                debtAmount = maxOf(0.0, -newBalance),
                balance = newBalance,
                lastPaymentTimestamp = now,
                isOverdueSmsSent = false,
                // Если после гашения баланс стал ≥ 0 — возвращаем в активное
                isReturned = if (newBalance >= 0) false else renter.isReturned
            )
            renterRepository.update(updated)
        } else {
            // ── Предоплата: создаём новый оплаченный контракт ──────────────
            val latestPaid = historyRepository.getLatestPaidContract(renter.id)
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs

            val lastWeekEnd: Long? = latestPaid?.weekEnd

            // Если последний оплаченный контракт закончился БОЛЬШЕ 7 дней назад
            // → новый контракт начинается с today. Иначе — с lastWeekEnd
            // (непрерывное покрытие без дыр). Fallback при null = end первой недели.
            val effectiveLastEnd = lastWeekEnd
                ?: (renter.rentStartDateTimestamp + weekMs)
            val effectiveGapMs = now - effectiveLastEnd
            val shouldStartFromNow = effectiveGapMs > weekMs

            val baseStart = if (shouldStartFromNow) now else effectiveLastEnd
            val weekStart = baseStart
            val weekEnd = baseStart + weekMs

            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val scooterVin = scooter?.vinNumber ?: ""
            val scooterEngine = scooter?.engineNumber ?: ""
            val scooterSerial = scooter?.scooterSerialNumber ?: ""
            val scooterBat1 = scooter?.batteryId1 ?: ""
            val scooterBat2 = scooter?.batteryId2 ?: ""
            val scooterExtra = scooter?.additionalInfo ?: ""

            val contractNotes = when {
                renter.isReturned -> "Qayta faollashtirildi (1 hafta to'lov)"
                shouldStartFromNow && lastWeekEnd != null ->
                    "Yangi hafta (eski kontrakt muddati o'tgan)"
                else -> "Oldindan to'lov (keyingi hafta)"
            }

            // Новый контракт-неделя, сразу оплаченный (зелёный)
            val newContract = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW, amount = effectivePrice,
                notes = contractNotes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = weekStart, weekEnd = weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooterVin,
                engineNumber = scooterEngine,
                scooterSerialNumber = scooterSerial,
                batteryId1 = scooterBat1,
                batteryId2 = scooterBat2,
                additionalInfo = scooterExtra,
                isPaid = true
            )
            val newContractId = historyRepository.insert(newContract)

            // Запись PAYMENT — для истории транзакций
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber, scooterName = renter.scooterName,
                weekStart = weekStart, weekEnd = weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooterVin,
                engineNumber = scooterEngine,
                scooterSerialNumber = scooterSerial,
                batteryId1 = scooterBat1,
                batteryId2 = scooterBat2,
                additionalInfo = scooterExtra
            )
            historyRepository.insert(paymentEntry)

            // ── Запись в таблицу transactions ──
            val dateFmtTx = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = dateFmtTx.format(java.util.Date(weekStart))
            val weStr = dateFmtTx.format(java.util.Date(weekEnd))
            val newContractLabel = "#$newContractId  $wsStr → $weStr"
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = newContractId.toInt(),
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = newContractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert transaction: ${e.message}")
            }

            // ── Авто-зачисление на «Glavnaya» карту ──
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (oldindan) — $notes",
                    contractId = newContractId.toInt()
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }

            // rentStartDateTimestamp и rentDurationDays НЕ МЕНЯЕМ — это
            // первоначальные условия аренды (см. комментарий в RenterViewModel).
            val updated = renter.copy(
                debtAmount = maxOf(0.0, -newBalance),
                balance = newBalance,
                lastPaymentTimestamp = now,
                isOverdueSmsSent = false,
                isReturned = false  // ← реактивация пассивного арендатора
            )
            renterRepository.update(updated)
        }

        // Обновляем нативные виджеты Android
        try { WidgetUpdater.updateAll(context) } catch (_: Exception) {}
    }

    /**
     * Расторжение контракта. Вызывается:
     *   • кнопкой «Uzish» в UI (RenterViewModel.terminateRenters);
     *   • action-кнопкой «Kontraktni uzish» в системном уведомлении.
     *
     * @param renter снимок арендатора на момент вызова.
     * @param weeklyPrice недельная цена (берётся из settings).
     */
    suspend fun terminate(renter: Renter, weeklyPrice: Double) {
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val now = System.currentTimeMillis()
        val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }

        // ── Шаг 1: решение по балансу ────────────────────────────────────
        val unpaid = if (renter.balance < 0) {
            historyRepository.getEarliestUnpaidContract(renter.id)
        } else null

        val finalBalance = when {
            unpaid != null -> renter.balance + effectivePrice
            renter.balance < 0 -> 0.0  // рассинхрон — обнуляем
            else -> renter.balance
        }

        var paidContractId: Int? = null
        if (unpaid != null) {
            historyRepository.update(unpaid.copy(isPaid = true))
            paidContractId = unpaid.id

            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice,
                notes = "Tugatish vaqtida to'lov (qarz yopildi)",
                renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = unpaid.weekStart, weekEnd = unpaid.weekEnd,
                weeklyPrice = effectivePrice,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            historyRepository.insert(paymentEntry)

            // Transaction PAYMENT в таблице транзакций
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = unpaid.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
            val weStr = unpaid.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
            val contractLabel = "#${unpaid.id}  $wsStr → $weStr"
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = unpaid.id,
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = Transaction.TYPE_PAYMENT,
                        amount = effectivePrice,
                        notes = "Tugatish vaqtida to'lov",
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = contractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert payment transaction: ${e.message}")
            }

            // Авто-зачисление на «Glavnaya» карту при погашении долга
            try {
                virtualCardRepository.depositContractIncome(
                    amount = effectivePrice,
                    note = "To'lov: ${renter.name} (tugatish vaqtida)",
                    contractId = unpaid.id
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }
        }

        // ── Шаг 2: переводим арендатора в пассивное состояние ─────────────
        val updated = renter.copy(
            isReturned = true,
            balance = finalBalance,
            debtAmount = maxOf(0.0, -finalBalance),
            lastPaymentTimestamp = now,
            isOverdueSmsSent = false
        )
        renterRepository.update(updated)

        // ── Шаг 3: создаём запись TERMINATED в истории контрактов ──────────
        val entry = ContractHistoryEntry(
            renterId = renter.id, timestamp = now,
            type = ContractHistoryEntry.TYPE_TERMINATED, amount = effectivePrice,
            notes = if (unpaid != null) "Kontrakt tugatildi (qarz yopildi)"
                    else "Kontrakt tugatildi",
            renterName = renter.name, renterPhone = renter.phoneNumber,
            scooterName = renter.scooterName,
            weekStart = renter.rentStartDateTimestamp,
            weekEnd = now,
            weeklyPrice = effectivePrice,
            passportData = renter.passportData,
            address = renter.address,
            pinfl = renter.pinfl,
            vinNumber = scooter?.vinNumber ?: "",
            engineNumber = scooter?.engineNumber ?: "",
            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
            batteryId1 = scooter?.batteryId1 ?: "",
            batteryId2 = scooter?.batteryId2 ?: "",
            additionalInfo = scooter?.additionalInfo ?: ""
        )
        historyRepository.insert(entry)

        // ── Шаг 4: Transaction TERMINATED в таблице транзакций ─────────────
        try {
            transactionRepository.insert(
                Transaction(
                    contractId = paidContractId,
                    renterId = renter.id,
                    scooterId = renter.scooterId,
                    timestamp = now,
                    type = Transaction.TYPE_TERMINATED,
                    amount = effectivePrice,
                    notes = "Kontrakt tugatildi",
                    renterName = renter.name,
                    renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName ?: "",
                    contractLabel = ""
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert terminated transaction: ${e.message}")
        }

        // Обновляем нативные виджеты Android
        try { WidgetUpdater.updateAll(context) } catch (_: Exception) {}
    }
}
