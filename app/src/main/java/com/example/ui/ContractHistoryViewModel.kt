package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.ContractHistoryRepository
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.Scooter
import com.example.data.TransactionRepository
import com.example.data.VirtualCardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContractHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: ContractHistoryRepository
    private val renterRepo: RenterRepository
    private val transactionRepo: TransactionRepository
    private val virtualCardRepo: VirtualCardRepository
    val history: StateFlow<List<ContractHistoryEntry>>

    // Кэш StateFlow по renterId — чтобы не создавать новый flow на каждую рекомпозицию
    // (старая версия создавала новый flow каждый вызов forRenter() → утечка + мерцание UI)
    private val renterFlows = mutableMapOf<Int, StateFlow<List<ContractHistoryEntry>>>()
    private val renterContractFlows = mutableMapOf<Int, StateFlow<List<ContractHistoryEntry>>>()
    private val scooterFlows = mutableMapOf<String, StateFlow<List<ContractHistoryEntry>>>()
    private val flowsLock = Any()

    init {
        val db = AppDatabase.getDatabase(application)
        repo = ContractHistoryRepository(db.contractHistoryDao())
        renterRepo = RenterRepository(db.renterDao())
        transactionRepo = TransactionRepository(db.transactionDao())
        virtualCardRepo = VirtualCardRepository(db.virtualCardDao(), db.cardTransactionDao())
        history = repo.allHistory.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    fun forRenter(renterId: Int): StateFlow<List<ContractHistoryEntry>> =
        synchronized(flowsLock) {
            renterFlows.getOrPut(renterId) {
                repo.forRenter(renterId).stateIn(
                    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
                )
            }
        }

    /**
     * Только контракты (CREATED + AUTO_RENEW) — для экрана истории контрактов.
     * Каждая запись имеет флаг isPaid (true = зелёная линия, false = красная).
     * Сортировка: ASC по weekStart (от самого раннего к самому позднему).
     */
    fun contractsForRenter(renterId: Int): StateFlow<List<ContractHistoryEntry>> =
        synchronized(flowsLock) {
            renterContractFlows.getOrPut(renterId) {
                repo.contractsForRenter(renterId).stateIn(
                    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
                )
            }
        }

    fun forScooter(scooterName: String): StateFlow<List<ContractHistoryEntry>> =
        synchronized(flowsLock) {
            scooterFlows.getOrPut(scooterName) {
                repo.forScooter(scooterName).stateIn(
                    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
                )
            }
        }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }

    /**
     * Создаёт новый контракт вручную с экрана истории контрактов.
     *
     * Создаётся запись типа AUTO_RENEW с денормализованными полями арендатора
     * и скутера (для корректной генерации PDF).
     *
     * Важно: баланс арендатора НЕ меняется — это просто запись о контракте.
     * Если пользователь хочет, чтобы контракт был "оплачен" (зелёный), он
     * должен использовать кнопку "To'lov" на основной таблице арендаторов,
     * которая реализует правильную логику баланса (см. RenterViewModel.applyWeeklyPayment).
     *
     * @param renter      арендатор (для денормализации и scooterId)
     * @param weekStart   начало недели (millis)
     * @param weekEnd     конец недели (millis)
     * @param amount      сумма контракта
     * @param isPaid      true = оплачен (зелёный), false = долг (красный)
     * @param notes       примечание (опционально)
     */
    fun createManualContract(
        renter: Renter,
        weekStart: Long,
        weekEnd: Long,
        amount: Double,
        isPaid: Boolean,
        notes: String?
    ) {
        createManualContractWithOverrides(
            renterId = renter.id,
            renterName = renter.name,
            renterPhone = renter.phoneNumber,
            scooterId = renter.scooterId,
            scooterName = renter.scooterName ?: "",
            passportData = renter.passportData,
            address = renter.address,
            pinfl = renter.pinfl,
            weekStart = weekStart,
            weekEnd = weekEnd,
            amount = amount,
            isPaid = isPaid,
            notes = notes,
            // Переопределения скутера — пустые строки → будут взяты из БД по scooterId
            overrideVin = "", overrideEngine = "", overrideSerial = "",
            overrideBatt1 = "", overrideBatt2 = "", overrideExtra = ""
        )
    }

    /**
     * Создаёт новый контракт вручную с ПОЛНЫМ набором переопределяемых полей
     * арендатора и скутера. Используется диалогом создания контракта, где
     * пользователь может выбрать любого арендатора/скутер из выпадающего
     * списка и вручную отредактировать любое поле для PDF.
     *
     * Если передан [scooterId] и хотя бы одно поле скутера пустое — поле
     * берётся из БД по этому ID. Это позволяет не требовать от пользователя
     * заполнять все 6 полей скутера вручную.
     */
    fun createManualContractWithOverrides(
        renterId: Int,
        renterName: String,
        renterPhone: String,
        scooterId: Int?,
        scooterName: String,
        passportData: String,
        address: String,
        pinfl: String,
        weekStart: Long,
        weekEnd: Long,
        amount: Double,
        isPaid: Boolean,
        notes: String?,
        overrideVin: String,
        overrideEngine: String,
        overrideSerial: String,
        overrideBatt1: String,
        overrideBatt2: String,
        overrideExtra: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val scooter: Scooter? = scooterId?.let {
                AppDatabase.getDatabase(getApplication()).scooterDao().getScooterById(it)
            }
            // Для каждого поля скутера: приоритет у пользовательского ввода,
            // затем — значение из БД, затем пустая строка.
            fun pickScooterField(override: String, dbValue: String?): String =
                override.ifBlank { dbValue ?: "" }

            val entry = ContractHistoryEntry(
                renterId = renterId,
                timestamp = System.currentTimeMillis(),
                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                amount = amount,
                notes = notes?.ifBlank { null },
                renterName = renterName,
                renterPhone = renterPhone,
                scooterName = scooterName.ifBlank { scooter?.name ?: "" },
                weekStart = weekStart,
                weekEnd = weekEnd,
                weeklyPrice = amount,
                passportData = passportData,
                address = address,
                pinfl = pinfl,
                vinNumber = pickScooterField(overrideVin, scooter?.vinNumber),
                engineNumber = pickScooterField(overrideEngine, scooter?.engineNumber),
                scooterSerialNumber = pickScooterField(overrideSerial, scooter?.scooterSerialNumber),
                batteryId1 = pickScooterField(overrideBatt1, scooter?.batteryId1),
                batteryId2 = pickScooterField(overrideBatt2, scooter?.batteryId2),
                additionalInfo = pickScooterField(overrideExtra, scooter?.additionalInfo),
                isPaid = isPaid
            )
            repo.insert(entry)
        }
    }

    fun deleteContract(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteContractWithCascade(id)
        }
    }

    fun deleteContracts(ids: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { deleteContractWithCascade(it) }
        }
    }

    /**
     * Каскадное удаление контракта — «мостик» между всеми связанными сущностями.
     *
     * Когда пользователь удаляет контракт C, выполняются следующие шаги
     * (порядок важен — сначала собираем данные, потом удаляем):
     *
     * 1. Загружаем сам контракт C из БД (нужны renterId, amount, isPaid, type).
     * 2. Загружаем все Transaction-записи с contractId = C.id. Запоминаем их
     *    суммы (для реверса баланса арендатора).
     * 3. Загружаем все CardTransaction-записи с contractId = C.id
     *    (это TYPE_CONTRACT_INCOME — деньги, упавшие на главную карту).
     *    Запоминаем их суммы (для реверса баланса главной карты).
     * 4. Удаляем все Transaction с contractId = C.id.
     * 5. Для каждой CardTransaction с contractId = C.id:
     *    - реверсим баланс главной карты на -amount (деньги возвращаются
     *      «во внешний источник»);
     *    - удаляем саму CardTransaction.
     * 6. Корректируем баланс арендатора:
     *    - если C.isPaid (оплачен) → balance -= C.amount (платёж был зачислен,
     *      теперь откатываем);
     *    - если C.isPaid = false (долг) → balance += C.amount (долг списывается,
     *      т.к. контракт больше не существует);
     *    - то же самое для TYPE_PAYMENT транзакций в истории контрактов: они
     *      учитываются в шаге 2 через Transaction-таблицу.
     *    У renter.debtAmount пересчитывается как max(0, -balance).
     * 7. Если удалённый контракт был «последним оплаченным» — обновляем
     *    renter.lastPaymentTimestamp на дату предыдущего оплаченного контракта
     *    (или null, если такового нет).
     * 8. Если у арендатора больше не осталось ни одного контракта (CREATED /
     *    AUTO_RENEW), помечаем его isReturned = true и освобождаем скутер
     *    (scooterId = null, scooterName = null).
     * 9. Удаляем сам контракт C.
     * 10. Обновляем нативные виджеты Android.
     *
     * ВАЖНО: остальные контракты этого арендатора НЕ удаляются — каждый
     * контракт независим. Если нужно удалить всю историю целиком, см.
     * [deleteAllForRenter].
     *
     * Все операции выполняются в одной coroutine на Dispatchers.IO. Room не
     * гарантирует транзакционность без явного @Transaction, но на практике
     * последовательность safe: даже если упадёт посередине, останутся
     * «осиротевшие» записи, которые не влияют на UI (фильтры по contractId
     * просто не найдут ничего).
     */
    private suspend fun deleteContractWithCascade(contractId: Int) {
        try {
            // ── 1. Загружаем контракт ──────────────────────────────────────
            val contract = repo.getById(contractId)
            if (contract == null) {
                Log.w(TAG, "deleteContractWithCascade: contract #$contractId not found, nothing to do")
                return
            }

            // ── 2. Загружаем связанные Transaction-записи ─────────────────
            // Это записи TYPE_PAYMENT, созданные при оплате этого контракта
            // (applyWeeklyPayment / updateContract status-change).
            val relatedTx = transactionRepo.forContractOnce(contractId)

            // ── 3. Загружаем связанные CardTransaction-записи ─────────────
            // Это TYPE_CONTRACT_INCOME — деньги, упавшие на Glavnaya карту.
            val relatedCardTx = virtualCardRepo.getCardTxForContract(contractId)

            // ── 4. Удаляем Transaction-записи ─────────────────────────────
            if (relatedTx.isNotEmpty()) {
                transactionRepo.deleteForContract(contractId)
                Log.d(TAG, "Deleted ${relatedTx.size} Transaction rows for contract #$contractId")
            }

            // ── 5. Реверсим и удаляем CardTransaction-записи ──────────────
            // Каждая CardTransaction с type=CONTRACT_INCOME увеличивала баланс
            // главной карты на +amount при оплате. Удаление контракта должно
            // откатить это: subtract amount с главной карты.
            // (CardTransaction.amount всегда > 0; реверс = -amount.)
            for (cardTx in relatedCardTx) {
                try {
                    virtualCardRepo.adjustCardBalance(
                        cardId = cardTx.toCardId,
                        delta = -cardTx.amount
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reverse cardTx #${cardTx.id} balance: ${e.message}")
                }
            }
            if (relatedCardTx.isNotEmpty()) {
                virtualCardRepo.deleteCardTxForContract(contractId)
                Log.d(TAG, "Deleted ${relatedCardTx.size} CardTransaction rows for contract #$contractId")
            }

            // ── 6. Корректируем баланс арендатора ─────────────────────────
            val renter = renterRepo.getById(contract.renterId)
            if (renter != null) {
                val isContractType = contract.type == ContractHistoryEntry.TYPE_CREATED ||
                                     contract.type == ContractHistoryEntry.TYPE_AUTO_RENEW
                var balanceDelta = 0.0

                if (isContractType) {
                    // ── Логика корректировки баланса при удалении контракта ──
                    //
                    // isPaid = false (долг):
                    //   При создании контракта баланс был уменьшен на -amount
                    //   (арендатор должен). Удаление контракта списывает долг:
                    //   balanceDelta = +amount.
                    //
                    // isPaid = true (оплачен):
                    //   Контракт «закрыт» — арендатор заплатил amount, контракт
                    //   помечен оплаченным. Баланс УЖЕ отражает эту оплату
                    //   (либо 0 при предоплате, либо 0 после погашения долга).
                    //   Удаление контракта НЕ должно менять баланс:
                    //   balanceDelta = 0.
                    //
                    //   ⚠ Предыдущая версия делала balanceDelta = -amount для
                    //   isPaid=true — это был БАГ: после удаления оплаченного
                    //   контракта баланс уходил в минус (например 0 → -amount),
                    //   хотя арендатор ничего не должен. Платёж уже поступил
                    //   (CardTransaction на главной карте), и откатывается он
                    //   на шаге 5 (reverse CardTransaction). Баланс арендатора
                    //   при этом трогать НЕ нужно.
                    //
                    //   Сценарии, которые работали неправильно:
                    //   1) Создание арендатора с предоплатой (balance=0,
                    //      contract.isPaid=true) → удаление → balance=-amount ❌
                    //   2) Просрочка + оплата (balance=-amount → 0 после оплаты,
                    //      contract.isPaid=true) → удаление → balance=-amount ❌
                    //   Теперь оба сценария оставляют баланс = 0. ✓
                    balanceDelta = if (contract.isPaid) 0.0 else +contract.amount
                }
                // Для TYPE_PAYMENT / TYPE_TERMINATED / TYPE_RETURNED баланс арендатора
                // не меняется — это аудиторские записи; фактическое изменение баланса
                // происходило в момент создания через applyWeeklyPayment /
                // applyTermination, и откатывается через удаление связанных
                // Transaction (шаг 4) — но step 4 только удаляет записи, не
                // меняя баланс. Поэтому для этих типов balanceDelta = 0.
                // (relatedTx для них обычно пуст, т.к. Transactions привязываются
                // к CREATED/AUTO_RENEW контрактам, а не к PAYMENT/TERMINATED.)
                if (!isContractType) {
                    balanceDelta = 0.0
                }

                if (balanceDelta != 0.0) {
                    val newBalance = renter.balance + balanceDelta
                    val updated = renter.copy(
                        balance = newBalance,
                        debtAmount = maxOf(0.0, -newBalance)
                    )
                    renterRepo.update(updated)
                    Log.d(TAG, "Renter #${renter.id} balance adjusted by $balanceDelta → $newBalance")
                } else {
                    Log.d(TAG, "Renter #${renter.id} balance unchanged (contract isPaid=${contract.isPaid}, type=${contract.type})")
                }

                // ── 7. Обновляем lastPaymentTimestamp ────────────────────
                // Если удалённый контракт был оплачен, ищем предыдущий оплаченный
                // контракт и берём его timestamp. Если таких не осталось — null.
                if (contract.isPaid || contract.type == ContractHistoryEntry.TYPE_PAYMENT) {
                    try {
                        val latestPaid = repo.getLatestPaidContract(renter.id)
                        val newLastPayment = latestPaid?.timestamp ?: latestPaid?.weekEnd
                        renterRepo.update(
                            (renterRepo.getById(renter.id) ?: renter).copy(
                                lastPaymentTimestamp = newLastPayment
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update lastPaymentTimestamp: ${e.message}")
                    }
                }

                // ── 8. Если контрактов не осталось — освобождаем арендатора ──
                val remainingContracts = repo.contractsForRenterOnce(renter.id)
                if (remainingContracts.isEmpty()) {
                    val current = renterRepo.getById(renter.id) ?: renter
                    renterRepo.update(
                        current.copy(
                            isReturned = true,
                            scooterId = null,
                            scooterName = null
                        )
                    )
                    Log.d(TAG, "Renter #${renter.id} marked returned (no contracts left)")
                }
            }

            // ── 9. Удаляем сам контракт ───────────────────────────────────
            repo.deleteById(contractId)
            Log.d(TAG, "Contract #$contractId deleted with cascade " +
                "(tx=${relatedTx.size}, cardTx=${relatedCardTx.size})")

            // ── 10. Обновляем виджеты ─────────────────────────────────────
            try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "deleteContractWithCascade failed for #$contractId", e)
        }
    }

    /**
     * Удаляет ВСЕ контракты арендатора [renterId] с полным каскадом.
     * Используется при удалении арендатора целиком (если такое когда-то
     * понадобится). Сейчас не вызывается из UI — оставлено как утилита.
     */
    suspend fun deleteAllForRenter(renterId: Int) {
        val contracts = repo.getForRenterOnce(renterId)
        contracts.forEach { deleteContractWithCascade(it.id) }
    }

    /**
     * Обновляет запись контракта.
     *
     * Корректировки баланса арендатора:
     *
     * 1) При изменении `amount` для PAYMENT/AUTO_RENEW:
     *    • PAYMENT     — старая сумма вычитается, новая добавляется
     *    • AUTO_RENEW  — старая сумма добавляется, новая вычитается
     *
     * 2) При изменении `isPaid` для CREATED/AUTO_RENEW (контракты-долги):
     *    • false → true  (контракт оплачен)   → баланс += amount  (долг списан)
     *      + создаётся Transaction.TYPE_PAYMENT для вкладки «Tranzaksiya»
     *      + сумма зачисляется на «Glavnaya» виртуальную карту через
     *        VirtualCardRepository.depositContractIncome()
     *    • true  → false (контракт НЕ оплачен) → баланс -= amount  (долг восстановлен)
     *      + создаётся Transaction.TYPE_PAYMENT с отрицательной суммой (возврат/отмена)
     *      + сумма списывается с «Glavnaya» карты (reverse-deposit)
     *    Это главное исправление: раньше при смене статуса контракта баланс
     *    арендатора не менялся, и арендатор оставался в минусе даже после
     *    пометки контракта как "To'langan". Дополнительно деньги не падали
     *    на главную карту и не появлялись в списке транзакций.
     *
     * 3) Если одновременно изменились и `amount`, и `isPaid` — обе корректировки
     *    применяются последовательно (сумма + статус).
     */
    fun updateContract(entry: ContractHistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val old = repo.getById(entry.id)
            if (old == null) {
                repo.update(entry)
                return@launch
            }

            val renter = renterRepo.getById(entry.renterId)
            if (renter == null) {
                repo.update(entry)
                return@launch
            }

            var delta = 0.0

            // ── Корректировка 1: изменение суммы ──────────────────────────
            if (old.amount != entry.amount) {
                delta += when (old.type) {
                    ContractHistoryEntry.TYPE_PAYMENT    -> entry.amount - old.amount        // +delta
                    ContractHistoryEntry.TYPE_AUTO_RENEW -> -(entry.amount - old.amount)    // -delta (долг вырос)
                    else                                 -> 0.0
                }
            }

            // ── Корректировка 2: изменение статуса оплаты (isPaid) ────────
            // Применяется только к контрактам (CREATED / AUTO_RENEW), не к
            // транзакциям (PAYMENT/TERMINATED/RETURNED — для них isPaid не
            // имеет смысла).
            val isContractType = old.type == ContractHistoryEntry.TYPE_CREATED ||
                                 old.type == ContractHistoryEntry.TYPE_AUTO_RENEW
            val statusChanged = isContractType && old.isPaid != entry.isPaid
            if (statusChanged) {
                // Сумма, по которой корректируем: если amount тоже изменился,
                // используем новое значение (оно уже учтено в delta выше как
                // "долг вырос/уменьшился", а здесь мы добавляем/вычитаем
                // финальную сумму как оплату).
                val amountForStatus = entry.amount
                delta += if (entry.isPaid) {
                    // Стал оплачен → долг списан, баланс растёт
                    +amountForStatus
                } else {
                    // Стал НЕ оплачен → долг восстановлен, баланс падает
                    -amountForStatus
                }

                // ── Создаём Transaction для вкладки «Tranzaksiya» ───────
                // Чтобы оплата/отмена отображалась в общем списке транзакций,
                // а не только в истории контрактов.
                val now = System.currentTimeMillis()
                val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                val wsStr = entry.weekStart?.let { dateFmt.format(Date(it)) } ?: ""
                val weStr = entry.weekEnd?.let { dateFmt.format(Date(it)) } ?: ""
                val contractLabel = "#${entry.id}  $wsStr → $weStr"
                val txType = com.example.data.Transaction.TYPE_PAYMENT
                val txNotes = if (entry.isPaid) {
                    "Kontrakt statusi o'zgartirildi: To'langan"
                } else {
                    "Kontrakt statusi o'zgartirildi: To'lanmagan (qaytarildi)"
                }
                try {
                    transactionRepo.insert(
                        com.example.data.Transaction(
                            contractId = entry.id,
                            renterId = renter.id,
                            scooterId = renter.scooterId,
                            timestamp = now,
                            type = txType,
                            amount = if (entry.isPaid) amountForStatus else -amountForStatus,
                            notes = txNotes,
                            renterName = renter.name,
                            renterPhone = renter.phoneNumber,
                            scooterName = renter.scooterName ?: "",
                            contractLabel = contractLabel
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ContractHistoryVM", "Failed to insert status-change transaction: ${e.message}")
                }

                // ── Создаём PAYMENT-запись в истории контрактов (аудит) ──
                // Чтобы при смене isPaid оставался след в истории контрактов
                // (как при applyWeeklyPayment). Без этой записи аудит-трейл
                // неполный: можно сменить статус, и не будет записи «когда и
                // каким образом».
                try {
                    val paymentAuditEntry = ContractHistoryEntry(
                        renterId = renter.id,
                        timestamp = now,
                        type = ContractHistoryEntry.TYPE_PAYMENT,
                        amount = if (entry.isPaid) amountForStatus else -amountForStatus,
                        notes = if (entry.isPaid)
                            "Status o'zgartirildi: To'langan — #${entry.id}"
                        else
                            "Status o'zgartirildi: Bekor qilindi — #${entry.id}",
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        weekStart = entry.weekStart,
                        weekEnd = entry.weekEnd,
                        weeklyPrice = amountForStatus,
                        passportData = renter.passportData,
                        address = renter.address,
                        pinfl = renter.pinfl
                    )
                    repo.insert(paymentAuditEntry)
                } catch (e: Exception) {
                    Log.w("ContractHistoryVM", "Failed to insert audit PAYMENT entry: ${e.message}")
                }

                // ── Зачисление / списание на «Glavnaya» карту ───────────
                // При оплате контракта (false → true) сумма падает на главную
                // карту как входящий поток (depositContractIncome).
                // При отмене оплаты (true → false) — наоборот, списываем,
                // используя depositContractIncome с отрицательной суммой
                // (adjustBalance в DAO корректно обрабатывает минус).
                try {
                    val signedAmount = if (entry.isPaid) amountForStatus else -amountForStatus
                    val noteText = if (entry.isPaid) {
                        "To'lov: ${renter.name} (status o'zgartirildi) — #${entry.id}"
                    } else {
                        "Bekor qilindi: ${renter.name} (status o'zgartirildi) — #${entry.id}"
                    }
                    virtualCardRepo.depositContractIncome(
                        amount = signedAmount,
                        note = noteText,
                        contractId = entry.id
                    )
                } catch (e: Exception) {
                    Log.w("ContractHistoryVM", "depositContractIncome failed: ${e.message}")
                }
            }

            if (delta != 0.0) {
                val newBalance = renter.balance + delta
                val updated = renter.copy(
                    balance = newBalance,
                    debtAmount = maxOf(0.0, -newBalance)
                )
                renterRepo.update(updated)
            }
            repo.update(entry)
        }
    }

    /**
     * Генерирует PDF-документ для контракта [contractId] и сохраняет в каталог
     * `Documents/ScooterContracts/` приложения. Возвращает file:// Uri.
     *
     * Использует android.graphics.pdf.PdfDocument — встроенный API, без сторонних библиотек.
     *
     * Скутер подтягивается из БД по entry.renterId → renter.scooterId → Scooter.
     * Это гарантирует, что данные скутера попадают в PDF, даже если в самой
     * записи entry они не были денормализованы (старые контракты).
     */
    suspend fun generateContractPdf(contractId: Int): Uri? = withContext(Dispatchers.IO) {
        try {
            val entry = repo.getById(contractId) ?: return@withContext null
            val renter = renterRepo.getById(entry.renterId)
            val scooter: Scooter? = renter?.scooterId?.let {
                AppDatabase.getDatabase(getApplication()).scooterDao().getScooterById(it)
            }
            PdfContractGenerator.generate(getApplication(), entry, renter, scooter)
        } catch (e: Exception) {
            Log.e(TAG, "PDF generation failed", e)
            null
        }
    }

    /**
     * Генерирует PDF-договор с НЕОГРАНИЧЕННЫМ сроком действия для данного
     * арендатора. Используется кнопкой PDF на странице истории контрактов
     * арендатора (рядом с карточкой информации об арендаторе).
     *
     * В отличие от [generateContractPdf], этот PDF не привязан к конкретной
     * записи контракта — он формируется из актуальных данных арендатора и
     * его скутера. В тексте договора прямо указано, что он действует на
     * неограниченный срок до момента, когда арендатор решит его расторгнуть.
     *
     * @param renterId ID арендатора
     * @return Uri на созданный PDF-файл, или null при ошибке
     */
    suspend fun generateUnlimitedContractPdf(renterId: Int): Uri? = withContext(Dispatchers.IO) {
        try {
            val renter = renterRepo.getById(renterId) ?: return@withContext null
            val scooter: Scooter? = renter.scooterId?.let {
                AppDatabase.getDatabase(getApplication()).scooterDao().getScooterById(it)
            }
            PdfContractGenerator.generateUnlimited(getApplication(), renter, scooter)
        } catch (e: Exception) {
            Log.e(TAG, "Unlimited PDF generation failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "ContractHistoryVM"
    }
}
