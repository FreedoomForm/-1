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
        // §10 / Batch 8: previously insertCard was completely unaudited —
        // the only signal was a new row in virtual_cards. A user creating
        // accounts left zero audit trail, which made post-incident reviews
        // impossible. ACTION_CARD_CREATE records the creation event with
        // the opening-balance snapshot for later reconciliation.
        database?.auditEventDao()?.insert(AuditEvent(
            actor = "LOCAL_SYSTEM",
            action = AuditEvent.ACTION_CARD_CREATE,
            entityType = "VIRTUAL_CARD",
            entityId = id.toString(),
            reason = "Card created via UI",
            beforeSnapshot = null,
            afterSnapshot = "name=${card.name}; balance=${card.balance}; kind=${card.kind}"
        ))
        id
    }
    suspend fun updateCard(card: VirtualCard) = atomic {
        val current = requireCard(card.id)
        require(current.isArchived == card.isArchived) { "Use archiveCard to close an account" }
        // Balance delta is allowed — recorded as a TYPE_ADJUSTMENT operation
        // (mirror of insertCard's opening-balance logic). Previously the repo
        // threw "Balance can only change through an operation" which made the
        // edit-card dialog silently fail whenever the user changed the balance
        // field.
        val delta = card.balance - current.balance
        val balanceEdited = kotlin.math.abs(delta) >= 0.005
        if (balanceEdited) {
            // Adjust the actual stored balance to match what the user entered.
            cardDao.adjustBalance(card.id, delta)
            appendOperation(BusinessOperation(
                type = BusinessOperation.TYPE_ADJUSTMENT,
                direction = if (delta > 0) BusinessOperation.DIRECTION_INCOME else BusinessOperation.DIRECTION_EXPENSE,
                amountMinor = BusinessOperation.toMinor(kotlin.math.abs(delta)),
                fromCardId = if (delta > 0) CardTransaction.EXTERNAL_SOURCE_ID else card.id,
                toCardId = if (delta > 0) card.id else CardTransaction.EXTERNAL_SOURCE_ID,
                note = "Manual balance edit: ${current.name}"
            ))
        }
        // §10 / Batch 8 (was M2/H3): the previous audit code reused
        // ACTION_CARD_TRANSACTION_REVERSED for a non-reversal balance edit —
        // a misleading action code that polluted card-reversal reports.
        // Now we emit ACTION_CARD_UPDATE whenever ANY card field changes
        // (name, color, info, kind, or balance), with before/after snapshots
        // capturing every edited dimension. The audit is emitted even when
        // only non-financial fields change (e.g. user renames a card) —
        // previously such edits left zero audit trail.
        val nameChanged = current.name != card.name
        val colorChanged = current.colorHex != card.colorHex
        val kindChanged = current.kind != card.kind
        val infoChanged = current.info != card.info
        if (balanceEdited || nameChanged || colorChanged || kindChanged || infoChanged) {
            database?.auditEventDao()?.insert(AuditEvent(
                actor = "LOCAL_SYSTEM",
                action = AuditEvent.ACTION_CARD_UPDATE,
                entityType = "VIRTUAL_CARD",
                entityId = card.id.toString(),
                reason = when {
                    balanceEdited && (nameChanged || colorChanged || kindChanged || infoChanged) ->
                        "Card fields + balance edited via card form"
                    balanceEdited -> "Balance edited via card form"
                    else -> "Card metadata edited via card form"
                },
                beforeSnapshot = "name=${current.name}; color=${current.colorHex}; kind=${current.kind}; info=${current.info}; balance=${current.balance}",
                afterSnapshot = "name=${card.name}; color=${card.colorHex}; kind=${card.kind}; info=${card.info}; balance=${current.balance + delta}; delta=$delta"
            ))
        }
        // Persist the rest (name/colorHex/info/kind). Use a copy that keeps
        // the freshly-adjusted balance to avoid overwriting it with the
        // pre-adjustment value.
        cardDao.updateCard(card.copy(balance = current.balance + delta))
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
    suspend fun archiveCard(card: VirtualCard, actor: String = "LOCAL_SYSTEM"): Int = atomic {
        require(!card.isDefault) { "System cards cannot be archived" }
        val current = requireCard(card.id)
        require(kotlin.math.abs(current.balance) < 0.005) { "Transfer or reconcile the card balance before closing it" }
        val result = cardDao.archiveCard(card.id)
        if (result > 0) database?.auditEventDao()?.insert(AuditEvent(
            actor = actor,
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
     * Reopens a previously-archived non-system card. Per §3: archived cards
     * can be unarchived only if their balance is still zero (no reconciliation
     * needed) — otherwise user must reconcile first.
     */
    suspend fun unarchiveCard(card: VirtualCard, actor: String = "LOCAL_SYSTEM"): Int = atomic {
        require(!card.isDefault) { "System cards cannot be archived/unarchived" }
        val current = requireCard(card.id)
        require(current.isArchived) { "Card is not archived" }
        require(kotlin.math.abs(current.balance) < 0.005) { "Cannot unarchive a card with non-zero balance — reconcile first" }
        val result = cardDao.unarchiveCard(card.id)
        if (result > 0) database?.auditEventDao()?.insert(AuditEvent(
            actor = actor,
            action = AuditEvent.ACTION_CARD_UNARCHIVED,
            entityType = "VIRTUAL_CARD",
            entityId = card.id.toString(),
            reason = "Account reopened by user",
            beforeSnapshot = "archived=true",
            afterSnapshot = "archived=false"
        ))
        result
    }

    /**
     * §3: Закрывает карту с переносом остатка на другую карту.
     *
     * Если на закрываемой карте есть деньги (положительный или отрицательный
     * баланс), они переносятся на [toCardId] отдельной операцией перевода.
     *
     * ВАЖНО: корректно обрабатывает отрицательный баланс (долг на карте).
     * При отрицательном балансе: долг переносится на целевую карту.
     *
     * @param card карта для закрытия (должна существовать и не быть системной).
     * @param toCardId карта-приёмник для переноса остатка (должна быть активной).
     * @param note описание причины закрытия.
     * @param actor инициатор операции (для аудит-трейла).
     */
    suspend fun closeCardWithBalanceTransfer(
        card: VirtualCard,
        toCardId: Int,
        note: String,
        actor: String = "LOCAL_SYSTEM"
    ): Int = atomic {
        require(!card.isDefault) { "System cards cannot be closed" }
        require(card.id != toCardId) { "Cannot transfer to the same card being closed" }
        val current = requireCard(card.id)
        val target = requireCard(toCardId)
        require(!target.isArchived) { "Target card must be active" }
        require(note.isNotBlank()) { "Close reason is required" }
        
        // Если есть остаток — сначала переносим его на карту-приёмник.
        if (kotlin.math.abs(current.balance) >= 0.005) {
            val balanceToTransfer = current.balance
            val absBalance = kotlin.math.abs(balanceToTransfer)
            
            // Обнуляем баланс закрываемой карты
            cardDao.adjustBalance(card.id, -balanceToTransfer)
            // Переносим на целевую карту (включая отрицательный баланс)
            cardDao.adjustBalance(toCardId, +balanceToTransfer)
            
            // Определяем направление для корректной записи операции
            val (fromCard, toCard, txAmount) = if (balanceToTransfer >= 0) {
                Triple(card.id, toCardId, balanceToTransfer)
            } else {
                // Отрицательный баланс = долг. Записываем как перевод долга.
                Triple(toCardId, card.id, absBalance)
            }
            
            val txId = txDao.insertTransaction(CardTransaction(
                fromCardId = fromCard,
                toCardId = toCard,
                amount = absBalance,
                note = "Карта закрывается: $note",
                type = CardTransaction.TYPE_CARD_TRANSFER
            ))
            appendOperation(BusinessOperation(
                occurredAt = System.currentTimeMillis(),
                type = BusinessOperation.TYPE_TRANSFER,
                direction = BusinessOperation.DIRECTION_TRANSFER,
                amountMinor = BusinessOperation.toMinor(absBalance),
                fromCardId = fromCard,
                toCardId = toCard,
                cardTransactionId = txId.toInt(),
                note = "Close-with-transfer: $note"
            ))
            database?.auditEventDao()?.insert(AuditEvent(
                actor = actor,
                action = AuditEvent.ACTION_CARD_TRANSACTION_REVERSED,
                entityType = "VIRTUAL_CARD",
                entityId = card.id.toString(),
                reason = "Balance transferred to #$toCardId before closure",
                beforeSnapshot = "balance=${current.balance}",
                afterSnapshot = "balance=0; transferred=$balanceToTransfer; toCard=$toCardId"
            ))
        }
        // Теперь баланс = 0, можно архивировать.
        val result = cardDao.archiveCard(card.id)
        if (result > 0) database?.auditEventDao()?.insert(AuditEvent(
            actor = actor,
            action = AuditEvent.ACTION_CARD_ARCHIVED,
            entityType = "VIRTUAL_CARD",
            entityId = card.id.toString(),
            reason = "Card closed with balance transfer: $note",
            beforeSnapshot = "name=${current.name}; balance=${current.balance}",
            afterSnapshot = "archived=true; transferredTo=$toCardId"
        ))
        result
    }

    /**
     * Переводит [amount] с карты [fromCardId] на карту [toCardId].
     * Атомарно: обновляет оба баланса и создаёт запись в истории транзакций.
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
        require(from.kind != VirtualCard.KIND_EXTERNAL_OUT) { "Cannot transfer from an external sink" }
        require(to.kind != VirtualCard.KIND_EXTERNAL_IN) { "Cannot transfer to an external source" }
        if (!from.isExternal) {
            require(from.balance + 0.005 >= amount) { "Insufficient available balance" }
        }
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
    suspend fun reverseTransaction(id: Int, note: String, actor: String = "LOCAL_SYSTEM") = atomic {
        val tx = txDao.getById(id) ?: throw IllegalArgumentException("Transaction #$id does not exist")
        require(!note.isBlank()) { "Reversal reason is required" }
        if (!VirtualCard.isExternalId(tx.fromCardId)) cardDao.adjustBalance(tx.fromCardId, tx.amount)
        if (!VirtualCard.isExternalId(tx.toCardId)) cardDao.adjustBalance(tx.toCardId, -tx.amount)
        txDao.deleteTransaction(id)
        database?.let { db ->
            db.businessOperationDao().getByCardTransactionId(id)?.let { original ->
                BusinessOperationRepository(db).reverse(original.id, note)
            }
            db.auditEventDao().insert(AuditEvent(
                actor = actor,
                action = AuditEvent.ACTION_CARD_TRANSACTION_REVERSED,
                entityType = "CARD_TRANSACTION",
                entityId = id.toString(),
                reason = note,
                beforeSnapshot = "from=${tx.fromCardId};to=${tx.toCardId};amount=${tx.amount}",
                afterSnapshot = "reversed"
            ))
        }
    }

    suspend fun updateTransaction(tx: CardTransaction) = atomic {
        val current = txDao.getById(tx.id)
            ?: throw IllegalArgumentException("Transaction #${tx.id} does not exist")
        // If nothing changed financially — only note/type — just update the row.
        val sameFromTo = current.fromCardId == tx.fromCardId &&
                         current.toCardId == tx.toCardId &&
                         kotlin.math.abs(current.amount - tx.amount) < 0.005
        if (!sameFromTo) {
            // Reverse the old movement (restore balances to pre-transaction state).
            if (!VirtualCard.isExternalId(current.fromCardId)) cardDao.adjustBalance(current.fromCardId, current.amount)
            if (!VirtualCard.isExternalId(current.toCardId)) cardDao.adjustBalance(current.toCardId, -current.amount)
            // Apply the new movement.
            if (!VirtualCard.isExternalId(tx.fromCardId)) cardDao.adjustBalance(tx.fromCardId, -tx.amount)
            if (!VirtualCard.isExternalId(tx.toCardId)) cardDao.adjustBalance(tx.toCardId, tx.amount)
            // Update the linked BusinessOperation row, if any.
            database?.let { db ->
                db.businessOperationDao().getByCardTransactionId(tx.id)?.let { original ->
                    db.businessOperationDao().update(original.copy(
                        amountMinor = BusinessOperation.toMinor(tx.amount),
                        fromCardId = tx.fromCardId,
                        toCardId = tx.toCardId,
                        note = tx.note ?: original.note
                    ))
                }
                db.auditEventDao().insert(AuditEvent(
                    actor = "LOCAL_SYSTEM",
                    action = AuditEvent.ACTION_CARD_TRANSACTION_REVERSED,
                    entityType = "CARD_TRANSACTION",
                    entityId = tx.id.toString(),
                    reason = "Transaction edited via UI",
                    beforeSnapshot = "from=${current.fromCardId};to=${current.toCardId};amount=${current.amount}",
                    afterSnapshot = "from=${tx.fromCardId};to=${tx.toCardId};amount=${tx.amount}"
                ))
            }
        }
        txDao.updateTransaction(tx)
    }
    suspend fun countCards(): Int = cardDao.count()

    suspend fun getCardTxForContract(contractId: Int): List<CardTransaction> =
        txDao.getForContractOnce(contractId)

    suspend fun deleteCardTxForContract(contractId: Int): Int {
        val list = txDao.getForContractOnce(contractId)
        txDao.deleteForContract(contractId)
        return list.size
    }

    suspend fun adjustCardBalance(cardId: Int, delta: Double) {
        cardDao.adjustBalance(cardId, delta)
    }
}
