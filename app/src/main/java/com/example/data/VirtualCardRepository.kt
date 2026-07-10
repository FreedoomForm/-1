package com.example.data

import kotlinx.coroutines.flow.Flow

class VirtualCardRepository(
    private val cardDao: VirtualCardDao,
    private val txDao: CardTransactionDao
) {
    val allCards: Flow<List<VirtualCard>> = cardDao.getAllCards()
    val allTransactions: Flow<List<CardTransaction>> = txDao.getAllTransactions()

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
        // Списываем с источника
        cardDao.adjustBalance(fromCardId, -amount)
        // Зачисляем на назначение
        cardDao.adjustBalance(toCardId, +amount)
        // Записываем в историю
        return txDao.insertTransaction(
            CardTransaction(
                fromCardId = fromCardId,
                toCardId = toCardId,
                amount = amount,
                note = note
            )
        )
    }

    suspend fun deleteTransaction(id: Int) = txDao.deleteTransaction(id)
    suspend fun countCards(): Int = cardDao.count()
}
