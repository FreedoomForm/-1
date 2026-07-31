package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AuditEvent
import com.example.data.DeletedItem
import com.example.data.TrashService
import kotlinx.coroutines.channels.BufferOverflow
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
    // Batch 8 (was M3): increased extraBufferCapacity 4 → 16 and switched
    // the overflow strategy to DROP_OLDEST. The previous configuration
    // (default SUSPEND + tryEmit) silently dropped every error past the
    // 4th when a user hit "Restore All" on a multi-selection of broken
    // trash items — the user saw 4 toasts and the rest vanished without
    // a trace. DROP_OLDEST keeps the MOST RECENT 16 errors, which is
    // what a user reviewing a failure batch actually wants to see.
    private val _restoreErrors = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val restoreErrors: SharedFlow<String> = _restoreErrors.asSharedFlow()

    fun restore(itemId: Long) = viewModelScope.launch {
        try { service.restore(itemId) }
        catch (e: Exception) { _restoreErrors.tryEmit(e.message ?: "Qayta tiklash amalga oshmadi") }
    }

    fun updateReason(itemId: Long, reason: String) = viewModelScope.launch {
        // Batch 14 (was MEDIUM 7.3 follow-up): wrapped in try/catch.
        // Previously a SQLiteException (e.g. DB full) would propagate to
        // the coroutine's UncaughtExceptionHandler and crash the app.
        try { db.deletedItemDao().updateReason(itemId, reason.ifBlank { null }) }
        catch (e: Exception) {
            android.util.Log.e("TrashViewModel", "updateReason failed for #$itemId", e)
        }
    }

    /**
     * Permanent purge. Batch 8 (was H3): now writes an ACTION_TRASH_PURGE
     * audit event with a compact snapshot summary BEFORE the DeletedItem
     * row is hard-deleted. Previously purge() was completely unaudited —
     * once the user confirmed "Butunlay o'chirish", every trace of the
     * item vanished, leaving no way to investigate "what did we lose
     * when the user purged item #42 last Tuesday?" The financial
     * BusinessOperation rows themselves are immutable (§0.3) and stay;
     * only the recoverable snapshot is removed. The audit event records
     * the sourceType, sourceId, title, deletedAt, and a truncated reason
     * so post-incident review can reconstruct what was purged.
     */
    fun purge(itemId: Long) = viewModelScope.launch {
        try {
            val item = db.deletedItemDao().byId(itemId) ?: return@launch
            db.auditEventDao().insert(AuditEvent(
                occurredAt = System.currentTimeMillis(),
                action = AuditEvent.ACTION_TRASH_PURGE,
                entityType = item.sourceType,
                entityId = item.sourceId,
                reason = "Permanent purge of trash item #${item.id}: ${item.title}".take(240),
                beforeSnapshot = buildString {
                    append("sourceType=${item.sourceType}; ")
                    append("sourceId=${item.sourceId}; ")
                    append("title=${item.title}; ")
                    append("deletedAt=${item.deletedAt}; ")
                    append("reason=${item.reason?.take(120) ?: "(none)"}")
                },
                afterSnapshot = "purged=true"
            ))
            db.deletedItemDao().purge(itemId)
        } catch (e: Exception) {
            _restoreErrors.tryEmit("O'chirish amalga oshmadi: ${e.message ?: "noma'lum xato"}")
        }
    }
}
