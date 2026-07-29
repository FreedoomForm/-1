package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CardTransaction
import com.example.data.VirtualCard
import com.example.data.VirtualCardRepository
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel для вкладки «Finansi».
 *
 * Отвечает за:
 *   • CRUD виртуальных карт (2 системные по умолчанию + пользовательские)
 *   • Перевод денег между картами (с атомарным обновлением балансов)
 *   • Лента транзакций (отображается на вкладке Tranzaksiya)
 *   • Уведомление нативных виджетов об изменениях
 */
class FinansiViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: VirtualCardRepository

    val cards: StateFlow<List<VirtualCard>>
    val transactions: StateFlow<List<CardTransaction>>

    /** UI feedback channel — emit (success, message) tuples for toast display. */
    private val _userMessage = MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<Pair<Boolean, String>> = _userMessage.asSharedFlow()

    /** Поток транзакций для конкретной карты (используется экраном истории карты). */
    fun transactionsForCard(cardId: Int): Flow<List<CardTransaction>> =
        repository.transactionsForCard(cardId)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = VirtualCardRepository(
            database.virtualCardDao(),
            database.cardTransactionDao(),
            database
        )
        cards = repository.allCards.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        transactions = repository.allTransactions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    /** Создаёт новую пользовательскую карту. */
    fun addCard(name: String, balance: Double, colorHex: String, info: String?) {
        viewModelScope.launch {
            try {
                if (name.isBlank()) {
                    _userMessage.emit(false to "Karta nomi bo'sh bo'lishi mumkin emas")
                    return@launch
                }
                // Duplicate-name check (case-insensitive, exclude archived).
                val dupCount = repository.getAllCardsOnce()
                    .count { it.name.trim().equals(name.trim(), ignoreCase = true) }
                if (dupCount > 0) {
                    _userMessage.emit(false to "Bunday nomdagi karta allaqachon mavjud: ${name.trim()}")
                    return@launch
                }
                repository.insertCard(
                    VirtualCard(
                        name = name,
                        balance = balance,
                        colorHex = colorHex,
                        info = info,
                        isDefault = false
                    )
                )
                WidgetUpdater.updateAll(getApplication())
                _userMessage.emit(true to "Karta yaratildi: ${name.trim()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add card", e)
                _userMessage.emit(false to "Karta yaratilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    /** Обновляет существующую карту (имя, цвет, инфо). Баланс можно менять переводами. */
    fun updateCard(card: VirtualCard) {
        viewModelScope.launch {
            try {
                if (card.name.isBlank()) {
                    _userMessage.emit(false to "Karta nomi bo'sh bo'lishi mumkin emas")
                    return@launch
                }
                // Duplicate-name check excluding self.
                val dupCount = repository.getAllCardsOnce()
                    .count { it.id != card.id && it.name.trim().equals(card.name.trim(), ignoreCase = true) }
                if (dupCount > 0) {
                    _userMessage.emit(false to "Bunday nomdagi karta allaqachon mavjud: ${card.name.trim()}")
                    return@launch
                }
                repository.updateCard(card)
                WidgetUpdater.updateAll(getApplication())
                _userMessage.emit(true to "Karta yangilandi: ${card.name.trim()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update card", e)
                _userMessage.emit(false to "Karta yangilanmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    /** Удаляет карту, если она не системная. Системные (isDefault=true) не трогает.
     *  Если на карте есть ненулевой баланс — он автоматически переносится на
     *  главную карту перед закрытием (раньше операция молча fail-илась). */
    fun deleteCard(card: VirtualCard) {
        viewModelScope.launch {
            try {
                val operator = com.example.data.OperatorSessionRepository(getApplication())
                    .requirePermission(AppDatabase.getDatabase(getApplication()), com.example.data.AccessPolicy.FINANCE_REVERSE)
                val current = repository.getCard(card.id) ?: card
                if (kotlin.math.abs(current.balance) >= 0.005) {
                    // Auto-transfer balance to MAIN card before closing —
                    // previously the repo threw "Transfer or reconcile..." and
                    // the VM silently swallowed the exception, so the user
                    // saw nothing happen.
                    repository.closeCardWithBalanceTransfer(
                        card = current,
                        toCardId = com.example.data.VirtualCard.MAIN_CARD_ID,
                        note = "Karta o'chirildi: balans avtomatik ko'chirildi",
                        actor = operator.displayName
                    )
                } else {
                    repository.archiveCard(card, operator.displayName)
                }
                com.example.data.TrashService(AppDatabase.getDatabase(getApplication()))
                    .snapshotCard(card, "Card archived by ${operator.displayName}")
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete card", e)
            }
        }
    }

    /**
     * Reopens a previously-archived card. Per PLAN_UNIVERSAL_ACCOUNTING §3:
     * archived cards shown in separate UI section, closure only via balance
     * transfer; reopening reverses the archive flag.
     */
    fun unarchiveCard(card: VirtualCard) {
        viewModelScope.launch {
            try {
                val operator = com.example.data.OperatorSessionRepository(getApplication())
                    .requirePermission(AppDatabase.getDatabase(getApplication()), com.example.data.AccessPolicy.FINANCE_REVERSE)
                repository.unarchiveCard(card, operator.displayName)
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unarchive card", e)
            }
        }
    }

    /**
     * §3: Закрывает карту с переносом остатка на другую активную карту.
     * Вызывается из UI FinansiPanel — кнопка «Yopish» на карточке карты.
     */
    fun closeCardWithTransfer(card: VirtualCard, toCardId: Int, note: String) {
        viewModelScope.launch {
            try {
                val operator = com.example.data.OperatorSessionRepository(getApplication())
                    .requirePermission(AppDatabase.getDatabase(getApplication()), com.example.data.AccessPolicy.FINANCE_REVERSE)
                repository.closeCardWithBalanceTransfer(card, toCardId, note, operator.displayName)
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close card with balance transfer", e)
            }
        }
    }

    /**
     * Переводит [amount] с карты [fromCardId] на карту [toCardId].
     * Если [reversed] = true — меняет направление (для кнопки разворота стрелки).
     *
     * ВНЕШНИЕ ПЕРЕВОДЫ:
     *   Если хотя бы одна из сторон — внешняя карта (Tashqidan / Tashqiga),
     *   параметр [note] становится ОБЯЗАТЕЛЬНЫМ. Пользователь должен указать,
     *   для чего вносится/выводится сумма. Без описания перевод отклоняется.
     */
    fun transfer(
        fromCardId: Int,
        toCardId: Int,
        amount: Double,
        note: String?,
        reversed: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                val actualFrom = if (reversed) toCardId else fromCardId
                val actualTo = if (reversed) fromCardId else toCardId
                if (actualFrom == actualTo) {
                    Log.w(TAG, "Cannot transfer: source and destination are the same card")
                    return@launch
                }
                if (amount <= 0.0) {
                    Log.w(TAG, "Cannot transfer: amount must be positive")
                    return@launch
                }
                // Внешний перевод (с участием Tashqidan / Tashqiga) требует описание.
                val involvesExternal =
                    VirtualCard.isExternalId(actualFrom) || VirtualCard.isExternalId(actualTo)
                if (involvesExternal && note.isNullOrBlank()) {
                    Log.w(TAG, "Cannot transfer: external transfer requires a non-empty note")
                    return@launch
                }
                repository.transfer(actualFrom, actualTo, amount, note)
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed", e)
            }
        }
    }

    /**
     * Зачисляет платёж по контракту на главную карту.
     * Вызывается из RenterViewModel.applyWeeklyPayment — автоматическое
     * поступление денег из контракта на «Glavnaya».
     */
    suspend fun depositContractIncome(amount: Double, note: String?) {
        try {
            repository.depositContractIncome(amount, note)
            WidgetUpdater.updateAll(getApplication())
        } catch (e: Exception) {
            Log.e(TAG, "depositContractIncome failed", e)
        }
    }

    /** Удаляет запись из истории транзакций (не откатывая балансы). */
    fun deleteTransaction(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val operator = com.example.data.OperatorSessionRepository(getApplication())
                    .requirePermission(AppDatabase.getDatabase(getApplication()), com.example.data.AccessPolicy.FINANCE_REVERSE)
                repository.reverseTransaction(id, "Bekor qilindi: foydalanuvchi so'rovi", operator.displayName)
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete transaction", e)
            }
        }
    }

    /**
     * Обновляет запись транзакции в истории (поля from/to/amount/note).
     * ВНИМАНИЕ: как и [deleteTransaction], НЕ трогает балансы карт —
     * правка носит характер «исправления описания/суммы записи».
     * Если нужно реально переместить деньги — используйте [transfer].
     */
    fun updateTransaction(tx: CardTransaction) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.updateTransaction(tx)
                WidgetUpdater.updateAll(getApplication())
                _userMessage.emit(true to "Tranzaksiya yangilandi")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update transaction", e)
                _userMessage.emit(false to "Tranzaksiya yangilanmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    companion object {
        private const val TAG = "FinansiViewModel"
    }
}
