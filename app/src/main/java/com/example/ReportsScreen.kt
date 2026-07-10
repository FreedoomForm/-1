package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.ui.components.*
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ============================================================================
   Страница «Отчёты» — дашборд для бизнеса и стат.
   ----------------------------------------------------------------------------
   16 виджетов с РАЗНОЙ визуализацией:
     1. NET_PROFIT       — KPI-карточка + спарклайн по неделям
     2. PAYMENT_SUM      — столбчатая диаграмма (8 недель)
     3. WEEKLY_TREND     — линейный график динамики выручки
     4. EXPECTED_NEXT    — KPI + progress-ring (ожидаемый vs. факт)
     5. ACTIVE_RENTERS   — кольцевая диаграмма (active/overdue/returned)
     6. SCOOTER_STATUS   — кольцевая диаграмма (in base/rented)
     7. OCCUPANCY        — кольцевой прогресс-бар + stacked bar
     8. IDLE_HEATMAP     — тепловая карта простоев (4 нед × N скутеров)
     9. ROI              — радарная диаграмма (5 осей «health score»)
    10. ARPU_LTV         — KPI-карточка ARPU + LTV
    11. MRR              — KPI-карточка MRR + прогресс к цели
    12. PAYMENT_DISCIPLINE — воронка конверсии
    13. CONTRACTS_TREND  — линейный график контрактов по месяцам
    14. OVERDUE_LIST     — таблица должников с суммами
    15. TOP_RENTERS      — горизонтальная диаграмма top-5
    16. TOP_SCOOTERS     — горизонтальная диаграмма top-5
   ============================================================================ */

