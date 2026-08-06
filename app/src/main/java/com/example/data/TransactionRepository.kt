package com.example.data

import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val dao: TransactionDao) {
    val all: Flow<List<Transaction>> = dao.getAll()
    val liveTransactions: Flow<List<Transaction>> = dao.getLiveTransactions()
    val trashedTransactions: Flow<List<Transaction>> = dao.getTrashedTransactions()

    suspend fun getById(id: Int): Transaction? = dao.getById(id)
    fun forRenter(renterId: Int): Flow<List<Transaction>> = dao.getForRenter(renterId)
    suspend fun forRenterOnce(renterId: Int): List<Transaction> = dao.getForRenterOnce(renterId)
    fun forScooter(scooterId: Int): Flow<List<Transaction>> = dao.getForScooter(scooterId)
    fun forContract(contractId: Int): Flow<List<Transaction>> = dao.getForContract(contractId)
    suspend fun forContractOnce(contractId: Int): List<Transaction> = dao.getForContractOnce(contractId)
    suspend fun insert(transaction: Transaction): Long = dao.insert(transaction)
    suspend fun update(transaction: Transaction) = dao.update(transaction)
    suspend fun deleteById(id: Int) = dao.deleteById(id)
    suspend fun deleteByIds(ids: List<Int>) = dao.deleteByIds(ids)
    suspend fun deleteForContract(contractId: Int) = dao.deleteForContract(contractId)
    suspend fun clear() = dao.clear()

    // ── Trash-mode operations ────────────────────────────────────────────
    suspend fun moveToTrash(id: Int) = dao.moveToTrash(id)
    suspend fun moveToTrashBatch(ids: List<Int>) = dao.moveToTrashBatch(ids)
    suspend fun restoreFromTrash(id: Int) = dao.restoreFromTrash(id)
    suspend fun restoreFromTrashBatch(ids: List<Int>) = dao.restoreFromTrashBatch(ids)
}
