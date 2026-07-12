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
import com.example.data.Transaction
import com.example.data.isExternal
import com.example.ui.ContractHistoryViewModel
import com.example.ui.RenterViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.TransactionViewModel
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
    KPI_CARDS("kpi_cards", "KPI ko'rsatkichlar"),
    NET_PROFIT("net_profit", "Sof foyda"),
    PAYMENT_SUM("payment_sum", "Haftalik to'lovlar"),
    ACTIVE_RENTERS_COUNT("active_renters", "Ijarachilar tarkibi"),
    SCOOTER_OCCUPANCY("occupancy", "Skuter bandligi"),
    IDLE_HEATMAP("idle_heatmap", "Bo'sh turgan kunlar (heatmap)"),
    ROI("roi", "Biznes salomatligi (radar)"),
    MRR("mrr", "Oylik takroriy daromad (MRR)"),
    TRANSACTION_TYPES("tx_types", "Tranzaksiya turlari"),
    CONTRACT_LIFECYCLE("contract_lifecycle", "Kontrakt hayot aylanishi"),
    PAYMENT_DISCIPLINE("discipline", "To'lov intizomi (voronka)"),
    CONTRACTS_TREND("contracts_trend", "Kontraktlar dinamikasi"),
    OVERDUE_LIST("overdue_list", "Qarzdorlar ro'yxati"),
    TOP_RENTERS("top_renters", "Eng yaxshi ijarachilar"),
    TOP_SCOOTERS("top_scooters", "Eng daromadli skuterlar"),
    // ── Финансовые виджеты (учитывают логику «Glavnaya = касса») ──
    CARDS_BALANCES("cards_balances", "Kartalar balansi"),
    CASH_FLOW("cash_flow", "Pul oqimi (kassa)"),
    MAIN_CARD_INCOME("main_income", "Glavnaya kartaga tushumlar"),
    EXPENSE_CARDS("expense_cards", "Rashod kartalari")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    renterViewModel: RenterViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    transactionViewModel: TransactionViewModel = viewModel(),
    finansiViewModel: com.example.ui.FinansiViewModel = viewModel()
) {
    val context = LocalContext.current

    val renters by renterViewModel.rentersList.collectAsStateWithLifecycle()
    val scooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val history by contractHistoryViewModel.history.collectAsStateWithLifecycle()
    val transactions by transactionViewModel.transactions.collectAsStateWithLifecycle()
    val virtualCards by finansiViewModel.cards.collectAsStateWithLifecycle()
    val cardTransactions by finansiViewModel.transactions.collectAsStateWithLifecycle()

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
    // ROI считается как отношение доходов за выбранный период к инвестициям,
    // нормализованное к месяце. Раньше было просто totalPayments / investment,
    // что росло линейно со временем и не отражало реальную окупаемость.
    val totalInvestmentUzs = scooters.size * scooterPriceUsd * usdToUzs
    val roiMultiple = if (totalInvestmentUzs > 0) totalPayments / totalInvestmentUzs else 0.0
    val roiPercent = roiMultiple * 100
    // Дополнительная метрика: ROI в месяц (нормализованная).
    // Если период = 30 дней, monthlyRoi = roiMultiple. Если 60 дней,
    // monthlyRoi = roiMultiple / 2 (делим на кол-во месяцев в периоде).
    val periodMonths = (effectivePeriodDays.toDouble() / 30.0).coerceAtLeast(1.0)
    val roiMultiplePerMonth = if (totalInvestmentUzs > 0)
        (totalPayments / periodMonths) / totalInvestmentUzs else 0.0
    val roiPercentPerMonth = roiMultiplePerMonth * 100

    // ── Ожидаемые доходы в следующем месяце ───────────────────────────
    // Используем точное число недель в месяце (4.348), а не округление 4.
    val expectedNextMonth = activeRenters * weeklyPrice * (365.25 / 7.0 / 12.0)

    // ── Новые метрики для бизнес-отчётности ───────────────────────────
    // ARPU = средний доход на одного активного арендатора (за период)
    val arpu = if (activeRenters > 0) totalPayments / activeRenters else 0.0
    // LTV = ARPU × среднее число недель аренды
    // Вычисляем avgWeeksPerRenter из истории контрактов: для каждого
    // арендатора считаем кол-во его записей CREATED+AUTO_RENEW, и берём
    // среднее. Раньше был хардкод 8.0 — это плохо для аналитики.
    val avgWeeksPerRenter = remember(history, renters) {
        val rentersWithContracts = renters.filter { it.id != 0 }
        if (rentersWithContracts.isEmpty()) 0.0
        else {
            val weeksPerRenter = rentersWithContracts.map { r ->
                history.count {
                    it.renterId == r.id &&
                    it.type in listOf(
                        ContractHistoryEntry.TYPE_CREATED,
                        ContractHistoryEntry.TYPE_AUTO_RENEW
                    )
                }
            }
            // Защита от деления на 0 и от 0-длинных историй
            val avg = weeksPerRenter.average()
            if (avg > 0) avg else 8.0  // fallback на 8 недель если нет данных
        }
    }
    val ltv = arpu * avgWeeksPerRenter
    // MRR = активные арендаторы × недельная ставка × (среднее число недель в месяце)
    // В месяце в среднем 365.25 / 7 / 12 = 4.348 недель. Раньше было * 4,
    // что занижало MRR на ~8%.
    val weeksPerMonth = 365.25 / 7.0 / 12.0  // ≈ 4.348
    val mrr = activeRenters * weeklyPrice * weeksPerMonth
    // Целевой MRR = (всего скутеров × weeklyPrice × недель в месяце) — если бы все были в аренде
    val targetMrr = scooters.size * weeklyPrice * weeksPerMonth
    // OverdueRate (доля должников среди активных). Раньше называлось churnRate,
    // что неправильно: churn = доля ПОКИНУВШИХ систему, а не доля должников.
    val overdueRate = if (activeRenters > 0) (overdueRenters.toDouble() / activeRenters * 100).toInt() else 0
    // Совместимость: оставляем churnRate как алиас для UI-кода, который уже его использует
    val churnRate = overdueRate
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

    // ── Типы транзакций (для круговой диаграммы) ──────────────────────
    val transactionTypeData = remember(transactions) {
        val grouped = transactions.groupBy { it.type }
        val pairs = mutableListOf<Pair<String, Float>>()
        val orderedTypes = listOf(
            Transaction.TYPE_PAYMENT to "To'lov",
            Transaction.TYPE_PENALTY to "Jarima",
            Transaction.TYPE_REPAIR to "Ta'mir",
            Transaction.TYPE_TERMINATED to "Tugatildi",
            Transaction.TYPE_RETURNED to "Qaytarildi",
            Transaction.TYPE_CUSTOM to "Boshqa"
        )
        for ((type, label) in orderedTypes) {
            val sum = grouped[type]?.sumOf { it.amount } ?: 0.0
            if (sum > 0) pairs.add(label to sum.toFloat())
        }
        pairs
    }

    // ── Жизненный цикл контракта (для воронки) ────────────────────────
    val contractLifecycleData = remember(history) {
        val created = history.count { it.type == ContractHistoryEntry.TYPE_CREATED }
        val payments = history.count { it.type == ContractHistoryEntry.TYPE_PAYMENT }
        val autoRenewed = history.count { it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
        val terminated = history.count {
            it.type == ContractHistoryEntry.TYPE_TERMINATED || it.type == ContractHistoryEntry.TYPE_RETURNED
        }
        listOf(
            "Yaratildi" to created,
            "To'lov" to payments,
            "Yangilandi" to autoRenewed,
            "Tugatildi" to terminated
        )
    }

    // ── Средний недельный чек ─────────────────────────────────────────
    val avgWeeklyCheck = if (activeRenters > 0) weeklyPrice else 0.0

    // ── Общий долг всех арендаторов ───────────────────────────────────
    val totalDebt = renters.filter { !it.isReturned && it.balance < 0 }.sumOf { -it.balance }

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
                        heatmapValues = heatmapData.third,
                        totalDebt = totalDebt,
                        avgWeeklyCheck = avgWeeklyCheck,
                        transactionTypeData = transactionTypeData,
                        contractLifecycleData = contractLifecycleData,
                        cards = virtualCards,
                        cardTransactions = cardTransactions
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
    val weeklyPaymentsSeries: List<Pair<String, Float>>,
    val sparkData: List<Float>,
    val monthlyContractsSeries: List<Pair<String, Float>>,
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
    val heatmapValues: List<List<Float>>,
    // ── Новые поля для перестроенных виджетов ──
    val totalDebt: Double,
    val avgWeeklyCheck: Double,
    val transactionTypeData: List<Pair<String, Float>>,
    val contractLifecycleData: List<Pair<String, Int>>,
    // ── Финансовые поля (для новых виджетов Finansi) ──
    val cards: List<com.example.data.VirtualCard> = emptyList(),
    val cardTransactions: List<com.example.data.CardTransaction> = emptyList()
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
                ReportWidgetType.KPI_CARDS -> KpiCardsWidget(data)
                ReportWidgetType.NET_PROFIT -> NetProfitWidget(data)
                ReportWidgetType.PAYMENT_SUM -> PaymentSumBarChartWidget(data)
                ReportWidgetType.ACTIVE_RENTERS_COUNT -> ActiveRentersDonutWidget(data)
                ReportWidgetType.SCOOTER_OCCUPANCY -> OccupancyRingWidget(data)
                ReportWidgetType.IDLE_HEATMAP -> IdleHeatmapWidget(data)
                ReportWidgetType.ROI -> RoiRadarWidget(data)
                ReportWidgetType.MRR -> MrrWidget(data)
                ReportWidgetType.TRANSACTION_TYPES -> TransactionTypesWidget(data)
                ReportWidgetType.CONTRACT_LIFECYCLE -> ContractLifecycleWidget(data)
                ReportWidgetType.PAYMENT_DISCIPLINE -> PaymentDisciplineFunnelWidget(data)
                ReportWidgetType.CONTRACTS_TREND -> ContractsTrendWidget(data)
                ReportWidgetType.OVERDUE_LIST -> OverdueTableWidget(data)
                ReportWidgetType.TOP_RENTERS -> TopRentersWidget(data)
                ReportWidgetType.TOP_SCOOTERS -> TopScootersWidget(data)
                ReportWidgetType.CARDS_BALANCES -> CardsBalancesWidget(data)
                ReportWidgetType.CASH_FLOW -> CashFlowWidget(data)
                ReportWidgetType.MAIN_CARD_INCOME -> MainCardIncomeWidget(data)
                ReportWidgetType.EXPENSE_CARDS -> ExpenseCardsWidget(data)
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
    val netProfit = d.totalPayments
    KpiCard(
        title = "Sof foyda",
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

/** 3. WEEKLY_TREND — удалён (дублировал PAYMENT_SUM). */

/** 4. EXPECTED_NEXT_MONTH — удалён (дублировал MRR). */


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

/** 6. SCOOTER_STATUS — удалён (дублировал OCCUPANCY). */


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

/** 10. ARPU_LTV — удалён по просьбе пользователя. */


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

// ════════════════════════════════════════════════════════════════════════════
// НОВЫЕ ВИДЖЕТЫ (добавлены при перестройке Otchetlar)
// ════════════════════════════════════════════════════════════════════════════

/** KPI_CARDS — ряд из 6 ключевых метрик. */
@Composable
private fun KpiCardsWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Kalit ko'rsatkichlar:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        // Ряд 1: Выручка | Долг | Активные аренды
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KpiCard(
                title = "Jami daromad",
                value = d.totalPayments.toLong().fmtUzs(),
                icon = Icons.Default.AccountBalanceWallet,
                accentColor = StatusOk,
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                title = "Jami qarz",
                value = d.totalDebt.toLong().fmtUzs(),
                icon = Icons.Default.Warning,
                accentColor = StatusOverdue,
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                title = "Faol ijaralar",
                value = "${d.activeRenters}",
                icon = Icons.Default.People,
                accentColor = ClaudeAccent,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        // Ряд 2: Просрочено | Свободных скутеров | Средний чек
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KpiCard(
                title = "Qarzdorlar",
                value = "${d.overdueRenters}",
                icon = Icons.Default.Error,
                accentColor = StatusOverdue,
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                title = "Bo'sh skuterlar",
                value = "${d.scootersInBase}",
                icon = Icons.Default.DirectionsBike,
                accentColor = ClaudeGold,
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                title = "O'rtacha haftalik",
                value = d.avgWeeklyCheck.toLong().fmtUzs(),
                icon = Icons.Default.Receipt,
                accentColor = ClaudeTeal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** TRANSACTION_TYPES — круговая диаграмма по типам транзакций. */
@Composable
private fun TransactionTypesWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Tranzaksiya turlari bo'yicha tuzilma:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        if (d.transactionTypeData.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Hozircha tranzaksiyalar yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
            }
        } else {
            DonutChart(
                segments = d.transactionTypeData.map { it.first to it.second.toInt() },
                colors = listOf(StatusOk, StatusOverdue, ClaudeGold, ClaudeTextSecondary, StatusReturned, ClaudeAccent),
                centerValue = "${d.transactionTypeData.sumOf { it.second.toLong() }.toLong()}",
                centerLabel = "jami so'm",
                chartSize = 130.dp
            )
            Spacer(Modifier.height(8.dp))
            // Легенда с суммами
            d.transactionTypeData.forEachIndexed { idx, (label, value) ->
                val color = listOf(StatusOk, StatusOverdue, ClaudeGold, ClaudeTextSecondary, StatusReturned, ClaudeAccent)
                    .getOrElse(idx) { ClaudeAccent }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(10.dp).background(color, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontSize = 11.sp, color = ClaudeText, modifier = Modifier.weight(1f))
                    Text(value.toLong().fmtUzs(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
                }
            }
        }
    }
}

/** CONTRACT_LIFECYCLE — воронка жизненного цикла контракта. */
@Composable
private fun ContractLifecycleWidget(d: ReportWidgetData) {
    Column {
        Text(
            "Kontrakt hayot aylanishi:",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Yaratildi → To'lov → Yangilandi → Tugatildi",
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary
        )
        Spacer(Modifier.height(12.dp))
        val stages = d.contractLifecycleData
        if (stages.isEmpty() || stages.all { it.second == 0 }) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Hozircha kontraktlar yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
            }
        } else {
            FunnelChart(
                stages = stages,
                colors = listOf(ClaudeAccent, StatusOk, ClaudeGold, StatusOverdue)
            )
            Spacer(Modifier.height(8.dp))
            // Процент конверсии между этапами
            val created = d.contractLifecycleData.getOrNull(0)?.second ?: 0
            val renewed = d.contractLifecycleData.getOrNull(2)?.second ?: 0
            val terminated = d.contractLifecycleData.getOrNull(3)?.second ?: 0
            if (created > 0) {
                val renewRate = (renewed.toDouble() / created * 100).toInt()
                val churnRate = (terminated.toDouble() / created * 100).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Yangilash: $renewRate%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusOk
                    )
                    Text(
                        "Tugatish: $churnRate%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusOverdue
                    )
                }
            }
        }
    }
}


