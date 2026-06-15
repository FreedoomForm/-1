package com.example.data

import kotlinx.coroutines.flow.Flow

class ContractHistoryRepository(private val dao: ContractHistoryDao) {
    val allHistory: Flow<List<ContractHistoryEntry>> = dao.getAll()

    suspend fun insert(entry: ContractHistoryEntry) = dao.insert(entry)
    suspend fun clear() = dao.clear()
}
