package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.TimelineBranch
import com.example.data.TimelineEvent
import com.example.data.TimelineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Branch-aware controller for table and visual timeline history. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val service = TimelineService(db)
    private val _activeBranchId = MutableStateFlow(1L)
    val activeBranchId: StateFlow<Long> = _activeBranchId

    val branches: StateFlow<List<TimelineBranch>> = db.timelineDao().branches().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val events: StateFlow<List<TimelineEvent>> = _activeBranchId
        .flatMapLatest { db.timelineDao().events(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectBranch(branchId: Long) { _activeBranchId.value = branchId }

    // Batch 13 (was MEDIUM 7.3): all viewModelScope.launch blocks below
    // are now wrapped in try/catch. Previously any exception thrown by
    // TimelineService would propagate to the coroutine's
    // UncaughtExceptionHandler and crash the app via the thread's default
    // handler. The launches are fire-and-forget UI actions (no caller
    // awaits them), so silent failure with a log message is the correct
    // behavior — the user can retry by tapping the button again.

    fun createBranch(atTimestamp: Long, name: String) {
        viewModelScope.launch {
            try {
                val id = service.createBranch(_activeBranchId.value, atTimestamp, name)
                _activeBranchId.value = id
            } catch (e: Exception) {
                Log.e(TAG, "createBranch failed", e)
            }
        }
    }

    /** Immutable timeline edit: append an explicit correction event. */
    fun correctSelected(event: TimelineEvent, note: String) {
        viewModelScope.launch {
            try {
                service.record(
                    branchId = _activeBranchId.value,
                    actionType = "HISTORY_CORRECTION",
                    screen = event.screen,
                    title = "Correction: ${event.title}",
                    entityType = event.entityType,
                    entityId = event.entityId,
                    payloadJson = "{\"sourceEventId\":${event.id},\"note\":\"${note.replace("\"", "\\\"")}\"}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "correctSelected failed", e)
            }
        }
    }

    fun archiveSelected(event: TimelineEvent, reason: String = "Archived from history") {
        viewModelScope.launch {
            try {
                com.example.data.TrashService(db).archiveTimelineEvent(event, reason)
            } catch (e: Exception) {
                Log.e(TAG, "archiveSelected failed", e)
            }
        }
    }

    /**
     * Restores (un-archives) a single timeline event AND records a RESTORE
     * audit event. Used by the "Вернуть объект" button next to the branch
     * name on the history screen.
     */
    fun unarchiveSelected(event: TimelineEvent) {
        viewModelScope.launch {
            try { service.unarchiveEvent(event) }
            catch (e: Exception) { Log.e(TAG, "unarchiveSelected failed", e) }
        }
    }

    /**
     * Permanently deletes the timeline event AND — if the event is a
     * DELETE-type timecode referencing a renter or scooter — also deletes
     * that referenced entity. Returns true if the entity was deleted
     * (false if only the event was).
     */
    suspend fun permanentlyDeleteReferencedObject(event: TimelineEvent): Boolean =
        service.permanentlyDeleteReferencedObject(event)

    /**
     * Renames an existing branch. Used by the universal ✎ button when a
     * block from a non-main branch is selected on the history tree.
     */
    fun renameBranch(branchId: Long, newName: String) {
        viewModelScope.launch {
            try { service.renameBranch(branchId, newName) }
            catch (e: Exception) { Log.e(TAG, "renameBranch failed", e) }
        }
    }

    /**
     * Permanently deletes a non-main branch and all its events. Used by
     * the universal 🗑 button when a block from a non-main branch is
     * selected on the history tree.
     */
    fun deleteBranch(branchId: Long) {
        viewModelScope.launch {
            try { service.deleteBranch(branchId) }
            catch (e: Exception) { Log.e(TAG, "deleteBranch failed", e) }
        }
    }

    /** Snapshot lookup for "Вернуться в это время" — exposes the active branch id. */
    fun activeBranchIdValue(): Long = _activeBranchId.value

    /**
     * Safe restore: never erases financial facts. Records a RESTORE event
     * referencing the nearest snapshot before/at [timestamp]. The snapshot's
     * stateJson can be read by the UI to render a historical frame.
     *
     * Per PLAN_UNIVERSAL_ACCOUNTING §9.0.
     * Returns the RESTORE event id, or null if no snapshot found.
     */
    suspend fun restoreToSnapshot(timestamp: Long, reason: String): Long? =
        service.restoreToSnapshot(_activeBranchId.value, timestamp, reason)

    suspend fun nearestSnapshot(timestamp: Long) =
        service.nearestRenderableState(_activeBranchId.value, timestamp)

    companion object {
        private const val TAG = "HistoryViewModel"
    }
}