// ════════════════════════════════════════════════════════════════════════════
// ФИНАНСОВЫЕ ВИДЖЕТЫ (учитывают логику «Glavnaya = касса»)
// ════════════════════════════════════════════════════════════════════════════

/** Парсит hex-строку вида "#FF1565C0" в Compose Color. */
private fun parseHexColor(hex: String): Color {
    return try {
        val normalized = hex.removePrefix("#")
        val full = if (normalized.length == 6) "FF$normalized" else normalized
        Color(full.toLong(16))
    } catch (_: Exception) {
        Color(0xFF1565C0)
    }
}

/** Форматирует сумму в "1 250 000" или "-350 000". */
private fun fmtMoney(amount: Double): String {
    val sign = if (amount < 0) "-" else ""
    val absValue = kotlin.math.abs(amount).toLong()
    val formatted = String.format(java.util.Locale.US, "%,d", absValue).replace(',', ' ')
    return "$sign$formatted"
}

/**
 * Виджет «Kartalar balansi» — показывает все виртуальные карты с их балансами.
 *
 * Логика:
 *   • Glavnaya (id=1) — основная касса, в неё падают все контрактные платежи.
 *   • Остальные карты — «карманы» для расходов (ремонт, штрафы и т.д.).
 *
 * Визуально: горизонтальный список мини-карт с цветом, именем и балансом.
 */
