package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.BusinessOperation
import com.example.ui.BusinessOperationViewModel
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
import com.example.ui.theme.StatusReserved
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §2: Operations journal — redesigned to follow the Renters page dress code.
 *
 * Layout (matches RenterTable):
 *   ┌─────────────────────────────────────────────────┐
 *   │ [🔍 Qidirish ............................] [⚙][📅]│ ← UnifiedSearchBar
 *   ├─────────────────────────────────────────────────┤
 *   │ [Filtr ▾]  [Storno only]                        │ ← action row
 *   ├─────────────────────────────────────────────────┤
 *   │ Korzinka — N ta yozuv           M ta tanlandi   │ ← header surface
 *   ├─────────────────────────────────────────────────┤
 *   │ ▌ ✓ №  ●  Title          Amount      [Detail]  │ ← row (bordered card)
 *   │ ▌    ●  Type • Date                            │
 *   │ ...                                              │
 *   └─────────────────────────────────────────────────┘
 *
 * Unique features preserved:
 *   • Filters by type / direction / reversed-only / date / search text
 *   • Detail dialog with full operation info + storno link
 *   • Status-aware row coloring (income green, expense red, etc.)
 *   • Storno badge on reversed operations
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OperationsJournalScreen(
    viewModel: BusinessOperationViewModel = viewModel()
) {
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    var showFilters by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var selectedOpId by remember { mutableStateOf<Long?>(null) }

    // ── Filters ────────────────────────────────────────────────────────────
    var filterType by remember { mutableStateOf<String?>(null) }
    var filterDirection by remember { mutableStateOf<String?>(null) }
    var filterStartMs by remember { mutableStateOf<Long?>(null) }
    var filterEndMs by remember { mutableStateOf<Long?>(null) }
    var filterSearchText by remember { mutableStateOf("") }
    var filterReversedOnly by remember { mutableStateOf(false) }

    val selected = selectedOpId?.let { id -> operations.firstOrNull { it.id == id } }

    // ── Apply filters ──────────────────────────────────────────────────────
    val filteredOps = remember(operations, filterType, filterDirection, filterStartMs, filterEndMs, filterSearchText, filterReversedOnly) {
        operations.filter { op ->
            (filterType == null || op.type == filterType) &&
            (filterDirection == null || op.direction == filterDirection) &&
            (filterStartMs?.let { op.occurredAt >= it } ?: true) &&
            (filterEndMs?.let { op.occurredAt <= it } ?: true) &&
            (filterSearchText.isBlank() ||
             op.type.contains(filterSearchText, ignoreCase = true) ||
             op.direction.contains(filterSearchText, ignoreCase = true) ||
             (op.note?.contains(filterSearchText, ignoreCase = true) == true)) &&
            (!filterReversedOnly || op.status == BusinessOperation.STATUS_REVERSED)
        }.sortedByDescending { it.occurredAt }
    }

    // ── Detail dialog ──────────────────────────────────────────────────────
    if (showDetail && selected != null) {
        val op = selected
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("Operatsiya tafsiloti") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Turi", typeLabel(op.type))
                    DetailRow("Yo'nalishi", directionLabel(op.direction))
                    DetailRow("Summa", "${BusinessOperation.fromMinor(op.amountMinor).toLong()} so'm")
                    DetailRow("Vaqt", formatter.format(Date(op.occurredAt)))
                    DetailRow("Holat", if (op.status == BusinessOperation.STATUS_REVERSED) "Storno qilingan" else "Aktiv")
                    op.renterId?.let { DetailRow("Arendator ID", it.toString()) }
                    op.scooterId?.let { DetailRow("Skuter ID", it.toString()) }
                    op.contractId?.let { DetailRow("Kontrakt ID", it.toString()) }
                    op.fromCardId?.let { DetailRow("Kartadan", "#$it") }
                    op.toCardId?.let { DetailRow("Kartaga", "#$it") }
                    op.note?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Izoh:", style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = ClaudeText)
                    }
                    if (op.reversesOperationId != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Storno operatsiya #${op.reversesOperationId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusOverdue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) { Text("Yopish") }
            }
        )
    }

    // ── Filters dialog ─────────────────────────────────────────────────────
    if (showFilters) {
        AlertDialog(
            onDismissRequest = { showFilters = false },
            title = { Text("Filtrlar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Operatsiya turi:", style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
                    val types = listOf(
                        null to "Hammasi",
                        BusinessOperation.TYPE_RENT_PAYMENT to "Ijara to'lovi",
                        BusinessOperation.TYPE_TRANSFER to "Transfer",
                        BusinessOperation.TYPE_EXPENSE to "Xarajat",
                        BusinessOperation.TYPE_REPAIR to "Ta'mir",
                        BusinessOperation.TYPE_DEPOSIT_RECEIVED to "Zalog kiritdi",
                        BusinessOperation.TYPE_DEPOSIT_REFUNDED to "Zalog qaytarildi",
                        BusinessOperation.TYPE_DISCOUNT to "Chegirma",
                        BusinessOperation.TYPE_DEBT_FORGIVEN to "Qarz kechirildi",
                        BusinessOperation.TYPE_REFUND to "Qaytarish",
                        BusinessOperation.TYPE_PENALTY_ACCRUAL to "Jarima",
                        BusinessOperation.TYPE_REVERSAL to "Storno"
                    )
                    types.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (value, label) ->
                                AssistChip(
                                    onClick = { filterType = if (filterType == value) null else value },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Yo'nalish:", style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
                    val dirs = listOf(
                        null to "Hammasi",
                        BusinessOperation.DIRECTION_INCOME to "Daromad",
                        BusinessOperation.DIRECTION_EXPENSE to "Xarajat",
                        BusinessOperation.DIRECTION_TRANSFER to "Transfer",
                        BusinessOperation.DIRECTION_LIABILITY to "Majburiyat"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        dirs.forEach { (value, label) ->
                            AssistChip(
                                onClick = { filterDirection = if (filterDirection == value) null else value },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = { filterReversedOnly = !filterReversedOnly },
                        label = { Text(if (filterReversedOnly) "✓ " else "" + "Faqat storno") }
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        filterType = null
                        filterDirection = null
                        filterReversedOnly = false
                        filterStartMs = null
                        filterEndMs = null
                        filterSearchText = ""
                    }) { Text("Tozalash") }
                    TextButton(onClick = { showFilters = false }) { Text("Tayyor") }
                }
            }
        )
    }

    // ── Active filter chips strip (matches History screen pattern) ─────────
    val hasActiveFilters = filterType != null || filterDirection != null ||
        filterReversedOnly || filterStartMs != null || filterEndMs != null ||
        filterSearchText.isNotBlank()
    val activeFilterCount = listOf(filterType, filterDirection,
        if (filterReversedOnly) "1" else null,
        filterStartMs?.toString(), filterEndMs?.toString(),
        if (filterSearchText.isNotBlank()) "1" else null
    ).count { it != null }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Unified search bar (matches Renters page) ──────────────────────
        UnifiedSearchBar(
            query = filterSearchText,
            onQueryChange = { filterSearchText = it },
            placeholder = "Operatsiyalarda qidirish — tur, yo'nalish yoki izoh",
            onFilterClick = { showFilters = true },
            filterActive = activeFilterCount > 0
        )

        // ── Action row (matches Renters page) ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryButton(
                label = "Filtr",
                icon = Icons.Default.Tune,
                onClick = { showFilters = true },
                modifier = Modifier.weight(1.4f)
            )
            UnifiedButton(
                label = if (filterReversedOnly) "✓ Storno" else "Storno",
                icon = Icons.Default.Assessment,
                onClick = { filterReversedOnly = !filterReversedOnly },
                variant = if (filterReversedOnly) UnifiedButtonVariant.PRIMARY
                         else UnifiedButtonVariant.SECONDARY,
                modifier = Modifier.weight(1.4f)
            )
            if (activeFilterCount > 0) {
                TextButton(
                    onClick = {
                        filterType = null
                        filterDirection = null
                        filterReversedOnly = false
                        filterStartMs = null
                        filterEndMs = null
                        filterSearchText = ""
                    }
                ) {
                    Text("Tozalash ($activeFilterCount)", color = ClaudeAccent)
                }
            }
        }

        // ── Header surface (count + selection count, matches Renters) ──────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Operatsiyalar jurnali — ${filteredOps.size} ta yozuv",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClaudeText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (selectedOpId != null) {
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

        if (filteredOps.isEmpty()) {
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
                        "Operatsiyalar yo'q",
                        style = MaterialTheme.typography.titleMedium,
                        color = ClaudeText,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Moliyaviy harakatlar shu yerda ko'rinadi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
            return@Column
        }

        // ── LazyColumn — same row design as Renters page ───────────────────
        // Border 1.5dp default → 2dp selected, border color = direction color
        // (income=green, expense=red, transfer=amber, liability=grey).
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(filteredOps, key = { _, it -> it.id }) { idx, op ->
                val dotColor = when (op.direction) {
                    BusinessOperation.DIRECTION_INCOME -> StatusOk
                    BusinessOperation.DIRECTION_EXPENSE -> StatusOverdue
                    BusinessOperation.DIRECTION_TRANSFER -> StatusReserved
                    BusinessOperation.DIRECTION_LIABILITY -> StatusArchived
                    else -> ClaudeTextSecondary
                }
                val isReversed = op.status == BusinessOperation.STATUS_REVERSED
                val isSelected = selectedOpId == op.id

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
                                color = if (isReversed) StatusArchived else dotColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(
                                when {
                                    isSelected -> Color(0xFFF3F4F6)
                                    isReversed -> StatusArchived.copy(alpha = 0.08f)
                                    else -> Color.White
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    selectedOpId = if (isSelected) null else op.id
                                },
                                onLongClick = {
                                    selectedOpId = op.id
                                    showDetail = true
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
                        // ── Direction dot ────────────────────────────────────
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(dotColor, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        // ── Title + meta column ──────────────────────────────
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = typeLabel(op.type) + if (isReversed) " (storno)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isReversed) ClaudeTextSecondary else ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatter.format(Date(op.occurredAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            op.note?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it.take(80) + if (it.length > 80) "…" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // ── Amount column ────────────────────────────────────
                        Text(
                            "${BusinessOperation.fromMinor(op.amountMinor).toLong()} so'm",
                            style = MaterialTheme.typography.titleSmall,
                            color = dotColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        // ── Detail button ────────────────────────────────────
                        androidx.compose.material3.IconButton(
                            onClick = {
                                selectedOpId = op.id
                                showDetail = true
                            }
                        ) {
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

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClaudeTextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ClaudeText, fontWeight = FontWeight.SemiBold)
    }
}

private fun typeLabel(type: String): String = when (type) {
    BusinessOperation.TYPE_RENT_PAYMENT -> "Ijara to'lovi"
    BusinessOperation.TYPE_TRANSFER -> "Transfer"
    BusinessOperation.TYPE_EXPENSE -> "Xarajat"
    BusinessOperation.TYPE_REPAIR -> "Ta'mir"
    BusinessOperation.TYPE_TAX -> "Soliq"
    BusinessOperation.TYPE_COMMISSION -> "Komissiya"
    BusinessOperation.TYPE_OTHER_INCOME -> "Boshqa daromad"
    BusinessOperation.TYPE_OTHER_EXPENSE -> "Boshqa xarajat"
    BusinessOperation.TYPE_PENALTY_ACCRUAL -> "Jarima hisoblandi"
    BusinessOperation.TYPE_PENALTY_PAYMENT -> "Jarima to'lovi"
    BusinessOperation.TYPE_DEPOSIT_RECEIVED -> "Zalog kiritildi"
    BusinessOperation.TYPE_DEPOSIT_REFUNDED -> "Zalog qaytarildi"
    BusinessOperation.TYPE_DISCOUNT -> "Chegirma"
    BusinessOperation.TYPE_DEBT_FORGIVEN -> "Qarz kechirildi"
    BusinessOperation.TYPE_REFUND -> "Qaytarish"
    BusinessOperation.TYPE_ADJUSTMENT -> "Tuzatish"
    BusinessOperation.TYPE_REVERSAL -> "Storno"
    else -> type
}

private fun directionLabel(direction: String): String = when (direction) {
    BusinessOperation.DIRECTION_INCOME -> "Daromad (kirim)"
    BusinessOperation.DIRECTION_EXPENSE -> "Xarajat (chiqim)"
    BusinessOperation.DIRECTION_TRANSFER -> "Transfer"
    BusinessOperation.DIRECTION_LIABILITY -> "Majburiyat"
    else -> direction
}
