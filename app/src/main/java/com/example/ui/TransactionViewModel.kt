package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.BusinessOperation
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.Scooter
import com.example.data.Transaction
import com.example.data.TransactionRepository
import com.example.data.TrashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private lateinit var database: AppDatabase
    val transactions: StateFlow<List<Transaction>>

    /** UI feedback channel — emit (success, message) tuples for toast display. */
    private val _userMessage = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 4)
    val userMessage: kotlinx.coroutines.flow.SharedFlow<Pair<Boolean, String>> = _userMessage.asSharedFlow()

    // Кэши StateFlow по renterId / scooterId / contractId — чтобы не создавать
    // новый flow на каждую рекомпозицию (аналогично ContractHistoryViewModel).
    private val renterTxFlows = mutableMapOf<Int, StateFlow<List<Transaction>>>()
    private val scooterTxFlows = mutableMapOf<Int, StateFlow<List<Transaction>>>()
    private val contractTxFlows = mutableMapOf<Int, StateFlow<List<Transaction>>>()
    private val txFlowsLock = Any()

    init {
        val db = AppDatabase.getDatabase(application)
        database = db
        repo = TransactionRepository(db.transactionDao())
        renterRepo = RenterRepository(db.renterDao())
        transactions = repo.all.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    /** Все транзакции конкретного арендатора. */
    fun transactionsForRenter(renterId: Int): StateFlow<List<Transaction>> =
        synchronized(txFlowsLock) {
            renterTxFlows.getOrPut(renterId) {
                repo.forRenter(renterId).stateIn(
                    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
                )
            }
        }

    /** Все транзакции конкретного скутера. */
    fun transactionsForScooter(scooterId: Int): StateFlow<List<Transaction>> =
        synchronized(txFlowsLock) {
            scooterTxFlows.getOrPut(scooterId) {
                repo.forScooter(scooterId).stateIn(
                    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
                )
            }
        }

    /** Все транзакции конкретного контракта (по contractId). */
    fun transactionsForContract(contractId: Int): StateFlow<List<Transaction>> =
        synchronized(txFlowsLock) {
            contractTxFlows.getOrPut(contractId) {
                repo.forContract(contractId).stateIn(
                    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
                )
            }
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
        scooterId: Int?,
        scooterName: String,
        contractLabel: String,
        type: String,
        amount: Double,
        timestamp: Long,
        notes: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                    scooterId = scooterId ?: renter?.scooterId,
                    timestamp = timestamp,
                    type = type,
                    amount = amount,
                    notes = notes?.ifBlank { null },
                    renterName = finalRenterName,
                    renterPhone = finalRenterPhone,
                    scooterName = finalScooterName,
                    contractLabel = finalContractLabel
                )
                val legacyId = repo.insert(tx)
                if (legacyId <= 0L) {
                    _userMessage.emit(false to "Tranzaksiya saqlanmadi (konflikt)")
                    return@launch
                }
                // Manual entries are also appended to the universal journal. They
                // do not change a card automatically: cash movement must be made
                // through the card-transfer screen, avoiding invisible balances.
                val (operationType, direction) = when (type) {
                    Transaction.TYPE_PAYMENT -> BusinessOperation.TYPE_RENT_PAYMENT to BusinessOperation.DIRECTION_INCOME
                    Transaction.TYPE_REPAIR -> BusinessOperation.TYPE_EXPENSE to BusinessOperation.DIRECTION_EXPENSE
                    Transaction.TYPE_PENALTY -> BusinessOperation.TYPE_PENALTY_PAYMENT to BusinessOperation.DIRECTION_INCOME
                    else -> BusinessOperation.TYPE_ADJUSTMENT to BusinessOperation.DIRECTION_LIABILITY
                }
                if (amount > 0.0 && amount.isFinite()) {
                    database.businessOperationDao().insert(BusinessOperation(
                        occurredAt = timestamp, type = operationType, direction = direction,
                        amountMinor = BusinessOperation.toMinor(amount), renterId = renterId.takeIf { it > 0 },
                        scooterId = scooterId, contractId = contractId,
                        legacyTransactionId = legacyId.toInt(), note = notes?.ifBlank { null }
                    ))
                }
                _userMessage.emit(true to "Tranzaksiya yaratildi")
            } catch (e: Exception) {
                Log.e("TransactionVM", "createTransaction failed", e)
                _userMessage.emit(false to "Tranzaksiya yaratilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Если обновили renterId — подтянем свежие renterName/Phone.
                val toSave = if (transaction.renterName.isBlank() || transaction.renterPhone.isBlank()) {
                    val renter = renterRepo.getById(transaction.renterId)
                    if (renter != null) transaction.copy(
                        renterName = transaction.renterName.ifBlank { renter.name },
                        renterPhone = transaction.renterPhone.ifBlank { renter.phoneNumber }
                    ) else transaction
                } else transaction
                repo.update(toSave)
                // Sync the linked BusinessOperation row so reports reflect the
                // edited amount/type. Manual transactions store their link via
                // legacyTransactionId ONLY — never use getByCardTransactionId
                // here, because Transaction.id and CardTransaction.id collide
                // (both are autoGenerate starting from 1) and the fallback
                // would reverse an UNRELATED operation.
                try {
                    val db = AppDatabase.getDatabase(getApplication())
                    val bo = db.businessOperationDao().getByLegacyTransactionId(toSave.id)
                    bo?.let {
                        db.businessOperationDao().update(it.copy(
                            amountMinor = BusinessOperation.toMinor(toSave.amount),
                            note = toSave.notes ?: it.note
                        ))
                    }
                } catch (e: Exception) {
                    Log.w("TransactionVM", "Failed to sync BusinessOperation on update", e)
                }
                _userMessage.emit(true to "Tranzaksiya yangilandi")
            } catch (e: Exception) {
                Log.e("TransactionVM", "updateTransaction failed", e)
                _userMessage.emit(false to "Tranzaksiya yangilanmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    /** Moves a user-visible manual transaction to the recycle bin.
     *  Also marks the linked BusinessOperation as REVERSED so reports
     *  no longer count the deleted transaction. */
    fun deleteTransaction(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(getApplication())
                val tx = repo.getById(id)
                if (tx != null) {
                    // Reverse the BusinessOperation FIRST so the ledger is
                    // marked REVERSED even if the row delete later fails.
                    // legacyTransactionId is the ONLY correct link — never
                    // use getByCardTransactionId (id collision would reverse
                    // an unrelated operation).
                    try {
                        val bo = db.businessOperationDao().getByLegacyTransactionId(id)
                        bo?.let { db.businessOperationDao().markReversed(it.id) }
                    } catch (e: Exception) {
                        Log.w("TransactionVM", "Failed to reverse BusinessOperation on delete", e)
                    }
                    TrashService(db).moveTransactionToTrash(tx, "User deleted transaction")
                    _userMessage.emit(true to "Tranzaksiya o'chirildi")
                } else {
                    _userMessage.emit(false to "Tranzaksiya topilmadi")
                }
            } catch (e: Exception) {
                Log.e("TransactionVM", "deleteTransaction failed", e)
                _userMessage.emit(false to "Tranzaksiya o'chirilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun deleteTransactions(ids: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            val db = AppDatabase.getDatabase(getApplication())
            ids.forEach { id ->
                try {
                    val tx = repo.getById(id) ?: run { failCount++; return@forEach }
                    // Reverse ledger FIRST (legacyTransactionId only — see deleteTransaction).
                    try {
                        val bo = db.businessOperationDao().getByLegacyTransactionId(id)
                        bo?.let { db.businessOperationDao().markReversed(it.id) }
                    } catch (e: Exception) {
                        Log.w("TransactionVM", "Failed to reverse BusinessOperation on bulk delete #$id", e)
                    }
                    TrashService(db).moveTransactionToTrash(tx, "Bulk deletion")
                    successCount++
                } catch (e: Exception) {
                    Log.e("TransactionVM", "deleteTransactions failed for #$id", e)
                    failCount++
                }
            }
            val msg = buildString {
                append("${successCount} ta tranzaksiya o'chirildi")
                if (failCount > 0) append(", $failCount ta xatolik")
            }
            _userMessage.emit((failCount == 0) to msg)
        }
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
