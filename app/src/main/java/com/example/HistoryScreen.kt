package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.TimelineEvent
import com.example.ui.HistoryViewModel
import com.example.ui.components.DangerOutlinedButton
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.UnifiedButton
import com.example.ui.components.UnifiedButtonVariant
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusArchived
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOverdue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Branch-aware history — redesigned to follow the Renters page dress code.
 *
 * Layout (matches RenterTable):
 *   ┌─────────────────────────────────────────────────┐
 *   │ [🔍 Qidirish ............................] [⚙][📅]│ ← UnifiedSearchBar
 *   ├─────────────────────────────────────────────────┤
 *   │ [Ko'rinish]  [Main ▾]  [Qaytish]  [+]  [Filtr]   │ ← action row
 *   ├─────────────────────────────────────────────────┤
 *   │ Tarix — N ta voqea          M ta tanlandi       │ ← header surface
 *   ├─────────────────────────────────────────────────┤
 *   │ ▌ ✓ № ●  Title          [Detail]                │ ← row (bordered card)
 *   │ ▌    ●  Action • Screen                         │
 *   │ ...                                              │
 *   └─────────────────────────────────────────────────┘
 *
 * Unique features preserved:
 *   • Visual timeline mode (toggle with «Ko'rinish» / «Jadval»)
 *   • Branch picker (Main / Custom branches)
 *   • «Qaytish» — safe restore to selected time code
 *   • «+» — create new branch from selected time code
 *   • Filters: entity type / action type / money only / period / search
 *   • Visual timeline with media-player controls (◀ ⏸ ▶)
 *   • Detail dialog with payload-parsed fields (amount/actor/reason/storno)
 */
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
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dateOnlyFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    var visualMode by remember { mutableStateOf(false) }
    var showBranchPicker by remember { mutableStateOf(false) }
    var showBranchCreate by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showPeriodPicker by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreReason by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var correctionNote by remember { mutableStateOf("") }
    var timelinePosition by remember { mutableStateOf(0f) }

    // ── Filters (§9B) ────────────────────────────────────────────────────────
    var filterEntityType by remember { mutableStateOf<String?>(null) }
    var filterActionType by remember { mutableStateOf<String?>(null) }
    var filterMoneyOnly by remember { mutableStateOf(false) }
    var filterStartMs by remember { mutableStateOf<Long?>(null) }
    var filterEndMs by remember { mutableStateOf<Long?>(null) }
    var filterSearchText by remember { mutableStateOf("") }

    LaunchedEffect(createTrigger) { if (createTrigger > 0) showBranchCreate = true }
    LaunchedEffect(editTrigger) { if (editTrigger > 0 && selectedEventId != null) showEdit = true }

    val chronological = remember(events) { events.sortedBy { it.timestamp } }
    val selected = selectedEventId?.let { id -> events.firstOrNull { it.id == id } }

    // ── Apply filters ────────────────────────────────────────────────────────
    val moneyActionTypes = remember {
        setOf("PAY", "PAYMENT", "TRANSFER", "DEPOSIT", "WITHDRAW", "STORNO",
              "REPAIR_COST", "RENT_INCOME", "REFUND", "DEPOSIT_HELD", "DEPOSIT_RETURNED")
    }
    val filteredEvents = remember(chronological, filterEntityType, filterActionType, filterMoneyOnly, filterStartMs, filterEndMs, filterSearchText) {
        chronological.filter { ev ->
            (filterEntityType == null || ev.entityType == filterEntityType) &&
            (filterActionType == null || ev.actionType.equals(filterActionType, ignoreCase = true)) &&
            (!filterMoneyOnly || ev.actionType.uppercase() in moneyActionTypes || ev.actionType.uppercase().contains("PAY")) &&
            (filterStartMs?.let { ev.timestamp >= it } ?: true) &&
            (filterEndMs?.let { ev.timestamp <= it } ?: true) &&
            (filterSearchText.isBlank() ||
             ev.title.contains(filterSearchText, ignoreCase = true) ||
             ev.screen.contains(filterSearchText, ignoreCase = true) ||
             ev.actionType.contains(filterSearchText, ignoreCase = true) ||
             (ev.entityType?.contains(filterSearchText, ignoreCase = true) == true))
        }
    }

    if (showBranchPicker) {
        AlertDialog(
            onDismissRequest = { showBranchPicker = false },
            title = { Text("Tarix tarmog'i") },
            text = {
                Column {
                    branches.forEach { branch ->
                        TextButton(onClick = { viewModel.selectBranch(branch.id); onSelectedEventChange(null); showBranchPicker = false }) {
                            Text(if (branch.id == activeBranchId) "✓ ${branch.name}" else branch.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBranchPicker = false }) { Text("Yopish") } }
        )
    }
    if (showBranchCreate) {
        AlertDialog(
            onDismissRequest = { showBranchCreate = false },
            title = { Text("Yangi tarix tarmog'i") },
            text = {
                Column {
                    Text("Tarmoq tanlangan taymkoddan boshlanadi.")
                    OutlinedTextField(branchName, { branchName = it }, label = { Text("Tarmoq nomi") })
                }
            },
            confirmButton = { TextButton(onClick = {
                val timestamp = selected?.timestamp ?: chronological.lastOrNull()?.timestamp ?: System.currentTimeMillis()
                viewModel.createBranch(timestamp, branchName)
                branchName = ""; showBranchCreate = false; onSelectedEventChange(null)
            }) { Text("Yaratish") } },
            dismissButton = { TextButton(onClick = { showBranchCreate = false }) { Text("Bekor qilish") } }
        )
    }
    if (showEdit && selected != null) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Tarixga tuzatish") },
            text = { OutlinedTextField(correctionNote, { correctionNote = it }, label = { Text("Tuzatish izohi") }) },
            confirmButton = { TextButton(onClick = {
                viewModel.correctSelected(selected, correctionNote)
                correctionNote = ""; showEdit = false
            }) { Text("Tuzatish kiritish") } },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Bekor qilish") } }
        )
    }

    // ── Detail dialog (§9B: safe detail of each record) ──────────────────────
    if (showDetail && selected != null) {
        val ev = selected
        val payloadAmount = remember(ev.payloadJson) {
            try {
                val o = org.json.JSONObject(ev.payloadJson)
                if (o.has("amount")) o.optDouble("amount", 0.0) else null
            } catch (_: Exception) { null }
        }
        val payloadActor = remember(ev.payloadJson) {
            try {
                val o = org.json.JSONObject(ev.payloadJson)
                o.optString("actor", "").ifBlank { null }
            } catch (_: Exception) { null }
        }
        val payloadReason = remember(ev.payloadJson) {
            try {
                val o = org.json.JSONObject(ev.payloadJson)
                o.optString("reason", "").ifBlank { null }
            } catch (_: Exception) { null }
        }
        val payloadStornoOf = remember(ev.payloadJson) {
            try {
                val o = org.json.JSONObject(ev.payloadJson)
                if (o.has("stornoOf")) o.optLong("stornoOf") else null
            } catch (_: Exception) { null }
        }
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("Voqea tafsiloti") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Sarlavha", ev.title)
                    DetailRow("Ekran", ev.screen)
                    DetailRow("Harakat turi", ev.actionType)
                    DetailRow("Vaqt", formatter.format(Date(ev.timestamp)))
                    ev.entityType?.let { DetailRow("Bog'langan obyekt", "$it #${ev.entityId ?: "—"}") }
                    payloadAmount?.let { DetailRow("Summa", "${it.toLong()} so'm") }
                    payloadActor?.let { DetailRow("Autor", it) }
                    payloadReason?.let { DetailRow("Sabab", it) }
                    payloadStornoOf?.let { DetailRow("Storno of", "#$it") }
                    DetailRow("Asosiy voqea", if (ev.isMajor) "ha" else "yo'q")
                    DetailRow("Arxivlangan", if (ev.isArchived) "ha" else "yo'q")
                    Spacer(Modifier.height(4.dp))
                    Text("Payload:", style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary, fontWeight = FontWeight.SemiBold)
                    Text(
                        ev.payloadJson.take(800) + if (ev.payloadJson.length > 800) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.archiveSelected(ev, "Arxivlandi tafsilotdan")
                        showDetail = false
                    }) { Text("Arxivlash") }
                    TextButton(onClick = { showDetail = false }) { Text("Yopish") }
                }
            }
        )
    }

    // ── Restore dialog (§9.0: safe restore with reason) ──────────────────────
    if (showRestoreDialog && selected != null) {
        val coroutineScope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false; restoreReason = "" },
            title = { Text("Holatni qaytarish") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tanlangan: ${selected.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClaudeText
                    )
                    Text(
                        "Vaqt: ${formatter.format(Date(selected.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Diqqat: moliyaviy faktlar o'chirilmaydi. Qaytarish auditable " +
                        "RESTORE voqeasi sifatida yoziladi. Moliyaviy holatni " +
                        "ko'rib chiqish va kerak bo'lsa storno yaratish kerak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                    OutlinedTextField(
                        value = restoreReason,
                        onValueChange = { restoreReason = it },
                        label = { Text("Qaytarish sababi (majburiy)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = restoreReason.isNotBlank(),
                    onClick = {
                        coroutineScope.launch {
                            val id = viewModel.restoreToSnapshot(selected.timestamp, restoreReason)
                            if (id != null) {
                                onSelectedEventChange(id)
                            }
                        }
                        restoreReason = ""
                        showRestoreDialog = false
                    }
                ) { Text("Qaytarish", color = ClaudeAccent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false; restoreReason = "" }) {
                    Text("Bekor qilish")
                }
            }
        )
    }

    // ── Filters dialog (§9B) ─────────────────────────────────────────────────
    if (showFilters) {
        AlertDialog(
            onDismissRequest = { showFilters = false },
            title = { Text("Filtrlar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ob'ekt turi:", style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
                    val entityTypes = listOf(null to "Hammasi", "RENTER" to "Arendator", "SCOOTER" to "Skuter",
                        "CONTRACT" to "Kontrakt", "CARD" to "Karta", "PAYMENT" to "To'lov",
                        "REPAIR" to "Ta'mir", "RESTORE" to "Qayta tiklash")
                    entityTypes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (value, label) ->
                                FilterChip(
                                    selected = filterEntityType == value,
                                    onClick = { filterEntityType = if (filterEntityType == value) null else value },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Harakat turi:", style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
                    val actionTypes = listOf(null to "Hammasi", "CREATE" to "Yaratish", "UPDATE" to "O'zgartirish",
                        "DELETE" to "O'chirish", "PAY" to "To'lov", "REPAIR" to "Ta'mir",
                        "RESTORE" to "Qayta tiklash", "CORRECTION" to "Tuzatish")
                    actionTypes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (value, label) ->
                                FilterChip(
                                    selected = filterActionType?.equals(value, ignoreCase = true) == true,
                                    onClick = {
                                        filterActionType = if (filterActionType?.equals(value, ignoreCase = true) == true) null else value
                                    },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = filterMoneyOnly,
                        onClick = { filterMoneyOnly = !filterMoneyOnly },
                        label = { Text("Faqat pul harakatlari") }
                    )

                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = filterSearchText,
                        onValueChange = { filterSearchText = it },
                        label = { Text("Qidiruv matni") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showPeriodPicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (filterStartMs != null || filterEndMs != null) {
                                "${filterStartMs?.let { dateOnlyFmt.format(Date(it)) } ?: "—"} — ${filterEndMs?.let { dateOnlyFmt.format(Date(it)) } ?: "—"}"
                            } else "Davr tanlash")
                        }
                        Spacer(Modifier.width(8.dp))
                        if (filterStartMs != null || filterEndMs != null) {
                            TextButton(onClick = { filterStartMs = null; filterEndMs = null }) {
                                Text("Tozalash")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        filterEntityType = null
                        filterActionType = null
                        filterMoneyOnly = false
                        filterStartMs = null
                        filterEndMs = null
                        filterSearchText = ""
                    }) { Text("Tozalash") }
                    TextButton(onClick = { showFilters = false }) { Text("Tayyor") }
                }
            }
        )
    }

    // ── Date range picker dialog ─────────────────────────────────────────────
    if (showPeriodPicker) {
        val dateState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPeriodPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val sel = dateState.selectedDateMillis
                    if (sel != null) {
                        if (filterStartMs == null) filterStartMs = sel
                        else if (filterEndMs == null && sel > filterStartMs!!) filterEndMs = sel + 86_400_000L
                        else { filterStartMs = sel; filterEndMs = null }
                    }
                    showPeriodPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPeriodPicker = false }) { Text("Bekor") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    val activeFilterCount = listOf(filterEntityType, filterActionType,
        if (filterMoneyOnly) "1" else null,
        filterStartMs?.toString(), filterEndMs?.toString(),
        if (filterSearchText.isNotBlank()) "1" else null
    ).count { it != null }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Unified search bar (matches Renters page) ──────────────────────
        UnifiedSearchBar(
            query = filterSearchText,
            onQueryChange = { filterSearchText = it },
            placeholder = "Tarixda qidirish — sarlavha, ekran, harakat yoki obyekt",
            onFilterClick = { showFilters = true },
            filterActive = activeFilterCount > 0
        )

        // ── Action row — 5 UnifiedButtons (matches Renters page dress code) ─
        // Вид / branch picker / Вернуться / Создать ветку / Фильтр.
        // All buttons always visible; some disabled when no selection.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // «Вид» — toggle table ↔ visual timeline
            UnifiedButton(
                label = if (visualMode) "Jadval" else "Ko'rinish",
                icon = if (visualMode) Icons.Default.TableView else Icons.Default.Visibility,
                onClick = { visualMode = !visualMode },
                variant = if (visualMode) UnifiedButtonVariant.PRIMARY else UnifiedButtonVariant.SECONDARY,
                modifier = Modifier.weight(1.0f)
            )
            // Branch picker — shows current branch name; opens picker dialog
            UnifiedButton(
                label = branches.firstOrNull { it.id == activeBranchId }?.name ?: "Main",
                icon = Icons.Default.AccountTree,
                onClick = { showBranchPicker = true },
                variant = UnifiedButtonVariant.SECONDARY,
                modifier = Modifier.weight(1.0f)
            )
            // «Вернуться» — restore to selected time code
            PrimaryButton(
                label = "Qaytish",
                icon = Icons.Default.History,
                enabled = selected != null,
                onClick = {
                    if (selected != null) showRestoreDialog = true
                },
                modifier = Modifier.weight(0.9f)
            )
            // «+» — create new branch from selected time code
            UnifiedButton(
                label = "+ Tarmoq",
                icon = Icons.Default.Add,
                onClick = { showBranchCreate = true },
                variant = UnifiedButtonVariant.SECONDARY,
                modifier = Modifier.weight(1.0f)
            )
        }

        // ── Active filter chips strip ────────────────────────────────────────
        if (filterEntityType != null || filterActionType != null || filterMoneyOnly ||
            filterStartMs != null || filterEndMs != null || filterSearchText.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                filterEntityType?.let { et ->
                    AssistChip(
                        onClick = { filterEntityType = null },
                        label = { Text(et, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                filterActionType?.let { at ->
                    AssistChip(
                        onClick = { filterActionType = null },
                        label = { Text(at, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (filterMoneyOnly) {
                    AssistChip(
                        onClick = { filterMoneyOnly = false },
                        label = { Text("Faqat pul", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (filterStartMs != null || filterEndMs != null) {
                    AssistChip(
                        onClick = { filterStartMs = null; filterEndMs = null },
                        label = { Text("Davr", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (filterSearchText.isNotBlank()) {
                    AssistChip(
                        onClick = { filterSearchText = "" },
                        label = { Text("«$filterSearchText»", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        // ── Header surface (count + selection count) ───────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tarix — ${filteredEvents.size} ta voqea",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClaudeText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (selected != null) {
                    Text(
                        "1 tanlandi",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClaudeAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        HorizontalDivider(color = ClaudeDivider)

        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = StatusArchived,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    if (chronological.isEmpty()) {
                        Text(
                            "Bu tarmoqda hali harakat yo'q",
                            style = MaterialTheme.typography.titleMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "+ bilan tanlangan taymkoddan yangi tarmoq yarating.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                    } else {
                        Text(
                            "Filtr bo'yicha hech narsa topilmadi",
                            style = MaterialTheme.typography.titleMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Filtrlarni tozalang yoki kengaytiring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                    }
                }
            }
            return@Column
        }

        if (visualMode) {
            // ── Visual timeline mode (media-player style) ──────────────────
            // Kept exactly as before — this is the unique feature of History.
            val index = timelinePosition.toInt().coerceIn(0, filteredEvents.lastIndex)
            val event = filteredEvents[index]
            var isPlaying by remember { mutableStateOf(false) }
            LaunchedEffect(isPlaying, index, filteredEvents.size) {
                if (isPlaying && index < filteredEvents.lastIndex) {
                    kotlinx.coroutines.delay(800L)
                    timelinePosition = (index + 1).toFloat()
                    onSelectedEventChange(filteredEvents[index + 1].id)
                } else if (isPlaying && index >= filteredEvents.lastIndex) {
                    isPlaying = false
                }
            }
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ClaudeCard,
                    border = androidx.compose.foundation.BorderStroke(2.dp, ClaudeAccent)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            event.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeText
                        )
                        Text(
                            event.screen,
                            style = MaterialTheme.typography.labelMedium,
                            color = ClaudeTextSecondary
                        )
                        Text(
                            formatter.format(Date(event.timestamp)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText
                        )
                        event.entityType?.let {
                            Text(
                                "$it #${event.entityId ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Payload: ${event.payloadJson}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                    }
                }
                Column {
                    Text(
                        "${index + 1} / ${filteredEvents.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary
                    )
                    Slider(
                        value = timelinePosition,
                        onValueChange = { value ->
                            timelinePosition = value
                            isPlaying = false
                            onSelectedEventChange(filteredEvents[value.toInt().coerceIn(0, filteredEvents.lastIndex)].id)
                        },
                        valueRange = 0f..filteredEvents.lastIndex.toFloat(),
                        steps = (filteredEvents.size - 2).coerceAtLeast(0)
                    )
                    // ── Media-player-style controls (§9.1) ──────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                isPlaying = false
                                val newIndex = (index - 1).coerceAtLeast(0)
                                timelinePosition = newIndex.toFloat()
                                onSelectedEventChange(filteredEvents[newIndex].id)
                            },
                            enabled = index > 0
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Oldingi", tint = ClaudeAccent)
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = { isPlaying = !isPlaying }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pauza" else "Ijro",
                                tint = ClaudeAccent,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(
                            onClick = {
                                isPlaying = false
                                val newIndex = (index + 1).coerceAtMost(filteredEvents.lastIndex)
                                timelinePosition = newIndex.toFloat()
                                onSelectedEventChange(filteredEvents[newIndex].id)
                            },
                            enabled = index < filteredEvents.lastIndex
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Keyingi", tint = ClaudeAccent)
                        }
                    }
                }
            }
        } else {
            // ── Table list — same row design as Renters page ──────────────
            // Border 1.5dp default → 2dp selected, border color = action color.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    filteredEvents.sortedByDescending { it.timestamp },
                    key = { _, it -> it.id }
                ) { idx, event ->
                    val isSelected = selectedEventId == event.id
                    // ── Status color (§11 unified language) ────────────────
                    val statusColor = when {
                        event.isArchived -> StatusArchived
                        event.actionType.equals("PAY", ignoreCase = true) ||
                            event.actionType.contains("PAYMENT", ignoreCase = true) -> StatusOk
                        event.actionType.contains("DELETE", ignoreCase = true) ||
                            event.actionType.contains("STORNO", ignoreCase = true) -> StatusOverdue
                        event.actionType.contains("CORRECTION", ignoreCase = true) -> StatusInfo
                        else -> ClaudeAccent
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 2.dp else 1.5.dp,
                                    color = statusColor,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .background(
                                    if (isSelected) ClaudeAccentBg else Color.White
                                )
                                .combinedClickable(
                                    onClick = {
                                        val newId = if (selectedEventId == event.id) null else event.id
                                        onSelectedEventChange(newId)
                                    },
                                    onLongClick = {
                                        onSelectedEventChange(event.id)
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ── № column ─────────────────────────────────────
                            Text(
                                "${idx + 1}",
                                modifier = Modifier.width(40.dp).padding(end = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            // ── Status dot ──────────────────────────────────
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            // ── Title + meta column ──────────────────────────
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isSelected) "✓ ${event.title}" else event.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ClaudeText,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${event.actionType} • ${event.screen}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatter.format(Date(event.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTextSecondary,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // ── Major event indicator (●) ────────────────────
                            Text(
                                if (event.isMajor) "●" else "·",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (event.isMajor) statusColor else ClaudeTextSecondary
                            )
                            Spacer(Modifier.width(4.dp))
                            // ── Detail (info) button ─────────────────────────
                            IconButton(onClick = {
                                onSelectedEventChange(event.id)
                                showDetail = true
                            }) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Tafsilot",
                                    tint = ClaudeAccent
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ClaudeText, fontWeight = FontWeight.SemiBold)
    }
}
