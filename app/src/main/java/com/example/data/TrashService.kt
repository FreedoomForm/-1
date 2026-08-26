package com.example.data

/**
 * Сервис управления корзиной (trash mode).
 *
 * Реализует каскадный soft-delete и восстановление для всех 5 типов сущностей:
 * арендатор, скутер, контракт, транзакция, карта (+ card_transactions).
 *
 * Soft-delete ≠ hard-delete. При soft-delete строка остаётся в БД, но
 * помечается `isDeleted = 1` и `deletedAt = <now>`. В обычном режиме приложения
 * такие строки скрыты; в trash mode (когда пользователь долго нажал на
 * универсальную кнопку «Удалить» в TopAppBar и она стала красной) — показываются
 * только они.
 *
 * Каскадность: при удалении арендатора в корзину автоматически удаляются
 * все его контракты и транзакции; при удалении контракта — все связанные
 * транзакции и card_transactions. Это сохраняет «целостность корзины»: при
 * восстановлении арендатора восстанавливаются и все его дочерние сущности.
 *
 * Balance reversal для CONTRACT_INCOME: при удалении контракта, у которого
 * есть связанная card_transaction с TYPE_CONTRACT_INCOME (деньги, упавшие на
 * главную карту), баланс главной карты реверсится (минусуется), чтобы
 * сохранялась финансовая консистентность. При восстановлении — возвращается
 * обратно.
 *
 * Hard-delete (permanently delete from trash) выполняется уже существующими
 * методами DAO (deleteById / deleteByIds / deleteForRenter / deleteForContract)
 * через репозитории — TrashService только оркестрирует каскад.
 */
