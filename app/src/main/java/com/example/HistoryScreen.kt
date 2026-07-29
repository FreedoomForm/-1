@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.TimelineBranch
import com.example.data.TimelineEvent
import com.example.ui.HistoryViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * History screen with three view modes:
 * 1. TABLE  — Classic list view
 * 2. VISUAL — Video player style timeline with timecodes and branch sliders
 * 3. TREE   — Branch tree visualization with time trunk + entity columns
 *
 * The view-mode toggle button changes BOTH its icon AND its color to match
 * the current view (gray for TABLE, blue for VISUAL, green for TREE).
 *
 * Header ("Tarix — N ta voqea") and selection badge were removed per user
 * request — they were noisy visual clutter.
 */
enum class HistoryViewMode(val icon: ImageVector, val label: String, val color: Color) {
    TABLE(Icons.Default.TableView, "Jadval", Color(0xFF6B7280)),
    VISUAL(Icons.Default.PlayArrow, "Video", Color(0xFF3B82F6)),
    TREE(Icons.Default.AccountTree, "Daraxt", Color(0xFF10B981))
}

/** Primary action colors — used for timecodes, event blocks, branch slider colors. */
object ActionColors {
    val CREATE = Color(0xFF10B981)   // Green  — creation of an entity
    val DELETE = Color(0xFFEF4444)   // Red    — deletion of an entity
    val EDIT = Color(0xFF3B82F6)     // Blue   — modification of an entity
    val SECONDARY = Color(0xFF6B7280)// Gray   — secondary action (screen transition, etc.)
}

