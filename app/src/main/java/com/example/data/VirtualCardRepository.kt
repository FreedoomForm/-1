package com.example.data

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
    private val txDao: CardTransactionDao
) {
    val allCards: Flow<List<VirtualCard>> = cardDao.getAllCards()
    val allTransactions: Flow<List<CardTransaction>> = txDao.getAllTransactions()

    /** Все транзакции, в которых участвует карта [cardId] (входящие + исходящие). */
    fun transactionsForCard(cardId: Int): Flow<List<CardTransaction>> =
        txDao.getTransactionsForCard(cardId)

    suspend fun getCard(id: Int): VirtualCard? = cardDao.getCardById(id)
    suspend fun getAllCardsOnce(): List<VirtualCard> = cardDao.getAllCardsOnce()

    suspend fun insertCard(card: VirtualCard): Long = cardDao.insertCard(card)
    suspend fun updateCard(card: VirtualCard) = cardDao.updateCard(card)

    /**
     * Удаляет карту, если она не является системной (isDefault=false).
     * Возвращает число удалённых строк (0 если карта была системной).
     */
    suspend fun deleteCard(card: VirtualCard): Int =
        cardDao.deleteCardIfNotDefault(card.id)

    /**
     * Переводит [amount] с карты [fromCardId] на карту [toCardId].
     * Атомарно: обновляет оба баланса и создаёт запись в истории транзакций.
     */
    suspend fun transfer(
        fromCardId: Int,
        toCardId: Int,
        amount: Double,
        note: String?
    ): Long {
        cardDao.adjustBalance(fromCardId, -amount)
        cardDao.adjustBalance(toCardId, +amount)
        return txDao.insertTransaction(
            CardTransaction(
                fromCardId = fromCardId,
                toCardId = toCardId,
                amount = amount,
                note = note,
                type = CardTransaction.TYPE_CARD_TRANSFER
            )
        )
    }

    /**
     * Зачисляет [amount] на главную карту (id=MAIN_CARD_ID) от «внешнего источника»
     * (id=0). Вызывается автоматически при оплате контракта арендатором.
     *
     * [note] — описание платежа (например, "To'lov: Akmal, 1 hafta").
     */
    suspend fun depositContractIncome(amount: Double, note: String?): Long {
        cardDao.adjustBalance(VirtualCard.MAIN_CARD_ID, +amount)
        return txDao.insertTransaction(
            CardTransaction(
                fromCardId = CardTransaction.EXTERNAL_SOURCE_ID,
                toCardId = VirtualCard.MAIN_CARD_ID,
                amount = amount,
                note = note,
                type = CardTransaction.TYPE_CONTRACT_INCOME
            )
        )
    }

    suspend fun deleteTransaction(id: Int) = txDao.deleteTransaction(id)
    suspend fun countCards(): Int = cardDao.count()
}
