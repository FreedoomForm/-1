package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DeletedItem
import com.example.data.TrashService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val service = TrashService(db)
    val items: StateFlow<List<DeletedItem>> = db.deletedItemDao().all().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun restore(itemId: Long) = viewModelScope.launch { service.restore(itemId) }

    /** Permanent purge does not remove the immutable financial audit. */
    fun purge(itemId: Long) = viewModelScope.launch { db.deletedItemDao().purge(itemId) }
}