/** Branch colors for tree view + visual slider stacking. */
val BRANCH_COLORS = listOf(
    Color(0xFF3B82F6), // Blue
    Color(0xFF10B981), // Green
    Color(0xFFF59E0B), // Amber
    Color(0xFFEF4444), // Red
    Color(0xFF8B5CF6), // Purple
    Color(0xFFEC4899), // Pink
    Color(0xFF14B8A6), // Teal
    Color(0xFFF97316)  // Orange
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    createTrigger: Int = 0,
    editTrigger: Int = 0,
    selectedEventId: Long? = null,
    onSelectedEventChange: (Long?) -> Unit = {},
    viewModel: HistoryViewModel = viewModel()
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val branches by viewModel.branches.collectAsStateWithLifecycle()
    val activeBranchId by viewModel.activeBranchId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dateOnlyFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val timeOnlyFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var viewMode by remember { mutableStateOf(HistoryViewMode.TABLE) }

    var showBranchPicker by remember { mutableStateOf(false) }
    var showBranchCreate by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showUnarchiveConfirm by remember { mutableStateOf(false) }
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()

    var branchName by remember { mutableStateOf("") }
    var restoreReason by remember { mutableStateOf("") }
    var timelinePosition by remember { mutableStateOf(0f) }
    var treeZoom by remember { mutableStateOf(1f) }
    // Universal visual zoom for TABLE and VISUAL modes (graphicsLayer scale).
    // Driven by pinch gesture; resets to 1f on tap.
    var universalZoom by remember { mutableStateOf(1f) }
    var universalPanX by remember { mutableStateOf(0f) }
    var universalPanY by remember { mutableStateOf(0f) }

    // Multi-select action-type filter — all 3 ON by default so user sees
    // every primary timecode. Each chip toggles membership; long-press
    // solos that type (sets the set to just that one element).
    var filterActionTypes by remember {
        mutableStateOf(setOf("CREATE", "EDIT", "DELETE"))
    }
    var filterEntityType by remember { mutableStateOf<String?>(null) }
    var filterMoneyOnly by remember { mutableStateOf(false) }
    var filterStartMs by remember { mutableStateOf<Long?>(null) }
    var filterEndMs by remember { mutableStateOf<Long?>(null) }
    var filterSearchText by remember { mutableStateOf("") }

    // Branch sliders for visual mode — each entry is (startTime, color).
    // Sliders are drawn ON TOP of the main slider, newest branch on top.
    var branchSliders by remember { mutableStateOf<List<Pair<Long, Color>>>(emptyList()) }

    LaunchedEffect(createTrigger) { if (createTrigger > 0) showBranchCreate = true }

    val chronological = remember(events) { events.sortedBy { it.timestamp } }
    val selected = selectedEventId?.let { id -> events.firstOrNull { it.id == id } }

    val filteredEvents = remember(chronological, filterActionTypes, filterEntityType, filterMoneyOnly, filterStartMs, filterEndMs, filterSearchText) {
        chronological.filter { ev ->
            val upperAction = ev.actionType.uppercase()
            // ── Action-type filter (per user spec 2026-07-29) ──────────
            // Primary actions are split into 3 color-coded categories:
            //   CREATE (green) — anything containing CREATE/INSERT/ADD/PAYMENT
            //   EDIT   (blue)  — anything containing EDIT/UPDATE/MODIFY
            //   DELETE (red)   — anything containing DELETE/REMOVE/TERMINATE/CANCEL
            // Secondary actions = everything else (gray). Per user spec:
            //   "второстепенные блоки действий являются всеми остальными
            //    действиями которые не являются первостепенными"
            // Examples: search query typed, page navigated, button tapped,
            // text typed in any input, etc.
            //
            // Filter behavior:
            //   • Secondary actions are ALWAYS shown regardless of which
            //     primary filter chips are toggled on/off. They are not
            //     affected by the type filter — only by the time-range /
            //     entity / search-text filters below.
            //   • Primary actions are filtered by the enabled chips:
            //       - All 3 ON → show all primary actions (no type filter)
            //       - None ON  → hide all primary actions (but secondary
            //                    actions still show)
            //       - Otherwise → primary must match one of the enabled types
            val isCreate = upperAction.contains("CREATE") || upperAction.contains("INSERT") || upperAction.contains("ADD") || upperAction.contains("PAYMENT")
            val isEdit   = upperAction.contains("EDIT") || upperAction.contains("UPDATE") || upperAction.contains("MODIFY")
            val isDelete = upperAction.contains("DELETE") || upperAction.contains("REMOVE") || upperAction.contains("TERMINATE") || upperAction.contains("CANCEL")
            val isPrimary = isCreate || isEdit || isDelete

            val typeMatch = when {
                // Secondary actions ALWAYS pass the type filter.
                !isPrimary -> true
                // All 3 ON → show everything (no type filter active)
                filterActionTypes.size == 3 -> true
                // None ON → hide all primary actions
                filterActionTypes.isEmpty() -> false
                // Otherwise: a primary action must match one of the enabled types
                else -> {
                    (isCreate && "CREATE" in filterActionTypes) ||
                    (isEdit && "EDIT" in filterActionTypes) ||
                    (isDelete && "DELETE" in filterActionTypes)
                }
            }
            typeMatch &&
            (filterEntityType == null || ev.entityType == filterEntityType) &&
            (filterStartMs?.let { ev.timestamp >= it } ?: true) &&
            (filterEndMs?.let { ev.timestamp <= it } ?: true) &&
            (filterSearchText.isBlank() ||
             ev.title.contains(filterSearchText, ignoreCase = true) ||
             ev.screen.contains(filterSearchText, ignoreCase = true) ||
             ev.actionType.contains(filterSearchText, ignoreCase = true))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════

    if (showBranchPicker) {
        BranchPickerDialog(
            branches = branches,
            activeBranchId = activeBranchId,
            onSelect = { branch ->
                viewModel.selectBranch(branch.id)
                onSelectedEventChange(null)
                showBranchPicker = false
            },
            onDismiss = { showBranchPicker = false }
        )
    }

    if (showBranchCreate && selected != null) {
        CreateBranchDialog(
            branchName = branchName,
            onBranchNameChange = { branchName = it },
            selectedEvent = selected,
            branches = branches,
            onConfirm = {
                if (branchName.isNotBlank()) {
                    val newColor = BRANCH_COLORS[branches.size % BRANCH_COLORS.size]
                    scope.launch {
                        viewModel.createBranch(selected.timestamp, branchName)
                        branchSliders = branchSliders + (selected.timestamp to newColor)
                    }
                    branchName = ""
                    showBranchCreate = false
                }
            },
            onDismiss = { showBranchCreate = false }
        )
    }

    if (showDetail && selected != null) {
        EventDetailDialog(
            event = selected,
            formatter = formatter,
            onDismiss = { showDetail = false }
        )
    }

    // ── Filter side panel (sliding in from the right — same UX as the
    // renters page). Contains 3 toggle chips for the primary timecode
    // types (CREATE/EDIT/DELETE) with long-press to solo one type, plus
    // a date-range summary so user can see the active calendar filter.
    if (showFilters) {
        HistoryFilterSidePanel(
            filterActionTypes = filterActionTypes,
            onActionTypesChange = { filterActionTypes = it },
            filterEntityType = filterEntityType,
            onEntityTypeChange = { filterEntityType = it },
            filterStartMs = filterStartMs,
            filterEndMs = filterEndMs,
            onClearDateRange = {
                filterStartMs = null
                filterEndMs = null
                dateRangePickerState.setSelection(null, null)
            },
            onReset = {
                filterActionTypes = setOf("CREATE", "EDIT", "DELETE")
                filterEntityType = null
                filterStartMs = null
                filterEndMs = null
                filterSearchText = ""
                dateRangePickerState.setSelection(null, null)
            },
            onDismiss = { showFilters = false },
            formatter = formatter
        )
    }

    // ── Unarchive confirmation — used by the "Вернуть объект" button next
    // to the branch name. Asks the user to confirm restoring the object
    // referenced by the selected event.
    if (showUnarchiveConfirm && selected != null) {
        AlertDialog(
            onDismissRequest = { showUnarchiveConfirm = false },
            title = { Text("Ob'ektni qaytarish") },
            text = {
                Column {
                    Text("Tanlangan voqea: ${selected.title}")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bu amal voqeani arxivdan chiqaradi va audit izda RESTORE_OBJECT yozuvi qo'shadi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { viewModel.unarchiveSelected(selected) }
                    showUnarchiveConfirm = false
                }) { Text("Qaytarish") }
            },
            dismissButton = {
                TextButton(onClick = { showUnarchiveConfirm = false }) { Text("Bekor") }
            }
        )
    }

    // Calendar date-range filter — same dialog component used on the
    // renters page. Filters the timeline by event timestamp.
    if (showDateRangePicker) {
        DateRangeFilterDialog(
            state = dateRangePickerState,
            onDismiss = {
                showDateRangePicker = false
                // Apply selection immediately so the user sees the filter
                // take effect when the dialog closes.
                filterStartMs = dateRangePickerState.selectedStartDateMillis
                filterEndMs = dateRangePickerState.selectedEndDateMillis
            },
            title = "Voqea sanasi bo'yicha filter"
        )
    }

    // Restore-to-snapshot dialog — asks the user for a reason, then
    // restores the timeline state to the selected event's timestamp.
    if (showRestoreDialog && selected != null) {
        RestoreToSnapshotDialog(
            reason = restoreReason,
            onReasonChange = { restoreReason = it },
            selectedEvent = selected,
            formatter = formatter,
            onConfirm = {
                scope.launch {
                    viewModel.restoreToSnapshot(selected.timestamp, restoreReason.ifBlank { "Manual restore" })
                }
                restoreReason = ""
                showRestoreDialog = false
            },
            onDismiss = {
                restoreReason = ""
                showRestoreDialog = false
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN LAYOUT
    // ═══════════════════════════════════════════════════════════════════════

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeBackground)
    ) {
        // Search bar — same component as on the renters page, with the
        // filter and calendar icons embedded in the trailing slot so
        // the layout matches renters exactly.
        UnifiedSearchBar(
            query = filterSearchText,
            onQueryChange = { filterSearchText = it },
            placeholder = "Tarixda qidirish...",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            onFilterClick = { showFilters = true },
            filterActive = filterActionTypes.size != 3 || filterEntityType != null,
            onCalendarClick = { showDateRangePicker = true },
            calendarActive = filterStartMs != null || filterEndMs != null
        )

        // Action row with view mode toggle + branch picker on the left,
        // PLUS two secondary buttons next to the branch name:
        //   • "Qaytish" (вернуться в это время) — opens RestoreToSnapshotDialog
        //   • "Objekt qaytarish" (вернуть объект) — opens unarchive confirm
        // These sit immediately after the branch-name chip so the user
        // sees them as branch-level actions, not list-level actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // View mode button — changes icon AND color based on current mode
            val buttonColor by animateColorAsState(viewMode.color, label = "viewModeColor")

            FilledTonalButton(
                onClick = {
                    viewMode = when (viewMode) {
                        HistoryViewMode.TABLE -> HistoryViewMode.VISUAL
                        HistoryViewMode.VISUAL -> HistoryViewMode.TREE
                        HistoryViewMode.TREE -> HistoryViewMode.TABLE
                    }
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = buttonColor.copy(alpha = 0.15f),
                    contentColor = buttonColor
                )
            ) {
                Icon(viewMode.icon, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(viewMode.label, fontSize = 12.sp)
            }

            // Branch picker — custom background matching branch color
            val activeBranch = branches.find { it.id == activeBranchId }
            val branchIdx = branches.indexOfFirst { it.id == activeBranchId }
            val branchColor = if (branchIdx >= 0) BRANCH_COLORS[branchIdx % BRANCH_COLORS.size]
                              else ClaudeAccent

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = branchColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, branchColor.copy(alpha = 0.4f)),
                onClick = { showBranchPicker = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountTree, null, Modifier.size(14.dp), tint = branchColor)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        activeBranch?.name ?: "Main",
                        fontSize = 12.sp,
                        color = branchColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // ── Secondary buttons — IMMEDIATELY after branch name ──────
            // Both visible only when an event is selected. They are the
            // user's "вернуться в это время" and "вернуть объект" actions
            // tied to the currently-selected timecode.
            if (selected != null) {
                // "Qaytish" — вернуться в это время: creates a NEW BRANCH
                // starting at the selected event's timestamp. This lets
                // the user "fork" history at this point (e.g., to try a
                // different action instead of a deletion).
                IconButton(
                    onClick = { showBranchCreate = true },
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White, CircleShape)
                        .border(1.dp, ClaudeDivider, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = "Bu vaqtga qaytish",
                        tint = StatusInfo,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // "Objekt qaytarish" — вернуть объект: unarchives the event
                // AND records a RESTORE_OBJECT audit entry so the financial
                // trail stays intact. Disabled if event has no entity ref.
                val canUnarchive = selected.entityId != null
                IconButton(
                    onClick = { if (canUnarchive) showUnarchiveConfirm = true },
                    enabled = canUnarchive,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (canUnarchive) Color.White else Color.White.copy(alpha = 0.5f),
                            CircleShape
                        )
                        .border(1.dp, ClaudeDivider, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Unarchive,
                        contentDescription = "Ob'ektni qaytarish",
                        tint = if (canUnarchive) StatusOk else ClaudeTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))
        }

        // (Header "Tarix — N ta voqea" + selection badge intentionally removed
        //  per user request — they were visual clutter above the list.)

        // Content area — wrapped in a pinch-to-zoom container so the user
        // can scale the scene with two fingers in ANY view mode (TABLE,
        // VISUAL, or TREE). All three modes apply the pinch as a true
        // graphicsLayer scale + pan (visual zoom). TREE mode additionally
        // feeds the zoom into treeZoom so block granularity changes too.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(viewMode) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        universalZoom = (universalZoom * zoomChange).coerceIn(0.5f, 3f)
                        universalPanX += pan.x
                        universalPanY += pan.y
                        // TREE mode also feeds the zoom into the layout-driving
                        // treeZoom so block granularity + column widths change.
                        if (viewMode == HistoryViewMode.TREE) {
                            treeZoom = (treeZoom * zoomChange).coerceIn(0.3f, 3f)
                        }
                    }
                }
                .graphicsLayer(
                    // Apply universalZoom to ALL view modes (including TREE)
                    // so the user gets true visual pinch-to-zoom everywhere.
                    // Previously TREE was forced to 1f, which is why the user
                    // reported "I can't zoom the tree with my fingers".
                    scaleX = universalZoom,
                    scaleY = universalZoom,
                    translationX = universalPanX,
                    translationY = universalPanY
                )
        ) {
            when (viewMode) {
                HistoryViewMode.TABLE -> TableView(
                    events = filteredEvents,
                    selectedEventId = selectedEventId,
                    onEventClick = { onSelectedEventChange(it.id) },
                    onEventLongClick = { onSelectedEventChange(it.id); showDetail = true },
                    formatter = formatter
                )

                HistoryViewMode.VISUAL -> VisualTimelineView(
                    events = filteredEvents,
                    selectedEventId = selectedEventId,
                    branches = branches,
                    branchSliders = branchSliders,
                    timelinePosition = timelinePosition,
                    onPositionChange = { timelinePosition = it },
                    onEventClick = { onSelectedEventChange(it.id) },
                    formatter = formatter,
                    timeOnlyFmt = timeOnlyFmt
                )

                HistoryViewMode.TREE -> TreeView(
                    events = filteredEvents,
                    branches = branches,
                    activeBranchId = activeBranchId,
                    selectedEventId = selectedEventId,
                    zoom = treeZoom,
                    onZoomChange = { treeZoom = it },
                    onEventClick = { onSelectedEventChange(it.id) },
                    onCreateBranch = { event ->
                        onSelectedEventChange(event.id)
                        showBranchCreate = true
                    },
                    formatter = formatter,
                    dateOnlyFmt = dateOnlyFmt
                )
            }

            // Zoom indicator chip — bottom-end. Shows current zoom %.
            // Double-tap to reset.
            val zoomPct = if (viewMode == HistoryViewMode.TREE) treeZoom else universalZoom
            if (zoomPct != 1f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                universalZoom = 1f
                                universalPanX = 0f
                                universalPanY = 0f
                                treeZoom = 1f
                            }
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = ClaudeCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider)
                ) {
                    Text(
                        "${(zoomPct * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TABLE VIEW — flat list with colored status strip + title + timestamp.
// No per-row "i" button, no enumeration number, no selection marker — the
// card itself shows selection via border. Long-press opens detail dialog.
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TableView(
    events: List<TimelineEvent>,
    selectedEventId: Long?,
    onEventClick: (TimelineEvent) -> Unit,
    onEventLongClick: (TimelineEvent) -> Unit,
    formatter: SimpleDateFormat
) {
    if (events.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Voqealar topilmadi", color = ClaudeTextSecondary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(events.reversed(), key = { it.id }) { event ->
            val isSelected = event.id == selectedEventId
            val actionColor = getActionColor(event.actionType)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onEventClick(event) },
                        onLongClick = { onEventLongClick(event) }
                    )
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) ClaudeAccent else ClaudeDivider,
                        shape = RoundedCornerShape(8.dp)
                    ),
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) ClaudeAccentBg else ClaudeCard
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Colored status strip (replaces per-row dot/marker)
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(40.dp)
                            .background(actionColor, RoundedCornerShape(2.dp))
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = ClaudeText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            Text(
                                event.actionType,
                                style = MaterialTheme.typography.labelSmall,
                                color = actionColor
                            )
                            Text(
                                " • ${event.screen}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                        }
                    }

                    Text(
                        formatter.format(Date(event.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// VISUAL TIMELINE VIEW
//
// Layout (top → bottom):
//   1. Big event card showing the event closest to current slider position.
//      Time badge uses the action color (green=CREATE / red=DELETE / blue=EDIT).
//   2. Dark playback panel:
//        ─ Timecode ticks rendered ON the slider track itself (not as a
//          separate row above the slider). Each tick is a small colored dot
//          positioned by its timestamp. Only primary actions get timecodes
//          with one of the 3 colors; secondary actions are skipped.
//        ─ Main slider (ClaudeAccent thumb).
//        ─ Branch sliders drawn ON TOP of the main slider (each branch adds
//          a thin colored bar that starts at the branch creation timestamp
//          and extends to the end of the timeline). The newest branch is
//          drawn last (topmost).
//        ─ Playback controls (skip / rewind / time / forward / skip).
//
// There is NO local "+" button — branches are created via the universal "+"
// in the action row above (visible only when an event is selected).
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun VisualTimelineView(
    events: List<TimelineEvent>,
    selectedEventId: Long?,
    branches: List<TimelineBranch>,
    branchSliders: List<Pair<Long, Color>>,
    timelinePosition: Float,
    onPositionChange: (Float) -> Unit,
    onEventClick: (TimelineEvent) -> Unit,
    formatter: SimpleDateFormat,
    timeOnlyFmt: SimpleDateFormat
) {
    // ── Visual timeline view (rewritten per user spec 2026-07-29) ───────
    // User: "там должно было рендериться окошко которое показывает все
    //         второстепенные и первостепенные действие то есть каждый шаг
    //         пользователя в приложении"
    // Previous implementation showed only ONE big card (the event nearest
    // the slider) above a dark playback panel — which the user perceived
    // as a useless "black box". The new layout is a vertical scrolling
    // list of ALL events (primary AND secondary), each rendered as a
    // color-coded chip with timestamp + title + actionType + screen.
    // The slider + playback controls are kept at the bottom (now on
    // ClaudeCard background instead of dark gray).
    if (events.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ClaudeBackground),
            contentAlignment = Alignment.Center
        ) {
            Text("Voqealar topilmadi", color = ClaudeTextSecondary)
        }
        return
    }

    val minTime = events.minOfOrNull { it.timestamp } ?: 0L
    val maxTime = events.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
    val range = (maxTime - minTime).coerceAtLeast(1L)
    val currentTime = minTime + (timelinePosition * range).toLong()

    val listState = rememberLazyListState()

    Column(modifier = Modifier
        .fillMaxSize()
        .background(ClaudeBackground)
    ) {
        // ── Header summary ───────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = ClaudeCard
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Harakatlar tarixi",
                    fontWeight = FontWeight.Bold,
                    color = ClaudeText,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${events.size} ta qadam",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ── Scrolling list of ALL actions (primary + secondary) ─────────
        // Each action is a chip colored by its type:
        //   • CREATE  → green tint
        //   • EDIT    → blue tint
        //   • DELETE  → red tint
        //   • Secondary (anything else) → gray tint
        // This is the "window that shows all user steps" the user asked for.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(events) { ev ->
                val actionColor = getActionColor(ev.actionType)
                val isPrimary = isPrimaryAction(ev.actionType)
                val isSelected = ev.id == selectedEventId

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onEventClick(ev) },
                            onLongClick = { onEventClick(ev) }
                        ),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) actionColor.copy(alpha = 0.18f) else ClaudeCard,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) actionColor else ClaudeDivider
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Colored stripe at left edge indicates action type.
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 36.dp)
                                .background(actionColor, RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ev.title.ifBlank { ev.actionType },
                                color = ClaudeText,
                                fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = ev.actionType,
                                    color = actionColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (ev.screen.isNotBlank()) {
                                    Text(
                                        text = " · ${ev.screen}",
                                        color = ClaudeTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                if (!isPrimary) {
                                    Text(
                                        text = " · ikkilamchi",
                                        color = ClaudeTextSecondary,
                                        fontSize = 10.sp,
                                        fontStyle = androidx.compose.ui.text.style.FontStyle.Italic
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = timeOnlyFmt.format(Date(ev.timestamp)),
                            color = ClaudeTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // ── Playback slider + controls (bottom panel) ───────────────────
        // Replaced the old dark gray (0xFF1A1A1A) panel with a ClaudeCard
        // panel so it matches the cream theme. The slider lets the user
        // scrub through time; the time label shows current scrub position.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ClaudeCard,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Slider — accent-colored thumb + track
                Slider(
                    value = timelinePosition,
                    onValueChange = onPositionChange,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = ClaudeAccent,
                        activeTrackColor = ClaudeAccent,
                        inactiveTrackColor = ClaudeDivider
                    )
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onPositionChange(0f) }) {
                        Icon(Icons.Default.SkipPrevious, "Boshiga", tint = ClaudeAccent)
                    }
                    IconButton(onClick = {
                        onPositionChange((timelinePosition - 0.1f).coerceAtLeast(0f))
                    }) {
                        Icon(Icons.Default.FastRewind, "Orqaga", tint = ClaudeAccent)
                    }

                    Text(
                        timeOnlyFmt.format(Date(currentTime)),
                        color = ClaudeText,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    IconButton(onClick = {
                        onPositionChange((timelinePosition + 0.1f).coerceAtMost(1f))
                    }) {
                        Icon(Icons.Default.FastForward, "Oldinga", tint = ClaudeAccent)
                    }
                    IconButton(onClick = { onPositionChange(1f) }) {
                        Icon(Icons.Default.SkipNext, "Oxiriga", tint = ClaudeAccent)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TREE VIEW
//
// Layout (left → right):
//   ┌──────────────┬─────────┬──────────────────────────────────────────┐
//   │  LEFT (30%)  │ TRUNK   │  RIGHT (70%) — horizontal scroll         │
//   │  alternate   │ (80dp)  │  ┌──────┬──────┬──────┬──────┬──────┐    │
//   │  branch      │ time    │  │RENTER│CONTR │TXN   │CARD  │SCOOT │    │
//   │  events      │ blocks  │  │ ▣ ▣  │ ▣    │ ▣ ▣  │      │ ▣    │    │
//   │              │         │  └──────┴──────┴──────┴──────┴──────┘    │
//   └──────────────┴─────────┴──────────────────────────────────────────┘
//
// Pinch-to-zoom changes time-block granularity (day / hour / 15min) AND
// column width. Time blocks have branch-colored background when a branch
// starts in that interval.
//
// Event blocks (TreeEventBlock):
//   • Primary CREATE  → green tinted background + green text
//   • Primary DELETE  → red tinted background + red text
//   • Primary EDIT    → blue tinted background + blue text
//   • Secondary       → grey background + grey text
//
// Long-press any event block → creates new branch starting from that event.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TreeView(
    events: List<TimelineEvent>,
    branches: List<TimelineBranch>,
    activeBranchId: Long,
    selectedEventId: Long?,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onEventClick: (TimelineEvent) -> Unit,
    onCreateBranch: (TimelineEvent) -> Unit,
    formatter: SimpleDateFormat,
    dateOnlyFmt: SimpleDateFormat
) {
    val scrollState = rememberScrollState()

    // Time block granularity changes with zoom level.
    val blockSizeMs = when {
        zoom < 0.5f -> 24L * 60 * 60 * 1000  // Day
        zoom < 1.5f -> 60L * 60 * 1000        // Hour
        else -> 15L * 60 * 1000               // 15 minutes
    }

    val groupedEvents = remember(events, blockSizeMs) {
        events.groupBy { (it.timestamp / blockSizeMs) * blockSizeMs }
            .toSortedMap()
    }

    val entityColumns = listOf("RENTER", "CONTRACT", "TRANSACTION", "CARD", "SCOOTER")

    // IMPORTANT: explicit ClaudeBackground on the outer Box. Without this
    // the empty space around the LazyColumns shows through to whatever
    // surface is behind (often the window background = black), which is
    // why the user saw "the tree has a black background".
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeBackground)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── LEFT SIDE — alternate branch events ─────────────────────
            Box(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight()
                    .background(ClaudeCard)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    val leftEvents = events.filter { event ->
                        branches.any { branch ->
                            branch.id != activeBranchId &&
                            event.timestamp >= branch.createdAt
                        }
                    }
                    items(leftEvents) { event ->
                        TreeEventBlock(
                            event = event,
                            isSelected = event.id == selectedEventId,
                            zoom = zoom,
                            onClick = { onEventClick(event) },
                            onLongClick = { onCreateBranch(event) }
                        )
                    }
                }
            }

            // ── CENTER — time trunk ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .background(ClaudeBackground)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    groupedEvents.forEach { (timeMs, _) ->
                        item {
                            val blockHeight = (40 * zoom).dp
                            val branchStartingHere = branches.firstOrNull {
                                it.createdAt in timeMs until (timeMs + blockSizeMs)
                            }
                            val branchColor = branchStartingHere?.let { branch ->
                                BRANCH_COLORS[branches.indexOf(branch) % BRANCH_COLORS.size]
                            }

                            Surface(
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .width(60.dp)
                                    .height(blockHeight),
                                shape = RoundedCornerShape(4.dp),
                                color = branchColor?.copy(alpha = 0.3f) ?: ClaudeDivider,
                                border = if (branchStartingHere != null)
                                    androidx.compose.foundation.BorderStroke(2.dp, branchColor ?: ClaudeAccent)
                                else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        dateOnlyFmt.format(Date(timeMs)),
                                        fontSize = (10 * zoom).sp,
                                        color = ClaudeText,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Connection line between time blocks (the "trunk")
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(8.dp)
                                    .background(ClaudeDivider)
                            )
                        }
                    }
                }
            }

            // ── RIGHT SIDE — main branch events grouped by entity type ──
            Row(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
            ) {
                entityColumns.forEach { entityType ->
                    Column(
                        modifier = Modifier
                            .width((100 * zoom).dp)
                            .fillMaxHeight()
                            .background(ClaudeCard)
                            .padding(4.dp)
                    ) {
                        Text(
                            entityType,
                            fontSize = 10.sp,
                            color = ClaudeTextSecondary,
                            modifier = Modifier.padding(4.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val columnEvents = events.filter {
                                it.entityType?.uppercase() == entityType
                            }
                            items(columnEvents) { event ->
                                TreeEventBlock(
                                    event = event,
                                    isSelected = event.id == selectedEventId,
                                    zoom = zoom,
                                    onClick = { onEventClick(event) },
                                    onLongClick = { onCreateBranch(event) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeEventBlock(
    event: TimelineEvent,
    isSelected: Boolean,
    zoom: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val actionColor = getActionColor(event.actionType)
    val isPrimary = isPrimaryAction(event.actionType)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) ClaudeAccent else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ),
        shape = RoundedCornerShape(4.dp),
        color = if (isPrimary) actionColor.copy(alpha = 0.2f) else ClaudeDivider
    ) {
        Box(
            modifier = Modifier.padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                event.actionType.take(3),
                fontSize = (10 * zoom).sp,
                color = if (isPrimary) actionColor else ActionColors.SECONDARY,
                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DIALOGS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun BranchPickerDialog(
    branches: List<TimelineBranch>,
    activeBranchId: Long,
    onSelect: (TimelineBranch) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tarix tarmog'ini tanlang") },
        text = {
            LazyColumn {
                itemsIndexed(branches) { index, branch ->
                    val branchColor = BRANCH_COLORS[index % BRANCH_COLORS.size]
                    val isActive = branch.id == activeBranchId

                    // Branch entry — custom background matching branch color.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(branch) },
                        shape = RoundedCornerShape(8.dp),
                        color = branchColor.copy(alpha = if (isActive) 0.3f else 0.1f),
                        border = if (isActive)
                            androidx.compose.foundation.BorderStroke(2.dp, branchColor)
                        else null
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActive) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint = branchColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                branch.name,
                                color = if (isActive) branchColor else ClaudeText,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Yopish") }
        }
    )
}

@Composable
private fun CreateBranchDialog(
    branchName: String,
    onBranchNameChange: (String) -> Unit,
    selectedEvent: TimelineEvent,
    branches: List<TimelineBranch>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val newColor = BRANCH_COLORS[branches.size % BRANCH_COLORS.size]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yangi tarmoq yaratish") },
        text = {
            Column {
                // Color preview — the new branch's identifying color.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = newColor,
                    shape = RoundedCornerShape(4.dp)
                ) {}

                Spacer(Modifier.height(16.dp))

                Text(
                    "Tarmoq boshlanish nuqtasi:",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
                Text(
                    selectedEvent.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = branchName,
                    onValueChange = onBranchNameChange,
                    label = { Text("Tarmoq nomi") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = branchName.isNotBlank()
            ) {
                Text("Yaratish")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Bekor") }
        }
    )
}

@Composable
private fun EventDetailDialog(
    event: TimelineEvent,
    formatter: SimpleDateFormat,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            Column {
                DetailRow("Vaqt", formatter.format(Date(event.timestamp)))
                DetailRow("Harakat", event.actionType)
                DetailRow("Sahifa", event.screen)
                event.entityType?.let { DetailRow("Ob'ekt turi", it) }
                event.entityId?.let { DetailRow("Ob'ekt ID", it) }
                event.payloadJson?.let { payload ->
                    if (payload.isNotBlank() && payload != "null") {
                        Spacer(Modifier.height(8.dp))
                        Text("Ma'lumotlar:", style = MaterialTheme.typography.labelSmall, color = ClaudeTextSecondary)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF1A1A1A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                payload,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Yopish") }
        }
    )
}

/**
 * Right-side sliding filter panel for the History screen.
 *
 * Mirrors the visual style of the renters page FilterSidePanel (sliding
 * from the right edge, cream paper background, 340dp wide) but the body
 * contains toggle chips for the 3 primary timecode types instead of
 * free-text fields.
 *
 * Behavior:
 *   • Tap a type chip → toggles its membership in [filterActionTypes]
 *   • Long-press a type chip → SOLOS that type (disables all others)
 *   • The "Sana oralig'i" row shows the active date range (set via the
 *     calendar icon on the search bar) and lets the user clear it from
 *     here too.
 *   • "Tozalash" resets everything; "Qo'llash" closes the panel.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryFilterSidePanel(
    filterActionTypes: Set<String>,
    onActionTypesChange: (Set<String>) -> Unit,
    filterEntityType: String?,
    onEntityTypeChange: (String?) -> Unit,
    filterStartMs: Long?,
    filterEndMs: Long?,
    onClearDateRange: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    formatter: SimpleDateFormat
) {
    val allActionTypes = listOf(
        Triple("CREATE", ActionColors.CREATE, "Yaratish"),
        Triple("EDIT",   ActionColors.EDIT,   "Tahrirlash"),
        Triple("DELETE", ActionColors.DELETE, "O'chirish")
    )

    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(340.dp)
                .clickable { /* consume click — don't dismiss when clicking panel */ },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 16.dp, bottomStart = 16.dp
            ),
            color = ClaudeCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                // ── Header ─────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = ClaudeAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Filtrlash",
                            style = MaterialTheme.typography.titleLarge,
                            color = ClaudeText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Yopish", tint = ClaudeTextSecondary)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Birlamchi taymkodlar turlari bo'yicha filtr",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.HorizontalDivider(color = ClaudeDivider)
                Spacer(Modifier.height(12.dp))

                // ── Primary timecode type toggles ─────────────────────────
                Text(
                    "Taymkod turi (uzun bosing — faqat shu tur)",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClaudeText,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))

                allActionTypes.forEach { (type, color, label) ->
                    val isEnabled = type in filterActionTypes
                    val isSolo = isEnabled && filterActionTypes.size == 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    onActionTypesChange(
                                        if (isEnabled) filterActionTypes - type
                                        else filterActionTypes + type
                                    )
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    // SOLO: keep only this type
                                    onActionTypesChange(setOf(type))
                                }
                            )
                            .background(
                                if (isEnabled) color.copy(alpha = 0.18f) else Color.White,
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (isEnabled) 2.dp else 1.dp,
                                color = if (isEnabled) color else ClaudeDivider,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(color, androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) ClaudeText else ClaudeTextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                type,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isEnabled) color else ClaudeTextSecondary
                            )
                        }
                        // Status indicator: green check when enabled,
                        // "OFF" label when disabled, "SOLO" badge when alone.
                        if (isSolo) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                color = color
                            ) {
                                Text(
                                    "SOLO",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (isEnabled) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Yoqilgan",
                                tint = color,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                "OFF",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Date range summary ────────────────────────────────────
                Text(
                    "Sana oralig'i (kalendardan)",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClaudeText,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                val hasDateRange = filterStartMs != null || filterEndMs != null
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = if (hasDateRange) ClaudeAccentBg else Color.White,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (hasDateRange) ClaudeAccent else ClaudeDivider
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = if (hasDateRange) ClaudeAccent else ClaudeTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (hasDateRange) {
                                val startStr = filterStartMs?.let { formatter.format(Date(it)) } ?: "—"
                                val endStr = filterEndMs?.let { formatter.format(Date(it)) } ?: "—"
                                Text(
                                    "$startStr → $endStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeText
                                )
                            } else {
                                Text(
                                    "Tanlanmagan",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeTextSecondary
                                )
                            }
                        }
                        if (hasDateRange) {
                            TextButton(onClick = onClearDateRange) {
                                Text("Tozalash", color = StatusOverdue)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Entity type (optional, single-select) ─────────────────
                Text(
                    "Ob'ekt turi (ixtiyoriy)",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClaudeText,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("RENTER", "CONTRACT", "CARD", "SCOOTER", "TRANSACTION").forEach { type ->
                        FilterChip(
                            selected = filterEntityType == type,
                            onClick = {
                                onEntityTypeChange(if (filterEntityType == type) null else type)
                            },
                            label = { Text(type, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Active filter count ────────────────────────────────────
                val activeCount =
                    (3 - filterActionTypes.size) +           // disabled types count as active
                    (if (filterEntityType != null) 1 else 0) +
                    (if (hasDateRange) 1 else 0)
                if (activeCount > 0) {
                    Text(
                        "$activeCount ta filtr faol",
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeAccent,
                        fontWeight = FontWeight.Medium
                    )
                }

                // ── Buttons ───────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.example.ui.components.SecondaryButton(
                        label = "Tozalash",
                        icon = Icons.Default.Clear,
                        onClick = onReset,
                        modifier = Modifier.weight(1f)
                    )
                    com.example.ui.components.PrimaryButton(
                        label = "Qo'llash",
                        icon = Icons.Default.Check,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeText
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

private fun getActionColor(actionType: String): Color {
    val upper = actionType.uppercase()
    return when {
        upper.contains("CREATE") || upper.contains("INSERT") || upper.contains("ADD") -> ActionColors.CREATE
        upper.contains("DELETE") || upper.contains("REMOVE") -> ActionColors.DELETE
        upper.contains("EDIT") || upper.contains("UPDATE") || upper.contains("MODIFY") -> ActionColors.EDIT
        upper.contains("PAYMENT") || upper.contains("PAY") -> ActionColors.CREATE
        upper.contains("TERMINATE") || upper.contains("CANCEL") -> ActionColors.DELETE
        else -> ActionColors.SECONDARY
    }
}

private fun isPrimaryAction(actionType: String): Boolean {
    val upper = actionType.uppercase()
    return upper.contains("CREATE") || upper.contains("DELETE") || upper.contains("EDIT") ||
           upper.contains("UPDATE") || upper.contains("INSERT") || upper.contains("REMOVE") ||
           upper.contains("PAYMENT") || upper.contains("TRANSFER") || upper.contains("TERMINATE")
}

/**
 * Restore-to-snapshot confirmation dialog.
 *
 * Asks the user for a free-text reason, then calls [onConfirm]. The
 * reason is recorded as part of the RESTORE event so the audit trail
 * stays complete — financial facts are never silently erased.
 */
@Composable
private fun RestoreToSnapshotDialog(
    reason: String,
    onReasonChange: (String) -> Unit,
    selectedEvent: TimelineEvent,
    formatter: SimpleDateFormat,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Voqeaga qaytish")
        },
        text = {
            Column {
                Text(
                    "Tanlangan voqea: ${selectedEvent.title}",
                    fontSize = 13.sp,
                    color = ClaudeText
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Vaqt: ${formatter.format(Date(selectedEvent.timestamp))}",
                    fontSize = 12.sp,
                    color = ClaudeTextSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    placeholder = { Text("Sabab (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Qaytish")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Bekor qilish")
            }
        }
    )
}
