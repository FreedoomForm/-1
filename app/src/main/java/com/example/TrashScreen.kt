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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.TrashViewModel
import com.example.ui.components.DangerButton
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.UnifiedButton
import com.example.ui.components.UnifiedButtonVariant
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusArchived
import com.example.ui.theme.StatusOverdue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recycle bin screen — redesigned to follow the same dress-code as the
 * Renters page (UnifiedSearchBar + action row of UnifiedButtons + card
 * list with status-colored borders and rounded corners).
 *
 * Unique functions preserved:
 *   • Restore (primary action) — SuccessButton-style
 *   • Edit reason — SecondaryButton
 *   • Permanent purge — DangerButton
 *   • Card restore balance check + actionable error dialog
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    var searchQuery by remember { mutableStateOf("") }

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
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (msg.contains("conflict", ignoreCase = true)) {
                        Text(
                            "Skuter boshaka aktiv ijara bilan band. Avval joriy " +
                            "ijarani tugating yoki sanalarni o'zgartiring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
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

    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    // ── Filter items by search query ──────────────────────────────────────
    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) items
        else items.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.sourceType.contains(searchQuery, ignoreCase = true) ||
            (it.reason?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Unified search bar (matches Renters page) ──────────────────────
        UnifiedSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Korzinkada qidirish — sarlavha, sabab yoki tur",
            onFilterClick = null,
            onCalendarClick = null
        )

        // ── Action row: 3 UnifiedButtons (matches Renters page dress-code) ─
        // To'lov / Uzish / SMS style — here: Restore / Edit / Purge.
        // Buttons are always visible; disabled (grey) when nothing selected.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val hasSelection = selected.isNotEmpty()
            PrimaryButton(
                label = "Qaytarish",
                icon = Icons.Default.Restore,
                enabled = hasSelection,
                onClick = {
                    selected.forEach { viewModel.restore(it) }
                    onSelectedChange(emptySet())
                },
                modifier = Modifier.weight(1.4f)
            )
            SecondaryButton(
                label = "Sababni tahrirlash",
                icon = Icons.Default.Tune,
                enabled = hasSelection && selected.size == 1,
                onClick = {
                    if (selected.size == 1) {
                        editedReason = items.firstOrNull { it.id == selected.first() }?.reason.orEmpty()
                        showEditDialog = true
                    }
                },
                modifier = Modifier.weight(1.4f)
            )
            DangerButton(
                label = "Butunlab o'chirish",
                icon = Icons.Default.DeleteForever,
                enabled = hasSelection,
                onClick = { showPurgeConfirm = true },
                modifier = Modifier.weight(1.4f)
            )
        }

        // ── Header row (count + selected count) ────────────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Korzinka — ${filteredItems.size} ta yozuv",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClaudeText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (selected.isNotEmpty()) {
                    Text(
                        "${selected.size} tanlandi",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClaudeAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = ClaudeDivider)

        if (filteredItems.isEmpty()) {
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
                    Text(
                        "Korzinka bo'sh",
                        style = MaterialTheme.typography.titleMedium,
                        color = ClaudeText,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "O'chirilgan obyektlar shu yerda vaqtincha saqlanadi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
            return@Column
        }

        // ── List — same row design as Renters page ─────────────────────────
        // Border 1.5dp default → 2dp selected, border color = StatusArchived
        // (grey, since these are deleted items). White bg → light grey when
        // selected. combinedClickable for tap = restore, long-press = select.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(filteredItems, key = { _, it -> it.id }) { idx, item ->
                val isSelected = item.id in selected
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
                                color = if (isSelected) ClaudeAccent else StatusArchived,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(
                                if (isSelected) Color(0xFFF3F4F6) else Color.White
                            )
                            .combinedClickable(
                                onClick = {
                                    // Single tap → open detail dialog inline (restore + purge)
                                    val newSet = if (isSelected) selected - item.id else selected + item.id
                                    onSelectedChange(newSet)
                                },
                                onLongClick = {
                                    onSelectedChange(setOf(item.id))
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ── № column ─────────────────────────────────────────
                        Text(
                            "${idx + 1}",
                            modifier = Modifier.width(40.dp).padding(end = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        // ── Status dot (always grey for trash items) ────────
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .padding(end = 2.dp)
                                .background(StatusArchived, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        // ── Title + meta ─────────────────────────────────────
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSelected) "✓ ${item.title}" else item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${item.sourceType} • ${formatter.format(Date(item.deletedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            item.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(
                                    reason.take(80) + if (reason.length > 80) "…" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // ── Inline action buttons (per-row quick actions) ────
                        // Same visual style as Renters page — small TextButtons
                        // aligned to the right edge of each row.
                        TextButton(
                            onClick = { viewModel.restore(item.id) }
                        ) { Text("Qaytarish", color = ClaudeAccent) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                onSelectedChange(setOf(item.id))
                                showPurgeConfirm = true
                            }
                        ) { Text("O'chirish", color = StatusOverdue) }
                    }
                }
            }
        }
    }
}
