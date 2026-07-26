package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * История уведомлений, отправленных приложением. Нужна для отображения
 * списка уведомлений внутри приложения (по кнопке-колокольчику).
 */
@Entity(tableName = "notification_history")
data class NotificationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val renterId: Int? = null,
    val title: String,
    val message: String
)
