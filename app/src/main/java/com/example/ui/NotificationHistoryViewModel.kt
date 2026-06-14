package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NotificationHistoryEntity
import com.example.data.NotificationHistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotificationHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: NotificationHistoryRepository

    val history: StateFlow<List<NotificationHistoryEntity>>

    init {
        val db = AppDatabase.getDatabase(application)
        repo = NotificationHistoryRepository(db.notificationHistoryDao())
        history = repo.allHistory.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }
}
