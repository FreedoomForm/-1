package com.example

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.example.ui.RenterViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Список типов виджетов отчётов. Используется для фильтра (показ/скрытие)
 * и для определения порядка отображения.
 */
enum class ReportWidgetType(val id: String, val title: String) {
    NET_PROFIT("net_profit", "Sof foyda"),
    EXPECTED_NEXT_MONTH("expected", "Keyingi oy kutilayotgan"),
    ACTIVE_RENTERS_COUNT("active_renters", "Faol ijarachilar"),
    SCOOTER_OCCUPANCY("occupancy", "Skuter bandligi"),
    IDLE_DAYS("idle_days", "Bo'sh turgan kunlar"),
    ROI("roi", "Investitsiya o'zini oqlashi"),
    PAYMENT_SUM("payment_sum", "Jami to'lovlar"),
    OVERDUE_RENTERS("overdue", "Qarzdor ijarachilar"),
    SCOOTERS_IN_BASE("in_base", "Bo'sh skuterlar"),
    CONTRACTS_COUNT("contracts", "Kontraktlar soni"),
    TOP_RENTERS("top_renters", "Eng yaxshi ijarachilar"),
    TOP_SCOOTERS("top_scooters", "Eng daromadli skuterlar")
}

/**
 * Страница «Отчёты» — дашборд с инфографикой.
 *
 * Виджеты можно перетаскивать вверх/вниз вручную (кнопки ▲▼ в каждом виджете),
 * при этом их порядковый номер автоматически обновляется. Все виджеты
 * расположены вертикально — для удобства на телефоне.
 *
 * Поисковая строка + фильтр (показ/скрытие виджетов) + календарь
 * (фильтр данных по периоду) — как на странице Ijarachilar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val renters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val scooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val history by contractHistoryViewModel.history.collectAsStateWithLifecycle()

    val settings = remember { SettingsRepository(context) }
    val weeklyPrice = settings.weeklyPrice.let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE }
    val monthlyPrice = settings.monthlyPrice.let { if (it > 0) it else SettingsRepository.DEFAULT_MONTHLY_PRICE }
    val scooterPriceUsd = settings.scooterPriceUsd.let { if (it > 0) it else SettingsRepository.DEFAULT_SCOOTER_PRICE_USD }
    val usdToUzs = settings.usdToUzsRate.let { if (it > 0) it else SettingsRepository.DEFAULT_USD_TO_UZS_RATE }

    // ── Сохранённый порядок виджетов (в SharedPreferences) ────────────
    // Пользователь может перетаскивать виджеты; порядок сохраняется между
    // запусками приложения.
    val orderPrefs = remember { context.getSharedPreferences("report_widget_order", android.content.Context.MODE_PRIVATE) }
    var widgetOrder by remember {
        mutableStateOf(
            orderPrefs.getString("order", null)
                ?.split(",")
                ?.filter { id -> ReportWidgetType.values().any { it.id == id } }
                ?.mapNotNull { id -> ReportWidgetType.values().find { it.id == id } }
                ?: ReportWidgetType.values().toList()
        )
    }
    // ── Видимость виджетов (из фильтра) ───────────────────────────────
    var hiddenWidgets by remember {
        mutableStateOf(
            orderPrefs.getStringSet("hidden", emptySet()) ?: emptySet()
        )
    }
    var showFilterPanel by remember { mutableStateOf(false) }

    fun saveOrder(newOrder: List<ReportWidgetType>) {
        widgetOrder = newOrder
        orderPrefs.edit().putString("order", newOrder.joinToString(",") { it.id }).apply()
    }
    fun saveHidden(newHidden: Set<String>) {
        hiddenWidgets = newHidden
        orderPrefs.edit().putStringSet("hidden", newHidden).apply()
    }

    // ── Календарь (период отчёта) ─────────────────────────────────────
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    // ── Поиск ─────────────────────────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }

    // ── Расчёт данных за выбранный период ─────────────────────────────
    val startMillis = dateRangePickerState.selectedStartDateMillis
    val endMillis = dateRangePickerState.selectedEndDateMillis
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    val weekMs = 7L * dayMs
    val monthMs = 30L * dayMs

    // Все транзакции типа PAYMENT в выбранном периоде
    val paymentsInRange = remember(history, startMillis, endMillis) {
        history.filter { entry ->
            entry.type == ContractHistoryEntry.TYPE_PAYMENT &&
            (startMillis == null || entry.timestamp >= startMillis) &&
            (endMillis == null || entry.timestamp <= endMillis)
        }
    }
    // Все контракты (CREATED/AUTO_RENEW) в выбранном периоде
    val contractsInRange = remember(history, startMillis, endMillis) {
        history.filter { entry ->
            (entry.type == ContractHistoryEntry.TYPE_CREATED || entry.type == ContractHistoryEntry.TYPE_AUTO_RENEW) &&
            (startMillis == null || entry.timestamp >= startMillis) &&
            (endMillis == null || entry.timestamp <= endMillis)
        }
    }

    val totalPayments = paymentsInRange.sumOf { it.amount }
    val totalContracts = contractsInRange.size
    val activeRenters = renters.count { !it.isReturned }
    val overdueRenters = renters.count { !it.isReturned && it.balance < 0 }
    val scootersInBase = scooters.count { s -> renters.none { it.scooterId == s.id && !it.isReturned } }
    val scootersRented = scooters.count { s -> renters.any { it.scooterId == s.id && !it.isReturned } }

    // ── Метрики занятости и простоя ───────────────────────────────────
    // Средний процент занятости = (среднее число арендованных / всего) × 100
    val occupancyPct = if (scooters.isNotEmpty()) {
        (scootersRented.toDouble() / scooters.size * 100).toInt()
    } else 0

    // Среднее число дней простоя за период (30 дней по умолчанию)
    val periodDays = ((endMillis ?: now) - (startMillis ?: (now - monthMs))) / dayMs
    val effectivePeriodDays = periodDays.coerceIn(1, 365).toInt()
    val avgIdleDaysPerScooter = if (scooters.isNotEmpty()) {
        // Простои ≈ periodDays × (1 - occupancyPct/100)
        (effectivePeriodDays * (100 - occupancyPct) / 100.0).toInt()
    } else 0

    // ── ROI ───────────────────────────────────────────────────────────
    val totalInvestmentUzs = scooters.size * scooterPriceUsd * usdToUzs
    val roiMultiple = if (totalInvestmentUzs > 0) totalPayments / totalInvestmentUzs else 0.0
    val roiPercent = roiMultiple * 100

    // ── Ожидаемые доходы в следующем месяце ───────────────────────────
    // Базируем на активных арендаторах × weeklyPrice × 4 недели
    val expectedNextMonth = activeRenters * weeklyPrice * 4

    // ── Топ арендаторов (по сумме оплат) ──────────────────────────────
    val topRenters = remember(paymentsInRange) {
        paymentsInRange
            .groupBy { it.renterName }
            .map { (name, payments) -> name to payments.sumOf { it.amount } }
            .sortedByDescending { it.second }
            .take(5)
    }
    val topScooters = remember(paymentsInRange) {
        paymentsInRange
            .groupBy { it.scooterName ?: "—" }
            .map { (name, payments) -> name to payments.sumOf { it.amount } }
            .sortedByDescending { it.second }
            .take(5)
    }

    // ── Фильтрация виджетов по поисковому запросу ─────────────────────
    // Если ввели текст в поиск — показываем только виджеты, чьё название
    // или основная метрика содержит этот текст.
    val visibleWidgets = remember(widgetOrder, hiddenWidgets, searchQuery) {
        widgetOrder
            .filter { it.id !in hiddenWidgets }
            .filter { wt ->
                if (searchQuery.isBlank()) true
                else wt.title.contains(searchQuery, ignoreCase = true)
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Поисковая строка + фильтр + календарь ──────────────────────
        UnifiedSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Vidjet qidirish...",
            onCalendarClick = { showDateRangePicker = true },
            calendarActive = dateRangePickerState.selectedStartDateMillis != null,
            onFilterClick = { showFilterPanel = true },
            filterActive = hiddenWidgets.isNotEmpty()
        )

        // ── Боковая панель фильтра (показ/скрытие виджетов) ────────────
        if (showFilterPanel) {
            AlertDialog(
                onDismissRequest = { showFilterPanel = false },
                title = { Text("Vidjetlarni ko'rsatish") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        ReportWidgetType.values().forEach { wt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = wt.id !in hiddenWidgets,
                                    onCheckedChange = { checked ->
                                        saveHidden(
                                            if (checked) hiddenWidgets - wt.id
                                            else hiddenWidgets + wt.id
                                        )
                                    }
                                )
                                Text(wt.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFilterPanel = false }) {
                        Text("Yopish")
                    }
                }
            )
        }

        // ── Индикатор выбранного периода ──────────────────────────────
        if (startMillis != null) {
            Surface(
                color = ClaudeAccentBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Davr: ${dateFmt.format(Date(startMillis))}${
                        if (endMillis != null) " → ${dateFmt.format(Date(endMillis))}" else " → bugun"
                    }",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeText
                )
            }
        }

        // ── Список виджетов ───────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(visibleWidgets) { index, widgetType ->
                ReportWidgetCard(
                    number = index + 1,
                    type = widgetType,
                    onMoveUp = {
                        if (index > 0) {
                            val newList = widgetOrder.toMutableList()
                            val currentIdx = newList.indexOf(widgetType)
                            if (currentIdx > 0) {
                                val tmp = newList[currentIdx - 1]
                                newList[currentIdx - 1] = widgetType
                                newList[currentIdx] = tmp
                                saveOrder(newList)
                            }
                        }
                    },
                    onMoveDown = {
                        val newList = widgetOrder.toMutableList()
                        val currentIdx = newList.indexOf(widgetType)
                        if (currentIdx >= 0 && currentIdx < newList.size - 1) {
                            val tmp = newList[currentIdx + 1]
                            newList[currentIdx + 1] = widgetType
                            newList[currentIdx] = tmp
                            saveOrder(newList)
                        }
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < visibleWidgets.size - 1,
                    data = ReportWidgetData(
                        totalPayments = totalPayments,
                        totalContracts = totalContracts,
                        activeRenters = activeRenters,
                        overdueRenters = overdueRenters,
                        scootersInBase = scootersInBase,
                        scootersRented = scootersRented,
                        totalScooters = scooters.size,
                        occupancyPct = occupancyPct,
                        avgIdleDays = avgIdleDaysPerScooter,
                        roiMultiple = roiMultiple,
                        roiPercent = roiPercent,
                        totalInvestmentUzs = totalInvestmentUzs,
                        scooterPriceUsd = scooterPriceUsd,
                        usdToUzs = usdToUzs,
                        expectedNextMonth = expectedNextMonth,
                        weeklyPrice = weeklyPrice,
                        monthlyPrice = monthlyPrice,
                        topRenters = topRenters,
                        topScooters = topScooters
                    )
                )
            }
        }
    }

    // ── Календарь периода ─────────────────────────────────────────────
    if (showDateRangePicker) {
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text("Tanlash")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dateRangePickerState.setSelection(null, null)
                    showDateRangePicker = false
                }) {
                    Text("Tozalash")
                }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.weight(1f),
                title = { Text("Davrni tanlang", modifier = Modifier.padding(16.dp)) },
                headline = { Text("Otchet davri", modifier = Modifier.padding(16.dp)) }
            )
        }
    }
}

/** Данные, передаваемые в каждый виджет отчёта. */
data class ReportWidgetData(
    val totalPayments: Double,
    val totalContracts: Int,
    val activeRenters: Int,
    val overdueRenters: Int,
    val scootersInBase: Int,
    val scootersRented: Int,
    val totalScooters: Int,
    val occupancyPct: Int,
    val avgIdleDays: Int,
    val roiMultiple: Double,
    val roiPercent: Double,
    val totalInvestmentUzs: Double,
    val scooterPriceUsd: Double,
    val usdToUzs: Double,
    val expectedNextMonth: Double,
    val weeklyPrice: Double,
    val monthlyPrice: Double,
    val topRenters: List<Pair<String, Double>>,
    val topScooters: List<Pair<String, Double>>
)

