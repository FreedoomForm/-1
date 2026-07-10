package com.example

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.Transaction
import com.example.ui.ContractHistoryViewModel
import com.example.ui.RenterViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.TransactionViewModel
import com.example.ui.components.FilterColumn
import com.example.ui.components.FilterSidePanel
import com.example.ui.components.NonSortableHeaderCellFixed
import com.example.ui.components.SortState
import com.example.ui.components.TableSortState
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.DangerButton
import com.example.ui.components.TextActionButton
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ОСНОВНАЯ СТРАНИЦА «TRANZAKSIYA»
   ============================================================================
   Доступна из нижней навигации. Показывает ВСЕ транзакции из таблицы
   [Transaction] — отдельной от contract_history.

   Каждая транзакция может быть связана с контрактом (contractId) — при
   создании пользователь выбирает контракт из выпадающего списка, и поля
   renter/scooter/contractLabel автозаполняются. Но транзакция может быть
   и самостоятельной (contractId = null).

   Структура повторяет Kontraktlar / Arendators:
     • Unified search bar с календарём и фильтром
     • FilterSidePanel с long-press toggle столбцов
     • Кнопки действий всегда видны (Task 3)
     • Цветная рамка строки: зелёная = приход (PAYMENT/RETURNED),
       красная = расход (TERMINATED/PENALTY/REPAIR), серая = CUSTOM
     • Создание/редактирование/удаление транзакций через диалоги
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(
    transactionViewModel: TransactionViewModel = viewModel(),
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    // Триггер извне: когда MainActivity увеличивает это значение (нажатие «+»
    // в верхней панели), экран открывает диалог создания транзакции. Заменяет
    // внутренний FAB, который раньше был здесь.
    createTrigger: Int = 0
) {
    val transactions by transactionViewModel.transactions.collectAsStateWithLifecycle()
    val allRenters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val allScooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val allHistory by contractHistoryViewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTxs by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // ── Реакция на внешний триггер создания транзакции ─────────────────
    // Каждый раз, когда createTrigger увеличивается, открываем диалог.
    // Пропускаем начальное значение 0.
    LaunchedEffect(createTrigger) {
        if (createTrigger > 0) showCreateDialog = true
    }

    // Все контракты (CREATED + AUTO_RENEW) — для выпадающего списка в диалоге
    val allContracts = remember(allHistory) {
        allHistory
            .filter { it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
            .sortedByDescending { it.weekStart ?: it.timestamp }
    }

    // ── Filter panel + column visibility ─────────────────────────────────
    var showFilterPanel by remember { mutableStateOf(false) }
    var filterValues by remember { mutableStateOf(mapOf<String, String>()) }
    var columnVisibility by remember { mutableStateOf(mapOf<String, Boolean>()) }

    // Calendar (DateRange) — фильтр по timestamp
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val filterColumns = remember {
        listOf(
            FilterColumn("col_id",       "#",              "ID"),
            FilterColumn("col_date",     "Sana",           "dd.MM.yyyy"),
            FilterColumn("col_renter",   "Mijoz",          "Ism bo'yicha"),
            FilterColumn("col_phone",    "Telefon",        "+998..."),
            FilterColumn("col_scooter",  "Skuter",         "Skuter nomi"),
            FilterColumn("col_contract", "Kontrakt",       "#ID  davr"),
            FilterColumn("col_type",     "Tur",            "To'lov / Jarima / ..."),
            FilterColumn("col_amount",   "Summa",          "summa")
        )
    }

    // ── Фильтрация ───────────────────────────────────────────────────────
    val filteredTxs = remember(transactions, searchQuery, filterValues, dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis) {
        transactions.filter { t ->
            val textMatch = searchQuery.isBlank() ||
                t.renterName.contains(searchQuery, ignoreCase = true) ||
                t.renterPhone.contains(searchQuery) ||
                (t.scooterName.contains(searchQuery, ignoreCase = true)) ||
                (t.contractLabel.contains(searchQuery, ignoreCase = true)) ||
                (t.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                t.id.toString().contains(searchQuery)

            val startMillis = dateRangePickerState.selectedStartDateMillis
            val endMillis = dateRangePickerState.selectedEndDateMillis
            val dateMatch = if (startMillis != null) {
                if (endMillis != null) t.timestamp in startMillis..endMillis
                else t.timestamp >= startMillis
            } else true

            val filterMatch = filterValues.all { (colId, filterText) ->
                if (filterText.isBlank()) true
                else when (colId) {
                    "col_id"       -> t.id.toString().contains(filterText, ignoreCase = true)
                    "col_date"     -> dateTimeFmt.format(Date(t.timestamp)).contains(filterText, ignoreCase = true)
                    "col_renter"   -> t.renterName.contains(filterText, ignoreCase = true)
                    "col_phone"    -> t.renterPhone.contains(filterText, ignoreCase = true)
                    "col_scooter"  -> t.scooterName.contains(filterText, ignoreCase = true)
                    "col_contract" -> t.contractLabel.contains(filterText, ignoreCase = true)
                    "col_type"     -> TransactionViewModel.typeLabel(t.type).contains(filterText, ignoreCase = true)
                    "col_amount"   -> t.amount.toLong().toString().contains(filterText, ignoreCase = true)
                    else -> true
                }
            }
            textMatch && dateMatch && filterMatch
        }
    }

    // ── Видимость столбцов ───────────────────────────────────────────────
    fun isColVisible(colId: String): Boolean = columnVisibility[colId] ?: true
    val showId       = isColVisible("col_id")
    val showDate     = isColVisible("col_date")
    val showRenter   = isColVisible("col_renter")
    val showPhone    = isColVisible("col_phone")
    val showScooter  = isColVisible("col_scooter")
    val showContract = isColVisible("col_contract")
    val showType     = isColVisible("col_type")
    val showAmount   = isColVisible("col_amount")

    val hScrollState = rememberScrollState()

    // Ширины fixed-колонок
    val wId       = 50.dp
    val wDate     = 110.dp
    val wRenter   = 110.dp
    val wPhone    = 95.dp
    val wScooter  = 80.dp
    val wContract = 140.dp
    val wType     = 90.dp
    val wAmount   = 85.dp

    // ── Содержимое страницы (без собственного Scaffold — вкладка живёт
    // внутри единого Scaffold в MainActivity, чтобы поисковая строка всех
    // 4 вкладок находилась на одной вертикали) ─────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
            // ── Unified search bar ──────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Mijoz, skuter, kontrakt qidirish",
                onCalendarClick = { showDateRangePicker = true },
                calendarActive = dateRangePickerState.selectedStartDateMillis != null,
                onFilterClick = { showFilterPanel = true },
                filterActive = filterValues.any { it.value.isNotBlank() }
            )

            FilterSidePanel(
                columns = filterColumns,
                filterValues = filterValues,
                onFilterChange = { colId, value ->
                    filterValues = filterValues.toMutableMap().apply { put(colId, value) }
                },
                onSearch = { /* applied reactively */ },
                onReset = { filterValues = emptyMap() },
                onDismiss = { showFilterPanel = false },
                visible = showFilterPanel,
                columnVisibility = columnVisibility,
                onColumnVisibilityChange = { colId, isVisible ->
                    columnVisibility = columnVisibility.toMutableMap().apply { put(colId, isVisible) }
                }
            )

            // ── Панель действий — ВСЕГДА ВИДНА ──────────────────────────
            // Кнопка "Yaratish" убрана — её заменяет "+" в верхней панели.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryButton(
                    label = "Tahrirlash",
                    icon = Icons.Default.Edit,
                    enabled = selectedTxs.size == 1,
                    onClick = {
                        val id = selectedTxs.first()
                        editingTx = transactions.firstOrNull { it.id == id }
                    }
                )
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    enabled = selectedTxs.isNotEmpty(),
                    onClick = { showDeleteConfirm = true }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Jami: ${filteredTxs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
            }

            // ── Заголовок таблицы ───────────────────────────────────────
            Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showId)       NonSortableHeaderCellFixed(Icons.Default.Numbers,              wId,       "#")
                    if (showDate)     NonSortableHeaderCellFixed(Icons.Default.DateRange,            wDate,     "Sana")
                    if (showRenter)   NonSortableHeaderCellFixed(Icons.Default.Person,               wRenter,   "Mijoz")
                    if (showPhone)    NonSortableHeaderCellFixed(Icons.Default.Phone,                wPhone,    "Telefon")
                    if (showScooter)  NonSortableHeaderCellFixed(Icons.Default.DirectionsBike,       wScooter,  "Skuter")
                    if (showContract) NonSortableHeaderCellFixed(Icons.Default.Description,          wContract, "Kontrakt")
                    if (showType)     NonSortableHeaderCellFixed(Icons.Default.Category,             wType,     "Tur")
                    if (showAmount)   NonSortableHeaderCellFixed(Icons.Default.AccountBalanceWallet, wAmount,   "Summa")
                }
            }
            HorizontalDivider(color = ClaudeDivider)

            // ── Список транзакций ──────────────────────────────────────
            if (filteredTxs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Tranzaksiyalar yo'q",
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredTxs, key = { it.id }) { tx ->
                        val isSelected = tx.id in selectedTxs
                        val isPositive = TransactionViewModel.typeIsPositive(tx.type)
                        val statusColor = when (tx.type) {
                            Transaction.TYPE_CUSTOM -> ClaudeTextSecondary
                            else -> if (isPositive) StatusOk else StatusOverdue
                        }
                        val typeLabel = TransactionViewModel.typeLabel(tx.type)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(hScrollState)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.5.dp,
                                        color = statusColor,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .background(if (isSelected) ClaudeAccentBg else ClaudeCard)
                                    .combinedClickable(
                                        onClick = {
                                            // Один клик = выбрать/снять выделение.
                                            // Диалог редактирования открывается ТОЛЬКО
                                            // кнопкой "Tahrirlash" в панели действий.
                                            selectedTxs = if (isSelected) selectedTxs - tx.id
                                            else selectedTxs + tx.id
                                        },
                                        onLongClick = {
                                            selectedTxs = if (isSelected) selectedTxs - tx.id
                                            else selectedTxs + tx.id
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showId) {
                                    Text(
                                        "#${tx.id}",
                                        modifier = Modifier.width(wId).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary,
                                        maxLines = 1
                                    )
                                }
                                if (showDate) {
                                    Text(
                                        dateTimeFmt.format(Date(tx.timestamp)),
                                        modifier = Modifier.width(wDate).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeText,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                if (showRenter) {
                                    Text(
                                        tx.renterName.ifBlank { "—" },
                                        modifier = Modifier.width(wRenter).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ClaudeText,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                if (showPhone) {
                                    Text(
                                        tx.renterPhone.ifBlank { "—" },
                                        modifier = Modifier.width(wPhone).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeTextSecondary,
                                        maxLines = 1
                                    )
                                }
                                if (showScooter) {
                                    Text(
                                        tx.scooterName.ifBlank { "—" },
                                        modifier = Modifier.width(wScooter).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                if (showContract) {
                                    Text(
                                        tx.contractLabel.ifBlank { "—" },
                                        modifier = Modifier.width(wContract).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary,
                                        maxLines = 1
                                    )
                                }
                                if (showType) {
                                    Row(
                                        modifier = Modifier.width(wType).padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(statusColor, CircleShape)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            typeLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = statusColor,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                                if (showAmount) {
                                    val sign = if (isPositive) "+" else "−"
                                    Text(
                                        "$sign ${tx.amount.toLong()}",
                                        modifier = Modifier.width(wAmount).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    // ── Диалог редактирования ────────────────────────────────────────────
    editingTx?.let { tx ->
        EditTransactionDialog(
            tx = tx,
            allContracts = allContracts,
            allRenters = allRenters,
            allScooters = allScooters,
            onDismiss = { editingTx = null },
            onSave = { updated ->
                transactionViewModel.updateTransaction(updated)
                editingTx = null
                Toast.makeText(context, "Tranzaksiya yangilandi", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                transactionViewModel.deleteTransaction(tx.id)
                editingTx = null
                Toast.makeText(context, "Tranzaksiya o'chirildi", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── Подтверждение удаления нескольких ────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Tranzaksiyalarni o'chirish") },
            text = { Text("${selectedTxs.size} ta tranzaksiyani o'chirmoqchimisz? Bu amalni qaytarib bo'lmaydi.") },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        transactionViewModel.deleteTransactions(selectedTxs.toList())
                        Toast.makeText(context, "${selectedTxs.size} ta o'chirildi", Toast.LENGTH_SHORT).show()
                        selectedTxs = emptySet()
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

    // ── Диалог создания транзакции ───────────────────────────────────────
    if (showCreateDialog) {
        CreateTransactionDialog(
            allContracts = allContracts,
            allRenters = allRenters,
            allScooters = allScooters,
            onDismiss = { showCreateDialog = false },
            onCreate = { contractId, renterId, rName, rPhone, sId, sName, cLabel, type, amount, timestamp, noteText ->
                transactionViewModel.createTransaction(
                    contractId = contractId,
                    renterId = renterId,
                    renterName = rName,
                    renterPhone = rPhone,
                    scooterId = sId,
                    scooterName = sName,
                    contractLabel = cLabel,
                    type = type,
                    amount = amount,
                    timestamp = timestamp,
                    notes = noteText
                )
                Toast.makeText(context, "Tranzaksiya yaratildi", Toast.LENGTH_SHORT).show()
                showCreateDialog = false
            }
        )
    }

    // ── Календарь-фильтр ─────────────────────────────────────────────────
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
   ДИАЛОГ СОЗДАНИЯ ТРАНЗАКЦИИ
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTransactionDialog(
    allContracts: List<ContractHistoryEntry>,
    allRenters: List<Renter>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onCreate: (
        contractId: Int?, renterId: Int,
        renterName: String, renterPhone: String,
        scooterId: Int?, scooterName: String, contractLabel: String,
        type: String, amount: Double, timestamp: Long, notes: String?
    ) -> Unit
) {
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000
    val initialTimestamp = (System.currentTimeMillis() / (15 * 60 * 1000)) * (15 * 60 * 1000)  // округление до 15 минут

    var contractId by remember { mutableStateOf<Int?>(null) }
    var renterId by remember { mutableStateOf(0) }
    var renterName by remember { mutableStateOf("") }
    var renterPhone by remember { mutableStateOf("") }
    var scooterId by remember { mutableStateOf<Int?>(null) }
    var scooterName by remember { mutableStateOf("") }
    var contractLabel by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(Transaction.TYPE_PAYMENT) }
    var amount by remember { mutableStateOf("") }
    var timestamp by remember { mutableStateOf(initialTimestamp) }
    var notes by remember { mutableStateOf("") }

    var expandedContract by remember { mutableStateOf(false) }
    var expandedRenter by remember { mutableStateOf(false) }
    var expandedScooter by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)

    val scrollState = rememberScrollState()

    val typeOptions = remember {
        listOf(
            Transaction.TYPE_PAYMENT    to "To'lov",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED   to "Qaytarildi",
            Transaction.TYPE_PENALTY    to "Jarima",
            Transaction.TYPE_REPAIR     to "Ta'mir",
            Transaction.TYPE_CUSTOM     to "Boshqa"
        )
    }
    val selectedTypeLabel = typeOptions.find { it.first == type }?.second ?: "To'lov"

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            // Сохраняем время суток из текущего timestamp
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Yangi tranzaksiya", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Контракт (опционально) ────────────────────────────────
                Text("Kontrakt (ixtiyoriy)", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)
                Text(
                    "Agar kontrakt tanlansa, mijoz va skuter avtomatik to'ldiriladi.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )

                ExposedDropdownMenuBox(
                    expanded = expandedContract,
                    onExpandedChange = { expandedContract = !expandedContract }
                ) {
                    OutlinedTextField(
                        value = contractLabel.ifBlank { "Tanlanmagan" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kontrakt") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedContract)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedContract,
                        onDismissRequest = { expandedContract = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan (mustaqil tranzaksiya)") },
                            onClick = {
                                contractId = null
                                contractLabel = ""
                                expandedContract = false
                            }
                        )
                        allContracts.take(50).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(TransactionViewModel.formatContractLabel(c)) },
                                onClick = {
                                    contractId = c.id
                                    contractLabel = TransactionViewModel.formatContractLabel(c)
                                    renterId = c.renterId
                                    renterName = c.renterName
                                    renterPhone = c.renterPhone
                                    scooterName = c.scooterName ?: ""
                                    // Пробуем найти скутер в БД по имени — для установки scooterId
                                    scooterId = allScooters.firstOrNull { it.name == c.scooterName }?.id
                                    expandedContract = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                // ── Mijoz ────────────────────────────────────────────────
                Text("Mijoz", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedRenter,
                    onExpandedChange = { expandedRenter = !expandedRenter }
                ) {
                    OutlinedTextField(
                        value = renterName,
                        onValueChange = { renterName = it },
                        label = { Text("Mijoz ismi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRenter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedRenter,
                        onDismissRequest = { expandedRenter = false }
                    ) {
                        allRenters.take(50).forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.name}  •  ${r.phoneNumber}") },
                                onClick = {
                                    renterId = r.id
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    if (scooterName.isBlank() && r.scooterName != null) {
                                        scooterName = r.scooterName
                                        scooterId = r.scooterId
                                    }
                                    expandedRenter = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = renterPhone,
                    onValueChange = { renterPhone = it },
                    label = { Text("Telefon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // ── Skuter (с выпадающим списком) ─────────────────────────
                ExposedDropdownMenuBox(
                    expanded = expandedScooter,
                    onExpandedChange = { expandedScooter = !expandedScooter }
                ) {
                    OutlinedTextField(
                        value = scooterName,
                        onValueChange = {
                            scooterName = it
                            // При ручном вводе сбрасываем scooterId, т.к. он
                            // может не соответствовать введённому имени.
                            scooterId = null
                        },
                        label = { Text("Skuter nomi (ixtiyoriy)") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedScooter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedScooter,
                        onDismissRequest = { expandedScooter = false }
                    ) {
                        allScooters.take(50).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    scooterId = s.id
                                    scooterName = s.name
                                    expandedScooter = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                // ── Tranzaksiya ──────────────────────────────────────────
                Text("Tranzaksiya", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                // Тип
                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTypeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tur") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        typeOptions.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    type = t
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                // Дата
                OutlinedTextField(
                    value = dateTimeFmt.format(Date(timestamp)),
                    onValueChange = {},
                    label = { Text("Sana va vaqt") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // Сумма
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // Примечание
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Yaratish",
                icon = Icons.Default.Add,
                enabled = renterName.isNotBlank() && amount.isNotBlank(),
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                    onCreate(
                        contractId, renterId, renterName, renterPhone,
                        scooterId, scooterName, contractLabel,
                        type, parsedAmount, timestamp,
                        notes.ifBlank { null }
                    )
                }
            )
        },
        dismissButton = {
            TextActionButton(
                label = "Bekor",
                icon = Icons.Default.Close,
                onClick = onDismiss
            )
        }
    )
}

/* ============================================================================
   ДИАЛОГ РЕДАКТИРОВАНИЯ ТРАНЗАКЦИИ
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTransactionDialog(
    tx: Transaction,
    allContracts: List<ContractHistoryEntry>,
    allRenters: List<Renter>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit,
    onDelete: () -> Unit
) {
    val dateTimeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000

    var contractId by remember { mutableStateOf(tx.contractId) }
    var renterId by remember { mutableStateOf(tx.renterId) }
    var renterName by remember { mutableStateOf(tx.renterName) }
    var renterPhone by remember { mutableStateOf(tx.renterPhone) }
    var scooterId by remember { mutableStateOf(tx.scooterId) }
    var scooterName by remember { mutableStateOf(tx.scooterName) }
    var contractLabel by remember { mutableStateOf(tx.contractLabel) }
    var type by remember { mutableStateOf(tx.type) }
    var amount by remember { mutableStateOf(tx.amount.toString()) }
    var timestamp by remember { mutableStateOf(tx.timestamp) }
    var notes by remember { mutableStateOf(tx.notes ?: "") }

    var expandedContract by remember { mutableStateOf(false) }
    var expandedRenter by remember { mutableStateOf(false) }
    var expandedScooter by remember { mutableStateOf(false) }
    var expandedType by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)

    val scrollState = rememberScrollState()

    val typeOptions = remember {
        listOf(
            Transaction.TYPE_PAYMENT    to "To'lov",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED   to "Qaytarildi",
            Transaction.TYPE_PENALTY    to "Jarima",
            Transaction.TYPE_REPAIR     to "Ta'mir",
            Transaction.TYPE_CUSTOM     to "Boshqa"
        )
    }
    val selectedTypeLabel = typeOptions.find { it.first == type }?.second ?: "To'lov"

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Tranzaksiya #${tx.id}", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Kontrakt", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedContract,
                    onExpandedChange = { expandedContract = !expandedContract }
                ) {
                    OutlinedTextField(
                        value = contractLabel.ifBlank { "Tanlanmagan" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kontrakt") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedContract)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedContract,
                        onDismissRequest = { expandedContract = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan (mustaqil tranzaksiya)") },
                            onClick = {
                                contractId = null
                                contractLabel = ""
                                expandedContract = false
                            }
                        )
                        allContracts.take(50).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(TransactionViewModel.formatContractLabel(c)) },
                                onClick = {
                                    contractId = c.id
                                    contractLabel = TransactionViewModel.formatContractLabel(c)
                                    renterId = c.renterId
                                    renterName = c.renterName
                                    renterPhone = c.renterPhone
                                    scooterName = c.scooterName ?: ""
                                    scooterId = allScooters.firstOrNull { it.name == c.scooterName }?.id
                                    expandedContract = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                Text("Mijoz", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedRenter,
                    onExpandedChange = { expandedRenter = !expandedRenter }
                ) {
                    OutlinedTextField(
                        value = renterName,
                        onValueChange = { renterName = it },
                        label = { Text("Mijoz ismi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRenter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedRenter,
                        onDismissRequest = { expandedRenter = false }
                    ) {
                        allRenters.take(50).forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.name}  •  ${r.phoneNumber}") },
                                onClick = {
                                    renterId = r.id
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    expandedRenter = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = renterPhone,
                    onValueChange = { renterPhone = it },
                    label = { Text("Telefon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // ── Skuter (с выпадающим списком) ─────────────────────────
                ExposedDropdownMenuBox(
                    expanded = expandedScooter,
                    onExpandedChange = { expandedScooter = !expandedScooter }
                ) {
                    OutlinedTextField(
                        value = scooterName,
                        onValueChange = {
                            scooterName = it
                            // При ручном вводе сбрасываем scooterId
                            scooterId = null
                        },
                        label = { Text("Skuter nomi") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedScooter)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedScooter,
                        onDismissRequest = { expandedScooter = false }
                    ) {
                        allScooters.take(50).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    scooterId = s.id
                                    scooterName = s.name
                                    expandedScooter = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ClaudeDivider)

                Text("Tranzaksiya", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTypeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tur") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        typeOptions.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    type = t
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = dateTimeFmt.format(Date(timestamp)),
                    onValueChange = {},
                    label = { Text("Sana va vaqt") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

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
                    label = { Text("Izoh (ixtiyoriy)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                enabled = renterName.isNotBlank(),
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                    onSave(tx.copy(
                        contractId = contractId,
                        renterId = renterId,
                        renterName = renterName,
                        renterPhone = renterPhone,
                        scooterId = scooterId,
                        scooterName = scooterName,
                        contractLabel = contractLabel,
                        type = type,
                        amount = parsedAmount,
                        timestamp = timestamp,
                        notes = notes.ifBlank { null }
                    ))
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
