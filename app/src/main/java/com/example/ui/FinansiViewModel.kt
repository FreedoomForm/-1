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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    init {
        val database = AppDatabase.getDatabase(application)
        repository = VirtualCardRepository(
            database.virtualCardDao(),
            database.cardTransactionDao()
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add card", e)
            }
        }
    }

    /** Обновляет существующую карту (имя, цвет, инфо). Баланс можно менять переводами. */
    fun updateCard(card: VirtualCard) {
        viewModelScope.launch {
            try {
                repository.updateCard(card)
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update card", e)
            }
        }
    }

    /** Удаляет карту, если она не системная. Системные (isDefault=true) не трогает. */
    fun deleteCard(card: VirtualCard) {
        viewModelScope.launch {
            try {
                val deleted = repository.deleteCard(card)
                if (deleted == 0) {
                    Log.w(TAG, "Attempted to delete default card #${card.id} — blocked")
                }
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete card", e)
            }
        }
    }

    /**
     * Переводит [amount] с карты [fromCardId] на карту [toCardId].
     * Если [reversed] = true — меняет направление (для кнопки разворота стрелки).
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
                repository.deleteTransaction(id)
                WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete transaction", e)
            }
        }
    }

    companion object {
        private const val TAG = "FinansiViewModel"
    }
}