/**
 * Карточка виджета с номером, кнопками перемещения ▲▼ и контентом.
 */
@Composable
private fun ReportWidgetCard(
    number: Int,
    type: ReportWidgetType,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    data: ReportWidgetData
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ClaudeCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Шапка: номер + название + кнопки перемещения ───────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кружок с номером виджета
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(ClaudeAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$number",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    type.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeText,
                    modifier = Modifier.weight(1f)
                )
                // Кнопки перемещения
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Yuqoriga",
                        tint = if (canMoveUp) ClaudeAccent else ClaudeTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Pastga",
                        tint = if (canMoveDown) ClaudeAccent else ClaudeTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Контент виджета ─────────────────────────────────────────
            when (type) {
                ReportWidgetType.NET_PROFIT -> NetProfitWidget(data)
                ReportWidgetType.EXPECTED_NEXT_MONTH -> ExpectedNextMonthWidget(data)
                ReportWidgetType.ACTIVE_RENTERS_COUNT -> ActiveRentersCountWidget(data)
                ReportWidgetType.SCOOTER_OCCUPANCY -> OccupancyWidget(data)
                ReportWidgetType.IDLE_DAYS -> IdleDaysWidget(data)
                ReportWidgetType.ROI -> RoiWidget(data)
                ReportWidgetType.PAYMENT_SUM -> PaymentSumWidget(data)
                ReportWidgetType.OVERDUE_RENTERS -> OverdueRentersWidget(data)
                ReportWidgetType.SCOOTERS_IN_BASE -> ScootersInBaseWidget(data)
                ReportWidgetType.CONTRACTS_COUNT -> ContractsCountWidget(data)
                ReportWidgetType.TOP_RENTERS -> TopRentersWidget(data)
                ReportWidgetType.TOP_SCOOTERS -> TopScootersWidget(data)
            }
        }
    }
}

