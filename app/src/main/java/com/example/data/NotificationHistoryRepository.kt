package com.example.data

import kotlinx.coroutines.flow.Flow

class NotificationHistoryRepository(private val dao: NotificationHistoryDao) {
    val allHistory: Flow<List<NotificationHistoryEntity>> = dao.getAll()

    suspend fun add(entity: NotificationHistoryEntity) = dao.insert(entity)
    suspend fun clear() = dao.clear()
}
