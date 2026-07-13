package com.example.data

import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val dao: TransactionDao) {
    val all: Flow<List<Transaction>> = dao.getAll()
    suspend fun getById(id: Int): Transaction? = dao.getById(id)
    fun forRenter(renterId: Int): Flow<List<Transaction>> = dao.getForRenter(renterId)
    fun forScooter(scooterId: Int): Flow<List<Transaction>> = dao.getForScooter(scooterId)
    fun forContract(contractId: Int): Flow<List<Transaction>> = dao.getForContract(contractId)
    suspend fun forContractOnce(contractId: Int): List<Transaction> = dao.getForContractOnce(contractId)
    suspend fun insert(transaction: Transaction): Long = dao.insert(transaction)
    suspend fun update(transaction: Transaction) = dao.update(transaction)
    suspend fun deleteById(id: Int) = dao.deleteById(id)
    suspend fun deleteByIds(ids: List<Int>) = dao.deleteByIds(ids)
    suspend fun deleteForContract(contractId: Int) = dao.deleteForContract(contractId)
    suspend fun clear() = dao.clear()
}
