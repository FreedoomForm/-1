package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AuditEvent
import com.example.data.BusinessOperation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UnifiedHistoryItem(
    val timestamp: Long,
    val kind: String,
    val title: String,
    val subtitle: String,
    val amountMinor: Long? = null,
    val sourceId: String
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val items: StateFlow<List<UnifiedHistoryItem>> = combine(
        db.auditEventDao().all(),
        db.businessOperationDao().getActive()
    ) { audits: List<AuditEvent>, operations: List<BusinessOperation> ->
        (audits.map { audit ->
            UnifiedHistoryItem(
                timestamp = audit.occurredAt,
                kind = "AUDIT",
                title = audit.action,
                subtitle = "${audit.entityType} #${audit.entityId}${audit.reason?.let { ": $it" } ?: ""}",
                sourceId = audit.id.toString()
            )
        } + operations.map { operation ->
            UnifiedHistoryItem(
                timestamp = operation.occurredAt,
                kind = operation.type,
                title = operation.type,
                subtitle = operation.note ?: operation.direction,
                amountMinor = operation.amountMinor,
                sourceId = operation.id.toString()
            )
        }).sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Adds a non-financial manual history branch at the operator-selected time. */
    fun createBranch(timestamp: Long, note: String) {
        viewModelScope.launch {
            db.auditEventDao().insert(AuditEvent(
                occurredAt = timestamp,
                action = "HISTORY_BRANCH_CREATED",
                entityType = "HISTORY_BRANCH",
                entityId = "manual-$timestamp",
                reason = note.ifBlank { "Manual history branch" }
            ))
        }
    }
}
