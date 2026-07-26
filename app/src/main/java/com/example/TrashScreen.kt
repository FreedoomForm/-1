package com.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
    var showPurgeConfirm by remember { mutableStateOf(false) }
    // ─§9.2: card restore balance check — surface error to user ─────────────
    var restoreError by remember { mutableStateOf<String?>(null) }
    var editedReason by remember { mutableStateOf("") }

    // Subscribe to async restore errors (from viewModelScope) ──────────────
    LaunchedEffect(Unit) {
        viewModel.restoreErrors.collect { msg -> restoreError = msg }
    }
    LaunchedEffect(restoreTrigger) {
        if (restoreTrigger > 0) selected.forEach { id -> viewModel.restore(id) }
        if (restoreTrigger > 0) onSelectedChange(emptySet())
    }
    LaunchedEffect(editTrigger) {
        if (editTrigger > 0 && selected.size == 1) {
            editedReason = items.firstOrNull { it.id == selected.first() }?.reason.orEmpty()
            showEditDialog = true
        }
    }
    LaunchedEffect(purgeTrigger) {
        if (purgeTrigger > 0 && selected.isNotEmpty()) showPurgeConfirm = true
    }

    // ─§9.2: restore error dialog with actionable hint ──────────────────────
    restoreError?.let { msg ->
        AlertDialog(
            onDismissRequest = { restoreError = null },
            title = { Text("Qayta tiklash xatosi") },
            text = {
                Column {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (msg.contains("balance", ignoreCase = true)) {
                        Text(
                            "Karta qoldig'i 0 emas. Avval balansni tekshiring yoki " +
                            "bank operatsiyalari orqali moslang, so'ng qayta urinib ko'ring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colors.error
                        )
                    } else if (msg.contains("conflict", ignoreCase = true)) {
                        Text(
                            "Skuter boshaka aktiv ijara bilan band. Avval joriy " +
                            "ijarani tugating yoki sanalarni o'zgartiring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colors.error
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { restoreError = null }) { Text("Tushunarli") } }
        )
    }

    if (showPurgeConfirm) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirm = false },
            title = { Text("Butunlay o'chirish?") },
            text = { Text("Tanlangan ${selected.size} ta korzinka yozuvi qayta tiklanmaydi. Moliyaviy audit saqlanadi.") },
            confirmButton = { TextButton(onClick = {
                selected.forEach { viewModel.purge(it) }
                onSelectedChange(emptySet())
                showPurgeConfirm = false
            }) { Text("Butunlay o'chirish") } },
            dismissButton = { TextButton(onClick = { showPurgeConfirm = false }) { Text("Bekor qilish") } }
        )
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
                        TextButton(onClick = {
                            onSelectedChange(setOf(item.id)); showPurgeConfirm = true
                        }) { Text("Butunlay o'chirish") }
                    }
                }
            }
        }
    }
}
