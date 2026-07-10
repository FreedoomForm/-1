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
import com.example.data.SettingsRepository
import com.example.ui.ContractHistoryViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.TransactionViewModel
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
    renterViewModel: com.example.ui.RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    transactionViewModel: TransactionViewModel = viewModel()
) {
    // Только контракты (CREATED + AUTO_RENEW) — отсортированы ASC по weekStart.
    // PAYMENT/TERMINATED/RETURNED — транзакции, не показываются на этом экране.
    val contracts by contractHistoryViewModel.contractsForRenter(renter.id).collectAsStateWithLifecycle()
    // Полные списки арендаторов и скутеров — для выпадающего поиска в диалоге
    // редактирования контракта (пользователь выбирает арендатора/скутер, всё
    // остальное автозаполняется).
    val allRenters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val allScooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Toggle: 0 = Контракты, 1 = Транзакции ──────────────────────────
    var selectedTab by remember { mutableStateOf(0) }

    var selectedContracts by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var editingContract by remember { mutableStateOf<ContractHistoryEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var generatingPdfFor by remember { mutableStateOf<Int?>(null) }
    var generatingUnlimitedPdf by remember { mutableStateOf(false) }

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
                    // ── PDF: договор с НЕОГРАНИЧЕННЫМ сроком действия ──────
                    // Эта кнопка — единственная, которая остаётся для PDF-договора
                    // арендатора. Она формирует PDF, в котором прямо указано, что
                    // договор действует на неограниченный срок, пока арендатор не
                    // решит его расторгнуть. Все остальные PDF-кнопки (на странице
                    // «Kontraktlar» и в истории контрактов скутера) удалены.
                    IconButton(
                        onClick = {
                            generatingUnlimitedPdf = true
                            scope.launch {
                                val uri = contractHistoryViewModel.generateUnlimitedContractPdf(renter.id)
                                generatingUnlimitedPdf = false
                                if (uri != null) {
                                    val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "PDF-ni ko'rish"))
                                    Toast.makeText(
                                        context,
                                        "PDF saqlandi: Documents/ScooterContracts/",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(context, "PDF yaratib bo'lmadi", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !generatingUnlimitedPdf
                    ) {
                        if (generatingUnlimitedPdf) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = ClaudeAccent
                            )
                        } else {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = "Cheksiz muddatli kontrakt PDF",
                                tint = StatusOverdue
                            )
                        }
                    }
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

            // ── Переключатель «Контракты» / «Транзакции» ────────────────
            // Две кнопки над таблицей. Активная — залита акцентным цветом.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleTabButton(
                    label = "Kontraktlar",
                    icon = Icons.Default.Description,
                    isSelected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                ToggleTabButton(
                    label = "Tranzaksiyalar",
                    icon = Icons.Default.Payments,
                    isSelected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }

            // ── Unified search ───────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = if (selectedTab == 0) "Kontrakt qidirish..."
                               else "Tranzaksiya qidirish...",
                onCalendarClick = null,
                onFilterClick = null
            )

            if (selectedTab == 0) {
                // ── ВКЛАДКА «КОНТРАКТЫ» ─────────────────────────────────────
                // ── Панель действий — ВСЕГДА ВИДНА (Task 3) ────────────────────
                // Кнопки Yaratish / Tahrirlash / O'chir всегда присутствуют.
                // Yaratish всегда активна. Tahrirlash активна только когда выбран
                // ровно 1 контракт. O'chir активна когда выбрано ≥1. Текст
                // "X ta tanlandi" убран по просьбе пользователя.
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
                    enabled = selectedContracts.size == 1,
                    onClick = {
                        // Находим выбранный контракт и открываем диалог редактирования
                        val id = selectedContracts.first()
                        editingContract = contracts.firstOrNull { it.id == id }
                    }
                )
                DangerButton(
                    label = "O'chir",
                    icon = Icons.Default.Delete,
                    enabled = selectedContracts.isNotEmpty(),
                    onClick = { showDeleteConfirm = true }
                )
                Spacer(Modifier.weight(1f))
            }

            // ── Заголовок таблицы — только иконки ──────────────────────
            // Колонка «Amal» (PDF-кнопка) удалена — PDF-договор теперь
            // формируется одной кнопкой в TopAppBar экрана (cheksiz muddatli).
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
                    NonSortableHeaderCell(Icons.Default.DateRange, 1.8f, "Muddat (hafta)")
                    NonSortableHeaderCell(Icons.Default.Payments,  1.0f, "Summa")
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
                            Column(modifier = Modifier.weight(1.8f)) {
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
                                modifier = Modifier.weight(1.0f),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.isPaid) StatusOk else StatusOverdue,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                            // PDF-кнопка строки удалена — теперь PDF-договор
                            // формируется одной кнопкой в TopAppBar (cheksiz
                            // muddatli — на неограниченный срок).
                        }
                    }
                }
            }
            } else {
                // ── ВКЛАДКА «ТРАНЗАКЦИИ» ─────────────────────────────────────
                RenterTransactionListSection(
                    renter = renter,
                    allScooters = allScooters,
                    transactionViewModel = transactionViewModel,
                    contractHistoryViewModel = contractHistoryViewModel
                )
            }
        }
    }

    // ── Диалог редактирования контракта ─────────────────────────────────
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

    // ── Диалог создания контракта вручную ───────────────────────────────
    if (showCreateDialog) {
        CreateContractDialog(
            renter = renter,
            defaultAmount = SettingsRepository(context).weeklyPrice
                .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE },
            allRenters = allRenters,
            allScooters = allScooters,
            onDismiss = { showCreateDialog = false },
            onCreate = { weekStart, weekEnd, amount, isPaid, notes ->
                // Обратная совместимость — не используется в новой версии диалога,
                // но оставлен как fallback на случай, если кто-то вызовет старый путь.
                contractHistoryViewModel.createManualContract(
                    renter = renter,
                    weekStart = weekStart,
                    weekEnd = weekEnd,
                    amount = amount,
                    isPaid = isPaid,
                    notes = notes
                )
                Toast.makeText(context, "Kontrakt yaratildi", Toast.LENGTH_SHORT).show()
                showCreateDialog = false
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditContractDialog(
    entry: ContractHistoryEntry,
    allRenters: List<Renter>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onSave: (ContractHistoryEntry) -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val dayMs = 24L * 60 * 60 * 1000

    // ── Редактируемые поля (берём начальные значения из entry) ────────────
    var amount by remember { mutableStateOf(entry.amount.toString()) }
    var notes by remember { mutableStateOf(entry.notes ?: "") }
    var isPaid by remember { mutableStateOf(entry.isPaid) }
    var weekStart by remember { mutableStateOf(entry.weekStart ?: System.currentTimeMillis()) }
    var weekEnd by remember { mutableStateOf(entry.weekEnd ?: (System.currentTimeMillis() + 7 * dayMs)) }

    // Реквизиты арендатора (для PDF)
    var renterName by remember { mutableStateOf(entry.renterName) }
    var renterPhone by remember { mutableStateOf(entry.renterPhone) }
    var passportData by remember { mutableStateOf(entry.passportData) }
    var address by remember { mutableStateOf(entry.address) }
    var pinfl by remember { mutableStateOf(entry.pinfl) }

    // Реквизиты скутера (для PDF)
    var scooterName by remember { mutableStateOf(entry.scooterName ?: "") }
    var vinNumber by remember { mutableStateOf(entry.vinNumber) }
    var engineNumber by remember { mutableStateOf(entry.engineNumber) }
    var scooterSerialNumber by remember { mutableStateOf(entry.scooterSerialNumber) }
    var batteryId1 by remember { mutableStateOf(entry.batteryId1) }
    var batteryId2 by remember { mutableStateOf(entry.batteryId2) }
    var additionalInfo by remember { mutableStateOf(entry.additionalInfo) }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val startPickerState = rememberDatePickerState(initialSelectedDateMillis = weekStart)
    val endPickerState = rememberDatePickerState(initialSelectedDateMillis = weekEnd)

    // ── Выпадающие списки арендаторов и скутеров ─────────────────────────
    // При выборе арендатора/скутера из списка все его реквизиты автоматически
    // подтягиваются в соответствующие поля. Пользователь может их
    // доредактировать вручную после выбора.
    var expandedRenter by remember { mutableStateOf(false) }
    var expandedScooter by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

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
                Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("Kontrakt #${entry.id}", style = MaterialTheme.typography.titleLarge)
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
                // ── Метаданные (read-only) ──────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ClaudeBackground),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoRow("Sana", dateFmt.format(Date(entry.timestamp)))
                        InfoRow("Tur", contractTypeLabel(entry.type))
                    }
                }

                // ── Секция: Арендатор (с выпадающим списком) ────────────
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
                                    // Автозаполнение всех полей арендатора из выбранной записи
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    passportData = r.passportData
                                    address = r.address
                                    pinfl = r.pinfl
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

                // ── Секция: Скутер (с выпадающим списком) ───────────────
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
                                    // Автозаполнение всех полей скутера из выбранной записи
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

                // ── Секция: Контракт ────────────────────────────────────
                Text("Kontrakt", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                // Дата начала
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
                // Дата конца
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
                // Сумма
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                // Статус оплаты
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
                // Примечание
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
                    onSave(entry.copy(
                        amount = newAmount,
                        notes = notes.ifBlank { null },
                        isPaid = isPaid,
                        weekStart = weekStart,
                        weekEnd = weekEnd,
                        renterName = renterName,
                        renterPhone = renterPhone,
                        scooterName = scooterName.ifBlank { null },
                        passportData = passportData,
                        address = address,
                        pinfl = pinfl,
                        vinNumber = vinNumber,
                        engineNumber = engineNumber,
                        scooterSerialNumber = scooterSerialNumber,
                        batteryId1 = batteryId1,
                        batteryId2 = batteryId2,
                        additionalInfo = additionalInfo
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

/**
 * Диалог ручного создания контракта с экрана истории контрактов.
 *
 * Позволяет задать:
 *   • Реквизиты арендатора (имя, телефон, паспорт, адрес, JSHSHIR)
 *     с выпадающим списком всех арендаторов — при выборе поля
 *     автозаполняются.
 *   • Реквизиты скутера (имя, VIN, двигатель, ID, аккумы 1/2, доп. инфо)
 *     с выпадающим списком всех скутеров — при выборе поля
 *     автозаполняются.
 *   • Дату начала недели (по умолчанию — сегодня)
 *   • Дату конца недели (по умолчанию — начало + 7 дней)
 *   • Сумму (по умолчанию — weeklyPrice из настроек)
 *   • Статус: To'langan (зелёный) / To'lanmagan (красный)
 *   • Примечание (опционально)
 *
 * Структура полностью повторяет EditContractDialog: три секции
 * Mijoz / Skuter / Kontrakt. При выборе арендатора или скутера из
 * выпадающего списка все связанные поля автозаполняются, но пользователь
 * может их доредактировать вручную перед созданием контракта.
 *
 * Баланс арендатора НЕ меняется — пользователь может управлять балансом
 * через кнопку "To'lov" на основной таблице.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContractDialog(
    renter: Renter,
    defaultAmount: Double,
    allRenters: List<Renter>,
    allScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onCreate: (weekStart: Long, weekEnd: Long, amount: Double, isPaid: Boolean, notes: String?) -> Unit,
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

    // Начало — сегодня (обрезаем до начала дня)
    val initialStart = (now / dayMs) * dayMs
    var weekStart by remember { mutableStateOf(initialStart) }
    var weekEnd by remember { mutableStateOf(initialStart + 7 * dayMs) }
    var amount by remember { mutableStateOf(defaultAmount.toLong().toString()) }
    var isPaid by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    // ── Реквизиты арендатора (предзаполнены из renter, но редактируемы) ──
    var selectedRenterId by remember { mutableStateOf(renter.id) }
    var renterName by remember { mutableStateOf(renter.name) }
    var renterPhone by remember { mutableStateOf(renter.phoneNumber) }
    var passportData by remember { mutableStateOf(renter.passportData) }
    var address by remember { mutableStateOf(renter.address) }
    var pinfl by remember { mutableStateOf(renter.pinfl) }

    // ── Реквизиты скутера (предзаполнены из renter.scooterName, но редактируемы) ──
    var selectedScooterId by remember { mutableStateOf(renter.scooterId) }
    var scooterName by remember { mutableStateOf(renter.scooterName ?: "") }
    var vinNumber by remember { mutableStateOf("") }
    var engineNumber by remember { mutableStateOf("") }
    var scooterSerialNumber by remember { mutableStateOf("") }
    var batteryId1 by remember { mutableStateOf("") }
    var batteryId2 by remember { mutableStateOf("") }
    var additionalInfo by remember { mutableStateOf("") }

    // ── Выпадающие списки арендаторов и скутеров ─────────────────────────
    // При выборе арендатора/скутера из списка все их реквизиты автоматически
    // подтягиваются в соответствующие поля. Пользователь может их
    // доредактировать вручную после выбора.
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
                    label = "Tanlash",
                    icon = Icons.Default.Check,
                    onClick = {
                        startPickerState.selectedDateMillis?.let {
                            weekStart = it
                            // Если конец оказался раньше начала — сдвигаем конец на +7 дней от нового начала
                            if (weekEnd <= weekStart) weekEnd = weekStart + 7 * dayMs
                        }
                        showStartPicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = { showStartPicker = false }
                )
            }
        ) {
            DatePicker(state = startPickerState)
        }
    }

    if (showEndPicker) {
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash",
                    icon = Icons.Default.Check,
                    onClick = {
                        endPickerState.selectedDateMillis?.let { weekEnd = it }
                        showEndPicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = { showEndPicker = false }
                )
            }
        ) {
            DatePicker(state = endPickerState)
        }
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
                // ── Секция: Арендатор (с выпадающим списком) ────────────
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
                                    // Автозаполнение всех полей арендатора из выбранной записи
                                    selectedRenterId = r.id
                                    renterName = r.name
                                    renterPhone = r.phoneNumber
                                    passportData = r.passportData
                                    address = r.address
                                    pinfl = r.pinfl
                                    // Также подтягиваем скутер этого арендатора, если есть
                                    if (r.scooterId != null && r.scooterName != null) {
                                        selectedScooterId = r.scooterId
                                        scooterName = r.scooterName ?: ""
                                        // Поля скутера (vin и т.д.) будут заполнены из БД
                                        // при создании контракта через createManualContractWithOverrides.
                                        // Сбрасываем локальные поля, чтобы подставились данные БД.
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

                // ── Секция: Скутер (с выпадающим списком) ───────────────
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
                                    // Автозаполнение всех полей скутера из выбранной записи
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

                // ── Секция: Контракт ────────────────────────────────────
                Text("Kontrakt", style = MaterialTheme.typography.titleSmall,
                    color = ClaudeAccent, fontWeight = FontWeight.SemiBold)

                // Дата начала
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

                // Дата конца
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

                // Сумма
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Summa (UZS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // Статус оплаты
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isPaid,
                        onClick = { isPaid = true },
                        label = { Text("To'langan") },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(StatusOk, CircleShape)
                            )
                        }
                    )
                    FilterChip(
                        selected = !isPaid,
                        onClick = { isPaid = false },
                        label = { Text("To'lanmagan") },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(StatusOverdue, CircleShape)
                            )
                        }
                    )
                }

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
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull() ?: defaultAmount
                    // Создаём контракт с полным набором переопределённых полей —
                    // это гарантирует, что в PDF попадут именно те данные,
                    // которые пользователь видел в диалоге.
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
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    transactionViewModel: TransactionViewModel = viewModel()
) {
    val contracts by contractHistoryViewModel.forScooter(scooter.name).collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Toggle: 0 = Контракты, 1 = Транзакции ──────────────────────────
    var selectedTab by remember { mutableStateOf(0) }

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
            // Цвет рамки = статус скутера (зелёный = в базе, красный = в аренде).
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ClaudeCard),
                border = BorderStroke(
                    1.5.dp,
                    if (activeRenter != null) StatusOverdue else StatusOk
                )
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

            // ── Переключатель «Контракты» / «Транзакции» ────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleTabButton(
                    label = "Kontraktlar",
                    icon = Icons.Default.Description,
                    isSelected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                ToggleTabButton(
                    label = "Tranzaksiyalar",
                    icon = Icons.Default.Payments,
                    isSelected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }

            // ── Unified search bar ────────────────────────────────────
            UnifiedSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = if (selectedTab == 0) "Kontrakt qidirish..."
                               else "Tranzaksiya qidirish...",
                onCalendarClick = null,
                onFilterClick = null
            )

            if (selectedTab == 0) {
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
                            // Цветная рамка статуса (зелёная = оплата, красная
                            // = долг/просрочка, золотая = создание и т.д.) —
                            // единое правило для всех страниц с контрактами.
                            border = BorderStroke(1.5.dp, typeColor)
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
                                    // PDF-кнопка удалена — PDF-договор теперь
                                    // формируется только кнопкой в TopAppBar на
                                    // странице истории контрактов арендатора
                                    // (cheksiz muddatli — на неограниченный срок).
                                }
                            }
                        }
                    }
                }
            }
            } else {
                // ── ВКЛАДКА «ТРАНЗАКЦИИ» ─────────────────────────────────────
                ScooterTransactionListSection(
                    scooter = scooter,
                    renters = renters,
                    transactionViewModel = transactionViewModel,
                    contractHistoryViewModel = contractHistoryViewModel
                )
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Кнопка-переключатель вкладок «Контракты» / «Транзакции».
 *
 * Активная кнопка [isSelected] залита акцентным цветом (ClaudeAccent) и
 * содержит белый текст/иконку. Неактивная — прозрачный фон, серый текст.
 * Ширина — weight(1f), чтобы две кнопки поровну делили Row.
 *
 * Расширение RowScope — это даёт доступ к `Modifier.weight(1f)`.
 */
@Composable
internal fun RowScope.ToggleTabButton(
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
                tint = if (isSelected) Color.White else ClaudeTextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color.White else ClaudeTextSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

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
