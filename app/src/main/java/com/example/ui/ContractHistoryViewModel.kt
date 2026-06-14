package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.ContractHistoryRepository
import com.example.data.remote.SyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContractHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: ContractHistoryRepository
    private val sync: SyncManager
    val history: StateFlow<List<ContractHistoryEntry>>

    init {
        val db = AppDatabase.getDatabase(application)
        repo = ContractHistoryRepository(db.contractHistoryDao())
        sync = SyncManager(application)
        history = repo.allHistory.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }
}