enum class ReportWidgetType(val id: String, val title: String) {
    NET_PROFIT("net_profit", "Sof foyda"),
    PAYMENT_SUM("payment_sum", "Haftalik to'lovlar"),
    WEEKLY_TREND("weekly_trend", "Daromad dinamikasi"),
    EXPECTED_NEXT_MONTH("expected", "Keyingi oy prognozi"),
    ACTIVE_RENTERS_COUNT("active_renters", "Ijarachilar tarkibi"),
    SCOOTER_STATUS("scooter_status", "Skuterlar holati"),
    SCOOTER_OCCUPANCY("occupancy", "Skuter bandligi"),
    IDLE_HEATMAP("idle_heatmap", "Bo'sh turgan kunlar (heatmap)"),
    ROI("roi", "Biznes salomatligi (radar)"),
    ARPU_LTV("arpu_ltv", "ARPU va LTV"),
    MRR("mrr", "Oylik takroriy daromad (MRR)"),
    PAYMENT_DISCIPLINE("discipline", "To'lov intizomi (voronka)"),
    CONTRACTS_TREND("contracts_trend", "Kontraktlar dinamikasi"),
    OVERDUE_LIST("overdue_list", "Qarzdorlar ro'yxati"),
    TOP_RENTERS("top_renters", "Eng yaxshi ijarachilar"),
    TOP_SCOOTERS("top_scooters", "Eng daromadli skuterlar")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel()
) {
    val context = LocalContext.current

    val renters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val scooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val history by contractHistoryViewModel.history.collectAsStateWithLifecycle()

    val settings = remember { SettingsRepository(context) }
    val weeklyPrice = settings.weeklyPrice.let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE }
    val monthlyPrice = settings.monthlyPrice.let { if (it > 0) it else SettingsRepository.DEFAULT_MONTHLY_PRICE }
    val scooterPriceUsd = settings.scooterPriceUsd.let { if (it > 0) it else SettingsRepository.DEFAULT_SCOOTER_PRICE_USD }
    val usdToUzs = settings.usdToUzsRate.let { if (it > 0) it else SettingsRepository.DEFAULT_USD_TO_UZS_RATE }

    // ── Сохранённый порядок виджетов ──────────────────────────────────
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
    var hiddenWidgets by remember {
        mutableStateOf(orderPrefs.getStringSet("hidden", emptySet()) ?: emptySet())
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

    var searchQuery by remember { mutableStateOf("") }

    // ── Расчёт данных за выбранный период ─────────────────────────────
    val startMillis = dateRangePickerState.selectedStartDateMillis
    val endMillis = dateRangePickerState.selectedEndDateMillis
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    val weekMs = 7L * dayMs
    val monthMs = 30L * dayMs

    val paymentsInRange = remember(history, startMillis, endMillis) {
        history.filter { entry ->
            entry.type == ContractHistoryEntry.TYPE_PAYMENT &&
            (startMillis == null || entry.timestamp >= startMillis) &&
            (endMillis == null || entry.timestamp <= endMillis)
        }
    }
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
    val returnedRenters = renters.count { it.isReturned }
    val scootersInBase = scooters.count { s -> renters.none { it.scooterId == s.id && !it.isReturned } }
    val scootersRented = scooters.count { s -> renters.any { it.scooterId == s.id && !it.isReturned } }

    // ── Серии данных для графиков ─────────────────────────────────────
    // 8 недель: суммы платежей по неделям (для BarChart и Sparkline)
    val weeklyPayments = remember(history) {
        val cal = Calendar.getInstance()
        val series = mutableListOf<Pair<String, Float>>()
        val spark = mutableListOf<Float>()
        for (i in 7 downTo 0) {
            cal.time = Date(now)
            cal.add(Calendar.DAY_OF_YEAR, -7 * i)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val weekStart = cal.timeInMillis
            val weekEnd = weekStart + weekMs
            val sum = history
                .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp in weekStart until weekEnd }
                .sumOf { it.amount }
                .toFloat()
            val label = SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(weekStart))
            series.add(label to sum)
            spark.add(sum)
        }
        series to spark
    }

    // 6 месяцев: количество контрактов (для линейного графика)
    val monthlyContracts = remember(history) {
        val cal = Calendar.getInstance()
        val series = mutableListOf<Pair<String, Float>>()
        for (i in 5 downTo 0) {
            cal.time = Date(now)
            cal.add(Calendar.MONTH, -i)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val mStart = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val mEnd = cal.timeInMillis
            val cnt = history.count {
                (it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW) &&
                it.timestamp in mStart until mEnd
            }.toFloat()
            val label = SimpleDateFormat("MMM", Locale.getDefault()).format(Date(mStart))
            series.add(label to cnt)
        }
        series
    }

    // ── Метрики занятости и простоя ───────────────────────────────────
    val occupancyPct = if (scooters.isNotEmpty()) {
        (scootersRented.toDouble() / scooters.size * 100).toInt()
    } else 0

    val periodDays = ((endMillis ?: now) - (startMillis ?: (now - monthMs))) / dayMs
    val effectivePeriodDays = periodDays.coerceIn(1, 365).toInt()
    val avgIdleDaysPerScooter = if (scooters.isNotEmpty()) {
        (effectivePeriodDays * (100 - occupancyPct) / 100.0).toInt()
    } else 0

    // ── ROI ───────────────────────────────────────────────────────────
    val totalInvestmentUzs = scooters.size * scooterPriceUsd * usdToUzs
    val roiMultiple = if (totalInvestmentUzs > 0) totalPayments / totalInvestmentUzs else 0.0
    val roiPercent = roiMultiple * 100

    // ── Ожидаемые доходы в следующем месяце ───────────────────────────
    val expectedNextMonth = activeRenters * weeklyPrice * 4

    // ── Новые метрики для бизнес-отчётности ───────────────────────────
    // ARPU = средний доход на одного активного арендатора (за период)
    val arpu = if (activeRenters > 0) totalPayments / activeRenters else 0.0
    // LTV = ARPU × среднее число недель аренды (оценка = 8 недель)
    val avgWeeksPerRenter = 8.0
    val ltv = arpu * avgWeeksPerRenter
    // MRR = активные арендаторы × недельная ставка × 4 недели
    val mrr = activeRenters * weeklyPrice * 4
    // Целевой MRR = (всего скутеров × weeklyPrice × 4) — если бы все были в аренде
    val targetMrr = scooters.size * weeklyPrice * 4
    // Churn rate (упрощённо) = overdueRenters / activeRenters × 100%
    val churnRate = if (activeRenters > 0) (overdueRenters.toDouble() / activeRenters * 100).toInt() else 0
    // Payment discipline = % арендаторов без долга
    val paymentDiscipline = if (activeRenters > 0) ((activeRenters - overdueRenters).toDouble() / activeRenters * 100).toInt() else 0
    // Revenue per scooter (эффективность парка)
    val revenuePerScooter = if (scooters.isNotEmpty()) totalPayments / scooters.size else 0.0
    // Дельта выручки этого месяца vs. прошлого
    val calNow = Calendar.getInstance().apply { time = Date(now) }
    val thisMonthStart = (calNow.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val prevMonthStart = (calNow.clone() as Calendar).apply {
        add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val paymentsThisMonth = history
        .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp >= thisMonthStart }
        .sumOf { it.amount }
    val paymentsPrevMonth = history
        .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp in prevMonthStart until thisMonthStart }
        .sumOf { it.amount }
    val revenueDeltaPercent = if (paymentsPrevMonth > 0)
        ((paymentsThisMonth - paymentsPrevMonth) / paymentsPrevMonth * 100).toInt() else 0

    // ── Топ-5 арендаторов и скутеров ──────────────────────────────────
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

    // ── Тепловая карта простоев (4 нед × N скутеров) ──────────────────
    // Значение 0 = всегда занят, 1 = всегда простаивал.
    val heatmapData = remember(scooters, renters, history) {
        val cal = Calendar.getInstance()
        val weeksStarts = (0 until 4).map { idx ->
            cal.time = Date(now)
            cal.add(Calendar.DAY_OF_YEAR, -7 * (3 - idx))
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        val scooterNames = scooters.take(6).map { it.name }  // топ-6 скутеров для краткости
        val values = scooterNames.map { sName ->
            weeksStarts.map { wStart ->
                // Если скутер не был в аренде в эту неделю — простой
                val wEnd = wStart + weekMs
                val rented = renters.any { r ->
                    r.scooterName == sName && !r.isReturned &&
                        r.rentStartDateTimestamp < wEnd &&
                        (r.rentStartDateTimestamp + r.rentDurationDays * dayMs) > wStart
                }
                if (rented) 0f else 1f
            }
        }
        val weekLabels = weeksStarts.map { SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(it)) }
        Triple(scooterNames, weekLabels, values)
    }

    // ── Список должников (для таблицы) ────────────────────────────────
    val overdueList = remember(renters) {
        renters
            .filter { !it.isReturned && it.balance < 0 }
            .sortedBy { it.balance }  // самые большие долги сверху
            .take(8)
    }

    // ── Дельта для KPI-карточек ───────────────────────────────────────
    val sparkData = weeklyPayments.second
    val profitDelta = revenueDeltaPercent

    // ── Фильтрация виджетов по поисковому запросу ─────────────────────
    val visibleWidgets = remember(widgetOrder, hiddenWidgets, searchQuery) {
        widgetOrder
            .filter { it.id !in hiddenWidgets }
            .filter { wt ->
                if (searchQuery.isBlank()) true
                else wt.title.contains(searchQuery, ignoreCase = true)
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        UnifiedSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Vidjet qidirish...",
            onCalendarClick = { showDateRangePicker = true },
            calendarActive = dateRangePickerState.selectedStartDateMillis != null,
            onFilterClick = { showFilterPanel = true },
            filterActive = hiddenWidgets.isNotEmpty()
        )

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
                    TextButton(onClick = { showFilterPanel = false }) { Text("Yopish") }
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
            items(visibleWidgets.size) { index ->
                val widgetType = visibleWidgets[index]
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
                        returnedRenters = returnedRenters,
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
                        topScooters = topScooters,
                        weeklyPaymentsSeries = weeklyPayments.first,
                        sparkData = sparkData,
                        monthlyContractsSeries = monthlyContracts,
                        arpu = arpu,
                        ltv = ltv,
                        mrr = mrr,
                        targetMrr = targetMrr,
                        churnRate = churnRate,
                        paymentDiscipline = paymentDiscipline,
                        revenuePerScooter = revenuePerScooter,
                        revenueDeltaPercent = revenueDeltaPercent,
                        profitDeltaPercent = profitDelta,
                        overdueList = overdueList,
                        heatmapRows = heatmapData.first,
                        heatmapCols = heatmapData.second,
                        heatmapValues = heatmapData.third
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
                TextButton(onClick = { showDateRangePicker = false }) { Text("Tanlash") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dateRangePickerState.setSelection(null, null)
                    showDateRangePicker = false
                }) { Text("Tozalash") }
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
    val returnedRenters: Int,
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
    val topScooters: List<Pair<String, Double>>,
    // ── Новые поля ──
    val weeklyPaymentsSeries: List<Pair<String, Float>>,
    val sparkData: List<Float>,
    val monthlyContractsSeries: List<Pair<String, Float>>,
    val arpu: Double,
    val ltv: Double,
    val mrr: Double,
    val targetMrr: Double,
    val churnRate: Int,
    val paymentDiscipline: Int,
    val revenuePerScooter: Double,
    val revenueDeltaPercent: Int,
    val profitDeltaPercent: Int,
    val overdueList: List<Renter>,
    val heatmapRows: List<String>,
    val heatmapCols: List<String>,
    val heatmapValues: List<List<Float>>
)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(ClaudeAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$number", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    type.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeText,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "Yuqoriga",
                        tint = if (canMoveUp) ClaudeAccent else ClaudeTextSecondary,
                        modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Pastga",
                        tint = if (canMoveDown) ClaudeAccent else ClaudeTextSecondary,
                        modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            when (type) {
                ReportWidgetType.NET_PROFIT -> NetProfitWidget(data)
                ReportWidgetType.PAYMENT_SUM -> PaymentSumBarChartWidget(data)
                ReportWidgetType.WEEKLY_TREND -> WeeklyTrendLineWidget(data)
                ReportWidgetType.EXPECTED_NEXT_MONTH -> ExpectedNextMonthWidget(data)
                ReportWidgetType.ACTIVE_RENTERS_COUNT -> ActiveRentersDonutWidget(data)
                ReportWidgetType.SCOOTER_STATUS -> ScooterStatusDonutWidget(data)
                ReportWidgetType.SCOOTER_OCCUPANCY -> OccupancyRingWidget(data)
                ReportWidgetType.IDLE_HEATMAP -> IdleHeatmapWidget(data)
                ReportWidgetType.ROI -> RoiRadarWidget(data)
                ReportWidgetType.ARPU_LTV -> ArpuLtvWidget(data)
                ReportWidgetType.MRR -> MrrWidget(data)
                ReportWidgetType.PAYMENT_DISCIPLINE -> PaymentDisciplineFunnelWidget(data)
                ReportWidgetType.CONTRACTS_TREND -> ContractsTrendWidget(data)
                ReportWidgetType.OVERDUE_LIST -> OverdueTableWidget(data)
                ReportWidgetType.TOP_RENTERS -> TopRentersWidget(data)
                ReportWidgetType.TOP_SCOOTERS -> TopScootersWidget(data)
            }
        }
    }
}

