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
import com.example.data.BusinessOperation
import com.example.ui.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact phone-first chronological history; details open from a selected row. */
@Composable
fun HistoryScreen(
    createTrigger: Int = 0,
    editTrigger: Int = 0,
    selectedSourceId: String? = null,
    onSelectedSourceChange: (String?) -> Unit = {},
    viewModel: HistoryViewModel = viewModel()
) {
    val items = viewModel.items.collectAsStateWithLifecycle().value
    var selectedTimestamp by remember { mutableStateOf<Long?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var branchNote by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }
    var correctionNote by remember { mutableStateOf("") }
    LaunchedEffect(createTrigger) {
        if (createTrigger > 0) showCreateDialog = true
    }
    LaunchedEffect(editTrigger) {
        if (editTrigger > 0 && selectedSourceId != null) showEditDialog = true
    }
    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Tarixga tuzatish") },
            text = { OutlinedTextField(correctionNote, { correctionNote = it }, label = { Text("Tuzatish izohi") }) },
            confirmButton = { TextButton(onClick = {
                selectedSourceId?.let { viewModel.correctSelected(it, correctionNote) }
                correctionNote = ""; showEditDialog = false
            }) { Text("Tuzatish kiritish") } },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Bekor qilish") } }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Tarix tarmog'ini yaratish") },
            text = {
                Column {
                    Text("Vaqt: ${formatter.format(Date(selectedTimestamp ?: System.currentTimeMillis()))}")
                    OutlinedTextField(branchNote, { branchNote = it }, label = { Text("Izoh") })
                }
            },
            confirmButton = { TextButton(onClick = {
                viewModel.createBranch(selectedTimestamp ?: System.currentTimeMillis(), branchNote)
                branchNote = ""; showCreateDialog = false
            }) { Text("Yaratish") } },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Bekor qilish") } }
        )
    }

    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Tarix hali bo'sh", style = MaterialTheme.typography.titleMedium)
            Text("To'lovlar, o'zgarishlar va amallar shu yerda ko'rinadi.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { "${it.kind}-${it.sourceId}" }) { item ->
            Card(Modifier.fillMaxWidth().clickable {
                selectedTimestamp = item.timestamp
                onSelectedSourceChange(if (selectedSourceId == item.sourceId) null else item.sourceId)
            }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (selectedSourceId == item.sourceId) "✓ ${item.title}" else item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(item.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(formatter.format(Date(item.timestamp)), style = MaterialTheme.typography.labelSmall)
                    }
                    item.amountMinor?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("${BusinessOperation.fromMinor(it).toLong()} UZS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
