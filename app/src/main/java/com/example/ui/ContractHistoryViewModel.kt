package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntity
import com.example.data.ContractHistoryRepository
import com.example.data.NotificationHistoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContractHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: ContractHistoryRepository
    val history: StateFlow<List<com.example.data.ContractHistoryEntry>>

    init {
        val db = AppDatabase.getDatabase(application)
        repo = ContractHistoryRepository(db.contractHistoryDao())
        history = repo.allHistory.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }
}