@Composable
private fun CardsBalancesWidget(d: ReportWidgetData) {
    // Внешние карты (Tashqidan / Tashqiga) исключаем — у них ∞ баланс,
    // учёт их в итогах бессмысленен.
    val regularCards = d.cards.filter { !it.isExternal }
    if (regularCards.isEmpty()) {
        EmptyWidget("Hozircha kartalar yo'q. Finansi bo'limidan yarating.")
        return
    }
    val totalBalance = regularCards.sumOf { it.balance }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Общий баланс всех карт
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Jami balans:",
                style = MaterialTheme.typography.bodyMedium,
                color = ClaudeText
            )
            Text(
                text = "${fmtMoney(totalBalance)} so'm",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (totalBalance >= 0) StatusOk else StatusOverdue
            )
        }
        HorizontalDivider(color = ClaudeDivider)
        // Список карт
        regularCards.forEach { card ->
            val cardColor = parseHexColor(card.colorHex)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Мини-карта (цветной прямоугольник)
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 26.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(cardColor.copy(alpha = 0.85f), cardColor)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.name + if (card.isDefault) " ★" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = ClaudeText
                    )
                    Text(
                        text = card.info ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary,
                        maxLines = 1
                    )
                }
                Text(
                    text = "${fmtMoney(card.balance)} so'm",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (card.balance >= 0) StatusOk else StatusOverdue
                )
            }
        }
    }
}

