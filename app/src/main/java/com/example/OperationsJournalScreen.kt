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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.BusinessOperation
import com.example.ui.BusinessOperationViewModel
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
 * §2: Visual page for the complete operations journal with filters, detail,
 * and storno (reversal) view.
 *
 * Shows every BusinessOperation record — payments, transfers, deposits,
 * discounts, refunds, repairs, reversals. Each row has a colored dot by
 * direction (income=green, expense=red, transfer=amber, liability=grey).
 *
 * Per PLAN_UNIVERSAL_ACCOUNTING §2: 'Visual page of the complete operations
 * journal with filters, detail, and storno.'
 */
@Composable
fun OperationsJournalScreen(
    viewModel: BusinessOperationViewModel = viewModel()
) {
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

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
            (filterStartMs == null || op.occurredAt >= filterStartMs) &&
            (filterEndMs == null || op.occurredAt <= filterEndMs) &&
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

    Column(Modifier.fillMaxSize()) {
        // ── Header row with filter button ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Operatsiyalar jurnali (${filteredOps.size})",
                style = MaterialTheme.typography.titleMedium,
                color = ClaudeText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            val activeFilterCount = listOf(filterType, filterDirection,
                if (filterReversedOnly) "1" else null,
                filterStartMs?.toString(), filterEndMs?.toString(),
                if (filterSearchText.isNotBlank()) "1" else null
            ).count { it != null }
            TextButton(onClick = { showFilters = true }) {
                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Filtr")
                if (activeFilterCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text("($activeFilterCount)", color = ClaudeAccent, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (filteredOps.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = StatusArchived, modifier = Modifier.padding(bottom = 8.dp))
                Text("Operatsiyalar yo'q", style = MaterialTheme.typography.titleMedium)
                Text("Moliyaviy harakatlar shu yerda ko'rinadi.", style = MaterialTheme.typography.bodySmall, color = ClaudeTextSecondary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredOps, key = { it.id }) { op ->
                    val dotColor = when (op.direction) {
                        BusinessOperation.DIRECTION_INCOME -> StatusOk
                        BusinessOperation.DIRECTION_EXPENSE -> StatusOverdue
                        BusinessOperation.DIRECTION_TRANSFER -> StatusReserved
                        BusinessOperation.DIRECTION_LIABILITY -> StatusArchived
                        else -> ClaudeTextSecondary
                    }
                    val isReversed = op.status == BusinessOperation.STATUS_REVERSED
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedOpId = op.id
                            showDetail = true
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isReversed) StatusArchived.copy(alpha = 0.1f) else ClaudeCard
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    typeLabel(op.type) + if (isReversed) " (storno)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ClaudeText,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    formatter.format(Date(op.occurredAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTextSecondary
                                )
                                op.note?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it.take(80) + if (it.length > 80) "…" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${BusinessOperation.fromMinor(op.amountMinor).toLong()} so'm",
                                style = MaterialTheme.typography.titleSmall,
                                color = dotColor,
                                fontWeight = FontWeight.Bold
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
