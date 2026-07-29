package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DeletedItem
import com.example.data.TrashService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val service = TrashService(db)
    val items: StateFlow<List<DeletedItem>> = db.deletedItemDao().all().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    // ── §9.2: surface restore errors (card balance, scooter conflict, etc.) ──
    private val _restoreErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val restoreErrors: SharedFlow<String> = _restoreErrors.asSharedFlow()

    fun restore(itemId: Long) = viewModelScope.launch {
        try { service.restore(itemId) }
        catch (e: Exception) { _restoreErrors.tryEmit(e.message ?: "Qayta tiklash amalga oshmadi") }
    }

    fun updateReason(itemId: Long, reason: String) = viewModelScope.launch {
        db.deletedItemDao().updateReason(itemId, reason.ifBlank { null })
    }

    /** Permanent purge does not remove the immutable financial audit. */
    fun purge(itemId: Long) = viewModelScope.launch { db.deletedItemDao().purge(itemId) }
}