/**
 * Виджет «Pul oqimi (kassa)» — показывает движение денег:
 *   • Приход (входящие на Glavnaya из контрактов)
 *   • Расход (переводы с Glavnaya на расходные карты)
 *   • Чистый поток = Приход − Расход
 */
@Composable
private fun CashFlowWidget(d: ReportWidgetData) {
    val income = d.cardTransactions
        .filter { it.type == com.example.data.CardTransaction.TYPE_CONTRACT_INCOME }
        .sumOf { it.amount }
    // Внутренние переводы между обычными картами (исключаем внешние —
    // Tashqidan/Tashqiga — поскольку они не являются внутренним расходом,
    // а представляют вход/выход средств относительно системы).
    val transfers = d.cardTransactions
        .filter {
            it.type == com.example.data.CardTransaction.TYPE_CARD_TRANSFER &&
            !com.example.data.VirtualCard.isExternalId(it.fromCardId) &&
            !com.example.data.VirtualCard.isExternalId(it.toCardId)
        }
        .sumOf { it.amount }
    val netFlow = income - transfers

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowMetricCard(
                title = "Tushum",
                value = fmtMoney(income),
                color = StatusOk,
                modifier = Modifier.weight(1f)
            )
            FlowMetricCard(
                title = "O'tkazma",
                value = fmtMoney(transfers),
                color = StatusOverdue,
                modifier = Modifier.weight(1f)
            )
            FlowMetricCard(
                title = "Sof oqim",
                value = fmtMoney(netFlow),
                color = if (netFlow >= 0) StatusOk else StatusOverdue,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "Glavnaya kartaga tushgan kontrakt to'lovlari va undan rashod kartalariga o'tkazilgan summalarning farqi.",
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary
        )
    }
}

