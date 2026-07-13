package com.example

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.example.data.CardTransaction
import com.example.data.VirtualCard
import com.example.data.isExternal
import com.example.data.isExternalIn
import com.example.data.isExternalOut
import com.example.ui.FinansiViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ЭКРАН ИСТОРИИ ТРАНЗАКЦИЙ ВИРТУАЛЬНОЙ КАРТЫ
   ----------------------------------------------------------------------------
   Шаблон — RenterContractHistoryScreen. Структура:
     • TopAppBar с именем карты, балансом, кнопкой «Назад» и «Редактировать»
     • Сводка (цветная полоса + баланс + кол-во транзакций + всего получено/потрачено)
     • Переключатель «Kiruvchi» (входящие) / «Chiquvchi» (исходящие)
     • Поиск
     • Панель действий (Yaratish / Tahrirlash / O'chir)
     • Список транзакций с цветными индикаторами:
         зелёный — приход на эту карту
         красный — расход с этой карты

   Диалог создания/редактирования транзакции — ВЕРТИКАЛЬНЫЙ:
     ┌──────────────────────────────┐
     │  [Manba karta — сверху]       │
     ├──────────────────────────────┤
     │   ↓             [Summa ___]   │
     │  ↓↓ (flip)      [Izoh  ___]   │
     │   ↓                            │
     ├──────────────────────────────┤
     │  [Maqsad karta — снизу]       │
     └──────────────────────────────┘
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardTransactionHistoryScreen(
    card: VirtualCard,
    onBack: () -> Unit,
    onEditCard: () -> Unit,
    finansiViewModel: FinansiViewModel = viewModel()
) {
    val context = LocalContext.current
    val allCards by finansiViewModel.cards.collectAsStateWithLifecycle()
    val transactions by finansiViewModel.transactionsForCard(card.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Свежий объект карты (баланс мог измениться)
    val currentCard = allCards.firstOrNull { it.id == card.id } ?: card

    // ── Toggle: 0 = Kiruvchi (входящие), 1 = Chiquvchi (исходящие) ──────
    var selectedTab by remember { mutableStateOf(0) }
    var selectedTxIds by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<CardTransaction?>(null) }

    // ── Фильтр по диапазону дат (как на странице «Arendatorlar») ────────
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()
    // ── Боковая панель фильтров по столбцам ─────────────────────────────
    var showFilterPanel by remember { mutableStateOf(false) }
    var filterValues by remember { mutableStateOf(mapOf<String, String>()) }
    val cardFilterColumns = remember {
        listOf(
            FilterColumn("col_counterparty", "Kontragent", "Masalan: Glavnaya"),
            FilterColumn("col_amount", "Summa", "0", KeyboardType.Number),
            FilterColumn("col_note", "Izoh", "Masalan: To'lov"),
            FilterColumn("col_date", "Sana", "dd.MM.yyyy")
        )
    }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    // Карта id → имя (для отображения контрагента)
    val cardNameById = remember(allCards) {
        allCards.associate { it.id to it.name }
    }

    fun cardName(id: Int): String = when (id) {
        CardTransaction.EXTERNAL_SOURCE_ID -> "Kontrakt"
        VirtualCard.EXTERNAL_IN_CARD_ID -> "Tashqidan"
        VirtualCard.EXTERNAL_OUT_CARD_ID -> "Tashqiga"
        currentCard.id -> "Bu karta"
        else -> cardNameById[id] ?: "Karta #$id"
    }

    // ── Эффективная сумма со знаком относительно текущей карты ──────────
    // Для каждой транзакции считаем «знак для этой карты»:
    //   • деньги пришли на эту карту (или ушли С внешней) → положительный знак = Kiruvchi
    //   • деньги ушли с этой карты (или пришли НА внешнюю) → отрицательный знак = Chiquvchi
    //
    // Это та же логика, что и в ContractTransactionHistoryScreen:
    //   «плюсовые → входящие, минусовые → уходящие».
    //
    // Для ОБЫЧНЫХ карт:
    //   • toCardId == currentCard.id  → приход (+)
    //   • fromCardId == currentCard.id → расход (−)
    //
    // Для ВНЕШНИХ карт (Tashqidan / Tashqiga) логика инвертирована, потому что
    // внешняя карта представляет «остальной мир» с точки зрения пользователя:
    //   • Tashqidan (EXTERNAL_IN): перевод С этой карты = деньги вошли в систему
    //     пользователя (поступление, +) → Kiruvchi;
    //     перевод НА эту карту = возврат во вне (расход, −) → Chiquvchi.
    //   • Tashqiga (EXTERNAL_OUT): перевод НА эту карту = деньги вышли из системы
    //     пользователя (расход, −) → Chiquvchi;
    //     перевод С этой карты = возврат из вне (поступление, +) → Kiruvchi.
    fun signedAmountFor(tx: CardTransaction): Double {
        val isExternal = currentCard.isExternal
        val arrivesOnThisCard = tx.toCardId == currentCard.id
        val leavesFromThisCard = tx.fromCardId == currentCard.id
        val isPositive = if (isExternal) leavesFromThisCard else arrivesOnThisCard
        return if (isPositive) tx.amount else -tx.amount
    }

    // ── Разделение по ЗНАКУ суммы (как в ContractTransactionHistoryScreen) ─
    val incoming = remember(transactions, currentCard.id, currentCard.isExternal) {
        transactions.filter { signedAmountFor(it) >= 0.0 }
    }
    val outgoing = remember(transactions, currentCard.id, currentCard.isExternal) {
        transactions.filter { signedAmountFor(it) < 0.0 }
    }

    val totalIncoming = incoming.sumOf { it.amount }
    val totalOutgoing = outgoing.sumOf { it.amount }

    val currentList = if (selectedTab == 0) incoming else outgoing

    val filteredTxs = remember(currentList, searchQuery, dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis, filterValues) {
        val startMillis = dateRangePickerState.selectedStartDateMillis
        val endMillis = dateRangePickerState.selectedEndDateMillis
        currentList.filter { t ->
            // Контрагент — всегда «другая сторона» транзакции относительно currentCard.
            // Это работает и для обычных, и для внешних карт.
            val counterparty = if (t.fromCardId == currentCard.id)
                cardName(t.toCardId) else cardName(t.fromCardId)
            val textMatch = searchQuery.isBlank() ||
                cardName(t.fromCardId).contains(searchQuery, ignoreCase = true) ||
                cardName(t.toCardId).contains(searchQuery, ignoreCase = true) ||
                (t.note?.contains(searchQuery, ignoreCase = true) == true) ||
                t.amount.toLong().toString().contains(searchQuery) ||
                t.id.toString().contains(searchQuery)
            val dateMatch = if (startMillis != null) {
                if (endMillis != null) t.timestamp in startMillis..endMillis
                else t.timestamp >= startMillis
            } else true
            val filterMatch = filterValues.all { (colId, filterText) ->
                if (filterText.isBlank()) true
                else when (colId) {
                    "col_counterparty" -> counterparty.contains(filterText, ignoreCase = true)
                    "col_amount" -> t.amount.toLong().toString().contains(filterText, ignoreCase = true)
                    "col_note" -> (t.note ?: "").contains(filterText, ignoreCase = true)
                    "col_date" -> dateFmt.format(Date(t.timestamp)).contains(filterText, ignoreCase = true)
                    else -> true
                }
            }
            textMatch && dateMatch && filterMatch
        }
    }

    Scaffold(
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            currentCard.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            if (currentCard.isExternal) "Balans: \u221e" else "Balans: ${formatMoney(currentCard.balance)} so'm",
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
                    IconButton(onClick = onEditCard) {
                        Icon(Icons.Default.Edit, contentDescription = "Kartani tahrirlash")
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
            // ── Сводка по карте ────────────────────────────────────────────
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
                    SummaryColumn("Kiruvchi", "${formatMoney(totalIncoming)}", StatusOk)
                    SummaryColumn("Chiquvchi", "${formatMoney(totalOutgoing)}", StatusOverdue)
                    SummaryColumn(
                        "Balans",
                        if (currentCard.isExternal) "\u221e" else "${formatMoney(currentCard.balance)}",
                        if (currentCard.balance < 0) StatusOverdue else StatusOk
                    )
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
                CardTabButton(
                    label = "Kiruvchi (${incoming.size})",
                    icon = Icons.Default.ArrowDownward,
                    isSelected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        selectedTxIds = emptySet()
                    }
                )
                CardTabButton(
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
                columns = cardFilterColumns,
                filterValues = filterValues,
                onFilterChange = { colId, value ->
                    filterValues = filterValues.toMutableMap().apply { put(colId, value) }
                },
                onSearch = { /* фильтры применяются реактивно */ },
                onReset = { filterValues = emptyMap() },
                onDismiss = { showFilterPanel = false },
                visible = showFilterPanel
            )

            // ── Панель действий — как в RenterContractHistoryScreen ──────
            // Yaratish — всегда активна. Tahrirlash — ровно 1 выбран.
            // O'chir — хоть 1 выбран.
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
                        // Контрагент — всегда «другая сторона» транзакции.
                        // Работает и для обычных, и для внешних карт.
                        val counterpartyId = if (tx.fromCardId == currentCard.id)
                            tx.toCardId else tx.fromCardId
                        CardTxRow(
                            tx = tx,
                            isIncoming = selectedTab == 0,
                            currentCardId = currentCard.id,
                            counterpartyName = cardName(counterpartyId),
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
                    "Bu amal balanslarga ta'sir qilmaydi — faqat tarix yozuvi o'chiriladi."
                )
            },
            confirmButton = {
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    onClick = {
                        selectedTxIds.forEach { id ->
                            finansiViewModel.deleteTransaction(id)
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
    // В режиме create: текущая карта автоматически становится «приёмником»
    // (toCard) если активна вкладка Kiruvchi, либо «источником» (fromCard)
    // если активна Chiquvchi — это естественное поведение для пользователя.
    if (showCreateDialog) {
        val defaultFrom = if (selectedTab == 1) currentCard else allCards.firstOrNull { it.id != currentCard.id }
        val defaultTo   = if (selectedTab == 0) currentCard else allCards.firstOrNull { it.id != currentCard.id }

        VerticalCardTransferDialog(
            cards = allCards,
            initialFrom = defaultFrom,
            initialTo = defaultTo,
            initialAmount = "",
            initialNote = "",
            title = "Tranzaksiya yaratish",
            onDismiss = { showCreateDialog = false },
            onSave = { fromCard, toCard, amount, note ->
                if (fromCard.id == toCard.id) {
                    Toast.makeText(context, "Manba va maqsad boshqa bo'lishi kerak", Toast.LENGTH_SHORT).show()
                    return@VerticalCardTransferDialog
                }
                finansiViewModel.transfer(fromCard.id, toCard.id, amount, note, reversed = false)
                Toast.makeText(context, "Tranzaksiya yaratildi", Toast.LENGTH_SHORT).show()
                showCreateDialog = false
            }
        )
    }

    // ── Диалог редактирования транзакции ───────────────────────────────
    // ВНИМАНИЕ: правка НЕ пересчитывает балансы карт — только правит запись.
    // Это соответствует поведению удаления (O'chir), где балансы также не
    // откатываются. Если нужно реально переместить деньги — создайте новую.
    editingTx?.let { tx ->
        VerticalCardTransferDialog(
            cards = allCards,
            initialFrom = allCards.firstOrNull { it.id == tx.fromCardId },
            initialTo = allCards.firstOrNull { it.id == tx.toCardId },
            initialAmount = tx.amount.toLong().toString(),
            initialNote = tx.note ?: "",
            title = "Tranzaksiyani tahrirlash",
            onDismiss = { editingTx = null },
            onSave = { fromCard, toCard, amount, note ->
                if (fromCard.id == toCard.id) {
                    Toast.makeText(context, "Manba va maqsad boshqa bo'lishi kerak", Toast.LENGTH_SHORT).show()
                    return@VerticalCardTransferDialog
                }
                finansiViewModel.updateTransaction(
                    tx.copy(
                        fromCardId = fromCard.id,
                        toCardId = toCard.id,
                        amount = amount,
                        note = note
                    )
                )
                Toast.makeText(context, "Tranzaksiya yangilandi", Toast.LENGTH_SHORT).show()
                editingTx = null
            }
        )
    }

    // ── Диалог выбора диапазона дат (фильтр по дате транзакции) ──────────
    if (showDateRangePicker) {
        com.example.ui.components.DateRangeFilterDialog(
            state = dateRangePickerState,
            onDismiss = { showDateRangePicker = false },
            title = "Sana bo'yicha filter"
        )
    }
}

/* ============================================================================
   ВЕРТИКАЛЬНЫЙ ДИАЛОГ ПЕРЕВОДА МЕЖДУ КАРТАМИ
   ----------------------------------------------------------------------------
   Карта-источник сверху, карта-назначение снизу, между ними — столбец с
   круглой кнопкой-стрелкой (вертикальной, переворачивается по тапу) и
   справа от неё — поля «Summa» и «Izoh». Это вертикальный аналог
   горизонтальной TransactionZone из FinansiPanel.kt.
   ============================================================================ */

@Composable
private fun VerticalCardTransferDialog(
    cards: List<VirtualCard>,
    initialFrom: VirtualCard?,
    initialTo: VirtualCard?,
    initialAmount: String,
    initialNote: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (from: VirtualCard, to: VirtualCard, amount: Double, note: String?) -> Unit
) {
    var fromCard by remember { mutableStateOf(initialFrom) }
    var toCard by remember { mutableStateOf(initialTo) }
    var amountText by remember { mutableStateOf(initialAmount) }
    var noteText by remember { mutableStateOf(initialNote) }
    var reversed by remember { mutableStateOf(false) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) } // 1 = from, 2 = to

    // Эффективные from/to с учётом флага разворота
    val effectiveFrom = if (reversed) toCard else fromCard
    val effectiveTo   = if (reversed) fromCard else toCard

    val amountParsed = amountText.replace(',', '.').toDoubleOrNull()
    // Внешний перевод — если хотя бы одна из карт Tashqidan/Tashqiga.
    // В этом случае поле «Izoh» становится обязательным.
    val involvesExternal = (effectiveFrom?.isExternal == true) ||
                           (effectiveTo?.isExternal == true)
    val noteLabel = if (involvesExternal) "Izoh (majburiy) *" else "Izoh (ixtiyoriy)"
    val canSave = fromCard != null && toCard != null &&
                  amountParsed != null && amountParsed > 0.0 &&
                  fromCard!!.id != toCard!!.id &&
                  (!involvesExternal || noteText.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = ClaudeCard,
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = ClaudeText)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ── Верхняя карта (источник в прямом направлении) ───────
                VerticalCardSlot(
                    label = "Manba karta (yuqori)",
                    card = effectiveFrom,
                    onClick = { pickingSlot = 1 }
                )

                // ── Стрелка-переворот + поля ввода рядом ──────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Кнопка-стрелка (вертикальная)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = { reversed = !reversed },
                            modifier = Modifier
                                .size(44.dp)
                                .background(ClaudeAccent, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (reversed) Icons.AutoMirrored.Filled.ArrowBack
                                              else Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Yo'nalishni o'zgartirish",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = if (reversed) "Pastdan yuqoriga" else "Yuqoridan pastga",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClaudeTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(80.dp)
                        )
                    }

                    // Поля ввода рядом со стрелкой
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { s -> amountText = s.filter { it.isDigit() || it == '.' || it == ',' } },
                            label = { Text("Summa (so'm) *") },
                            placeholder = { Text("0") },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
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
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text(noteLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            isError = involvesExternal && noteText.isBlank() && amountText.isNotBlank(),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = ClaudeDivider,
                                focusedBorderColor = ClaudeAccent,
                                unfocusedContainerColor = ClaudeBackground,
                                focusedContainerColor = ClaudeBackground
                            )
                        )
                        if (involvesExternal) {
                            Text(
                                text = "Tashqi o'tkazma uchun izoh majburiy.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                        }
                    }
                }

                // ── Нижняя карта (назначение в прямом направлении) ──────
                VerticalCardSlot(
                    label = "Maqsad karta (pastki)",
                    card = effectiveTo,
                    onClick = { pickingSlot = 2 }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val f = effectiveFrom
                    val t = effectiveTo
                    if (f != null && t != null && amt > 0 && f.id != t.id) {
                        onSave(f, t, amt, noteText.ifBlank { null })
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

    // ── Вложенный диалог выбора карты в слот ──────────────────────────
    pickingSlot?.let { slot ->
        CardPickerDialogInline(
            cards = cards,
            excludeId = if (slot == 1) effectiveTo?.id else effectiveFrom?.id,
            title = if (slot == 1) "Manba kartani tanlang" else "Maqsad kartani tanlang",
            onDismiss = { pickingSlot = null },
            onPick = { c ->
                if (slot == 1) fromCard = c else toCard = c
                pickingSlot = null
            }
        )
    }
}

/** Компактный слот карты для вертикального диалога. */
@Composable
private fun VerticalCardSlot(
    label: String,
    card: VirtualCard?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (card != null) ClaudeBackground else ClaudeAccentBg,
        border = BorderStroke(
            width = if (card != null) 1.dp else 1.5.dp,
            color = if (card != null) ClaudeDivider else ClaudeAccentMuted
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (card != null) parseColorHex(card.colorHex)
                        else ClaudeAccent.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (card != null) Icons.Default.CreditCard else Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
                Text(
                    text = card?.name ?: "Tanlanmagan",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (card != null) ClaudeText else ClaudeAccent
                )
                if (card != null) {
                    Text(
                        text = if (card.isExternal) "\u221e" else "${formatMoney(card.balance)} so'm",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = ClaudeTextSecondary
            )
        }
    }
}

/** Inline-диалог выбора карты (упрощённый, без зависимостей от FinansiPanel). */
@Composable
private fun CardPickerDialogInline(
    cards: List<VirtualCard>,
    excludeId: Int?,
    title: String,
    onDismiss: () -> Unit,
    onPick: (VirtualCard) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = ClaudeCard,
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = ClaudeText)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cards
                    .filter { it.id != excludeId }
                    .forEach { card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ClaudeBackground)
                                .clickable { onPick(card) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(parseColorHex(card.colorHex))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = card.name,
                                    fontWeight = FontWeight.Medium,
                                    color = ClaudeText
                                )
                                Text(
                                    text = if (card.isExternal) "∞" else "${formatMoney(card.balance)} so'm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeTextSecondary
                                )
                            }
                        }
                    }
                if (cards.isEmpty() || cards.all { it.id == excludeId }) {
                    Text(
                        text = "Boshqa kartalar yo'q. Avval yangi karta yarating.",
                        color = ClaudeTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Yopish", color = ClaudeTextSecondary)
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
private fun RowScope.CardTabButton(
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
private fun CardTxRow(
    tx: CardTransaction,
    isIncoming: Boolean,
    currentCardId: Int,
    counterpartyName: String,
    isSelected: Boolean,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val accentColor = if (isIncoming) StatusOk else StatusOverdue
    val sign = if (isIncoming) "+" else "−"

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
            // Цветной индикатор направления
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

            // Контрагент + дата + заметка
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = counterpartyName,
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
                if (!tx.note.isNullOrBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary,
                        maxLines = 2
                    )
                }
            }

            // Сумма
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$sign ${formatMoney(tx.amount)}",
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

/** Парсит HEX-цвет (#RRGGBB) в Compose Color. */
private fun parseColorHex(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val r = cleaned.substring(0, 2).toInt(16)
        val g = cleaned.substring(2, 4).toInt(16)
        val b = cleaned.substring(4, 6).toInt(16)
        Color(r, g, b)
    } catch (e: Exception) {
        Color(0x607080FF)
    }
}

private fun formatMoney(amount: Double): String {
    val sign = if (amount < 0) "-" else ""
    val absValue = kotlin.math.abs(amount).toLong()
    val formatted = String.format(Locale.US, "%,d", absValue).replace(',', ' ')
    return "$sign$formatted"
}
