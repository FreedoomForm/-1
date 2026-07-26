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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.TrashViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recycle bin. Restore is the primary action; permanent purge is explicit. */
@Composable
fun TrashScreen(
    restoreTrigger: Int = 0,
    editTrigger: Int = 0,
    purgeTrigger: Int = 0,
    selected: Set<Long> = emptySet(),
    onSelectedChange: (Set<Long>) -> Unit = {},
    viewModel: TrashViewModel = viewModel()
) {
    val items = viewModel.items.collectAsStateWithLifecycle().value
    var showEditDialog by remember { mutableStateOf(false) }
    var editedReason by remember { mutableStateOf("") }
    LaunchedEffect(restoreTrigger) {
        if (restoreTrigger > 0) selected.forEach { viewModel.restore(it) }
        if (restoreTrigger > 0) onSelectedChange(emptySet())
    }
    LaunchedEffect(editTrigger) {
        if (editTrigger > 0 && selected.size == 1) {
            editedReason = items.firstOrNull { it.id == selected.first() }?.reason.orEmpty()
            showEditDialog = true
        }
    }
    LaunchedEffect(purgeTrigger) {
        if (purgeTrigger > 0) selected.forEach { viewModel.purge(it) }
        if (purgeTrigger > 0) onSelectedChange(emptySet())
    }
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Korzinka yozuvini tahrirlash") },
            text = { OutlinedTextField(editedReason, { editedReason = it }, label = { Text("O'chirish sababi") }) },
            confirmButton = { TextButton(onClick = {
                selected.firstOrNull()?.let { viewModel.updateReason(it, editedReason) }
                showEditDialog = false
            }) { Text("Saqlash") } },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Bekor qilish") } }
        )
    }

    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Korzinka bo'sh", style = MaterialTheme.typography.titleMedium)
            Text("O'chirilgan obyektlar shu yerda vaqtincha saqlanadi.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth().clickable {
                onSelectedChange(if (item.id in selected) selected - item.id else selected + item.id)
            }) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (item.id in selected) "✓ ${item.title}" else item.title, fontWeight = FontWeight.SemiBold)
                    Text("${item.sourceType} • ${formatter.format(Date(item.deletedAt))}", style = MaterialTheme.typography.labelSmall)
                    item.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.restore(item.id) }) { Text("Qaytarish") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { viewModel.purge(item.id) }) { Text("Butunlay o'chirish") }
                    }
                }
            }
        }
    }
}