private fun Double.fmtUzs(): String = "%,.0f so'm".format(this)
private fun Long.fmtUzs(): String = "%,d so'm".format(this)
private fun Double.fmtMln(): String {
    val mln = this / 1_000_000.0
    return if (mln >= 1) "%.1f mln".format(mln) else "%,.0f".format(this)
}

// ════════════════════════════════════════════════════════════════════════════
// ВИДЖЕТЫ С РАЗНОЙ ВИЗУАЛИЗАЦИЕЙ
// ════════════════════════════════════════════════════════════════════════════

/** 1. NET_PROFIT — KPI-карточка с дельтой и спарклайном по неделям. */
@Composable
private fun NetProfitWidget(d: ReportWidgetData) {
    val netProfit = d.totalPayments * 0.70
    KpiCard(
        title = "Sof foyda (70% marja)",
        value = netProfit.toLong().fmtUzs(),
        deltaPercent = d.profitDeltaPercent,
        deltaPositive = d.profitDeltaPercent >= 0,
        sparkline = d.sparkData,
        icon = Icons.Default.AccountBalanceWallet,
        accentColor = StatusOk
    )
}

/** 2. PAYMENT_SUM — столбчатая диаграмма по 8 неделям. */
@Composable
private fun PaymentSumBarChartWidget(d: ReportWidgetData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.totalPayments.toLong().fmtUzs(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ClaudeText
            )
            Spacer(Modifier.width(8.dp))
            TrendDelta(d.revenueDeltaPercent, d.revenueDeltaPercent >= 0)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Tanlangan davrdagi jami to'lovlar. Ostida — 8 haftalik dinamika.",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        BarChart(
            data = d.weeklyPaymentsSeries,
            barColor = ClaudeAccent,
            height = 140.dp
        )
    }
}

