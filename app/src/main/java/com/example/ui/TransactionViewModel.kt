package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.Scooter
import com.example.data.Transaction
import com.example.data.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel для страницы «Tranzaksiya».
 *
 * Хранит отдельную таблицу транзакций [Transaction]. В отличие от
 * [ContractHistoryViewModel], который хранит ВСЕ события по арендатору
 * (создание, оплаты, продления, расторжения, возвраты), эта ViewModel
 * предназначена для ручного учёта произвольных денежных операций
 * (доплаты, штрафы, ремонты, продажи расходников и т.д.).
 *
 * Транзакция МОЖЕТ быть связана с контрактом (contractId) — тогда при
 * выборе контракта в диалоге создания все поля (renter, scooter)
 * автозаполняются. Но может быть и самостоятельной.
 *
 * Баланс арендатора НЕ меняется — это просто запись для учёта. Если
 * нужно применить оплату к балансу, используйте кнопку «To'lov» на
 * странице Arendators.
 */
class TransactionViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: TransactionRepository
    private val renterRepo: RenterRepository
    val transactions: StateFlow<List<Transaction>>

    init {
        val db = AppDatabase.getDatabase(application)
        repo = TransactionRepository(db.transactionDao())
        renterRepo = RenterRepository(db.renterDao())
        transactions = repo.all.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    /**
     * Создаёт новую транзакцию.
     *
     * Если передан [contractId], функция подтянет из БД арендатора и скутер
     * этого контракта и денормализует их в поля транзакции. Иначе использует
     * переданные [renterName], [renterPhone], [scooterName], [contractLabel]
     * как есть.
     */
    fun createTransaction(
        contractId: Int?,
        renterId: Int,
        renterName: String,
        renterPhone: String,
        scooterName: String,
        contractLabel: String,
        type: String,
        amount: Double,
        timestamp: Long,
        notes: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Если есть contractId — попробуем подтянуть renter/scooter из БД
            // для большей достоверности (на случай, если переданные значения
            // пустые).
            val contract = contractId?.let {
                AppDatabase.getDatabase(getApplication())
                    .contractHistoryDao().getById(it)
            }
            val renter = renterRepo.getById(renterId)

            val finalRenterName = renterName.ifBlank { renter?.name ?: "" }
            val finalRenterPhone = renterPhone.ifBlank { renter?.phoneNumber ?: "" }
            val finalScooterName = scooterName.ifBlank { contract?.scooterName ?: renter?.scooterName ?: "" }
            val finalContractLabel = contractLabel.ifBlank {
                contract?.let { formatContractLabel(it) } ?: ""
            }

            val tx = Transaction(
                contractId = contractId,
                renterId = renterId,
                timestamp = timestamp,
                type = type,
                amount = amount,
                notes = notes?.ifBlank { null },
                renterName = finalRenterName,
                renterPhone = finalRenterPhone,
                scooterName = finalScooterName,
                contractLabel = finalContractLabel
            )
            repo.insert(tx)
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            // Если обновили renterId — подтянем свежие renterName/Phone.
            if (transaction.renterName.isBlank() || transaction.renterPhone.isBlank()) {
                val renter = renterRepo.getById(transaction.renterId)
                if (renter != null) {
                    val updated = transaction.copy(
                        renterName = transaction.renterName.ifBlank { renter.name },
                        renterPhone = transaction.renterPhone.ifBlank { renter.phoneNumber }
                    )
                    repo.update(updated)
                    return@launch
                }
            }
            repo.update(transaction)
        }
    }

    fun deleteTransaction(id: Int) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteById(id) }
    }

    fun deleteTransactions(ids: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteByIds(ids) }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) { repo.clear() }
    }

    suspend fun getById(id: Int): Transaction? = withContext(Dispatchers.IO) {
        repo.getById(id)
    }

    companion object {
        private const val TAG = "TransactionVM"

        /**
         * Форматирует подпись контракта для отображения в транзакции:
         *   "#123  01.07.2025 → 08.07.2025"
         */
        fun formatContractLabel(entry: ContractHistoryEntry): String {
            val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val period = buildString {
                entry.weekStart?.let { append(dateFmt.format(Date(it))) }
                if (entry.weekEnd != null) {
                    append(" → ")
                    entry.weekEnd?.let { append(dateFmt.format(Date(it))) }
                }
            }
            return "#${entry.id}  $period"
        }

        fun typeLabel(t: String): String = when (t) {
            Transaction.TYPE_PAYMENT    -> "To'lov"
            Transaction.TYPE_TERMINATED -> "Tugatildi"
            Transaction.TYPE_RETURNED   -> "Qaytarildi"
            Transaction.TYPE_PENALTY    -> "Jarima"
            Transaction.TYPE_REPAIR     -> "Ta'mir"
            Transaction.TYPE_CUSTOM     -> "Boshqa"
            else -> t
        }

        /** Цвет статус-линии строки транзакции. */
        fun typeIsPositive(t: String): Boolean = when (t) {
            Transaction.TYPE_PAYMENT,
            Transaction.TYPE_RETURNED -> true
            Transaction.TYPE_TERMINATED,
            Transaction.TYPE_PENALTY,
            Transaction.TYPE_REPAIR -> false
            else -> true
        }
    }
}
