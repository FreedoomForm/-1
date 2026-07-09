package com.example

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.ui.ContractHistoryViewModel
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.components.FilterSidePanel
import com.example.ui.components.FilterColumn
import com.example.ui.components.SortableHeaderCell
import com.example.ui.components.NonSortableHeaderCell
import com.example.ui.components.TableSortState
import com.example.ui.components.SortState
import com.example.ui.components.UnifiedButton
import com.example.ui.components.UnifiedButtonVariant
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.SuccessButton
import com.example.ui.components.DangerButton
import com.example.ui.components.DangerOutlinedButton
import com.example.ui.components.TextActionButton
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ЭКРАН ИСТОРИИ КОНТРАКТОВ АРЕНДАТОРА
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RenterContractHistoryScreen(
    renter: Renter,
    onBack: () -> Unit,
    onEditRenter: () -> Unit,
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    renterViewModel: com.example.ui.RenterViewModel = viewModel()
) {
    // Только контракты (CREATED + AUTO_RENEW) — отсортированы ASC по weekStart.
    // PAYMENT/TERMINATED/RETURNED — транзакции, не показываются на этом экране.
    val contracts by contractHistoryViewModel.contractsForRenter(renter.id).collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedContracts by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingContract by remember { mutableStateOf<ContractHistoryEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var generatingPdfFor by remember { mutableStateOf<Int?>(null) }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    val filteredContracts = contracts.filter { c ->
        val periodStr = buildString {
            c.weekStart?.let { append(dateFmt.format(Date(it))) }
            if (c.weekEnd != null) append(" → ")
            c.weekEnd?.let { append(dateFmt.format(Date(it))) }
        }
        val textMatch = periodStr.contains(searchQuery, ignoreCase = true) ||
            c.notes?.contains(searchQuery, ignoreCase = true) == true ||
            c.id.toString().contains(searchQuery) ||
            (c.scooterName?.contains(searchQuery, ignoreCase = true) == true)
        textMatch
    }

    Scaffold(
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            renter.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "${renter.phoneNumber}  •  Balans: ${renter.balance.toLong()} UZS",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClaudeTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    IconButton(onClick = onEditRenter) {
                        Icon(Icons.Default.Edit, contentDescription = "Mijozni tahrirlash")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClaudeCard,
                    titleContentColor = ClaudeText,
                    navigationIconContentColor = ClaudeText,
                    actionIconContentColor = ClaudeAccent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ── Сводка по арендатору ────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ClaudeAccentBg),
                border = BorderStroke(1.dp, ClaudeAccentMuted)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryColumn("Skuter", renter.scooterName ?: "—")
                    SummaryColumn("Muddat", "${renter.rentDurationDays} kun")
                    SummaryColumn(
                        "Boshlanish",
                        dateFmt.format(Date(renter.rentStartDateTimestamp))
                    )
                    SummaryColumn(
                        "Balans",
                        "${renter.balance.toLong()} UZS",
                        valueColor = when {
                            renter.balance < 0 -> StatusOverdue
                            renter.balance > 0 -> StatusOk
                            else -> ClaudeText
                        }
                    )
                }
            }

            // ── Unified search ───────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Kontrakt qidirish...",
                onCalendarClick = null,
                onFilterClick = null
            )

            // ── Панель действий с выбранными ────────────────────────────
            if (selectedContracts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedContracts.size} ta tanlandi",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClaudeText
                    )
                    DangerButton(
                        label = "O'chir",
                        icon = Icons.Default.Delete,
                        onClick = { showDeleteConfirm = true }
                    )
                }
            }

            // ── Заголовок таблицы — только иконки ──────────────────────
            Surface(
                color = ClaudeCard,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NonSortableHeaderCell(Icons.Default.Numbers,   0.4f, "#")
                    NonSortableHeaderCell(Icons.Default.DateRange, 1.6f, "Muddat (hafta)")
                    NonSortableHeaderCell(Icons.Default.Payments,  0.9f, "Summa")
                    NonSortableHeaderCell(Icons.Default.Build,     0.7f, "Amal")
                }
            }
            HorizontalDivider(color = ClaudeDivider)

            // ── Список контрактов ──────────────────────────────────────
            // Каждый контракт обведён цветной рамкой:
            //   зелёной  — оплачен (isPaid = true)
            //   красной  — долг (isPaid = false)
            // Так же, как цветная линия статуса у арендатора в таблице.
            if (filteredContracts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Kontraktlar yo'q",
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredContracts, key = { it.id }) { entry ->
                        val isSelected = entry.id in selectedContracts
                        val statusColor = if (entry.isPaid) StatusOk else StatusOverdue
                        val statusLabel = if (entry.isPaid) "To'langan" else "To'lanmagan"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) ClaudeAccentBg else ClaudeCard)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelected) {
                                            selectedContracts = selectedContracts - entry.id
                                        } else {
                                            editingContract = entry
                                        }
                                    },
                                    onLongClick = {
                                        selectedContracts = if (isSelected) selectedContracts - entry.id
                                        else selectedContracts + entry.id
                                    }
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.5.dp,
                                    color = statusColor,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // #
                            Text(
                                "#${entry.id}",
                                modifier = Modifier.weight(0.4f),
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                            // Muddat (weekStart → weekEnd) + статус-метка
                            Column(modifier = Modifier.weight(1.6f)) {
                                Text(
                                    text = buildString {
                                        entry.weekStart?.let { append(dateFmt.format(Date(it))) }
                                        if (entry.weekEnd != null) append(" → ")
                                        entry.weekEnd?.let { append(dateFmt.format(Date(it))) }
                                    }.ifEmpty { "—" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ClaudeText,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(statusColor, CircleShape)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        statusLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    if (!entry.notes.isNullOrBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "• ${entry.notes}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ClaudeTextSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            // Summa
                            Text(
                                "${entry.amount.toLong()}",
                                modifier = Modifier.weight(0.9f),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.isPaid) StatusOk else StatusOverdue,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                            // Amal: PDF download
                            Row(
                                modifier = Modifier.weight(0.7f),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (generatingPdfFor == entry.id) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = ClaudeAccent
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            generatingPdfFor = entry.id
                                            scope.launch {
                                                val uri = contractHistoryViewModel.generateContractPdf(entry.id)
                                                generatingPdfFor = null
                                                if (uri != null) {
                                                    val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "application/pdf")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "PDF-ni ko'rish"))
                                                    Toast.makeText(context,
                                                        "PDF saqlandi: Documents/ScooterContracts/",
                                                        Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context,
                                                        "PDF yaratib bo'lmadi",
                                                        Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PictureAsPdf,
                                            contentDescription = "PDF yuklab olish",
                                            tint = StatusOverdue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Диалог редактирования контракта ─────────────────────────────────
    editingContract?.let { entry ->
        EditContractDialog(
            entry = entry,
            onDismiss = { editingContract = null },
            onSave = { updated ->
                contractHistoryViewModel.updateContract(updated)
                editingContract = null
                Toast.makeText(context, "Kontrakt yangilandi", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                contractHistoryViewModel.deleteContract(entry.id)
                editingContract = null
                Toast.makeText(context, "Kontrakt o'chirildi", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Kontraktlarni o'chirish") },
            text = { Text("${selectedContracts.size} ta kontraktni o'chirmoqchimisz? Bu amalni qaytarib bo'lmaydi.") },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        contractHistoryViewModel.deleteContracts(selectedContracts.toList())
                        Toast.makeText(context, "${selectedContracts.size} ta o'chirildi", Toast.LENGTH_SHORT).show()
                        selectedContracts = emptySet()
                        showDeleteConfirm = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = { showDeleteConfirm = false }
                )
            }
        )
    }
}

@Composable
private fun SummaryColumn(label: String, value: String, valueColor: Color = ClaudeText) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EditContractDialog(
    entry: ContractHistoryEntry,
    onDismiss: () -> Unit,
    onSave: (ContractHistoryEntry) -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    var amount by remember { mutableStateOf(entry.amount.toString()) }
    var notes by remember { mutableStateOf(entry.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Kontrakt #${entry.id}", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Метаданные (read-only)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ClaudeBackground),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoRow("Sana", dateFmt.format(Date(entry.timestamp)))
                        InfoRow("Tur", contractTypeLabel(entry.type))
                        InfoRow("Mijoz", entry.renterName)
                        InfoRow("Skuter", entry.scooterName ?: "—")
                        entry.weekStart?.let {
                            InfoRow("Boshlanish", dateFmt.format(Date(it)))
                        }
                        entry.weekEnd?.let {
                            InfoRow("Tugash", dateFmt.format(Date(it)))
                        }
                    }
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                onClick = {
                    val newAmount = amount.toDoubleOrNull() ?: entry.amount
                    onSave(entry.copy(amount = newAmount, notes = notes.ifBlank { null }))
                }
            )
        },
        dismissButton = {
            Row {
                TextActionButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = onDelete
                )
                Spacer(Modifier.width(8.dp))
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = onDismiss
                )
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ClaudeTextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = ClaudeText, fontWeight = FontWeight.SemiBold)
    }
}

/* ============================================================================
   ЭКРАН ИСТОРИИ КОНТРАКТОВ СКУТЕРА
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScooterContractHistoryScreen(
    scooter: Scooter,
    renters: List<Renter>,
    onBack: () -> Unit,
    onEditScooter: () -> Unit,
    contractHistoryViewModel: ContractHistoryViewModel = viewModel()
) {
    val contracts by contractHistoryViewModel.forScooter(scooter.name).collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var generatingPdfFor by remember { mutableStateOf<Int?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    // Кто сейчас арендует этот скутер
    val activeRenter = renters.firstOrNull { it.scooterId == scooter.id && !it.isReturned }
    val pastRenters = renters.filter { it.scooterId == scooter.id && it.isReturned }

    val filteredContracts = contracts.filter { c ->
        c.notes?.contains(searchQuery, ignoreCase = true) == true ||
        c.renterName.contains(searchQuery, ignoreCase = true) ||
        c.type.contains(searchQuery, ignoreCase = true) ||
        (c.scooterName?.contains(searchQuery, ignoreCase = true) == true)
    }

    Scaffold(
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            scooter.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Holat: ${if (activeRenter != null) "Ijarada — ${activeRenter.name}" else "Bazada"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeRenter != null) StatusOverdue else StatusOk
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                },
                actions = {
                    IconButton(onClick = onEditScooter) {
                        Icon(Icons.Default.Edit, contentDescription = "Skuterni tahrirlash", tint = ClaudeAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClaudeCard,
                    titleContentColor = ClaudeText,
                    navigationIconContentColor = ClaudeText
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ── Сводка по скутеру ───────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ClaudeCard),
                border = BorderStroke(1.dp, ClaudeDivider)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (scooter.documentedNumber.isNullOrBlank().not()) {
                        InfoRow("Hujjat raqami", scooter.documentedNumber!!)
                    }
                    InfoRow("Jami ijarachilar", "${renters.count { it.scooterId == scooter.id }}")
                    InfoRow("Jami kontraktlar", "${contracts.size}")
                    InfoRow(
                        "Umumiy daromad",
                        "${contracts.filter { it.type == ContractHistoryEntry.TYPE_PAYMENT }.sumOf { it.amount }.toLong()} UZS"
                    )
                }
            }

            // ── Активный арендатор ─────────────────────────────────────
            if (activeRenter != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusOverdueBg),
                    border = BorderStroke(1.dp, StatusOverdue.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = StatusOverdue)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hozirgi ijarachi", style = MaterialTheme.typography.labelSmall, color = ClaudeTextSecondary)
                            Text(activeRenter.name, style = MaterialTheme.typography.titleSmall,
                                color = ClaudeText, fontWeight = FontWeight.Bold)
                            Text(
                                "Tel: ${activeRenter.phoneNumber}  •  ${dateFmt.format(Date(activeRenter.rentStartDateTimestamp))}",
                                style = MaterialTheme.typography.bodySmall, color = ClaudeTextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Unified search bar ────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Kontrakt qidirish...",
                onCalendarClick = null,
                onFilterClick = null
            )

            Text(
                "Kontraktlar tarixi",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                color = ClaudeText,
                fontWeight = FontWeight.Bold
            )

            // ── Список контрактов ──────────────────────────────────────
            if (filteredContracts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Bu skuter uchun kontraktlar yo'q",
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(filteredContracts, key = { it.id }) { entry ->
                        val typeColor = contractTypeColor(entry.type)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = ClaudeCard),
                            border = BorderStroke(1.dp, ClaudeDivider)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(typeColor, CircleShape)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            contractTypeLabel(entry.type),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = typeColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            dateTimeFmt.format(Date(entry.timestamp)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ClaudeTextSecondary
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${entry.renterName}  •  ${entry.scooterName ?: "—"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ClaudeText,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (!entry.notes.isNullOrBlank()) {
                                        Text(
                                            entry.notes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClaudeTextSecondary
                                        )
                                    }
                                    entry.weekStart?.let { ws ->
                                        entry.weekEnd?.let { we ->
                                            Text(
                                                "${dateFmt.format(Date(ws))} → ${dateFmt.format(Date(we))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ClaudeTextSecondary
                                            )
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${entry.amount.toLong()} UZS",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (entry.type == ContractHistoryEntry.TYPE_PAYMENT) StatusOk else ClaudeText,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (generatingPdfFor == entry.id) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp).padding(top = 4.dp),
                                            strokeWidth = 2.dp,
                                            color = ClaudeAccent
                                        )
                                    } else {
                                        UnifiedButton(
                                            label = "PDF",
                                            icon = Icons.Default.PictureAsPdf,
                                            variant = UnifiedButtonVariant.DANGER_OUTLINED,
                                            height = 32,
                                            onClick = {
                                                generatingPdfFor = entry.id
                                                scope.launch {
                                                    val uri = contractHistoryViewModel.generateContractPdf(entry.id)
                                                    generatingPdfFor = null
                                                    if (uri != null) {
                                                        val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(uri, "application/pdf")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        context.startActivity(Intent.createChooser(shareIntent, "PDF-ni ko'rish"))
                                                        Toast.makeText(context,
                                                            "PDF saqlandi: Documents/ScooterContracts/",
                                                            Toast.LENGTH_LONG).show()
                                                    } else {
                                                        Toast.makeText(context,
                                                            "PDF yaratib bo'lmadi",
                                                            Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

internal fun contractTypeLabel(t: String): String = when (t) {
    ContractHistoryEntry.TYPE_CREATED    -> "Yaratildi"
    ContractHistoryEntry.TYPE_PAYMENT    -> "To'lov"
    ContractHistoryEntry.TYPE_AUTO_RENEW -> "Avto-yangilash"
    ContractHistoryEntry.TYPE_TERMINATED -> "Tugatildi"
    ContractHistoryEntry.TYPE_RETURNED   -> "Qaytarildi"
    else -> t
}

internal fun contractTypeColor(t: String): Color = when (t) {
    ContractHistoryEntry.TYPE_CREATED    -> StatusInfo
    ContractHistoryEntry.TYPE_PAYMENT    -> StatusOk
    ContractHistoryEntry.TYPE_AUTO_RENEW -> ClaudeAccent
    ContractHistoryEntry.TYPE_TERMINATED -> StatusOverdue
    ContractHistoryEntry.TYPE_RETURNED   -> StatusReturned
    else -> ClaudeTextSecondary
}