/** 3. WEEKLY_TREND — линейный график динамики выручки (8 недель). */
@Composable
private fun WeeklyTrendLineWidget(d: ReportWidgetData) {
    Column {
        Text(
            "So'nggi 8 hafta to'lovlar dinamikasi",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        LineChart(
            data = d.weeklyPaymentsSeries,
            lineColor = ClaudeTeal,
            fillColor = ClaudeTeal.copy(alpha = 0.15f),
            height = 160.dp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val minV = d.sparkData.minOrNull() ?: 0f
            val maxV = d.sparkData.maxOrNull() ?: 0f
            val avgV = if (d.sparkData.isNotEmpty()) d.sparkData.average().toFloat() else 0f
            Column {
                Text("Min", fontSize = 10.sp, color = ClaudeTextSecondary)
                Text("${minV.toLong()} so'm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ClaudeText)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("O'rtacha", fontSize = 10.sp, color = ClaudeTextSecondary)
                Text("${avgV.toLong()} so'm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Max", fontSize = 10.sp, color = ClaudeTextSecondary)
                Text("${maxV.toLong()} so'm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = StatusOk)
            }
        }
    }
}

/** 4. EXPECTED_NEXT_MONTH — KPI с кольцевым прогресс-баром (ожидание vs. цель). */
@Composable
private fun ExpectedNextMonthWidget(d: ReportWidgetData) {
    val target = (d.totalScooters * d.weeklyPrice * 4).coerceAtLeast(1.0)
    val ratio = (d.expectedNextMonth / target * 100).toInt().coerceIn(0, 100)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                d.expectedNextMonth.toLong().fmtUzs(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = ClaudeGold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${d.activeRenters} faol × ${d.weeklyPrice.toLong()} so'm × 4 hafta",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "To'liq bandlik maqsadi: ${target.toLong().fmtUzs()}",
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.width(16.dp))
        ProgressRing(
            percent = ratio,
            color = ClaudeGold,
            chartSize = 90.dp,
            label = "maqsad"
        )
    }
}

/** 5. ACTIVE_RENTERS_COUNT — кольцевая диаграмма (active/overdue/returned). */
@Composable
private fun ActiveRentersDonutWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Ijarachilar tarkibi:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        DonutChart(
            segments = listOf(
                "Faol" to d.activeRenters,
                "Qarzdor" to d.overdueRenters,
                "Qaytgan" to d.returnedRenters
            ),
            colors = listOf(StatusOk, StatusOverdue, StatusReturned),
            centerValue = "${d.activeRenters + d.overdueRenters + d.returnedRenters}",
            centerLabel = "jami",
            chartSize = 110.dp
        )
    }
}

