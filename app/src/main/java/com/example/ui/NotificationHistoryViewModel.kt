package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NotificationHistoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotificationHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppDatabase.getDatabase(application).notificationHistoryDao()
    val history: StateFlow<List<NotificationHistoryEntity>>

    init {
        history = repo.getAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    // Batch 14 (was MEDIUM 7.3 follow-up): both launches wrapped in
    // try/catch. Previously a SQLiteException would propagate to the
    // coroutine's UncaughtExceptionHandler and crash the app.

    fun saveAndPush(entry: NotificationHistoryEntity) {
        viewModelScope.launch {
            try { repo.insert(entry) }
            catch (e: Exception) { Log.e(TAG, "saveAndPush failed", e) }
        }
    }

    fun clear() {
        viewModelScope.launch {
            try { repo.clear() }
            catch (e: Exception) { Log.e(TAG, "clear failed", e) }
        }
    }

    companion object {
        private const val TAG = "NotifHistoryVM"
    }
}
