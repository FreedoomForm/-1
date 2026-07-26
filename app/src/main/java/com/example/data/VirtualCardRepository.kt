package com.example.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Репозиторий виртуальных карт и транзакций между ними.
 *
 * Ответственность:
 *   • CRUD виртуальных карт (2 системные по умолчанию + пользовательские)
 *   • Перевод денег между картами (с атомарным обновлением балансов)
 *   • Зачисление «внешнего дохода» на главную карту (когда арендатор платит
 *     за неделю — эта сумма автоматически падает на Glavnaya через
 *     [depositContractIncome]).
 */
class VirtualCardRepository(
    private val cardDao: VirtualCardDao,
    private val txDao: CardTransactionDao,
    private val database: AppDatabase? = null
) {
    private suspend fun <T> atomic(block: suspend () -> T): T =
        database?.withTransaction { block() } ?: block()

    private suspend fun requireCard(id: Int): VirtualCard =
        cardDao.getCardById(id) ?: throw IllegalArgumentException("Card #$id does not exist")

    private suspend fun appendOperation(operation: BusinessOperation) {
        database?.businessOperationDao()?.insert(operation)
    }
    val allCards: Flow<List<VirtualCard>> = cardDao.getAllCards()
    val allTransactions: Flow<List<CardTransaction>> = txDao.getAllTransactions()

    /** Все транзакции, в которых участвует карта [cardId] (входящие + исходящие). */
    fun transactionsForCard(cardId: Int): Flow<List<CardTransaction>> =
        txDao.getTransactionsForCard(cardId)

    suspend fun getCard(id: Int): VirtualCard? = cardDao.getCardById(id)
    suspend fun getAllCardsOnce(): List<VirtualCard> = cardDao.getAllCardsOnce()

    /** Creates an account and records any opening balance as a non-revenue adjustment. */
    suspend fun insertCard(card: VirtualCard): Long = atomic {
        require(card.name.isNotBlank()) { "Card name is required" }
        require(card.balance.isFinite()) { "Opening balance must be finite" }
        val id = cardDao.insertCard(card)
        if (card.balance != 0.0) {
            appendOperation(BusinessOperation(
                type = BusinessOperation.TYPE_ADJUSTMENT,
                direction = BusinessOperation.DIRECTION_TRANSFER,
                amountMinor = BusinessOperation.toMinor(kotlin.math.abs(card.balance)),
                fromCardId = if (card.balance > 0) CardTransaction.EXTERNAL_SOURCE_ID else id.toInt(),
                toCardId = if (card.balance > 0) id.toInt() else CardTransaction.EXTERNAL_SOURCE_ID,
                note = "Opening balance: ${card.name}"
            ))
        }
        id
    }
    suspend fun updateCard(card: VirtualCard) = atomic {
        val current = requireCard(card.id)
        require(current.balance == card.balance) { "Balance can only change through an operation" }
        require(current.isArchived == card.isArchived) { "Use archiveCard to close an account" }
        cardDao.updateCard(card)
    }

    /**
     * Удаляет карту, если она не является системной (isDefault=false).
     * Возвращает число удалённых строк (0 если карта была системной).
     */
    suspend fun deleteCard(card: VirtualCard): Int = atomic {
        require(!card.isDefault) { "System cards cannot be deleted" }
        val current = requireCard(card.id)
        require(kotlin.math.abs(current.balance) < 0.005) { "Transfer or reconcile the card balance before closing it" }
        require(txDao.countForCard(card.id) == 0) { "A card with transaction history must be archived, not deleted" }
        cardDao.deleteCardIfNotDefault(card.id)
    }

    /** Closes an empty non-system card while preserving its full audit trail. */
    suspend fun archiveCard(card: VirtualCard): Int = atomic {
        require(!card.isDefault) { "System cards cannot be archived" }
        val current = requireCard(card.id)
        require(kotlin.math.abs(current.balance) < 0.005) { "Transfer or reconcile the card balance before closing it" }
        val result = cardDao.archiveCard(card.id)
        if (result > 0) database?.auditEventDao()?.insert(AuditEvent(
            action = AuditEvent.ACTION_CARD_ARCHIVED,
            entityType = "VIRTUAL_CARD",
            entityId = card.id.toString(),
            reason = "Account closed after zero-balance reconciliation",
            beforeSnapshot = "name=${current.name}; balance=${current.balance}",
            afterSnapshot = "archived=true"
        ))
        result
    }

    /**
     * Переводит [amount] с карты [fromCardId] на карту [toCardId].
     * Атомарно: обновляет оба баланса и создаёт запись в истории транзакций.
     *
     * ВНЕШНИЕ КАРТЫ (Tashqidan / Tashqiga):
     *   Если одна из сторон — внешняя карта (kind = EXTERNAL_IN / EXTERNAL_OUT),
     *   вызов adjustBalance для неё пропускается. Баланс внешней карты концептуально
     *   бесконечен и не должен меняться. Это позволяет:
     *     • вносить деньги «из вне» (с Tashqidan на любую обычную) — обычная карта
     *       увеличивается, внешняя не трогается;
     *     • выводить деньги «вне» (с любой обычной на Tashqiga) — обычная карта
     *       уменьшается, внешняя не трогается.
     *   Валидация обязательного [note] для таких переводов — в FinansiViewModel.transfer.
     */
    suspend fun transfer(
        fromCardId: Int,
        toCardId: Int,
        amount: Double,
        note: String?
    ): Long = atomic {
        require(amount > 0.0 && amount.isFinite()) { "Transfer amount must be positive" }
        require(fromCardId != toCardId) { "Source and destination must differ" }
        val from = requireCard(fromCardId)
        val to = requireCard(toCardId)
        require(!from.isArchived && !to.isArchived) { "Archived cards cannot receive new transfers" }
        // An external source may only fund the business; an external sink may
        // only receive funds. This prevents accidental fictitious cash flows.
        require(from.kind != VirtualCard.KIND_EXTERNAL_OUT) { "Cannot transfer from an external sink" }
        require(to.kind != VirtualCard.KIND_EXTERNAL_IN) { "Cannot transfer to an external source" }
        if (VirtualCard.isExternalId(fromCardId) || VirtualCard.isExternalId(toCardId)) {
            require(!note.isNullOrBlank()) { "External transfers require a note" }
        }
        if (!VirtualCard.isExternalId(fromCardId)) cardDao.adjustBalance(fromCardId, -amount)
        if (!VirtualCard.isExternalId(toCardId)) cardDao.adjustBalance(toCardId, +amount)
        val id = txDao.insertTransaction(CardTransaction(
            fromCardId = fromCardId, toCardId = toCardId, amount = amount,
            note = note, type = CardTransaction.TYPE_CARD_TRANSFER
        ))
        appendOperation(BusinessOperation(
            occurredAt = System.currentTimeMillis(), type = BusinessOperation.TYPE_TRANSFER,
            direction = BusinessOperation.DIRECTION_TRANSFER,
            amountMinor = BusinessOperation.toMinor(amount), fromCardId = from.id,
            toCardId = to.id, cardTransactionId = id.toInt(), note = note
        ))
        id
    }

    /**
     * Зачисляет [amount] на главную карту (id=MAIN_CARD_ID) от «внешнего источника»
     * (id=0). Вызывается автоматически при оплате контракта арендатором.
     *
     * [note] — описание платежа (например, "To'lov: Akmal, 1 hafta").
     *
     * [contractId] — ID контракта ContractHistoryEntry, для которого выполняется
     *   зачисление. Заполняет поле `contractId` в создаваемой CardTransaction,
     *   что позволяет каскадно удалить/реверснуть эту запись при удалении
     *   контракта (см. ContractHistoryViewModel.deleteContractWithCascade).
     *   null допустим для обратной совместимости (старые вызовы).
     */
    suspend fun depositContractIncome(
        amount: Double,
        note: String?,
        contractId: Int? = null,
        renterId: Int? = null,
        scooterId: Int? = null
    ): Long = atomic {
        require(amount > 0.0 && amount.isFinite()) { "Income amount must be positive" }
        requireCard(VirtualCard.MAIN_CARD_ID)
        cardDao.adjustBalance(VirtualCard.MAIN_CARD_ID, +amount)
        val id = txDao.insertTransaction(CardTransaction(
            fromCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            toCardId = VirtualCard.MAIN_CARD_ID, amount = amount, note = note,
            type = CardTransaction.TYPE_CONTRACT_INCOME, contractId = contractId
        ))
        appendOperation(BusinessOperation(
            type = BusinessOperation.TYPE_RENT_PAYMENT,
            direction = BusinessOperation.DIRECTION_INCOME,
            amountMinor = BusinessOperation.toMinor(amount), renterId = renterId,
            scooterId = scooterId, contractId = contractId,
            fromCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            toCardId = VirtualCard.MAIN_CARD_ID, cardTransactionId = id.toInt(), note = note
        ))
        id
    }

    /** Financial rows are immutable. Delete is an auditable reversal, not a silent erase. */
    suspend fun reverseTransaction(id: Int, note: String) = atomic {
        val tx = txDao.getById(id) ?: throw IllegalArgumentException("Transaction #$id does not exist")
        require(!note.isBlank()) { "Reversal reason is required" }
        if (!VirtualCard.isExternalId(tx.fromCardId)) cardDao.adjustBalance(tx.fromCardId, tx.amount)
        if (!VirtualCard.isExternalId(tx.toCardId)) cardDao.adjustBalance(tx.toCardId, -tx.amount)
        txDao.deleteTransaction(id)
        // The original journal movement is marked reversed; the card balance
        // rollback above is the compensating accounting movement.
        database?.let { db ->
            db.businessOperationDao().getByCardTransactionId(id)?.let { original ->
                db.businessOperationDao().markReversed(original.id)
            }
            db.auditEventDao().insert(AuditEvent(
                action = AuditEvent.ACTION_CARD_TRANSACTION_REVERSED,
                entityType = "CARD_TRANSACTION",
                entityId = id.toString(),
                reason = note,
                beforeSnapshot = "from=${tx.fromCardId};to=${tx.toCardId};amount=${tx.amount}",
                afterSnapshot = "reversed"
            ))
        }
    }

    suspend fun updateTransaction(tx: CardTransaction) {
        throw UnsupportedOperationException("Financial transactions are immutable; reverse and create a new one")
    }
    suspend fun countCards(): Int = cardDao.count()

    /**
     * Возвращает все CardTransaction, привязанные к контракту [contractId].
     * Используется каскадным удалением контракта для определения, какие
     * записи нужно реверснуть и удалить.
     */
    suspend fun getCardTxForContract(contractId: Int): List<CardTransaction> =
        txDao.getForContractOnce(contractId)

    /**
     * Удаляет все CardTransaction, привязанные к контракту [contractId].
     * Возвращает количество удалённых строк. Сам баланс карт НЕ трогает —
     * вызывающий код должен сначала реверснуть баланс через adjustBalance
     * для каждой удаляемой записи, иначе деньги «потеряются».
     */
    suspend fun deleteCardTxForContract(contractId: Int): Int {
        // Room не возвращает count из @Query DELETE напрямую; используем
        // getForContractOnce для подсчёта, затем deleteForContract.
        val list = txDao.getForContractOnce(contractId)
        txDao.deleteForContract(contractId)
        return list.size
    }

    /**
     * Прямой доступ к VirtualCardDao.adjustBalance — нужен каскадному удалению
     * для реверса баланса главной карты при удалении оплаченного контракта.
     */
    suspend fun adjustCardBalance(cardId: Int, delta: Double) {
        cardDao.adjustBalance(cardId, delta)
    }
}