/** 6. SCOOTER_STATUS — кольцевая диаграмма (in base / rented). */
@Composable
private fun ScooterStatusDonutWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Skuterlar holati:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        DonutChart(
            segments = listOf(
                "Ijarada" to d.scootersRented,
                "Bazada" to d.scootersInBase
            ),
            colors = listOf(StatusOverdue, StatusOk),
            centerValue = "${d.totalScooters}",
            centerLabel = "jami",
            chartSize = 110.dp
        )
        Spacer(Modifier.height(8.dp))
        // Дополнительная метрика: доход на один скутер
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Har skuter daromadi:", fontSize = 11.sp, color = ClaudeTextSecondary)
            Text(
                d.revenuePerScooter.toLong().fmtUzs(),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ClaudeAccent
            )
        }
    }
}

/** 7. OCCUPANCY — кольцевой прогресс + горизонтальный stacked bar. */
@Composable
private fun OccupancyRingWidget(d: ReportWidgetData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProgressRing(
            percent = d.occupancyPct,
            color = if (d.occupancyPct >= 70) StatusOk else if (d.occupancyPct >= 40) ClaudeGold else StatusOverdue,
            chartSize = 100.dp,
            label = "bandlik"
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${d.scootersRented} / ${d.totalScooters} skuter ijarada",
                style = MaterialTheme.typography.bodyMedium,
                color = ClaudeText
            )
            Spacer(Modifier.height(8.dp))
            // Stacked bar: rented | in base
            val rentedRatio = if (d.totalScooters > 0) d.scootersRented.toFloat() / d.totalScooters else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ClaudeDivider)
            ) {
                Row {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(rentedRatio)
                            .fillMaxHeight()
                            .background(
                                if (d.occupancyPct >= 70) StatusOk
                                else if (d.occupancyPct >= 40) ClaudeGold
                                else StatusOverdue
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusChip("Ijarada ${d.scootersRented}", StatusOk)
                StatusChip("Bazada ${d.scootersInBase}", ClaudeTextSecondary)
            }
        }
    }
}

