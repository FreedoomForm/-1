package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.TimelineEvent
import com.example.ui.HistoryViewModel
import kotlinx.coroutines.launch
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusArchived
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusOk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Branch-aware history: list and visual timeline are two views of same events.
 *
 * Per PLAN_UNIVERSAL_ACCOUNTING §9.1 / §9B:
 *  - Action row: «Вид», branch picker, «Вернуться»
 *  - Filters: period (date range), renter/scooter/contract (entity type), action type,
 *    money/non-money
 *  - Safe detail dialog: linked entity, amount (if money), author, reason, storno link
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var filterEntityType by remember { mutableStateOf<String?>(null) }   // RENTER, SCOOTER, CONTRACT, CARD, PAYMENT, REPAIR, RESTORE
    var filterActionType by remember { mutableStateOf<String?>(null) }   // CREATE, UPDATE, DELETE, PAY, REPAIR, RESTORE, CORRECTION...
    var filterMoneyOnly by remember { mutableStateOf(false) }            // only money-related events
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
        // §9B: парсим payloadJson чтобы извлечь сумму, автора, причину.
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

    Column(Modifier.fillMaxSize()) {
        // ── History action row (§9.1: «Вид» / branch / «Вернуться») ──────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { visualMode = !visualMode }) {
                Text(if (visualMode) "Jadval" else "Ko'rinish")
            }
            TextButton(onClick = { showBranchPicker = true }) {
                Text(branches.firstOrNull { it.id == activeBranchId }?.name ?: "Main")
            }
            TextButton(
                enabled = selected != null,
                onClick = {
                    // §9.0: open restore dialog — never erases financial facts,
                    // records an auditable RESTORE event instead.
                    if (selected != null) showRestoreDialog = true
                }
            ) { Text("Qaytish") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { showFilters = true }) {
                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Filtr")
                val activeFilterCount = listOf(filterEntityType, filterActionType,
                    if (filterMoneyOnly) "1" else null,
                    filterStartMs?.toString(), filterEndMs?.toString(),
                    if (filterSearchText.isNotBlank()) "1" else null
                ).count { it != null }
                if (activeFilterCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text("($activeFilterCount)", color = ClaudeAccent, fontWeight = FontWeight.Bold)
                }
            }
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

        if (filteredEvents.isEmpty()) {
            // ── Empty state (§11: clear empty states + hints) ─────────────────
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = StatusArchived,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (chronological.isEmpty()) {
                    Text("Bu tarmoqda hali harakat yo'q", style = MaterialTheme.typography.titleMedium)
                    Text("+ bilan tanlangan taymkoddan yangi tarmoq yarating.")
                } else {
                    Text("Filtr bo'yicha hech narsa topilmadi", style = MaterialTheme.typography.titleMedium)
                    Text("Filtrlarni tozalang yoki kengaytiring.")
                }
            }
        } else if (visualMode) {
            val index = timelinePosition.toInt().coerceIn(0, filteredEvents.lastIndex)
            val event = filteredEvents[index]
            // ── Play/pause auto-advance state (§9.1: play sequentially plays events) ──
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
                Card(Modifier.fillMaxWidth().clickable { onSelectedEventChange(event.id) }) {
                    Column(Modifier.padding(20.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(event.screen, style = MaterialTheme.typography.labelMedium)
                        Text(formatter.format(Date(event.timestamp)), style = MaterialTheme.typography.bodyMedium)
                        event.entityType?.let { Text("$it #${event.entityId ?: "—"}") }
                        Text("Render: ${event.payloadJson}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column {
                    Text("${index + 1} / ${filteredEvents.size}", style = MaterialTheme.typography.labelSmall)
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
                    // ── Media-player-style controls (§9.1) ──────────────────────
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredEvents.sortedByDescending { it.timestamp }, key = { it.id }) { event ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            val newId = if (selectedEventId == event.id) null else event.id
                            onSelectedEventChange(newId)
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedEventId == event.id) ClaudeAccentBg else ClaudeCard
                        )
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            // ── Color dot by entity type (§11 unified language) ───────
                            val dotColor = when {
                                event.isArchived -> StatusArchived
                                event.actionType.equals("PAY", ignoreCase = true) ||
                                    event.actionType.contains("PAYMENT", ignoreCase = true) -> StatusOk
                                event.actionType.contains("DELETE", ignoreCase = true) ||
                                    event.actionType.contains("STORNO", ignoreCase = true) -> StatusArchived
                                event.actionType.contains("CORRECTION", ignoreCase = true) -> StatusInfo
                                else -> ClaudeAccent
                            }
                            Box(
                                Modifier.size(8.dp).padding(end = 2.dp)
                            ) {
                                Box(
                                    Modifier.size(8.dp).background(dotColor, androidx.compose.foundation.shape.CircleShape)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (selectedEventId == event.id) "✓ ${event.title}" else event.title,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${event.actionType} • ${event.screen}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeTextSecondary
                                )
                                Text(
                                    formatter.format(Date(event.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTextSecondary
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (event.isMajor) "●" else "·", style = MaterialTheme.typography.titleMedium)
                            // ── Detail (info) button ──────────────────────────────────
                            IconButton(onClick = {
                                onSelectedEventChange(event.id)
                                showDetail = true
                            }) {
                                Icon(Icons.Default.Info, contentDescription = "Tafsilot", tint = ClaudeAccent)
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
