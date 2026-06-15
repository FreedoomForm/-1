package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NotificationHistoryEntity
import com.example.data.remote.SyncManager
import com.example.worker.NotificationHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotificationHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppDatabase.getDatabase(application).notificationHistoryDao()
    private val sync = SyncManager(application)
    val history: StateFlow<List<NotificationHistoryEntity>>

    init {
        history = repo.getAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    fun saveAndPush(entry: NotificationHistoryEntity) {
        viewModelScope.launch {
            repo.insert(entry)
            sync.pushNotification(entry)
        }
    }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }
}
