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
     * Оплата указанного числа дней аренды.
     *
     * Логика:
     *   1. Если у арендатора есть неоплаченные контракты (balance < 0 или
     *      просто есть isPaid=false записи) — гасим их СТРОГО ПО ПОРЯДКУ
     *      (от самого раннего). Каждый погашенный контракт добавляет к
     *      балансу свою недельную цену.
     *   2. Если после гашения всех неоплаченных контрактов осталась сдача
     *      (сумма платежа больше долга) — создаём НОВЫЙ оплаченный контракт
     *      AUTO_RENEW на эту сдачу: количество дней = сдача / dailyPrice.
     *   3. Если неоплаченных контрактов нет — весь платёж уходит в
     *      предоплату (новый оплаченный контракт на N дней).
     *
     * @param renter снимок арендатора на момент вызова.
     * @param days сколько дней оплачивает пользователь (7 = неделя, 14 = 2
     *             недели, 30 = месяц, и т.д.). Минимум 1.
     * @param notes описание платежа (для истории и Transaction.notes).
     */
    suspend fun payForDays(
        renter: Renter,
        days: Int,
        notes: String
    ) {
        val safeDays = days.coerceAtLeast(1)
        val dailyPrice = settingsRepository.dailyPrice.let {
            if (it > 0) it else SettingsRepository.DEFAULT_DAILY_PRICE
        }
        val paymentAmount = safeDays * dailyPrice
        val now = System.currentTimeMillis()

        Log.d(TAG, "payForDays: renter=#${renter.id} days=$safeDays daily=$dailyPrice total=$paymentAmount balance=${renter.balance}")

        // ── Шаг 1: получаем все неоплаченные контракты арендатора ────────
        val unpaidContracts = historyRepository.getUnpaidContractsForRenter(renter.id)

        var remainingAmount = paymentAmount
        var contractsPaid = 0
        val dayMs = 24L * 60 * 60 * 1000

        for (unpaid in unpaidContracts) {
            if (remainingAmount <= 0) break

            // Гасим этот контракт полностью
            historyRepository.update(unpaid.copy(isPaid = true))
            contractsPaid++

            // Сумма, зачтённая за этот контракт (берём amount из контракта,
            // а не из dailyPrice — контракт мог быть создан с другой ставкой)
            val contractAmount = unpaid.amount.let { if (it > 0) it else dailyPrice * 7 }
            remainingAmount -= contractAmount

            // PAYMENT-запись в историю контрактов
            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = contractAmount,
                notes = "$notes (qarz yopildi #${unpaid.id})",
                renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = unpaid.weekStart, weekEnd = unpaid.weekEnd,
                weeklyPrice = contractAmount,
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            historyRepository.insert(paymentEntry)

            // Transaction в таблицу транзакций
            val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val wsStr = unpaid.weekStart?.let { dateFmt.format(java.util.Date(it)) } ?: ""
            val weStr = unpaid.weekEnd?.let { dateFmt.format(java.util.Date(it)) } ?: ""
            val contractLabel = "#${unpaid.id}  $wsStr → $weStr"
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = unpaid.id, renterId = renter.id, scooterId = renter.scooterId,
                        timestamp = now, type = Transaction.TYPE_PAYMENT, amount = contractAmount,
                        notes = notes, renterName = renter.name, renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "", contractLabel = contractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert transaction: ${e.message}")
            }

            // Зачисление на «Glavnaya» карту
            try {
                virtualCardRepository.depositContractIncome(
                    amount = contractAmount,
                    note = "To'lov: ${renter.name} (qarz #${unpaid.id}) — $notes",
                    contractId = unpaid.id
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }
        }

        // ── Шаг 2: если осталась сдача — создаём новый оплаченный контракт ──
        if (remainingAmount > 0) {
            val paidDays = (remainingAmount / dailyPrice).toInt().coerceAtLeast(1)
            val actualAmount = paidDays * dailyPrice

            // Определяем начало нового контракта: либо с конца последнего
            // оплаченного, либо с now, если последний давно закончился.
            val latestPaid = historyRepository.getLatestPaidContract(renter.id)
            val weekMs = 7L * dayMs
            val lastWeekEnd = latestPaid?.weekEnd
            val effectiveLastEnd = lastWeekEnd ?: (renter.rentStartDateTimestamp + weekMs)
            val shouldStartFromNow = (now - effectiveLastEnd) > weekMs
            val newStart = if (shouldStartFromNow) now else effectiveLastEnd
            val newEnd = newStart + paidDays * dayMs

            val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }
            val newContract = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW, amount = actualAmount,
                notes = if (contractsPaid > 0) "Qoldiqdan yangi kontrakt ($paidDays kun)"
                        else "Oldindan to'lov ($paidDays kun)",
                renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = newStart, weekEnd = newEnd,
                weeklyPrice = actualAmount,
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = true
            )
            val newContractId = historyRepository.insert(newContract)

            // PAYMENT-запись
            val paymentEntry = ContractHistoryEntry(
                renterId = renter.id, timestamp = now,
                type = ContractHistoryEntry.TYPE_PAYMENT, amount = actualAmount, notes = notes,
                renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = newStart, weekEnd = newEnd,
                weeklyPrice = actualAmount,
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            historyRepository.insert(paymentEntry)

            // Transaction
            val dateFmtTx = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val newContractLabel = "#$newContractId  ${dateFmtTx.format(java.util.Date(newStart))} → ${dateFmtTx.format(java.util.Date(newEnd))}"
            try {
                transactionRepository.insert(
                    Transaction(
                        contractId = newContractId.toInt(), renterId = renter.id,
                        scooterId = renter.scooterId, timestamp = now,
                        type = Transaction.TYPE_PAYMENT, amount = actualAmount, notes = notes,
                        renterName = renter.name, renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "", contractLabel = newContractLabel
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert transaction: ${e.message}")
            }

            // Зачисление на карту
            try {
                virtualCardRepository.depositContractIncome(
                    amount = actualAmount,
                    note = "To'lov: ${renter.name} (oldindan $paidDays kun) — $notes",
                    contractId = newContractId.toInt()
                )
            } catch (e: Exception) {
                Log.w(TAG, "depositContractIncome failed: ${e.message}")
            }
        }

        // ── Шаг 3: обновляем баланс арендатора ───────────────────────────
        val newBalance = renter.balance + paymentAmount
        val updated = renter.copy(
            debtAmount = maxOf(0.0, -newBalance),
            balance = newBalance,
            lastPaymentTimestamp = now,
            isOverdueSmsSent = false,
            isReturned = if (newBalance >= 0) false else renter.isReturned
        )
        renterRepository.update(updated)

        Log.d(TAG, "payForDays done: renter=#${renter.id} contractsPaid=$contractsPaid newBalance=$newBalance")

        try { WidgetUpdater.updateAll(context) } catch (_: Exception) {}
    }

    /**
     * Оплата одной недели (legacy-метод, оставлен для совместимости со
     * старыми вызовами). Делегирует в [payForDays] с days=7.
     */
    suspend fun payWeekly(
        renter: Renter,
        notes: String,
        weeklyPriceOverride: Double? = null
    ) {
        payForDays(renter = renter, days = 7, notes = notes)
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
