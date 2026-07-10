package com.example

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.SettingsRepository
import com.example.ui.ContractHistoryViewModel
import com.example.ui.RenterViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.components.FilterColumn
import com.example.ui.components.FilterSidePanel
import com.example.ui.components.NonSortableHeaderCell
import com.example.ui.components.NonSortableHeaderCellFixed
import com.example.ui.components.SortableHeaderCell
import com.example.ui.components.SortableHeaderCellFixed
import com.example.ui.components.SortState
import com.example.ui.components.TableSortState
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.DangerButton
import com.example.ui.components.TextActionButton
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ОСНОВНАЯ СТРАНИЦА «KONTRAKTLAR»
   ============================================================================
   Доступна из нижней навигации. Показывает ВСЕ контракты (CREATED +
   AUTO_RENEW) всех арендаторов и скутеров, отсортированные по weekStart DESC.

   Дополнительные столбцы (по сравнению с RenterContractHistoryScreen):
     • Mijoz   — имя арендатора (entry.renterName)
     • Skuter  — имя скутера (entry.scooterName)

   Присутствуют:
     • Календарь (DateRangePicker) — фильтр по weekStart
     • FilterSidePanel — long-press скрывает столбцы (как на странице Arendators)
     • Кнопки действий всегда видны (Task 3: независимо от выбора)
     • Цветная рамка строки: зелёная = оплачен, красная = долг
     • Создание/редактирование/удаление контрактов через диалоги
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ContractListScreen(
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    // Триггер извне: когда MainActivity увеличивает это значение (нажатие «+»
    // в верхней панели), экран открывает диалог создания контракта. Заменяет
    // внутренний FAB, который раньше был здесь.
    createTrigger: Int = 0
) {
    val allHistory by contractHistoryViewModel.history.collectAsStateWithLifecycle()
    val allRenters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val allScooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedContracts by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingContract by remember { mutableStateOf<ContractHistoryEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var generatingPdfFor by remember { mutableStateOf<Int?>(null) }

    // ── Реакция на внешний триггер создания контракта ─────────────────
    // Каждый раз, когда createTrigger увеличивается, открываем диалог.
    // Пропускаем начальное значение 0 — иначе диалог открылся бы при первом
    // входе на вкладку.
    LaunchedEffect(createTrigger) {
        if (createTrigger > 0) showCreateDialog = true
    }

    // Только контракты (CREATED + AUTO_RENEW), отсортированы по weekStart DESC
    val allContracts = remember(allHistory) {
        allHistory
            .filter { it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
            .sortedByDescending { it.weekStart ?: it.timestamp }
    }

    // ── Filter panel + column visibility ─────────────────────────────────
    var showFilterPanel by remember { mutableStateOf(false) }
    var filterValues by remember { mutableStateOf(mapOf<String, String>()) }
    var columnVisibility by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var sortState by remember { mutableStateOf(TableSortState()) }

    // Calendar (DateRange) — фильтр по weekStart
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    val filterColumns = remember {
        listOf(
            FilterColumn("col_id",      "#",                "ID"),
            FilterColumn("col_renter",  "Mijoz",            "Ism bo'yicha"),
            FilterColumn("col_phone",   "Telefon",          "+998..."),
            FilterColumn("col_scooter", "Skuter",           "Skuter nomi"),
            FilterColumn("col_start",   "Boshlanish",       "dd.MM.yyyy"),
            FilterColumn("col_end",     "Tugash",           "dd.MM.yyyy"),
            FilterColumn("col_amount",  "Summa",            "summa"),
            FilterColumn("col_status",  "Holat",            "To'langan / To'lanmagan"),
            FilterColumn("col_passport","Pasport",          "Pasport"),
            FilterColumn("col_address", "Manzil",           "Manzil"),
            FilterColumn("col_pinfl",   "JSHSHIR",          "14 raqam")
        )
    }

    // ── Фильтрация + сортировка ──────────────────────────────────────────
    val filteredContracts = remember(allContracts, searchQuery, filterValues, dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis, sortState) {
        allContracts.filter { c ->
            // Текстовый поиск
            val textMatch = searchQuery.isBlank() ||
                c.renterName.contains(searchQuery, ignoreCase = true) ||
                c.renterPhone.contains(searchQuery) ||
                (c.scooterName?.contains(searchQuery, ignoreCase = true) == true) ||
                c.id.toString().contains(searchQuery) ||
                (c.notes?.contains(searchQuery, ignoreCase = true) == true)

            // Фильтр по диапазону дат — по weekStart
            val startMillis = dateRangePickerState.selectedStartDateMillis
            val endMillis = dateRangePickerState.selectedEndDateMillis
            val dateMatch = if (startMillis != null && c.weekStart != null) {
                if (endMillis != null) c.weekStart in startMillis..endMillis
                else c.weekStart >= startMillis
            } else true

            // Фильтры по столбцам
            val filterMatch = filterValues.all { (colId, filterText) ->
                if (filterText.isBlank()) true
                else when (colId) {
                    "col_id"       -> c.id.toString().contains(filterText, ignoreCase = true)
                    "col_renter"   -> c.renterName.contains(filterText, ignoreCase = true)
                    "col_phone"    -> c.renterPhone.contains(filterText, ignoreCase = true)
                    "col_scooter"  -> (c.scooterName ?: "").contains(filterText, ignoreCase = true)
                    "col_start"    -> c.weekStart?.let { dateFmt.format(Date(it)).contains(filterText, ignoreCase = true) } ?: false
                    "col_end"      -> c.weekEnd?.let { dateFmt.format(Date(it)).contains(filterText, ignoreCase = true) } ?: false
                    "col_amount"   -> c.amount.toLong().toString().contains(filterText, ignoreCase = true)
                    "col_status"   -> (if (c.isPaid) "To'langan" else "To'lanmagan").contains(filterText, ignoreCase = true)
                    "col_passport" -> c.passportData.contains(filterText, ignoreCase = true)
                    "col_address"  -> c.address.contains(filterText, ignoreCase = true)
                    "col_pinfl"    -> c.pinfl.contains(filterText, ignoreCase = true)
                    else -> true
                }
            }
            textMatch && dateMatch && filterMatch
        }.let { list ->
            val col = sortState.activeColumn
            val state = sortState.stateFor(col ?: "")
            if (state == SortState.NONE) {
                // Default: by weekStart DESC (most recent first)
                list.sortedByDescending { it.weekStart ?: it.timestamp }
            } else {
                val comparator = when (col) {
                    "col_id"       -> compareBy<ContractHistoryEntry> { it.id }
                    "col_renter"   -> compareBy<ContractHistoryEntry> { it.renterName.lowercase() }
                    "col_phone"    -> compareBy<ContractHistoryEntry> { it.renterPhone }
                    "col_scooter"  -> compareBy<ContractHistoryEntry> { it.scooterName ?: "" }
                    "col_start"    -> compareBy<ContractHistoryEntry> { it.weekStart ?: 0L }
                    "col_end"      -> compareBy<ContractHistoryEntry> { it.weekEnd ?: 0L }
                    "col_amount"   -> compareBy<ContractHistoryEntry> { it.amount }
                    "col_status"   -> compareBy<ContractHistoryEntry> { it.isPaid }
                    else -> compareBy<ContractHistoryEntry> { it.weekStart ?: 0L }
                }
                if (state == SortState.ASCENDING) list.sortedWith(comparator)
                else list.sortedWith(comparator.reversed())
            }
        }
    }

    // ── Видимость столбцов ───────────────────────────────────────────────
    fun isColVisible(colId: String): Boolean = columnVisibility[colId] ?: true
    val showId      = isColVisible("col_id")
    val showRenter  = isColVisible("col_renter")
    val showPhone   = isColVisible("col_phone")
    val showScooter = isColVisible("col_scooter")
    val showStart   = isColVisible("col_start")
    val showEnd     = isColVisible("col_end")
    val showAmount  = isColVisible("col_amount")
    val showStatus  = isColVisible("col_status")
    val showPassport= isColVisible("col_passport")
    val showAddress = isColVisible("col_address")
    val showPinfl   = isColVisible("col_pinfl")

    val hasAnyExtra = showPassport || showAddress || showPinfl  // kept for future use
    val hScrollState = rememberScrollState()

    // Ширины fixed-колонок (когда есть extra или слишком много колонок)
    val wId       = 50.dp
    val wRenter   = 110.dp
    val wPhone    = 95.dp
    val wScooter  = 80.dp
    val wStart    = 80.dp
    val wEnd      = 80.dp
    val wAmount   = 75.dp
    val wStatus   = 90.dp
    val wPassport = 105.dp
    val wAddress  = 130.dp
    val wPinfl    = 90.dp

    Scaffold(
        containerColor = ClaudeBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ── Unified search bar с календарём и фильтром ─────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Mijoz, skuter yoki kontrakt qidirish",
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
            // Tahrirlash активна только при выборе ровно 1 строки.
            // O'chir активна при выборе ≥1 строки.
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
                    enabled = selectedContracts.size == 1,
                    onClick = {
                        val id = selectedContracts.first()
                        editingContract = allContracts.firstOrNull { it.id == id }
                    }
                )
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    enabled = selectedContracts.isNotEmpty(),
                    onClick = { showDeleteConfirm = true }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Jami: ${filteredContracts.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
            }

            // ── Заголовок таблицы ───────────────────────────────────────
            // В этой таблице всегда много колонок (9+), поэтому всегда
            // используем fixed-width + horizontalScroll — как на странице
            // Arendators когда включены extra-колонки.
            Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showId)       SortableHeaderCellFixed(Icons.Default.Numbers,              wId,       "col_id",      sortState) { sortState = sortState.click("col_id") }
                    if (showRenter)   SortableHeaderCellFixed(Icons.Default.Person,               wRenter,   "col_renter",  sortState) { sortState = sortState.click("col_renter") }
                    if (showPhone)    NonSortableHeaderCellFixed(Icons.Default.Phone,              wPhone,    "Telefon")
                    if (showScooter)  SortableHeaderCellFixed(Icons.Default.DirectionsBike,       wScooter,  "col_scooter", sortState) { sortState = sortState.click("col_scooter") }
                    if (showStart)    SortableHeaderCellFixed(Icons.Default.CalendarToday,        wStart,    "col_start",   sortState) { sortState = sortState.click("col_start") }
                    if (showEnd)      SortableHeaderCellFixed(Icons.Default.Event,                wEnd,      "col_end",     sortState) { sortState = sortState.click("col_end") }
                    if (showAmount)   SortableHeaderCellFixed(Icons.Default.AccountBalanceWallet, wAmount,   "col_amount",  sortState) { sortState = sortState.click("col_amount") }
                    if (showStatus)   NonSortableHeaderCellFixed(Icons.Default.Check,              wStatus,   "Holat")
                    if (showPassport) NonSortableHeaderCellFixed(Icons.Default.CreditCard,         wPassport, "Pasport")
                    if (showAddress)  NonSortableHeaderCellFixed(Icons.Default.Home,               wAddress,  "Manzil")
                    if (showPinfl)    NonSortableHeaderCellFixed(Icons.Default.Fingerprint,        wPinfl,    "JSHSHIR")
                    // Колонка «PDF» удалена — PDF-договор теперь формируется
                    // только кнопкой в TopAppBar на странице истории контрактов
                    // арендатора (cheksiz muddatli — на неограниченный срок).
                }
            }
            HorizontalDivider(color = ClaudeDivider)

            // ── Список контрактов ──────────────────────────────────────
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
                                            selectedContracts = if (isSelected) selectedContracts - entry.id
                                            else selectedContracts + entry.id
                                        },
                                        onLongClick = {
                                            // Долгое нажатие — резервный способ выбора.
                                            selectedContracts = if (isSelected) selectedContracts - entry.id
                                            else selectedContracts + entry.id
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showId) {
                                    Text(
                                        "#${entry.id}",
                                        modifier = Modifier.width(wId).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary,
                                        maxLines = 1
                                    )
                                }
                                if (showRenter) {
                                    Text(
                                        entry.renterName.ifBlank { "—" },
                                        modifier = Modifier.width(wRenter).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ClaudeText,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                if (showPhone) {
                                    Text(
                                        entry.renterPhone.ifBlank { "—" },
                                        modifier = Modifier.width(wPhone).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeTextSecondary,
                                        maxLines = 1
                                    )
                                }
                                if (showScooter) {
                                    Text(
                                        entry.scooterName ?: "—",
                                        modifier = Modifier.width(wScooter).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                if (showStart) {
                                    Text(
                                        entry.weekStart?.let { dateFmt.format(Date(it)) } ?: "—",
                                        modifier = Modifier.width(wStart).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                if (showEnd) {
                                    Text(
                                        entry.weekEnd?.let { dateFmt.format(Date(it)) } ?: "—",
                                        modifier = Modifier.width(wEnd).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                if (showAmount) {
                                    Text(
                                        "${entry.amount.toLong()}",
                                        modifier = Modifier.width(wAmount).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (entry.isPaid) StatusOk else StatusOverdue,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        maxLines = 1
                                    )
                                }
                                if (showStatus) {
                                    Row(
                                        modifier = Modifier.width(wStatus).padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
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
                                    }
                                }
                                if (showPassport) {
                                    Text(
                                        entry.passportData.ifBlank { "—" },
                                        modifier = Modifier.width(wPassport).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                if (showAddress) {
                                    Text(
                                        entry.address.ifBlank { "—" },
                                        modifier = Modifier.width(wAddress).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                if (showPinfl) {
                                    Text(
                                        entry.pinfl.ifBlank { "—" },
                                        modifier = Modifier.width(wPinfl).padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText,
                                        maxLines = 1
                                    )
                                }
                                // PDF-кнопка строки удалена — PDF-договор теперь
                                // формируется только кнопкой в TopAppBar на странице
                                // истории контрактов арендатора (cheksiz muddatli).
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Диалог редактирования ────────────────────────────────────────────
    editingContract?.let { entry ->
        EditContractDialog(
            entry = entry,
            allRenters = allRenters,
            allScooters = allScooters,
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

    // ── Подтверждение удаления нескольких ────────────────────────────────
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

    // ── Диалог создания контракта ────────────────────────────────────────
    if (showCreateDialog) {
        // Используем любой арендатор по умолчанию (или пустого), чтобы
        // диалог создания открывался без привязки к конкретному арендатору.
        val defaultRenter = allRenters.firstOrNull() ?: Renter(
            id = 0,
            name = "",
            phoneNumber = "",
            rentDurationDays = 7,
            rentStartDateTimestamp = System.currentTimeMillis()
        )
        CreateContractDialogForMain(
            defaultRenter = defaultRenter,
            defaultAmount = SettingsRepository(context).weeklyPrice
                .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE },
            allRenters = allRenters,
            allScooters = allScooters,
            onDismiss = { showCreateDialog = false },
            onCreateWithOverrides = { renterId, rName, rPhone, sId, sName,
                                       passport, addr, pin,
                                       wStart, wEnd, amt, paid, noteText,
                                       vin, engine, serial, batt1, batt2, extra ->
                contractHistoryViewModel.createManualContractWithOverrides(
                    renterId = renterId,
                    renterName = rName,
                    renterPhone = rPhone,
                    scooterId = sId,
                    scooterName = sName,
                    passportData = passport,
                    address = addr,
                    pinfl = pin,
                    weekStart = wStart,
                    weekEnd = wEnd,
                    amount = amt,
                    isPaid = paid,
                    notes = noteText,
                    overrideVin = vin,
                    overrideEngine = engine,
                    overrideSerial = serial,
                    overrideBatt1 = batt1,
                    overrideBatt2 = batt2,
                    overrideExtra = extra
                )
                Toast.makeText(context, "Kontrakt yaratildi", Toast.LENGTH_SHORT).show()
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
                title = { Text("Boshlanish sanasi bo'yicha filter", modifier = Modifier.padding(16.dp)) },
                headline = { Text("Davrni tanlang", modifier = Modifier.padding(16.dp)) }
            )
        }
    }
}

/**
 * Диалог создания контракта для главной страницы «Kontraktlar».
 * Похож на CreateContractDialog, но не требует предзаполнения из конкретного
 * арендатора — пользователь выбирает арендатора из выпадающего списка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContractDialogForMain(
    defaultRenter: Renter,
    defaultAmount: Double,
    allRenters: List<Renter>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onCreateWithOverrides: (
        renterId: Int, renterName: String, renterPhone: String,
        scooterId: Int?, scooterName: String,
        passportData: String, address: String, pinfl: String,
        weekStart: Long, weekEnd: Long, amount: Double, isPaid: Boolean,
        notes: String?,
        vinNumber: String, engineNumber: String, scooterSerialNumber: String,
        batteryId1: String, batteryId2: String, additionalInfo: String
    ) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000

    val initialStart = (now / dayMs) * dayMs
    var weekStart by remember { mutableStateOf(initialStart) }
    var weekEnd by remember { mutableStateOf(initialStart + 7 * dayMs) }
    var amount by remember { mutableStateOf(defaultAmount.toLong().toString()) }
    var isPaid by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    var selectedRenterId by remember { mutableStateOf(defaultRenter.id) }
    var renterName by remember { mutableStateOf(defaultRenter.name) }
    var renterPhone by remember { mutableStateOf(defaultRenter.phoneNumber) }
    var passportData by remember { mutableStateOf(defaultRenter.passportData) }
    var address by remember { mutableStateOf(defaultRenter.address) }
    var pinfl by remember { mutableStateOf(defaultRenter.pinfl) }

    var selectedScooterId by remember { mutableStateOf(defaultRenter.scooterId) }
    var scooterName by remember { mutableStateOf(defaultRenter.scooterName ?: "") }
    var vinNumber by remember { mutableStateOf("") }
    var engineNumber by remember { mutableStateOf("") }
    var scooterSerialNumber by remember { mutableStateOf("") }
    var batteryId1 by remember { mutableStateOf("") }
    var batteryId2 by remember { mutableStateOf("") }
    var additionalInfo by remember { mutableStateOf("") }

    var expandedRenter by remember { mutableStateOf(false) }
    var expandedScooter by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val startPickerState = rememberDatePickerState(initialSelectedDateMillis = weekStart)
    val endPickerState = rememberDatePickerState(initialSelectedDateMillis = weekEnd)

    if (showStartPicker) {
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        startPickerState.selectedDateMillis?.let {
                            weekStart = it
                            if (weekEnd <= weekStart) weekEnd = weekStart + 7 * dayMs
                        }
                        showStartPicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showStartPicker = false }
                )
            }
        ) { DatePicker(state = startPickerState) }
    }

    if (showEndPicker) {
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash", icon = Icons.Default.Check,
                    onClick = {
                        endPickerState.selectedDateMillis?.let { weekEnd = it }
                        showEndPicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor", icon = Icons.Default.Close,
                    onClick = { showEndPicker = false }
                )
            }
        ) { DatePicker(state = endPickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Yangi kontrakt", style = MaterialTheme.typography.titleLarge)
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
                                    selectedRenterId = r.id
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    passportData = r.passportData
                                    address = r.address
                                    pinfl = r.pinfl
                                    if (r.scooterId != null && r.scooterName != null) {
                                        selectedScooterId = r.scooterId
                                        scooterName = r.scooterName ?: ""
                                        val scoot = allScooters.find { it.id == r.scooterId }
                                        if (scoot != null) {
                                            vinNumber = scoot.vinNumber
                                            engineNumber = scoot.engineNumber
                                            scooterSerialNumber = scoot.scooterSerialNumber
                                            batteryId1 = scoot.batteryId1
                                            batteryId2 = scoot.batteryId2
                                            additionalInfo = scoot.additionalInfo
                                        }
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
                OutlinedTextField(
                    value = passportData,
                    onValueChange = { passportData = it },
                    label = { Text("Pasport ma'lumotlari") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Manzil") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = pinfl,
                    onValueChange = { pinfl = it },
                    label = { Text("JSHSHIR (PINFL)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                Text("Skuter", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                ExposedDropdownMenuBox(
                    expanded = expandedScooter,
                    onExpandedChange = { expandedScooter = !expandedScooter }
                ) {
                    OutlinedTextField(
                        value = scooterName,
                        onValueChange = { scooterName = it },
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
                                    selectedScooterId = s.id
                                    scooterName = s.name
                                    vinNumber = s.vinNumber
                                    engineNumber = s.engineNumber
                                    scooterSerialNumber = s.scooterSerialNumber
                                    batteryId1 = s.batteryId1
                                    batteryId2 = s.batteryId2
                                    additionalInfo = s.additionalInfo
                                    expandedScooter = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = vinNumber,
                    onValueChange = { vinNumber = it },
                    label = { Text("VIN") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = engineNumber,
                    onValueChange = { engineNumber = it },
                    label = { Text("Dvigatel raqami") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = scooterSerialNumber,
                    onValueChange = { scooterSerialNumber = it },
                    label = { Text("ID raqami") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = batteryId1,
                        onValueChange = { batteryId1 = it },
                        label = { Text("Akkum. 1") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = batteryId2,
                        onValueChange = { batteryId2 = it },
                        label = { Text("Akkum. 2") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                OutlinedTextField(
                    value = additionalInfo,
                    onValueChange = { additionalInfo = it },
                    label = { Text("Qo'shimcha ma'lumot") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider)

                Text("Kontrakt", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = dateFmt.format(Date(weekStart)),
                    onValueChange = {},
                    label = { Text("Boshlanish sanasi") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showStartPicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = dateFmt.format(Date(weekEnd)),
                    onValueChange = {},
                    label = { Text("Tugash sanasi") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showEndPicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Sanani tanlash")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isPaid,
                        onClick = { isPaid = true },
                        label = { Text("To'langan") },
                        leadingIcon = {
                            Box(Modifier.size(8.dp).background(StatusOk, CircleShape))
                        }
                    )
                    FilterChip(
                        selected = !isPaid,
                        onClick = { isPaid = false },
                        label = { Text("To'lanmagan") },
                        leadingIcon = {
                            Box(Modifier.size(8.dp).background(StatusOverdue, CircleShape))
                        }
                    )
                }
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
                enabled = renterName.isNotBlank() && renterPhone.isNotBlank(),
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: defaultAmount
                    onCreateWithOverrides(
                        selectedRenterId, renterName, renterPhone,
                        selectedScooterId, scooterName,
                        passportData, address, pinfl,
                        weekStart, weekEnd, parsedAmount, isPaid,
                        notes,
                        vinNumber, engineNumber, scooterSerialNumber,
                        batteryId1, batteryId2, additionalInfo
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