private fun Long.fmtUzs(): String {
    return "%,d UZS".format(this)
}

@Composable
private fun NetProfitWidget(d: ReportWidgetData) {
    // Условно считаем «чистую прибыль» как 70% от платежей (30% — расходы:
    // электричество, ремонт, SMS, амортизация). Можно вынести в настройки.
    val netProfit = d.totalPayments * 0.70
    BigStatCard(
        value = netProfit.toLong().fmtUzs(),
        subtitle = "Jami to'lovlar: ${d.totalPayments.toLong().fmtUzs()}",
        icon = Icons.Default.AccountBalanceWallet,
        gradient = listOf(StatusOk, ClaudeTeal)
    )
}

@Composable
private fun ExpectedNextMonthWidget(d: ReportWidgetData) {
    BigStatCard(
        value = d.expectedNextMonth.toLong().fmtUzs(),
        subtitle = "${d.activeRenters} faol ijarachi × ${d.weeklyPrice.toLong()} UZS × 4 hafta",
        icon = Icons.Default.TrendingUp,
        gradient = listOf(ClaudeGold, ClaudeAccent)
    )
}

@Composable
private fun ActiveRentersCountWidget(d: ReportWidgetData) {
    TwoStatRow(
        first = d.activeRenters.toString() to "Faol",
        second = d.overdueRenters.toString() to "Qarzdor",
        firstColor = StatusOk,
        secondColor = StatusOverdue
    )
}