@Composable
private fun FlowMetricCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = ClaudeTextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

/**
 * Виджет «Glavnaya kartaga tushumlar» — лента последних контрактных платежей,
 * которые автоматически зачислены на главную карту.
 *
 * Показывает последние 8 записей CONTRACT_INCOME с датой, суммой и примечанием.
 */
@Composable
private fun MainCardIncomeWidget(d: ReportWidgetData) {
    val incomes = d.cardTransactions
        .filter { it.type == com.example.data.CardTransaction.TYPE_CONTRACT_INCOME }
        .take(8)
    if (incomes.isEmpty()) {
        EmptyWidget("Hozircha Glavnaya kartaga tushumlar yo'q.")
        return
    }
    val total = incomes.sumOf { it.amount }
    val dateFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Oxirgi ${incomes.size} tushum", style = MaterialTheme.typography.bodySmall, color = ClaudeTextSecondary)
            Text(
                "Jami: ${fmtMoney(total)} so'm",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = StatusOk
            )
        }
        incomes.forEach { tx ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(StatusOk, CircleShape)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tx.note ?: "Kontrakt to'lovi",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeText,
                        maxLines = 1
                    )
                    Text(
                        text = dateFmt.format(Date(tx.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary
                    )
                }
                Text(
                    text = "+${fmtMoney(tx.amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusOk
                )
            }
        }
    }
}

