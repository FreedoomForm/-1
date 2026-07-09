package com.example.data

import kotlinx.coroutines.flow.Flow

class ContractHistoryRepository(private val dao: ContractHistoryDao) {
    val allHistory: Flow<List<ContractHistoryEntry>> = dao.getAll()

    fun forRenter(renterId: Int): Flow<List<ContractHistoryEntry>> = dao.getForRenterFlow(renterId)
    fun forScooter(scooterName: String): Flow<List<ContractHistoryEntry>> = dao.getForScooterFlow(scooterName)

    suspend fun getById(id: Int): ContractHistoryEntry? = dao.getById(id)
    suspend fun getForRenterOnce(renterId: Int): List<ContractHistoryEntry> = dao.getForRenter(renterId)
    suspend fun insert(entry: ContractHistoryEntry): Long = dao.insert(entry)
    suspend fun update(entry: ContractHistoryEntry) = dao.update(entry)
    suspend fun deleteById(id: Int) = dao.deleteById(id)
    suspend fun deleteByIds(ids: List<Int>) = dao.deleteByIds(ids)
    suspend fun deleteForRenter(renterId: Int) = dao.deleteForRenter(renterId)
    suspend fun clear() = dao.clear()
}