/** 8. IDLE_HEATMAP — тепловая карта простоев (4 нед × N скутеров). */
@Composable
private fun IdleHeatmapWidget(d: ReportWidgetData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.avgIdleDays} kun",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ClaudeGold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "o'rtacha har skuter bo'sh turgan",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.height(12.dp))
        HeatmapGrid(
            rows = d.heatmapRows,
            cols = d.heatmapCols,
            values = d.heatmapValues
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Yashil = ijarada bo'lgan, qizil = butun hafta bo'sh turgan.",
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary
        )
    }
}

/** 9. ROI — радарная диаграмма biznes salomatligi (5 осей). */
@Composable
private fun RoiRadarWidget(d: ReportWidgetData) {
    // Нормализуем метрики в 0..1
    val roiScore = (d.roiMultiple / 2.0).coerceIn(0.0, 1.0)  // 2× = 100%
    val occupancyScore = (d.occupancyPct / 100.0).coerceIn(0.0, 1.0)
    val disciplineScore = (d.paymentDiscipline / 100.0).coerceIn(0.0, 1.0)
    val growthScore = ((d.revenueDeltaPercent + 50) / 150.0).coerceIn(0.0, 1.0)  // -50%..+100% → 0..1
    val marginScore = 0.70  // 70% маржа

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%.2f×".format(d.roiMultiple),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (d.roiMultiple >= 1) StatusOk else StatusOverdue
            )
            Spacer(Modifier.width(8.dp))
            Text("(${d.roiPercent.toInt()}%)", style = MaterialTheme.typography.titleMedium, color = ClaudeTextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Investitsiya: ${d.totalInvestmentUzs.toLong().fmtUzs()} • \$${d.scooterPriceUsd.toInt()} × ${d.totalScooters} • 1\$ = ${d.usdToUzs.toLong()} so'm",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(8.dp))
        RadarChart(
            axes = listOf(
                "ROI" to roiScore.toFloat(),
                "Bandlik" to occupancyScore.toFloat(),
                "Intizom" to disciplineScore.toFloat(),
                "O'sish" to growthScore.toFloat(),
                "Marja" to marginScore.toFloat()
            ),
            color = if (d.roiMultiple >= 1) StatusOk else StatusOverdue,
            chartSize = 200.dp
        )
        if (d.roiMultiple >= 1) {
            Spacer(Modifier.height(8.dp))
            StatusChip("✓ Biznes o'zini oqladi", StatusOk)
        }
    }
}