/**
 * Виджет «Rashod kartalari» — показывает баланс всех карт КРОМЕ Glavnaya.
 *
 * Логика: эти карты играют роль «расходных карманов» — на них переводят деньги
 * с Glavnaya для целевых расходов (ремонт, штрафы, налоги и т.д.).
 *
 * Показывает имя карты, баланс и долю от общего расхода.
 */
@Composable
private fun ExpenseCardsWidget(d: ReportWidgetData) {
    // Внешние карты исключаем — они не являются «расходными карманами».
    val expenseCards = d.cards.filter {
        it.id != com.example.data.VirtualCard.MAIN_CARD_ID && !it.isExternal
    }
    if (expenseCards.isEmpty()) {
        EmptyWidget("Rashod kartalari yo'q. Finansi bo'limidan yarating.")
        return
    }
    val totalExpenses = expenseCards.sumOf { it.balance }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${expenseCards.size} ta rashod kartasi",
                style = MaterialTheme.typography.bodyMedium,
                color = ClaudeText
            )
            Text(
                "Jami: ${fmtMoney(totalExpenses)} so'm",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (totalExpenses >= 0) StatusOk else StatusOverdue
            )
        }
        HorizontalDivider(color = ClaudeDivider)
        expenseCards.forEach { card ->
            val cardColor = parseHexColor(card.colorHex)
            val pct = if (totalExpenses != 0.0)
                (kotlin.math.abs(card.balance) / kotlin.math.abs(totalExpenses) * 100).toInt()
            else 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 24.dp)
                        .background(cardColor, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = ClaudeText
                    )
                    // Progress bar showing relative share
                    LinearProgressIndicator(
                        progress = { (pct.toFloat() / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        color = cardColor,
                        trackColor = ClaudeDivider
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${fmtMoney(card.balance)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (card.balance >= 0) ClaudeText else StatusOverdue
                )
            }
        }
    }
}

@Composable
private fun EmptyWidget(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = ClaudeTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