@Composable
private fun OccupancyWidget(d: ReportWidgetData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.occupancyPct}%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (d.occupancyPct >= 70) StatusOk else if (d.occupancyPct >= 40) ClaudeGold else StatusOverdue
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${d.scootersRented} / ${d.totalScooters} skuter ijarada",
                style = MaterialTheme.typography.bodyMedium,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        // Прогресс-бар занятости
        LinearProgressIndicator(
            progress = { d.occupancyPct / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = if (d.occupancyPct >= 70) StatusOk else if (d.occupancyPct >= 40) ClaudeGold else StatusOverdue,
            trackColor = ClaudeDivider
        )
    }
}

@Composable
private fun IdleDaysWidget(d: ReportWidgetData) {
    BigStatCard(
        value = "${d.avgIdleDays} kun",
        subtitle = "O'rtacha har bir skuter bo'sh turgan kunlar soni",
        icon = Icons.Default.PauseCircle,
        gradient = listOf(ClaudeGold, StatusOverdue)
    )
}

@Composable
private fun RoiWidget(d: ReportWidgetData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%.2f×".format(d.roiMultiple),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (d.roiMultiple >= 1) StatusOk else StatusOverdue
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "(${d.roiPercent.toInt()}%)",
                style = MaterialTheme.typography.titleMedium,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Investitsiya: ${d.totalInvestmentUzs.toLong().fmtUzs()}\n" +
            "Skuter narxi: \$${d.scooterPriceUsd.toInt()} × ${d.totalScooters} dona\n" +
            "Kurs: 1 USD = ${d.usdToUzs.toLong()} UZS",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        if (d.roiMultiple >= 1) {
            Spacer(Modifier.height(8.dp))
            Text(
                "✓ Biznes o'zini oqladi — investitsiya qaytarib olindi",
                style = MaterialTheme.typography.labelMedium,
                color = StatusOk,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PaymentSumWidget(d: ReportWidgetData) {
    BigStatCard(
        value = d.totalPayments.toLong().fmtUzs(),
        subtitle = "Tanlangan davrdagi jami to'lovlar",
        icon = Icons.Default.Payments,
        gradient = listOf(StatusOk, ClaudeTeal)
    )
}

@Composable
private fun OverdueRentersWidget(d: ReportWidgetData) {
    BigStatCard(
        value = d.overdueRenters.toString(),
        subtitle = "To'lovni kechiktirgan ijarachilar",
        icon = Icons.Default.Warning,
        gradient = listOf(StatusOverdue, ClaudeAccentDark)
    )
}

@Composable
private fun ScootersInBaseWidget(d: ReportWidgetData) {
    TwoStatRow(
        first = d.scootersInBase.toString() to "Bazada",
        second = d.scootersRented.toString() to "Ijarada",
        firstColor = StatusOk,
        secondColor = StatusOverdue
    )
}

@Composable
private fun ContractsCountWidget(d: ReportWidgetData) {
    BigStatCard(
        value = d.totalContracts.toString(),
        subtitle = "Tanlangan davrda yaratilgan kontraktlar",
        icon = Icons.Default.Description,
        gradient = listOf(ClaudeAccent, ClaudeAccentDark)
    )
}

@Composable
private fun TopRentersWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Eng ko'p to'lov qilgan ijarachilar:",
            style = MaterialTheme.typography.labelMedium,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(8.dp))
        if (d.topRenters.isEmpty()) {
            Text("Hozircha ma'lumot yo'q", color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            d.topRenters.forEachIndexed { i, (name, sum) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(ClaudeAccentBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ClaudeAccent)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        sum.toLong().fmtUzs(),
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusOk,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TopScootersWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Eng daromadli skuterlar:",
            style = MaterialTheme.typography.labelMedium,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(8.dp))
        if (d.topScooters.isEmpty()) {
            Text("Hozircha ma'lumot yo'q", color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            d.topScooters.forEachIndexed { i, (name, sum) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(ClaudeAccentBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ClaudeAccent)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        sum.toLong().fmtUzs(),
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusOk,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Универсальная карточка с крупным числом и иконкой на градиентном фоне.
 */
@Composable
private fun BigStatCard(
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: List<Color>
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(gradient))
            .padding(16.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.CenterEnd)
        )
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun TwoStatRow(
    first: Pair<String, String>,
    second: Pair<String, String>,
    firstColor: Color,
    secondColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatBox(
            value = first.first,
            label = first.second,
            color = firstColor,
            modifier = Modifier.weight(1f)
        )
        StatBox(
            value = second.first,
            label = second.second,
            color = secondColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatBox(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── LazyColumn itemsIndexed не входит в стандартный import — добавим ─
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    items: List<ReportWidgetType>,
    itemContent: @Composable (index: Int, item: ReportWidgetType) -> Unit
) {
    items(items.size) { index ->
        itemContent(index, items[index])
    }
}
