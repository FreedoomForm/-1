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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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

    var branchName by remember { mutableStateOf("") }
    var restoreReason by remember { mutableStateOf("") }
    var timelinePosition by remember { mutableStateOf(0f) }
    var treeZoom by remember { mutableStateOf(1f) }

    var filterEntityType by remember { mutableStateOf<String?>(null) }
    var filterActionType by remember { mutableStateOf<String?>(null) }
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

    val filteredEvents = remember(chronological, filterEntityType, filterActionType, filterMoneyOnly, filterStartMs, filterEndMs, filterSearchText) {
        chronological.filter { ev ->
            (filterEntityType == null || ev.entityType == filterEntityType) &&
            (filterActionType == null || ev.actionType.contains(filterActionType!!, ignoreCase = true)) &&
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

    if (showFilters) {
        FiltersDialog(
            filterEntityType = filterEntityType,
            filterActionType = filterActionType,
            filterMoneyOnly = filterMoneyOnly,
            onEntityTypeChange = { filterEntityType = it },
            onActionTypeChange = { filterActionType = it },
            onMoneyOnlyChange = { filterMoneyOnly = it },
            onClear = {
                filterEntityType = null
                filterActionType = null
                filterMoneyOnly = false
                filterStartMs = null
                filterEndMs = null
            },
            onDismiss = { showFilters = false }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN LAYOUT
    // ═══════════════════════════════════════════════════════════════════════

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeCard)
    ) {
        // Search bar
        UnifiedSearchBar(
            query = filterSearchText,
            onQueryChange = { filterSearchText = it },
            placeholder = "Tarixda qidirish...",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Action row with view mode toggle
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

            Spacer(Modifier.weight(1f))

            // Restore button (only when event selected)
            if (selected != null) {
                IconButton(onClick = { showRestoreDialog = true }) {
                    Icon(Icons.Default.Restore, "Qaytish", tint = StatusInfo)
                }
            }

            // Create branch button — only the universal + button (top of screen)
            // creates branches. NO local "+" button inside the visual view.
            if (selected != null) {
                IconButton(onClick = { showBranchCreate = true }) {
                    Icon(Icons.Default.Add, "Tarmoq", tint = StatusOk)
                }
            }

            // Filters
            IconButton(onClick = { showFilters = true }) {
                Icon(
                    Icons.Default.Tune,
                    "Filtr",
                    tint = if (filterEntityType != null || filterActionType != null || filterMoneyOnly)
                           ClaudeAccent else ClaudeTextSecondary
                )
            }
        }

        // (Header "Tarix — N ta voqea" + selection badge intentionally removed
        //  per user request — they were visual clutter above the list.)

        // Content based on view mode
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
    if (events.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
    val currentEvent = events.minByOrNull { kotlin.math.abs(it.timestamp - currentTime) }

    // Primary-action timecodes (max 30 ticks for legibility).
    val primaryTicks = remember(events, minTime, range) {
        events
            .filter { isPrimaryAction(it.actionType) }
            .take(30)
            .map { ev ->
                val pos = ((ev.timestamp - minTime).toFloat() / range).coerceIn(0f, 1f)
                Triple(ev, pos, getActionColor(ev.actionType))
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Event display area ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (currentEvent != null) {
                val actionColor = getActionColor(currentEvent.actionType)

                Surface(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    shape = RoundedCornerShape(16.dp),
                    color = actionColor.copy(alpha = 0.1f),
                    border = androidx.compose.foundation.BorderStroke(2.dp, actionColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Time badge — colored by action type (3-color system)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = actionColor
                        ) {
                            Text(
                                formatter.format(Date(currentEvent.timestamp)),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            currentEvent.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeText,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        Row {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = actionColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    currentEvent.actionType,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = actionColor
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                currentEvent.screen,
                                style = MaterialTheme.typography.labelMedium,
                                color = ClaudeTextSecondary
                            )
                        }

                        currentEvent.entityType?.let { entityType ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entityType,
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // ── Dark playback panel ──────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ── Slider with ON-TRACK timecodes + branch sliders ON TOP ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)  // room for ticks above slider + branch sliders below
                ) {
                    // Branch sliders drawn FIRST (lowest layer) — thin colored bars
                    // spanning from branch start to end of timeline.
                    branchSliders.forEachIndexed { idx, (startTime, color) ->
                        val startPos = ((startTime - minTime).toFloat() / range).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(1f - startPos)
                                .align(Alignment.BottomStart)
                                .padding(start = (startPos * 100f * 3.6f).dp.let { _ -> (startPos * 320f).dp.coerceAtMost(320.dp) })
                                .height(3.dp)
                                .background(color, RoundedCornerShape(2.dp))
                        )
                    }

                    // Primary-action timecodes: dots positioned along the slider track.
                    // Each tick is colored by its action type (3-color system).
                    primaryTicks.forEach { (ev, pos, color) ->
                        Box(
                            modifier = Modifier
                                .offset(x = (pos * 320f).dp.let { dp -> dp.coerceAtMost(320.dp) })
                                .align(Alignment.TopStart)
                                .padding(top = 6.dp)
                                .size(10.dp)
                                .background(color, CircleShape)
                                .border(1.dp, Color.White, CircleShape)
                                .clickable { onEventClick(ev) }
                        )
                    }

                    // Main slider (drawn LAST → topmost layer for thumb interaction)
                    Slider(
                        value = timelinePosition,
                        onValueChange = onPositionChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.CenterStart),
                        colors = SliderDefaults.colors(
                            thumbColor = ClaudeAccent,
                            activeTrackColor = ClaudeAccent,
                            inactiveTrackColor = Color(0xFF3A3A3A)
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ── Playback controls ─────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onPositionChange(0f) }) {
                        Icon(Icons.Default.SkipPrevious, "Boshiga", tint = Color.White)
                    }
                    IconButton(onClick = {
                        onPositionChange((timelinePosition - 0.1f).coerceAtLeast(0f))
                    }) {
                        Icon(Icons.Default.FastRewind, "Orqaga", tint = Color.White)
                    }

                    Text(
                        timeOnlyFmt.format(Date(currentTime)),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    IconButton(onClick = {
                        onPositionChange((timelinePosition + 0.1f).coerceAtMost(1f))
                    }) {
                        Icon(Icons.Default.FastForward, "Oldinga", tint = Color.White)
                    }
                    IconButton(onClick = { onPositionChange(1f) }) {
                        Icon(Icons.Default.SkipNext, "Oxiriga", tint = Color.White)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    onZoomChange((zoom * zoomChange).coerceIn(0.3f, 3f))
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── LEFT SIDE — alternate branch events ─────────────────────
            Box(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight()
                    .background(Color(0xFF0A0A0A))
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
                    .background(Color(0xFF1A1A1A))
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
                                color = branchColor?.copy(alpha = 0.3f) ?: Color(0xFF2A2A2A),
                                border = if (branchStartingHere != null)
                                    androidx.compose.foundation.BorderStroke(2.dp, branchColor ?: ClaudeAccent)
                                else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        dateOnlyFmt.format(Date(timeMs)),
                                        fontSize = (10 * zoom).sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Connection line between time blocks (the "trunk")
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(8.dp)
                                    .background(Color(0xFF3A3A3A))
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
                            .background(Color(0xFF0F0F0F))
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
        color = if (isPrimary) actionColor.copy(alpha = 0.2f) else Color(0xFF2A2A2A)
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

@Composable
private fun FiltersDialog(
    filterEntityType: String?,
    filterActionType: String?,
    filterMoneyOnly: Boolean,
    onEntityTypeChange: (String?) -> Unit,
    onActionTypeChange: (String?) -> Unit,
    onMoneyOnlyChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtrlar") },
        text = {
            Column {
                Text("Ob'ekt turi:", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("RENTER", "CONTRACT", "CARD", "SCOOTER").forEach { type ->
                        FilterChip(
                            selected = filterEntityType == type,
                            onClick = {
                                onEntityTypeChange(if (filterEntityType == type) null else type)
                            },
                            label = { Text(type, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("Harakat turi:", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("CREATE", "EDIT", "DELETE").forEach { type ->
                        FilterChip(
                            selected = filterActionType == type,
                            onClick = {
                                onActionTypeChange(if (filterActionType == type) null else type)
                            },
                            label = { Text(type, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = filterMoneyOnly,
                        onCheckedChange = onMoneyOnlyChange
                    )
                    Text("Faqat pul operatsiyalari")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Qo'llash") }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text("Tozalash") }
        }
    )
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
