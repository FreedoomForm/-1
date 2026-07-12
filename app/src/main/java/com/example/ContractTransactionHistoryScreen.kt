package com.example

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.data.Transaction
import com.example.ui.ContractHistoryViewModel
import com.example.ui.RenterViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.TransactionViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ЭКРАН ИСТОРИИ ТРАНЗАКЦИЙ КОНТРАКТА
   ----------------------------------------------------------------------------
   Шаблон — CardTransactionHistoryScreen. Структура:
     • TopAppBar с информацией о контракте (#ID, renter, period), «Назад» и «PDF»
     • Сводка (сумма, статус, кол-во транзакций, всего получено/потрачено)
     • Переключатель «Kiruvchi» (входящие) / «Chiquvchi» (исходящие)
     • Поиск + календарь + фильтр
     • Панель действий (Yaratish / Tahrirlash / O'chir)
     • Список транзакций с цветными индикаторами:
         зелёный — приход (PAYMENT, RETURNED)
         красный — расход (TERMINATED, PENALTY, REPAIR)
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractTransactionHistoryScreen(
    contract: ContractHistoryEntry,
    onBack: () -> Unit,
    onEditContract: () -> Unit,
    transactionViewModel: TransactionViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel()
) {
    val context = LocalContext.current
    val allRenters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val allScooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val transactions by transactionViewModel.transactionsForContract(contract.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // ── Toggle: 0 = Kiruvchi (входящие), 1 = Chiquvchi (исходящие) ──────
    var selectedTab by remember { mutableStateOf(0) }
    var selectedTxIds by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }

    // ── Фильтр по диапазону дат (как на странице «Arendatorlar») ────────
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()
    // ── Боковая панель фильтров по столбцам ─────────────────────────────
    var showFilterPanel by remember { mutableStateOf(false) }
    var filterValues by remember { mutableStateOf(mapOf<String, String>()) }
    val txFilterColumns = remember {
        listOf(
            FilterColumn("col_amount", "Summa", "0", KeyboardType.Number),
            FilterColumn("col_note", "Izoh", "Masalan: To'lov"),
            FilterColumn("col_date", "Sana", "dd.MM.yyyy")
        )
    }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dateFmtDay = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    // ── Разделение транзакций по ЗНАКУ суммы ─────────────────────────────
    // Любая транзакция, которая ОТНИМАЕТ деньги (отрицательная сумма) → Chiquvchi.
    // Любая транзакция, которая ПРИНОСИТ деньги (положительная сумма)  → Kiruvchi.
    // Тип транзакции больше НЕ используется для классификации — только знак суммы.
    val incoming = remember(transactions) {
        transactions.filter { it.amount >= 0.0 }
    }
    val outgoing = remember(transactions) {
        transactions.filter { it.amount < 0.0 }
    }

    val totalIncoming = incoming.sumOf { it.amount }
    val totalOutgoing = outgoing.sumOf { it.amount }

    val currentList = if (selectedTab == 0) incoming else outgoing

    val filteredTxs = remember(currentList, searchQuery, dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis, filterValues) {
        val startMillis = dateRangePickerState.selectedStartDateMillis
        val endMillis = dateRangePickerState.selectedEndDateMillis
        currentList.filter { t ->
            val textMatch = searchQuery.isBlank() ||
                (t.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                t.amount.toLong().toString().contains(searchQuery) ||
                t.id.toString().contains(searchQuery) ||
                t.renterName.contains(searchQuery, ignoreCase = true) ||
                (t.scooterName.contains(searchQuery, ignoreCase = true))
            val dateMatch = if (startMillis != null) {
                if (endMillis != null) t.timestamp in startMillis..endMillis
                else t.timestamp >= startMillis
            } else true
            val filterMatch = filterValues.all { (colId, filterText) ->
                if (filterText.isBlank()) true
                else when (colId) {
                    "col_amount" -> t.amount.toLong().toString().contains(filterText, ignoreCase = true)
                    "col_note" -> (t.notes ?: "").contains(filterText, ignoreCase = true)
                    "col_date" -> dateFmt.format(Date(t.timestamp)).contains(filterText, ignoreCase = true)
                    else -> true
                }
            }
            textMatch && dateMatch && filterMatch
        }
    }

    val periodStr = remember(contract) {
        buildString {
            contract.weekStart?.let { append(dateFmtDay.format(Date(it))) }
            if (contract.weekEnd != null) append(" → ")
            contract.weekEnd?.let { append(dateFmtDay.format(Date(it))) }
        }.ifEmpty { "—" }
    }

    val statusColor = if (contract.isPaid) StatusOk else StatusOverdue
    val statusLabel = if (contract.isPaid) "To'langan" else "To'lanmagan"

    Scaffold(
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Kontrakt #${contract.id}  •  ${contract.renterName}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            "$periodStr  •  $statusLabel",
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
                    IconButton(onClick = onEditContract) {
                        Icon(Icons.Default.Edit, contentDescription = "Kontraktni tahrirlash")
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
            // ── Сводка по контракту ──────────────────────────────────────
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
                    SummaryColumn("Kiruvchi", formatMoney(totalIncoming), StatusOk)
                    SummaryColumn("Chiquvchi", formatMoney(kotlin.math.abs(totalOutgoing)), StatusOverdue)
                    SummaryColumn("Summa", formatMoney(contract.amount), statusColor)
                    SummaryColumn("Tranzaksiya", "${incoming.size + outgoing.size}")
                }
            }

            // ── Переключатель «Kiruvchi» / «Chiquvchi» ───────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContractTabButton(
                    label = "Kiruvchi (${incoming.size})",
                    icon = Icons.Default.ArrowDownward,
                    isSelected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        selectedTxIds = emptySet()
                    }
                )
                ContractTabButton(
                    label = "Chiquvchi (${outgoing.size})",
                    icon = Icons.Default.ArrowUpward,
                    isSelected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        selectedTxIds = emptySet()
                    }
                )
            }

            // ── Поиск ───────────────────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Tranzaksiya qidirish...",
                onCalendarClick = { showDateRangePicker = true },
                calendarActive = dateRangePickerState.selectedStartDateMillis != null,
                onFilterClick = { showFilterPanel = true },
                filterActive = filterValues.any { it.value.isNotBlank() }
            )

            // ── Боковая панель фильтров ──────────────────────────────────
            FilterSidePanel(
                columns = txFilterColumns,
                filterValues = filterValues,
                onFilterChange = { colId, value ->
                    filterValues = filterValues.toMutableMap().apply { put(colId, value) }
                },
                onSearch = { /* фильтры применяются реактивно */ },
                onReset = { filterValues = emptyMap() },
                onDismiss = { showFilterPanel = false },
                visible = showFilterPanel
            )

            // ── Панель действий ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryButton(
                    label = "Yaratish",
                    icon = Icons.Default.Add,
                    onClick = { showCreateDialog = true }
                )
                SecondaryButton(
                    label = "Tahrirlash",
                    icon = Icons.Default.Edit,
                    enabled = selectedTxIds.size == 1,
                    onClick = {
                        val id = selectedTxIds.first()
                        editingTx = currentList.firstOrNull { it.id == id }
                    }
                )
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    enabled = selectedTxIds.isNotEmpty(),
                    onClick = { showDeleteConfirm = true }
                )
                Spacer(Modifier.weight(1f))
            }

            // ── Список транзакций ──────────────────────────────────────
            if (filteredTxs.isEmpty()) {
                EmptyStateBox(
                    text = if (selectedTab == 0)
                        "Kiruvchi tranzaksiyalar yo'q"
                    else
                        "Chiquvchi tranzaksiyalar yo'q"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTxs, key = { it.id }) { tx ->
                        ContractTxRow(
                            tx = tx,
                            isIncoming = selectedTab == 0,
                            isSelected = tx.id in selectedTxIds,
                            dateFmt = dateFmt,
                            onClick = {
                                selectedTxIds = if (tx.id in selectedTxIds) {
                                    selectedTxIds - tx.id
                                } else {
                                    selectedTxIds + tx.id
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Tranzaksiyalarni o'chirish") },
            text = {
                Text(
                    "${selectedTxIds.size} ta tranzaksiyani o'chirmoqchimisz? " +
                    "Bu amal faqat tarix yozuvini o'chiradi."
                )
            },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        selectedTxIds.forEach { id ->
                            transactionViewModel.deleteTransaction(id)
                        }
                        Toast.makeText(
                            context,
                            "${selectedTxIds.size} ta o'chirildi",
                            Toast.LENGTH_SHORT
                        ).show()
                        selectedTxIds = emptySet()
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

    // ── Диалог создания транзакции ─────────────────────────────────────
    // Тип и знак суммы определяются автоматически по выбранной вкладке:
    //   • Kiruvchi (0)  → TYPE_PAYMENT, положительная сумма (поступление)
    //   • Chiquvchi (1) → TYPE_CUSTOM,  отрицательная сумма (расход)
    // Пользователь не выбирает тип и не вводит знак — только сумму и заметку.
    if (showCreateDialog) {
        CreateContractTransactionDialog(
            contract = contract,
            selectedTab = selectedTab,
            onDismiss = { showCreateDialog = false },
            onCreate = { type, amount, timestamp, notes ->
                transactionViewModel.createTransaction(
                    contractId = contract.id,
                    renterId = contract.renterId,
                    renterName = contract.renterName,
                    renterPhone = contract.renterPhone,
                    scooterId = null,
                    scooterName = contract.scooterName ?: "",
                    contractLabel = TransactionViewModel.formatContractLabel(contract),
                    type = type,
                    amount = amount,
                    timestamp = timestamp,
                    notes = notes
                )
                Toast.makeText(context, "Tranzaksiya yaratildi", Toast.LENGTH_SHORT).show()
                showCreateDialog = false
            }
        )
    }

    // ── Диалог редактирования транзакции ───────────────────────────────
    editingTx?.let { tx ->
        EditContractTransactionDialog(
            tx = tx,
            onDismiss = { editingTx = null },
            onSave = { updated ->
                transactionViewModel.updateTransaction(updated)
                Toast.makeText(context, "Tranzaksiya yangilandi", Toast.LENGTH_SHORT).show()
                editingTx = null
            }
        )
    }

    // ── Диалог выбора диапазона дат ──────────────────────────────────────
    if (showDateRangePicker) {
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash",
                    icon = Icons.Default.Check,
                    onClick = { showDateRangePicker = false }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Tozalash",
                    icon = Icons.Default.Clear,
                    onClick = {
                        dateRangePickerState.setSelection(null, null)
                        showDateRangePicker = false
                    }
                )
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.weight(1f),
                title = { Text("Sana bo'yicha filter", modifier = Modifier.padding(16.dp)) },
                headline = { Text("Davrni tanlang", modifier = Modifier.padding(16.dp)) }
            )
        }
    }
}

/* ============================================================================
   ДИАЛОГ СОЗДАНИЯ ТРАНЗАКЦИИ ДЛЯ КОНТРАКТА
   ----------------------------------------------------------------------------
   Минимальный диалог: только сумма, дата и заметка. Тип и знак суммы
   определяются автоматически по выбранной вкладке (Kiruvchi → PAYMENT/+,
   Chiquvchi → CUSTOM/−). Поля renter, scooter, contractId уже предзаполнены
   из контракта. Никакого выбора типа в UI нет.
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContractTransactionDialog(
    contract: ContractHistoryEntry,
    selectedTab: Int,
    onDismiss: () -> Unit,
    onCreate: (type: String, amount: Double, timestamp: Long, notes: String?) -> Unit
) {
    val dayMs = 24L * 60 * 60 * 1000
    val initialTimestamp = (System.currentTimeMillis() / (15 * 60 * 1000)) * (15 * 60 * 1000)

    // Тип и знак авто-определяются по вкладке:
    //   Kiruvchi (0)  → TYPE_PAYMENT, положительная сумма (поступление)
    //   Chiquvchi (1) → TYPE_CUSTOM,  отрицательная сумма (расход)
    val type = if (selectedTab == 0) Transaction.TYPE_PAYMENT else Transaction.TYPE_CUSTOM

    var amountText by remember { mutableStateOf("") }
    var timestamp by remember { mutableStateOf(initialTimestamp) }
    var notes by remember { mutableStateOf("") }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)
    val scrollState = rememberScrollState()

    val amountParsed = amountText.replace(',', '.').toDoubleOrNull()
    val canSave = amountParsed != null && amountParsed > 0.0

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val timeOfDay = timestamp % dayMs
                            timestamp = it + timeOfDay
                        }
                        showDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showDatePicker = false }
                )
            }
        ) { DatePicker(state = datePickerState) }
    }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = ClaudeCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Yangi tranzaksiya", fontWeight = FontWeight.Bold, color = ClaudeText)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Информация о контракте (read-only) ─────────────────────
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ClaudeAccentBg,
                    border = BorderStroke(1.dp, ClaudeAccentMuted)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Kontrakt #${contract.id}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeText
                        )
                        Text(
                            contract.renterName,
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                        Text(
                            "Skuter: ${contract.scooterName ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                    }
                }

                // ── Сумма ──────────────────────────────────────────────────
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { s -> amountText = s.filter { it.isDigit() || it == '.' || it == ',' } },
                    label = { Text("Summa (so'm) *") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = ClaudeBackground,
                        focusedContainerColor = ClaudeBackground
                    )
                )

                // ── Дата ───────────────────────────────────────────────────
                OutlinedTextField(
                    value = dateFmt.format(Date(timestamp)),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sana") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = ClaudeBackground,
                        focusedContainerColor = ClaudeBackground
                    )
                )

                // ── Заметка ────────────────────────────────────────────────
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = ClaudeBackground,
                        focusedContainerColor = ClaudeBackground
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        // Знак суммы авто-определяется по вкладке:
                        //   Kiruvchi (0)  → положительная (поступление)
                        //   Chiquvchi (1) → отрицательная (расход)
                        val signedAmount = if (selectedTab == 0) amt else -amt
                        onCreate(type, signedAmount, timestamp, notes.ifBlank { null })
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Saqlash", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Bekor", color = ClaudeTextSecondary)
            }
        }
    )
}

/* ============================================================================
   ДИАЛОГ РЕДАКТИРОВАНИЯ ТРАНЗАКЦИИ КОНТРАКТА
   ----------------------------------------------------------------------------
   Минимальный диалог: только сумма, дата и заметка. Тип транзакции НЕ
   редактируется (сохраняется оригинальный). Знак суммы также сохраняется —
   пользователь вводит положительное число, а знак берётся из исходной
   транзакции (positive = Kiruvchi, negative = Chiquvchi).
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditContractTransactionDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit
) {
    val dayMs = 24L * 60 * 60 * 1000

    // Знак суммы сохраняется из исходной транзакции.
    val originalSign = if (tx.amount < 0) -1.0 else 1.0
    // В поле показываем абсолютное значение — пользователь видит положительное число.
    var amountText by remember { mutableStateOf(kotlin.math.abs(tx.amount).toLong().toString()) }
    var timestamp by remember { mutableStateOf(tx.timestamp) }
    var notes by remember { mutableStateOf(tx.notes ?: "") }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)
    val scrollState = rememberScrollState()

    val amountParsed = amountText.replace(',', '.').toDoubleOrNull()
    val canSave = amountParsed != null && amountParsed > 0.0

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val timeOfDay = timestamp % dayMs
                            timestamp = it + timeOfDay
                        }
                        showDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showDatePicker = false }
                )
            }
        ) { DatePicker(state = datePickerState) }
    }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = ClaudeCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Tranzaksiyani tahrirlash", fontWeight = FontWeight.Bold, color = ClaudeText)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { s -> amountText = s.filter { it.isDigit() || it == '.' || it == ',' } },
                    label = { Text("Summa (so'm) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = ClaudeBackground,
                        focusedContainerColor = ClaudeBackground
                    )
                )

                OutlinedTextField(
                    value = dateFmt.format(Date(timestamp)),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sana") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = ClaudeBackground,
                        focusedContainerColor = ClaudeBackground
                    )
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = ClaudeBackground,
                        focusedContainerColor = ClaudeBackground
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        // Знак сохраняется из оригинальной транзакции.
                        onSave(tx.copy(
                            amount = amt * originalSign,
                            timestamp = timestamp,
                            notes = notes.ifBlank { null }
                        ))
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Saqlash", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Bekor", color = ClaudeTextSecondary)
            }
        }
    )
}

/* ── Вспомогательные компоненты ───────────────────────────────────────── */

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
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun RowScope.ContractTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) ClaudeAccent else ClaudeCard,
        border = BorderStroke(
            1.dp,
            if (isSelected) ClaudeAccent else ClaudeDivider
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected) Color.White else ClaudeTextSecondary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = if (isSelected) Color.White else ClaudeText,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ContractTxRow(
    tx: Transaction,
    isIncoming: Boolean,
    isSelected: Boolean,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val accentColor = if (isIncoming) StatusOk else StatusOverdue
    val sign = if (isIncoming) "+" else "−"
    // Заголовок строки — имя арендатора (и скутер, если есть). Тип транзакции
    // больше нигде не отображается — только сумма и её знак.
    val title = buildString {
        append(tx.renterName.ifBlank { "Tranzaksiya #${tx.id}" })
        if (tx.scooterName.isNotBlank()) append("  •  ${tx.scooterName}")
    }
    // Сумма показывается по модулю — знак уже отображён индикатором/префиксом.
    val absAmount = kotlin.math.abs(tx.amount)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ClaudeAccentBg else ClaudeCard
        ),
        border = if (isSelected) BorderStroke(2.dp, ClaudeAccent)
                 else BorderStroke(1.dp, ClaudeDivider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accentColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isIncoming) Icons.Default.ArrowDownward
                                  else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeText,
                    maxLines = 1
                )
                Text(
                    text = dateFmt.format(Date(tx.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
                if (!tx.notes.isNullOrBlank()) {
                    Text(
                        text = tx.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary,
                        maxLines = 2
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$sign ${formatMoney(absAmount)}",
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.End
                )
                Text(
                    text = "so'm",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun EmptyStateBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                tint = ClaudeTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                color = ClaudeTextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

private fun formatMoney(amount: Double): String {
    val sign = if (amount < 0) "-" else ""
    val absValue = kotlin.math.abs(amount).toLong()
    val formatted = String.format(Locale.US, "%,d", absValue).replace(',', ' ')
    return "$sign$formatted"
}
