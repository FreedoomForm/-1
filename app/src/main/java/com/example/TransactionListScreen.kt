package com.example

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.components.SortableHeaderCellFixed
import com.example.ui.components.TableSortState
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
    finansiViewModel: com.example.ui.FinansiViewModel = viewModel(),
    // Триггер извне: когда MainActivity увеличивает это значение (нажатие «+»
    // в верхней панели), экран открывает диалог создания транзакции. Заменяет
    // внутренний FAB, который раньше был здесь.
    createTrigger: Int = 0,
    // Триггеры универсальных ✎/🗑 из верхней панели MainActivity.
    // Увеличение значения открывает диалог редактирования (если выбран 1) или
    // подтверждение удаления (если выбрано ≥1). Заменяют внутренние кнопки
    // "Tahrirlash / O'chir", которые раньше были над таблицей.
    editTrigger: Int = 0,
    deleteTrigger: Int = 0,
    // Выделение поднято в MainActivity, чтобы универсальные ✎/🗑 в верхней
    // панели могли его видеть. Раньше было внутренним state этого экрана.
    selectedTxs: Set<Int> = emptySet(),
    onSelectedTxsChange: (Set<Int>) -> Unit = {},
    // ── Поиск и триггеры календаря/фильтра из TopAppBar ──────────────
    // Раньше searchQuery был внутренним state этого экрана, а поисковая
    // панель (UnifiedSearchBar) рендерилась в Column контента. Теперь поиск
    // живёт в TopAppBar MainActivity (CompactSearchPanel), поэтому query
    // и колбэки поднимаются сюда.
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    calendarTrigger: Int = 0,
    filterTrigger: Int = 0,
    // ── Trash mode (v36+) ──────────────────────────────────────────────
    // true = показывать только удалённые транзакции (isDeleted=1) + скрыть
    //        диалоги создания (поскольку «+» в trash mode = restore).
    // false = обычный режим (активные транзакции, «+» = create).
    isTrashMode: Boolean = false
) {
    val transactions by transactionViewModel.transactions.collectAsStateWithLifecycle()
    val allRenters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val allScooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val allHistory by contractHistoryViewModel.history.collectAsStateWithLifecycle()
    // ── Карточные транзакции (переводы между виртуальными картами) ────────
    // Раньше были отдельной секцией ВНЕ LazyColumn, без поиска/фильтра и с
    // лимитом 20 строк. Теперь включаем их в общую ленту: пользователь видит
    // все движения денег (и контрактные платежи, и переводы между картами)
    // в одном списке, с единым поиском, фильтром по дате и колонкам.
    val cardTxs by finansiViewModel.transactions.collectAsStateWithLifecycle()
    val cards by finansiViewModel.cards.collectAsStateWithLifecycle()
    val cardById = remember(cards) { cards.associateBy { it.id } }
    val context = LocalContext.current

    // ── Источник данных: в trash mode показываем только удалённые транзакции.
    val liveTransactions by transactionViewModel.liveTransactions.collectAsStateWithLifecycle()
    val trashedTransactions by transactionViewModel.trashedTransactions.collectAsStateWithLifecycle()
    val liveCardTxs by finansiViewModel.liveTransactions.collectAsStateWithLifecycle()
    val trashedCardTxs by finansiViewModel.trashedTransactions.collectAsStateWithLifecycle()
    val txSource = if (isTrashMode) trashedTransactions else liveTransactions
    val cardTxSource = if (isTrashMode) trashedCardTxs else liveCardTxs

    // searchQuery больше не внутренний state — поднят в MainActivity.
    var editingTx by remember { mutableStateOf<Transaction?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Запоминаем последнее обработанное значение каждого триггера, чтобы
    // НЕ реагировать на начальное значение при входе на вкладку.
    var lastCreateTrigger by remember { mutableStateOf(createTrigger) }
    var lastEditTrigger by remember { mutableStateOf(editTrigger) }
    var lastDeleteTrigger by remember { mutableStateOf(deleteTrigger) }
    var lastCalendarTrigger by remember { mutableStateOf(calendarTrigger) }
    var lastFilterTrigger by remember { mutableStateOf(filterTrigger) }

    // ── Реакция на внешний триггер создания транзакции ─────────────────
    // Срабатывает ТОЛЬКО при реальном увеличении значения, а не при входе.
    LaunchedEffect(createTrigger) {
        if (createTrigger > lastCreateTrigger) showCreateDialog = true
        lastCreateTrigger = createTrigger
    }

    // ── Реакция на универсальный ✎ из верхней панели ───────────────
    LaunchedEffect(editTrigger) {
        if (editTrigger > lastEditTrigger && selectedTxs.size == 1) {
            editingTx = transactions.firstOrNull { it.id == selectedTxs.first() }
        }
        lastEditTrigger = editTrigger
    }

    // ── Реакция на универсальный 🗑 из верхней панели ───────────────
    LaunchedEffect(deleteTrigger) {
        if (deleteTrigger > lastDeleteTrigger && selectedTxs.isNotEmpty()) {
            showDeleteConfirm = true
        }
        lastDeleteTrigger = deleteTrigger
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
    // ── Состояние сортировки ──
    // По запросу пользователя все столбцы таблицы транзакций теперь сортируемые
    // (от меньшего к большему и наоборот). Раньше таблица была отсортирована
    // только по timestamp DESC без возможности переключать.
    var sortState by remember { mutableStateOf(TableSortState()) }

    // Calendar (DateRange) — фильтр по timestamp
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()

    // ── Реакция на триггер календаря из TopAppBar ───────────────────
    // Когда пользователь нажал кнопку календаря в CompactSearchPanel,
    // MainActivity увеличивает calendarTrigger — открываем DateRangePicker.
    LaunchedEffect(calendarTrigger) {
        if (calendarTrigger > lastCalendarTrigger) showDateRangePicker = true
        lastCalendarTrigger = calendarTrigger
    }

    // ── Реакция на триггер фильтра из TopAppBar ─────────────────────
    // Когда пользователь нажал кнопку фильтров в CompactSearchPanel,
    // MainActivity увеличивает filterTrigger — открываем FilterSidePanel.
    LaunchedEffect(filterTrigger) {
        if (filterTrigger > lastFilterTrigger) showFilterPanel = true
        lastFilterTrigger = filterTrigger
    }

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
    // Объединённая лента: Transaction (контрактные платежи) + CardTransaction
    // (переводы между виртуальными картами). Оба типа фильтруются одними и
    // теми же критериями (searchQuery, dateRange, column filters) и потом
    // сливаются в один список, отсортированный по timestamp DESC.
    val filteredTxs = remember(txSource, searchQuery, filterValues, dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis) {
        txSource.filter { t ->
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

    // ── Фильтрация CardTransaction (переводы между картами) ──────────────
    // Применяем ТЕ ЖЕ критерии: searchQuery ищет по именам карт и note,
    // dateRange — по timestamp, column filters — по date/amount/type.
    val filteredCardTxs = remember(cardTxSource, cardById, searchQuery, filterValues, dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis) {
        cardTxSource.filter { ctx ->
            val fromName = cardById[ctx.fromCardId]?.name ?: "Kontrakt"
            val toName = cardById[ctx.toCardId]?.name ?: "—"
            val typeLabel = when (ctx.type) {
                com.example.data.CardTransaction.TYPE_CONTRACT_INCOME -> "Kontrakt to'lovi"
                com.example.data.CardTransaction.TYPE_EXPENSE -> "Xarajat"
                else -> "Karta o'tkazmasi"
            }
            val textMatch = searchQuery.isBlank() ||
                fromName.contains(searchQuery, ignoreCase = true) ||
                toName.contains(searchQuery, ignoreCase = true) ||
                typeLabel.contains(searchQuery, ignoreCase = true) ||
                (ctx.note?.contains(searchQuery, ignoreCase = true) == true) ||
                ctx.id.toString().contains(searchQuery)

            val startMillis = dateRangePickerState.selectedStartDateMillis
            val endMillis = dateRangePickerState.selectedEndDateMillis
            val dateMatch = if (startMillis != null) {
                if (endMillis != null) ctx.timestamp in startMillis..endMillis
                else ctx.timestamp >= startMillis
            } else true

            val filterMatch = filterValues.all { (colId, filterText) ->
                if (filterText.isBlank()) true
                else when (colId) {
                    "col_id"       -> ("K${ctx.id}").contains(filterText, ignoreCase = true)
                    "col_date"     -> dateTimeFmt.format(Date(ctx.timestamp)).contains(filterText, ignoreCase = true)
                    "col_renter"   -> fromName.contains(filterText, ignoreCase = true)
                    "col_phone"    -> false  // у CardTransaction нет телефона
                    "col_scooter"  -> false  // у CardTransaction нет скутера
                    "col_contract" -> (ctx.contractId?.toString() ?: "").contains(filterText, ignoreCase = true)
                    "col_type"     -> typeLabel.contains(filterText, ignoreCase = true)
                    "col_amount"   -> ctx.amount.toLong().toString().contains(filterText, ignoreCase = true)
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
    val wNum       = 40.dp    // № — порядковый номер строки
    val wId       = 50.dp
    val wDate     = 110.dp
    val wRenter   = 130.dp   // увеличено с 110 — вмещает «Имя Фамилия»
    val wPhone    = 95.dp
    val wScooter  = 80.dp
    val wContract = 140.dp
    val wType     = 90.dp
    val wAmount   = 85.dp

    // ── Содержимое страницы (без собственного Scaffold — вкладка живёт
    // внутри единого Scaffold в MainActivity, чтобы поисковая строка всех
    // 4 вкладок находилась на одной вертикали) ─────────────────────────
    // ── ВАЖНО: вся таблица + FilterSidePanel оборачиваются в Box, чтобы
    // FilterSidePanel рендерился как overlay поверх таблицы. ────────────
    Box(modifier = Modifier
        .fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // ── Поисковая панель удалена из контента ─────────────────────
            // Теперь поиск живёт в TopAppBar MainActivity (CompactSearchPanel).
            // Календарь и фильтры открываются кнопками оттуда же через
            // calendarTrigger / filterTrigger, см. LaunchedEffect выше.

            // ── Заголовок таблицы ───────────────────────────────────────
            Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NonSortableHeaderCellFixed(Icons.Default.Numbers, wNum, "№")
                    if (showId)       SortableHeaderCellFixed(Icons.Default.Numbers,              wId,       "col_id",       sortState) { sortState = sortState.click("col_id") }
                    if (showDate)     SortableHeaderCellFixed(Icons.Default.DateRange,            wDate,     "col_date",     sortState) { sortState = sortState.click("col_date") }
                    if (showRenter)   SortableHeaderCellFixed(Icons.Default.Person,               wRenter,   "col_renter",   sortState) { sortState = sortState.click("col_renter") }
                    if (showPhone)    SortableHeaderCellFixed(Icons.Default.Phone,                wPhone,    "col_phone",    sortState) { sortState = sortState.click("col_phone") }
                    if (showScooter)  SortableHeaderCellFixed(Icons.Default.DirectionsBike,       wScooter,  "col_scooter",  sortState) { sortState = sortState.click("col_scooter") }
                    if (showContract) SortableHeaderCellFixed(Icons.Default.Description,          wContract, "col_contract", sortState) { sortState = sortState.click("col_contract") }
                    if (showType)     SortableHeaderCellFixed(Icons.Default.Category,             wType,     "col_type",     sortState) { sortState = sortState.click("col_type") }
                    if (showAmount)   SortableHeaderCellFixed(Icons.Default.AccountBalanceWallet, wAmount,   "col_amount",   sortState) { sortState = sortState.click("col_amount") }
                }
            }
            HorizontalDivider(color = ClaudeDivider)

            // ── Список транзакций (объединённая лента Transaction + CardTransaction) ──
            if (filteredTxs.isEmpty() && filteredCardTxs.isEmpty()) {
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
                // ── Сортируем обе ленты вместе ──────────────────────────
                // Используем sealed class для统一 типа элемента списка.
                // По умолчанию (NONE) — по timestamp DESC (как раньше).
                // При активной сортировке — по выбранному столбцу ASC/DESC.
                val rawItems: List<UnifiedTxItem> = buildList {
                    filteredTxs.forEach { add(UnifiedTxItem.ContractTx(it)) }
                    filteredCardTxs.forEach { add(UnifiedTxItem.CardTx(it)) }
                }
                val mergedItems: List<UnifiedTxItem> = run {
                    val col = sortState.activeColumn
                    val state = sortState.stateFor(col ?: "")
                    if (state == SortState.NONE) {
                        rawItems.sortedByDescending { it.timestamp }
                    } else {
                        // ── Sort comparators для всех столбцов транзакций ──
                        // Для ContractTx и CardTx значения извлекаются по-разному.
                        val comparator = when (col) {
                            "col_id"       -> compareBy<UnifiedTxItem> { it.sortId }
                            "col_date"     -> compareBy<UnifiedTxItem> { it.timestamp }
                            "col_renter"   -> compareBy<UnifiedTxItem> { it.sortRenter.lowercase() }
                            "col_phone"    -> compareBy<UnifiedTxItem> { it.sortPhone }
                            "col_scooter"  -> compareBy<UnifiedTxItem> { it.sortScooter }
                            "col_contract" -> compareBy<UnifiedTxItem> { it.sortContract }
                            "col_type"     -> compareBy<UnifiedTxItem> { it.sortType.lowercase() }
                            "col_amount"   -> compareBy<UnifiedTxItem> { it.sortAmount }
                            else -> compareBy<UnifiedTxItem> { it.timestamp }
                        }
                        if (state == SortState.ASCENDING) rawItems.sortedWith(comparator)
                        else rawItems.sortedWith(comparator.reversed())
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(mergedItems, key = { _, it -> it.uniqueKey }) { idx, item ->
                        when (item) {
                            is UnifiedTxItem.ContractTx -> {
                                val tx = item.tx
                                val isSelected = tx.id in selectedTxs
                                val isPositive = TransactionViewModel.typeIsPositive(tx.type)
                                // Если сумма отрицательная (например, отмена оплаты контракта),
                                // это всегда расход/возврат, независимо от типа.
                                val effectivePositive = isPositive && tx.amount >= 0
                                val statusColor = when (tx.type) {
                                    Transaction.TYPE_CUSTOM -> ClaudeTextSecondary
                                    else -> if (effectivePositive) StatusOk else StatusOverdue
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
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Color(0xFFF3F4F6) else Color.White)
                                            .combinedClickable(
                                                onClick = {
                                                    onSelectedTxsChange(
                                                        if (isSelected) selectedTxs - tx.id
                                                        else selectedTxs + tx.id
                                                    )
                                                },
                                                onLongClick = {
                                                    onSelectedTxsChange(
                                                        if (isSelected) selectedTxs - tx.id
                                                        else selectedTxs + tx.id
                                                    )
                                                }
                                            )
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // ── № — порядковый номер строки ──
                                        Text(
                                            "${idx + 1}",
                                            modifier = Modifier.width(wNum).padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClaudeTextSecondary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
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
                                            // Полное имя арендатора без обрезки.
                                            Text(
                                                tx.renterName.ifBlank { "—" },
                                                modifier = Modifier.width(wRenter).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = ClaudeText,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = Int.MAX_VALUE,
                                                softWrap = true,
                                                overflow = TextOverflow.Visible
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
                                            val sign = if (effectivePositive) "+" else "−"
                                            val displayAmount = kotlin.math.abs(tx.amount.toLong())
                                            Text(
                                                "$sign $displayAmount",
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
                            is UnifiedTxItem.CardTx -> {
                                // ── Строка CardTransaction (перевод между картами) ──
                                // ВАЖНО: визуально ИДЕНТИЧНА строке ContractTx —
                                // тот же белый фон, та же структура, тот же формат
                                // суммы со знаком +/−. Раньше фон был ClaudeCard
                                // (кремовый) и сумма всегда с "+" — это создавало
                                // визуальное несоответствие, на которое жаловался
                                // пользователь.
                                val ctx = item.tx
                                val from = cardById[ctx.fromCardId]
                                val to = cardById[ctx.toCardId]
                                val isIncome = ctx.type == com.example.data.CardTransaction.TYPE_CONTRACT_INCOME
                                val isExpense = ctx.type == com.example.data.CardTransaction.TYPE_EXPENSE
                                val fromLabel = when {
                                    isIncome || ctx.fromCardId == 0 -> "Kontrakt"
                                    ctx.fromCardId == com.example.data.VirtualCard.EXTERNAL_IN_CARD_ID -> "Tashqidan"
                                    ctx.fromCardId == com.example.data.VirtualCard.EXTERNAL_OUT_CARD_ID -> "Tashqiga"
                                    else -> from?.name ?: "—"
                                }
                                val toLabel = when (ctx.toCardId) {
                                    com.example.data.VirtualCard.EXTERNAL_IN_CARD_ID -> "Tashqidan"
                                    com.example.data.VirtualCard.EXTERNAL_OUT_CARD_ID -> "Tashqiga"
                                    else -> to?.name ?: "—"
                                }
                                val typeLabel = when (ctx.type) {
                                    com.example.data.CardTransaction.TYPE_CONTRACT_INCOME -> "Kontrakt to'lovi"
                                    com.example.data.CardTransaction.TYPE_EXPENSE -> "Xarajat"
                                    else -> "Karta o'tkazmasi"
                                }
                                // Для CardTransaction «контракт» — это ID, если есть.
                                val contractLabel = ctx.contractId?.let { "#$it" } ?: ""
                                val statusColor = when (ctx.type) {
                                    com.example.data.CardTransaction.TYPE_CONTRACT_INCOME -> StatusOk
                                    com.example.data.CardTransaction.TYPE_EXPENSE -> StatusOverdue
                                    else -> Color(0xFF1565C0)
                                }
                                // Эффективное направление движения денег:
                                // • TYPE_EXPENSE → расход (−)
                                // • TYPE_CONTRACT_INCOME → доход (+)
                                // • прочие переводы между картами → нейтрально (+),
                                //   так как деньги не покинули бизнес.
                                val effectivePositive = !isExpense
                                val amountSign = if (effectivePositive) "+" else "−"

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .horizontalScroll(hScrollState)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White)
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // ── № — порядковый номер строки ──
                                        Text(
                                            "${idx + 1}",
                                            modifier = Modifier.width(wNum).padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClaudeTextSecondary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        if (showId) {
                                            Text(
                                                "#${ctx.id}",
                                                modifier = Modifier.width(wId).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ClaudeTextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                        if (showDate) {
                                            Text(
                                                dateTimeFmt.format(Date(ctx.timestamp)),
                                                modifier = Modifier.width(wDate).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ClaudeText,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                        }
                                        if (showRenter) {
                                            // Для CardTransaction в колонке «Mijoz» показываем
                                            // только источник (fromLabel) — так же, как в
                                            // ContractTx показывается только имя арендатора.
                                            // Раньше здесь было «$fromLabel → $toLabel» со
                                            // стрелкой, что визуально отличало строку от
                                            // ContractTx. Назначение (toLabel) при необходимости
                                            // видно в колонке «Kontrakt» или «Izoh».
                                            Text(
                                                fromLabel,
                                                modifier = Modifier.width(wRenter).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = ClaudeText,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                        }
                                        if (showPhone) {
                                            Text(
                                                "—",
                                                modifier = Modifier.width(wPhone).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = ClaudeTextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                        if (showScooter) {
                                            Text(
                                                "—",
                                                modifier = Modifier.width(wScooter).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = ClaudeTextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                        if (showContract) {
                                            // Если явного contractId нет, показываем
                                            // назначение перевода (toLabel) — это даёт
                                            // пользователю понять, куда ушли деньги.
                                            val contractDisplay = contractLabel.ifBlank { toLabel }
                                            Text(
                                                contractDisplay,
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
                                            val displayAmount = kotlin.math.abs(ctx.amount.toLong())
                                            Text(
                                                "$amountSign $displayAmount",
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
            }
        }

        // ── FilterSidePanel как overlay поверх таблицы (внутри Box) ──
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
                        onSelectedTxsChange(emptySet())
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
        com.example.ui.components.DateRangeFilterDialog(
            state = dateRangePickerState,
            onDismiss = { showDateRangePicker = false },
            title = "Sana bo'yicha filter"
        )
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
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось renterName+amount — убрано.
                enabled = true,
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
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось renterName — убрано.
                enabled = true,
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

/**
 * Секция переводов между виртуальными картами — УДАЛЕНА.
 *
 * Раньше внизу вкладки «Tranzaksiya» была отдельная секция с переводами
 * между картами (CardTransaction), но она показывалась ВНЕ основного
 * LazyColumn, без поиска/фильтра и с лимитом 20 строк.
 *
 * Теперь CardTransaction включены в общую ленту через sealed class
 * [UnifiedTxItem] и рендерятся в том же LazyColumn, что и Transaction,
 * с единым фильтром по дате, поиску и колонкам. См. основной экран
 * TransactionListScreen — там используется `mergedItems: List<UnifiedTxItem>`.
 *
 * Эта функция оставлена как placeholder для совместимости, если вдруг
 * где-то остались ссылки — но в UI больше не вызывается.
 */

/** Парсит hex-строку в Compose Color. */
private fun parseHexColor(hex: String): Color {
    return try {
        val normalized = hex.removePrefix("#")
        val full = if (normalized.length == 6) "FF$normalized" else normalized
        Color(full.toLong(16))
    } catch (_: Exception) {
        Color.Gray
    }
}

/**
 * Объединённый элемент ленты транзакций — «мостик» между двумя типами
 * движения денег в приложении:
 *
 *   • [ContractTx] — платежи по контрактам (таблица `transactions`).
 *     Привязаны к арендатору и (опционально) к контракту через contractId.
 *     Это деньги, которые платит арендатор за аренду скутера.
 *
 *   • [CardTx] — переводы между виртуальными картами (таблица `card_transactions`).
 *     Включает TYPE_CONTRACT_INCOME (зачисление оплаты контракта на главную
 *     карту), TYPE_CARD_TRANSFER (ручной перевод между картами) и TYPE_EXPENSE
 *     (списание денег «во вне»). Если cardTx.contractId != null — это
 *     зачисление, связанное с конкретным контрактом; его можно каскадно
 *     удалить при удалении контракта.
 *
 * Оба типа имеют общий `timestamp` (по нему лента сортируется) и общий
 * `uniqueKey` (для Compose `items(key = ...)`). Поля телефона/скутера/имени
 * арендатора у CardTransaction заменяются на «Карта-источник → Карта-назначения»
 * и прочерки, т.к. у CardTransaction нет этих данных.
 */
sealed class UnifiedTxItem {
    abstract val timestamp: Long
    abstract val uniqueKey: String

    // ── Sort helper properties ─────────────────────────────────────────
    // Используются comparator'ом при сортировке по столбцам. Каждое свойство
    // возвращает значение соответствующего столбца для данного элемента.
    abstract val sortId: String
    abstract val sortRenter: String
    abstract val sortPhone: String
    abstract val sortScooter: String
    abstract val sortContract: String
    abstract val sortType: String
    abstract val sortAmount: Long

    data class ContractTx(val tx: Transaction) : UnifiedTxItem() {
        override val timestamp: Long get() = tx.timestamp
        override val uniqueKey: String get() = "t${tx.id}"
        override val sortId: String get() = tx.id.toString()
        override val sortRenter: String get() = tx.renterName
        override val sortPhone: String get() = tx.renterPhone
        override val sortScooter: String get() = tx.scooterName
        override val sortContract: String get() = tx.contractLabel
        override val sortType: String get() = TransactionViewModel.typeLabel(tx.type)
        override val sortAmount: Long get() = tx.amount.toLong()
    }

    data class CardTx(val tx: com.example.data.CardTransaction) : UnifiedTxItem() {
        override val timestamp: Long get() = tx.timestamp
        override val uniqueKey: String get() = "k${tx.id}"
        override val sortId: String get() = "K${tx.id}"
        override val sortRenter: String get() = ""  // у CardTransaction нет арендатора
        override val sortPhone: String get() = ""
        override val sortScooter: String get() = ""
        override val sortContract: String get() = tx.contractId?.toString() ?: ""
        override val sortType: String get() = when (tx.type) {
            com.example.data.CardTransaction.TYPE_CONTRACT_INCOME -> "Kontrakt to'lovi"
            com.example.data.CardTransaction.TYPE_EXPENSE -> "Xarajat"
            else -> "Karta o'tkazmasi"
        }
        override val sortAmount: Long get() = tx.amount.toLong()
    }
}
