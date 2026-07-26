package com.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.TimelineEvent
import com.example.ui.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Branch-aware history: list and visual timeline are two views of same events. */
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
    var visualMode by remember { mutableStateOf(false) }
    var showBranchPicker by remember { mutableStateOf(false) }
    var showBranchCreate by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var branchName by remember { mutableStateOf("") }
    var correctionNote by remember { mutableStateOf("") }
    var timelinePosition by remember { mutableStateOf(0f) }

    LaunchedEffect(createTrigger) { if (createTrigger > 0) showBranchCreate = true }
    LaunchedEffect(editTrigger) { if (editTrigger > 0 && selectedEventId != null) showEdit = true }

    val chronological = events.sortedBy { it.timestamp }
    val selected = selectedEventId?.let { id -> events.firstOrNull { it.id == id } }

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

    Column(Modifier.fillMaxSize()) {
        // Dedicated History action row, analogous to renter quick actions.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { visualMode = !visualMode }) { Text(if (visualMode) "Jadval" else "Ko'rinish") }
            TextButton(onClick = { showBranchPicker = true }) {
                Text(branches.firstOrNull { it.id == activeBranchId }?.name ?: "Main")
            }
            TextButton(
                enabled = selected != null,
                onClick = {
                    // State restoration is represented by selection now; the
                    // event/snapshot engine supplies the render frame below.
                    selected?.let { onSelectedEventChange(it.id) }
                }
            ) { Text("Qaytish") }
        }

        if (chronological.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Bu tarmoqda hali harakat yo'q", style = MaterialTheme.typography.titleMedium)
                Text("+ bilan tanlangan taymkoddan yangi tarmoq yarating.")
            }
        } else if (visualMode) {
            val index = timelinePosition.toInt().coerceIn(0, chronological.lastIndex)
            val event = chronological[index]
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
                    Text("${index + 1} / ${chronological.size}", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = timelinePosition,
                        onValueChange = { value ->
                            timelinePosition = value
                            onSelectedEventChange(chronological[value.toInt().coerceIn(0, chronological.lastIndex)].id)
                        },
                        valueRange = 0f..chronological.lastIndex.toFloat(),
                        steps = (chronological.size - 2).coerceAtLeast(0)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events.sortedByDescending { it.timestamp }, key = { it.id }) { event ->
                    Card(Modifier.fillMaxWidth().clickable { onSelectedEventChange(if (selectedEventId == event.id) null else event.id) }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (selectedEventId == event.id) "✓ ${event.title}" else event.title, fontWeight = FontWeight.SemiBold)
                                Text("${event.actionType} • ${event.screen}", style = MaterialTheme.typography.bodySmall)
                                Text(formatter.format(Date(event.timestamp)), style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (event.isMajor) "●" else "·", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}