/** 10. ARPU_LTV — две KPI-карточки рядом. */
@Composable
private fun ArpuLtvWidget(d: ReportWidgetData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        KpiCard(
            title = "ARPU (bir ijarachi)",
            value = d.arpu.toLong().fmtUzs(),
            icon = Icons.Default.Person,
            accentColor = ClaudeAccent,
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            title = "LTV (8 hafta)",
            value = d.ltv.toLong().fmtUzs(),
            icon = Icons.Default.Star,
            accentColor = ClaudeTeal,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 11. MRR — KPI + прогресс к цели (target = все скутеры в аренде). */
@Composable
private fun MrrWidget(d: ReportWidgetData) {
    val ratio = (d.mrr / d.targetMrr.coerceAtLeast(1.0) * 100).toInt().coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(ClaudeGold, ClaudeAccent)))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Oylik takroriy daromad (MRR)",
                    fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TrendDelta(d.revenueDeltaPercent, d.revenueDeltaPercent >= 0, Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                d.mrr.toLong().fmtUzs(),
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            // Прогресс-бар к цели
            LinearProgressIndicator(
                progress = { ratio / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Maqsad: ${d.targetMrr.toLong().fmtUzs()} ($ratio%) • Churn: ${d.churnRate}%",
                fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

/** 12. PAYMENT_DISCIPLINE — воронка конверсии. */
@Composable
private fun PaymentDisciplineFunnelWidget(d: ReportWidgetData) {
    val totalActive = d.activeRenters + d.overdueRenters  // все активные (не возвращённые)
    val onTime = d.activeRenters - d.overdueRenters  // активные без долга
    val stages = listOf(
        "Jami ijarachi" to (d.activeRenters + d.overdueRenters + d.returnedRenters),
        "Faol" to totalActive,
        "O'z vaqtida" to onTime.coerceAtLeast(0)
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.paymentDiscipline}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (d.paymentDiscipline >= 70) StatusOk else if (d.paymentDiscipline >= 40) ClaudeGold else StatusOverdue
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "to'lov intizomi (kechikishsiz)",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.height(12.dp))
        FunnelChart(
            stages = stages,
            colors = listOf(ClaudeAccent, ClaudeGold, StatusOk)
        )
    }
}

/** 13. CONTRACTS_TREND — линейный график контрактов по месяцам (6 мес). */
@Composable
private fun ContractsTrendWidget(d: ReportWidgetData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.totalContracts}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ClaudeText
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "kontrakt tanlangan davrda",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.height(12.dp))
        LineChart(
            data = d.monthlyContractsSeries,
            lineColor = ClaudeGold,
            fillColor = ClaudeGold.copy(alpha = 0.15f),
            height = 140.dp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "So'nggi 6 oy: CREATED + AUTO_RENEW kontraktlar soni.",
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary
        )
    }
}

/** 14. OVERDUE_LIST — таблица должников. */
@Composable
private fun OverdueTableWidget(d: ReportWidgetData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.overdueRenters} ijarachi",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = StatusOverdue
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "qarzdor (eng katta 8 tasi)",
                style = MaterialTheme.typography.bodySmall,
                color = ClaudeTextSecondary
            )
        }
        Spacer(Modifier.height(12.dp))
        if (d.overdueList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = StatusOk, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Qarzdorlar yo'q — barcha o'z vaqtida to'layapti!",
                        color = StatusOk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            SimpleDataTable(
                headers = listOf("Ism", "Telefon", "Skuter", "Qarz"),
                rows = d.overdueList.map { r ->
                    listOf(
                        r.name,
                        r.phoneNumber,
                        r.scooterName ?: "—",
                        "${r.balance.toLong()} so'm"
                    )
                },
                headerColors = listOf(ClaudeText, ClaudeTextSecondary, ClaudeTextSecondary, StatusOverdue)
            )
        }
    }
}

/** 15. TOP_RENTERS — горизонтальная столбчатая top-5. */
@Composable
private fun TopRentersWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Eng ko'p to'lov qilgan ijarachilar:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(8.dp))
        if (d.topRenters.isEmpty()) {
            Text("Hozircha ma'lumot yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
        } else {
            HorizontalBarChart(
                data = d.topRenters.map { it.first to it.second.toFloat() },
                barColor = StatusOk,
                valueFormatter = { it.toLong().toString() + " so'm" }
            )
        }
    }
}

/** 16. TOP_SCOOTERS — горизонтальная столбчатая top-5. */
@Composable
private fun TopScootersWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Eng daromadli skuterlar:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(8.dp))
        if (d.topScooters.isEmpty()) {
            Text("Hozircha ma'lumot yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
        } else {
            HorizontalBarChart(
                data = d.topScooters.map { it.first to it.second.toFloat() },
                barColor = ClaudeAccent,
                valueFormatter = { it.toLong().toString() + " so'm" }
            )
        }
    }
}