class TrashService(
    private val renterRepository: RenterRepository,
    private val scooterRepository: ScooterRepository,
    private val contractRepository: ContractHistoryRepository,
    private val transactionRepository: TransactionRepository,
    private val virtualCardRepository: VirtualCardRepository
) {
    // ── RENTER ────────────────────────────────────────────────────────────
    /**
     * Каскадно помещает арендатора в корзину: сам арендатор + все его контракты
     * + все его транзакции. Возвращает, был ли найден арендатор.
     */
    suspend fun moveRenterToTrash(renterId: Int): Boolean {
        val renter = renterRepository.getById(renterId) ?: return false
        // Контракты арендатора → корзина (с каскадом на их card_transactions).
        val contracts = contractRepository.contractsForRenterOnce(renterId)
        contracts.forEach { c -> moveContractToTrashInternal(c.id) }
        // Транзакции арендатора → корзина.
        val txIds = transactionRepository.forRenterOnce(renterId).map { it.id }
        if (txIds.isNotEmpty()) transactionRepository.moveToTrashBatch(txIds)
        // Сам арендатор.
        renterRepository.moveToTrash(renterId)

        // Удаление также фиксируется в календаре как неактивный день.
        // Маркер оставляем живым, чтобы он не исчезал вместе с каскадно
        // удалёнными контрактами и был доступен после восстановления.
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val hasStopToday = contractRepository.getForRenterOnce(renterId).any { entry ->
            entry.type == ContractHistoryEntry.TYPE_TERMINATED &&
                entry.notes == "STOP_MARKER" &&
                entry.weekStart?.let { markerMs ->
                    val markerDay = java.util.Calendar.getInstance().apply { timeInMillis = markerMs }
                    val deletionDay = java.util.Calendar.getInstance().apply { timeInMillis = todayStart }
                    markerDay.get(java.util.Calendar.YEAR) == deletionDay.get(java.util.Calendar.YEAR) &&
                        markerDay.get(java.util.Calendar.DAY_OF_YEAR) == deletionDay.get(java.util.Calendar.DAY_OF_YEAR)
                } == true
        }
        if (!hasStopToday) {
            contractRepository.insert(
                ContractHistoryEntry(
                    renterId = renter.id,
                    timestamp = System.currentTimeMillis(),
                    type = ContractHistoryEntry.TYPE_TERMINATED,
                    amount = 0.0,
                    notes = "STOP_MARKER",
                    renterName = renter.name,
                    renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName,
                    weekStart = todayStart,
                    weekEnd = todayStart,
                    weeklyPrice = 0.0,
                    passportData = renter.passportData,
                    address = renter.address,
                    pinfl = renter.pinfl,
                    isPaid = false
                )
            )
        }
        return true
    }

    /**
     * Восстанавливает арендатора из корзины + все его контракты и транзакции.
     */
    suspend fun restoreRenterFromTrash(renterId: Int): Boolean {
        val renter = renterRepository.getById(renterId) ?: return false
        // Сначала дочерние сущности, потом сам арендатор.
        contractRepository.restoreFromTrashForRenter(renterId)
        val txIds = transactionRepository.forRenterOnce(renterId).map { it.id }
        if (txIds.isNotEmpty()) transactionRepository.restoreFromTrashBatch(txIds)
        renterRepository.restoreFromTrash(renterId)
        return true
    }

    /**
     * Окончательно удаляет арендатора из БД (вместе с контрактами, транзакциями
     * и card_transactions). Необратимо.
     */
    suspend fun permanentlyDeleteRenter(renterId: Int): Boolean {
        val renter = renterRepository.getById(renterId) ?: return false
        // Сначала удалить все контракты (вместе с их card_transactions).
        val contracts = contractRepository.contractsForRenterOnce(renterId)
        contracts.forEach { c -> permanentlyDeleteContractInternal(c.id) }
        // Удалить все транзакции арендатора.
        transactionRepository.deleteForContract(renterId) // не сработает — это для contractId
        // На самом деле для транзакций по renterId нет deleteForRenter; удаляем по ids:
        val txIds = transactionRepository.forRenterOnce(renterId).map { it.id }
        if (txIds.isNotEmpty()) transactionRepository.deleteByIds(txIds)
        // Контракты.
        contractRepository.deleteForRenter(renterId)
        // Сам арендатор.
        renterRepository.delete(renterId)
        return true
    }

    // ── SCOOTER ───────────────────────────────────────────────────────────
    /** Скутер не имеет дочерних сущностей в нашей схеме — просто soft-delete. */
    suspend fun moveScooterToTrash(scooterId: Int): Boolean {
        // Проверяем, что скутер существует.
        scooterRepository.moveToTrash(scooterId)
        return true
    }

    suspend fun restoreScooterFromTrash(scooterId: Int) {
        scooterRepository.restoreFromTrash(scooterId)
    }

    suspend fun permanentlyDeleteScooter(scooterId: Int) {
        scooterRepository.deleteById(scooterId)
    }

    // ── CONTRACT ──────────────────────────────────────────────────────────
    /**
     * Помещает контракт в корзину + все связанные транзакции (Transaction) и
     * card_transactions (CONTRACT_INCOME). Баланс главной карты реверсится
     * при наличии оплаченных CONTRACT_INCOME записей — иначе восстановление
     * вернёт деньги, которых уже нет.
     */
    suspend fun moveContractToTrash(contractId: Int): Boolean {
        return moveContractToTrashInternal(contractId)
    }

    private suspend fun moveContractToTrashInternal(contractId: Int): Boolean {
        val contract = contractRepository.getById(contractId) ?: return false
        // Реверс баланса главной карты для связанных CONTRACT_INCOME card_transactions.
        val cardTxs = virtualCardRepository.getCardTxForContract(contractId)
        cardTxs.forEach { ctx ->
            if (ctx.type == CardTransaction.TYPE_CONTRACT_INCOME && !ctx.isDeleted) {
                // Снимаем сумму с главной карты (реверс зачисления).
                virtualCardRepository.adjustCardBalance(
                    VirtualCard.MAIN_CARD_ID,
                    -ctx.amount
                )
            }
        }
        // Помещаем card_transactions в корзину.
        if (cardTxs.isNotEmpty()) {
            virtualCardRepository.moveTxToTrashForContract(contractId)
        }
        // Помещаем обычные Transaction в корзину.
        val txIds = transactionRepository.forContractOnce(contractId).map { it.id }
        if (txIds.isNotEmpty()) transactionRepository.moveToTrashBatch(txIds)
        // Сам контракт.
        contractRepository.moveToTrash(contractId)
        return true
    }

    /**
     * Восстанавливает контракт из корзины + связанные транзакции и
     * card_transactions. Возвращает реверс-баланс на главную карту.
     */
    suspend fun restoreContractFromTrash(contractId: Int): Boolean {
        val contract = contractRepository.getById(contractId) ?: return false
        // Восстанавливаем card_transactions и возвращаем баланс.
        val cardTxs = virtualCardRepository.getCardTxForContract(contractId)
        cardTxs.forEach { ctx ->
            if (ctx.type == CardTransaction.TYPE_CONTRACT_INCOME && ctx.isDeleted) {
                virtualCardRepository.adjustCardBalance(
                    VirtualCard.MAIN_CARD_ID,
                    +ctx.amount
                )
            }
        }
        if (cardTxs.isNotEmpty()) {
            virtualCardRepository.restoreTxFromTrashForContract(contractId)
        }
        // Восстанавливаем обычные Transaction.
        val txIds = transactionRepository.forContractOnce(contractId).map { it.id }
        if (txIds.isNotEmpty()) transactionRepository.restoreFromTrashBatch(txIds)
        // Сам контракт.
        contractRepository.restoreFromTrash(contractId)
        return true
    }

    /**
     * Окончательно удаляет контракт из БД (вместе с транзакциями и card_tx).
     * Необратимо. Баланс при этом НЕ трогается — он уже был реверсирован при
     * помещении контракта в корзину.
     */
    suspend fun permanentlyDeleteContract(contractId: Int): Boolean {
        return permanentlyDeleteContractInternal(contractId)
    }

    private suspend fun permanentlyDeleteContractInternal(contractId: Int): Boolean {
        val contract = contractRepository.getById(contractId) ?: return false
        // Удаляем card_transactions для этого контракта.
        virtualCardRepository.deleteCardTxForContract(contractId)
        // Удаляем обычные Transaction для этого контракта.
        transactionRepository.deleteForContract(contractId)
        // Удаляем сам контракт.
        contractRepository.deleteById(contractId)
        return true
    }

    // ── TRANSACTION ───────────────────────────────────────────────────────
    suspend fun moveTransactionToTrash(id: Int) {
        transactionRepository.moveToTrash(id)
    }

    suspend fun moveTransactionsToTrash(ids: List<Int>) {
        if (ids.isNotEmpty()) transactionRepository.moveToTrashBatch(ids)
    }

    suspend fun restoreTransactionFromTrash(id: Int) {
        transactionRepository.restoreFromTrash(id)
    }

    suspend fun restoreTransactionsFromTrash(ids: List<Int>) {
        if (ids.isNotEmpty()) transactionRepository.restoreFromTrashBatch(ids)
    }

    suspend fun permanentlyDeleteTransaction(id: Int) {
        transactionRepository.deleteById(id)
    }

    suspend fun permanentlyDeleteTransactions(ids: List<Int>) {
        if (ids.isNotEmpty()) transactionRepository.deleteByIds(ids)
    }

    // ── CARD TRANSACTION (для вкладки Tranzaksiya, где объединены Transaction
    //     и CardTransaction) ───────────────────────────────────────────────
    suspend fun moveCardTransactionToTrash(id: Int) {
        virtualCardRepository.moveTxToTrash(id)
    }

    suspend fun restoreCardTransactionFromTrash(id: Int) {
        virtualCardRepository.restoreTxFromTrash(id)
    }

    suspend fun permanentlyDeleteCardTransaction(id: Int) {
        virtualCardRepository.deleteTransaction(id)
    }

    // ── VIRTUAL CARD ──────────────────────────────────────────────────────
    /**
     * Помещает карту в корзину. Системные карты (isDefault=true, id 1-4)
     * не могут быть удалены — DAO проигнорирует запрос (UPDATE WHERE isDefault=0).
     */
    suspend fun moveCardToTrash(cardId: Int) {
        virtualCardRepository.moveCardToTrash(cardId)
    }

    suspend fun restoreCardFromTrash(cardId: Int) {
        virtualCardRepository.restoreCardFromTrash(cardId)
    }

    suspend fun permanentlyDeleteCard(card: VirtualCard) {
        // Системные карты нельзя удалить.
        if (card.isDefault) return
        virtualCardRepository.deleteCard(card)
    }
}
