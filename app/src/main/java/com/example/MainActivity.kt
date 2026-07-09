package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.NotificationHistoryEntity
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.remote.InAppUpdateManager
import com.example.data.remote.InAppUpdateState
import com.example.data.remote.UpdateCheckResult
import com.example.data.remote.UpdateChecker
import com.example.data.remote.UpdateInfo
import com.example.ui.ContractHistoryViewModel
import com.example.ui.NotificationHistoryViewModel
import com.example.ui.RenterViewModel
import com.example.ui.SettingsViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeAccentMuted
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOkBg
import com.example.ui.theme.StatusOverdue
import com.example.ui.theme.StatusOverdueBg
import com.example.ui.theme.StatusReturned
import com.example.ui.theme.StatusReturnedBg
import com.example.ui.components.UnifiedSearchBar
import com.example.ui.components.FilterSidePanel
import com.example.ui.components.FilterColumn
import com.example.ui.components.PhoneReceiverSortIcon
import com.example.ui.components.SortableHeaderCell
import com.example.ui.components.NonSortableHeaderCell
import com.example.ui.components.TableSortState
import com.example.ui.components.SortState
import com.example.ui.components.applyFilters
import com.example.ui.components.UnifiedButton
import com.example.ui.components.UnifiedButtonVariant
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.SuccessButton
import com.example.ui.components.DangerButton
import com.example.ui.components.DangerOutlinedButton
import com.example.ui.components.TextActionButton
import com.example.ui.components.SortableHeaderCellFixed
import com.example.ui.components.NonSortableHeaderCellFixed
import com.example.worker.NotificationHelper
import android.util.Log
import com.example.worker.PaymentCheckWorker
import com.example.worker.SmsWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NotificationHelper.createChannel(applicationContext)

        // SMS-воркер для просроченных (как раньше)
        val smsWorkRequest = PeriodicWorkRequestBuilder<SmsWorker>(4, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "OverdueSmsWork",
            ExistingPeriodicWorkPolicy.KEEP,
            smsWorkRequest
        )

        // Периодическая проверка наступления срока оплаты (раз в час)
        val paymentCheckRequest =
            PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "PaymentCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            paymentCheckRequest
        )

        setContent {
            MyApplicationTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* результат не важен — мы запрашиваем автоматически */ }

                // Авто-запрос SMS + POST_NOTIFICATIONS (Android 13+) + READ_PHONE_STATE при первом старте.
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.SEND_SMS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    // READ_PHONE_STATE — SIM kartalarni aniqlash uchun kerak (dual-SIM qo'llab-quvvatlash)
                    permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }

                MainScreen()
            }
        }
    }
}

enum class SortColumn {
    NAME, START_TIME, STATUS, DEBT
}

enum class SortDirection { ASC, DESC }

/**
 * Состояние навигации верхнего уровня.
 *   • MainView         — список арендаторов / скутеров с табами
 *   • RenterHistory    — история контрактов конкретного арендатора
 *   • ScooterHistory   — история контрактов конкретного скутера
 */
sealed class NavigationState {
    data object MainView : NavigationState()
    data class RenterHistory(val renter: Renter) : NavigationState()
    data class ScooterHistory(val scooter: Scooter) : NavigationState()
}

/**
 * Цвет статус-индикатора:
 *   • серый  — арендатор вернул скутер
 *   • красный — есть долг (просрочена оплата)
 *   • зелёный — активный, оплачено в срок
 */
private enum class RenterStatus { RETURNED, OVERDUE, OK }

private fun statusOf(renter: Renter): RenterStatus = when {
    renter.isReturned -> RenterStatus.RETURNED
    renter.balance < 0.0 -> RenterStatus.OVERDUE
    else -> RenterStatus.OK
}

private fun statusColor(s: RenterStatus): Color = when (s) {
    RenterStatus.RETURNED -> StatusReturned
    RenterStatus.OVERDUE  -> StatusOverdue
    RenterStatus.OK       -> StatusOk
}

private fun statusLabel(s: RenterStatus): String = when (s) {
    RenterStatus.RETURNED -> "Qaytgan"
    RenterStatus.OVERDUE  -> "Qarzdor"
    RenterStatus.OK       -> "Faol"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: RenterViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel(),
    historyViewModel: NotificationHistoryViewModel = viewModel(),
    contractHistoryViewModel: ContractHistoryViewModel = viewModel()
) {
    var currentTab by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddScooterDialog by remember { mutableStateOf(false) }
    var renterToEdit by remember { mutableStateOf<Renter?>(null) }
    var scooterToEdit by remember { mutableStateOf<Scooter?>(null) }
    var selectedRenters by remember { mutableStateOf(setOf<Int>()) }
    var selectedScooters by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()

    // ── Навигация ────────────────────────────────────────────────────
    var navState by remember { mutableStateOf<NavigationState>(NavigationState.MainView) }

    var renterSortState by remember { mutableStateOf(TableSortState()) }
    var scooterSortState by remember { mutableStateOf(TableSortState()) }
    // Filter panel state
    var showRenterFilterPanel by remember { mutableStateOf(false) }
    var showScooterFilterPanel by remember { mutableStateOf(false) }
    var renterFilterValues by remember { mutableStateOf(mapOf<String, String>()) }
    var scooterFilterValues by remember { mutableStateOf(mapOf<String, String>()) }

    // Filter column definitions (shared between search bar and filter panel)
    // Базовые колонки всегда показываются. Опциональные колонки добавляются
    // динамически ниже (после того, как `renters` объявлен) — только если хотя
    // бы у одного арендатора есть данные в этом поле.
    val renterFilterColumnsBase = remember {
        listOf(
            FilterColumn("col_name", "Mijoz", "Ism bo'yicha"),
            FilterColumn("col_phone", "Telefon", "+998..."),
            FilterColumn("col_scooter", "Skuter", "Skuter nomi"),
            FilterColumn("col_start", "Boshlanish sanasi", "dd.MM.yyyy"),
            FilterColumn("col_end", "Tugash sanasi", "dd.MM.yyyy"),
            FilterColumn("col_balance", "Balans", "summa")
        )
    }
    val scooterFilterColumns = remember {
        listOf(
            FilterColumn("col_name", "Nomi", "Skuter nomi"),
            FilterColumn("col_doc", "Hujjat raqami", "Doc #"),
            FilterColumn("col_status", "Holat", "Ijarada / Bosh")
        )
    }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isUpToDate by remember { mutableStateOf(false) } // Приложение актуально — не показываем уведомление
    val localContext = LocalContext.current
    val updateManager = remember { InAppUpdateManager(localContext) }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val scooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()

    // Авто-проверка обновлений при запуске
    // Показываем уведомление ТОЛЬКО если есть реальное обновление
    LaunchedEffect(Unit) {
        try {
            val checker = UpdateChecker(localContext)
            val (result, info) = checker.checkForUpdate()
            when (result) {
                UpdateCheckResult.UPDATE_AVAILABLE -> {
                    updateInfo = info
                    isUpToDate = false
                    Log.d("MainScreen", "Update available: v${info?.versionName}")
                }
                UpdateCheckResult.UP_TO_DATE -> {
                    updateInfo = null
                    isUpToDate = true
                    Log.d("MainScreen", "App is up to date")
                }
                UpdateCheckResult.ERROR -> {
                    // Ошибка API = НЕ показываем уведомление
                    updateInfo = null
                    isUpToDate = false
                    Log.d("MainScreen", "Update check failed — not showing notification")
                }
            }
        } catch (e: Exception) {
            Log.w("MainScreen", "Auto-update check failed", e)
            // Ошибка = не показываем уведомление
        }
    }

    val renters by viewModel.rentersList.collectAsStateWithLifecycle()
    val history by historyViewModel.history.collectAsStateWithLifecycle()

    // Динамически расширяем список колонок фильтра опциональными PDF-реквизитами,
    // если хотя бы у одного арендатора есть данные в этом поле. Колонка, по которой
    // нет данных ни у одного арендатора, скрывается из фильтр-панели (и из таблицы).
    val renterFilterColumns = remember(renters, renterFilterColumnsBase) {
        val extra = buildList {
            if (renters.any { it.passportData.isNotBlank() })           add(FilterColumn("col_passport", "Pasport", "AA 1234567"))
            if (renters.any { it.address.isNotBlank() })                add(FilterColumn("col_address",  "Manzil", "Manzil bo'yicha"))
            if (renters.any { it.pinfl.isNotBlank() })                  add(FilterColumn("col_pinfl",    "JSHSHIR", "14 raqam"))
        }
        renterFilterColumnsBase + extra
    }

    // ── Рендер экрана истории контрактов, если активен ─────────────────
    when (val st = navState) {
        is NavigationState.RenterHistory -> {
            // Получаем свежего renter из БД (на случай если он изменился)
            val currentRenter = renters.firstOrNull { it.id == st.renter.id } ?: st.renter
            RenterContractHistoryScreen(
                renter = currentRenter,
                onBack = { navState = NavigationState.MainView },
                onEditRenter = { renterToEdit = currentRenter },
                contractHistoryViewModel = contractHistoryViewModel,
                renterViewModel = viewModel
            )
            // Диалог редактирования арендатора (поверх экрана истории)
            if (renterToEdit != null) {
                val weekly by settingsViewModel.weeklyPrice.collectAsStateWithLifecycle()
                val monthly by settingsViewModel.monthlyPrice.collectAsStateWithLifecycle()
                RenterFormDialog(
                    initialRenter = renterToEdit,
                    weeklyPrice = weekly,
                    monthlyPrice = monthly,
                    scooters = scooters,
                    activeRenters = renters,
                    onDismiss = { renterToEdit = null },
                    onSave = { result ->
                        renterToEdit?.let {
                            viewModel.updateRenterWithContracts(
                                existing = it,
                                newName = result.name, newPhone = result.phone, newDebt = result.debt,
                                newDuration = result.duration, newStartTimestamp = result.startTimestamp,
                                newScooterId = result.scooterId, newScooterName = result.scooterName,
                                newIsActive = result.isActive, weeklyPrice = weekly,
                                passportData = result.passportData,
                                address = result.address,
                                pinfl = result.pinfl
                            )
                        }
                        renterToEdit = null
                    }
                )
            }
            return
        }
        is NavigationState.ScooterHistory -> {
            ScooterContractHistoryScreen(
                scooter = st.scooter,
                renters = renters,
                onBack = { navState = NavigationState.MainView },
                onEditScooter = { scooterToEdit = st.scooter },
                contractHistoryViewModel = contractHistoryViewModel
            )
            if (scooterToEdit != null) {
                ScooterFormDialog(
                    initialScooter = scooterToEdit,
                    existingScooters = scooters,
                    onDismiss = { scooterToEdit = null },
                    onSave = { name, docNum, vin, engine, serial, batt1, batt2, extra ->
                        scooterToEdit?.let {
                            scooterViewModel.updateScooter(
                                it.copy(
                                    name = name,
                                    documentedNumber = docNum,
                                    vinNumber = vin,
                                    engineNumber = engine,
                                    scooterSerialNumber = serial,
                                    batteryId1 = batt1,
                                    batteryId2 = batt2,
                                    additionalInfo = extra
                                )
                            )
                        }
                        scooterToEdit = null
                    }
                )
            }
            return
        }
        NavigationState.MainView -> { /* продолжаем — основной Scaffold ниже */ }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Skuter Ijarasi",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClaudeBackground,
                    titleContentColor = ClaudeText,
                    actionIconContentColor = ClaudeText
                ),
                actions = {
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier
                            .padding(end = 16.dp, start = 4.dp)
                            .size(40.dp)
                            .background(Color.White, CircleShape)
                            .border(1.dp, ClaudeDivider, CircleShape)
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Sozlamalar",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (currentTab == 0) showAddDialog = true
                    else showAddScooterDialog = true
                },
                containerColor = ClaudeAccent,
                contentColor = Color.White,
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (currentTab == 0) "Ijarachi qo'shish" else "Skuter qo'shish",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (currentTab == 0) "Qo'shish" else "Qo'shish",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = ClaudeCard, contentColor = ClaudeText) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Ijarachilar") },
                    label = { Text("Ijarachilar") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClaudeAccent,
                        unselectedIconColor = ClaudeTextSecondary,
                        selectedTextColor = ClaudeAccent,
                        unselectedTextColor = ClaudeTextSecondary,
                        indicatorColor = ClaudeAccentBg
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.DirectionsBike, contentDescription = "Skuterlar") },
                    label = { Text("Skuterlar") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClaudeAccent,
                        unselectedIconColor = ClaudeTextSecondary,
                        selectedTextColor = ClaudeAccent,
                        unselectedTextColor = ClaudeTextSecondary,
                        indicatorColor = ClaudeAccentBg
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ── Баннер обновления (ТОЛЬКО если есть обновление) ──
            when (val st = updateState) {
                is InAppUpdateState.Downloading -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Yangilash yuklab olinmoqda... ${(st.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF000000),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { st.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF000000),
                                trackColor = Color(0xFFE5E5E5)
                            )
                        }
                    }
                }
                is InAppUpdateState.Installing -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF000000),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "O'rnatilmoqda...",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF000000)
                            )
                        }
                    }
                }
                is InAppUpdateState.Error -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                st.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF000000),
                                modifier = Modifier.weight(1f)
                            )
                            TextActionButton(
                                label = "Yopish",
                                icon = Icons.Default.Close,
                                onClick = { updateManager.reset() }
                            )
                        }
                    }
                }
                is InAppUpdateState.Installed -> {
                    // Установлено — ничего не показываем
                }
                else -> {
                    // Idle или ReadyToInstall — показываем баннер только если есть обновление
                    if (updateInfo != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable {
                                    coroutineScope.launch {
                                        if (!updateManager.canInstallFromUnknownSources()) {
                                            updateManager.openInstallPermissionSettings()
                                            Toast.makeText(localContext, "Ilova sozlamalaridan \"Noma'lum manbalardan o'rnatish\" ruxsatini bering", Toast.LENGTH_LONG).show()
                                        } else {
                                            updateManager.downloadAndInstall(updateInfo!!)
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF000000),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Yangi versiya: v${updateInfo!!.versionName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF000000),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Bosing — bir tugma bilan yangilash",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF000000)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (currentTab == 0) {
                // Unified search bar with calendar + filter buttons
                UnifiedSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Mijoz yoki skuter qidirish",
                    onCalendarClick = { showDateRangePicker = true },
                    calendarActive = dateRangePickerState.selectedStartDateMillis != null,
                    onFilterClick = { showRenterFilterPanel = true },
                    filterActive = renterFilterValues.any { it.value.isNotBlank() }
                )

                // Filter side panel
                FilterSidePanel(
                    columns = renterFilterColumns,
                    filterValues = renterFilterValues,
                    onFilterChange = { colId, value ->
                        renterFilterValues = renterFilterValues.toMutableMap().apply { put(colId, value) }
                    },
                    onSearch = { /* filters already applied reactively */ },
                    onReset = { renterFilterValues = emptyMap() },
                    onDismiss = { showRenterFilterPanel = false },
                    visible = showRenterFilterPanel
                )

                if (selectedRenters.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SuccessButton(
                            label = "To'lov",
                            icon = Icons.Default.Payments,
                            onClick = {
                                viewModel.payWeeklyForRenters(selectedRenters)
                                Toast.makeText(localContext, "To'lov qabul qilindi", Toast.LENGTH_SHORT).show()
                                selectedRenters = emptySet()
                            },
                            modifier = Modifier.weight(1.4f)
                        )
                        PrimaryButton(
                            label = "Uzish",
                            icon = Icons.Default.PowerOff,
                            onClick = {
                                viewModel.terminateRenters(selectedRenters)
                                Toast.makeText(localContext, "Kontrakt tugatildi", Toast.LENGTH_SHORT).show()
                                selectedRenters = emptySet()
                            },
                            modifier = Modifier.weight(1.2f)
                        )
                        // SMS yuborish tugmasi — tanlangan mijozlarga
                        UnifiedButton(
                            label = "SMS",
                            icon = Icons.Default.Sms,
                            onClick = {
                                val rentersToSend = renters.filter { it.id in selectedRenters }
                                var sentCount = 0
                                var failCount = 0
                                rentersToSend.forEach { renter ->
                                    val settingsRepo = com.example.data.SettingsRepository(localContext)
                                    val currentTime = System.currentTimeMillis()
                                    val elapsedDays = ((currentTime - renter.rentStartDateTimestamp) / (1000L * 60 * 60 * 24)).toInt()
                                    val daysOverdue = elapsedDays - renter.rentDurationDays
                                    val phone = com.example.worker.SimHelper.normalizePhoneNumber(renter.phoneNumber)
                                    // Долг = -balance (balance < 0). debtAmount может быть рассинхронизирован.
                                    val debt = maxOf(0.0, -renter.balance)
                                    val message = settingsRepo.smsTemplate
                                        .replace("{name}", renter.name.trim().lowercase())
                                        .replace("{days}", maxOf(1, daysOverdue).toString())
                                        .replace("{debt}", debt.toLong().toString())
                                        .replace("{payme}", settingsRepo.paymeLink)
                                        .replace("{call}", settingsRepo.callCenter)
                                    val smsManager = com.example.worker.SimHelper.getSmsManagerForSim(localContext)
                                    if (smsManager != null) {
                                        try {
                                            com.example.worker.SimHelper.sendSmsAuto(smsManager, phone, message, null, null)
                                            if (daysOverdue > 0 && !renter.isOverdueSmsSent) {
                                                viewModel.updateRenter(renter.copy(isOverdueSmsSent = true))
                                            }
                                            sentCount++
                                        } catch (e: Exception) {
                                            Log.w("SMS", "Failed for ${renter.name}: ${e.message}")
                                            failCount++
                                        }
                                    } else {
                                        failCount++
                                    }
                                }
                                if (sentCount > 0) {
                                    Toast.makeText(localContext, "$sentCount ta SMS yuborildi${if (failCount > 0) ", $failCount ta xato" else ""}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(localContext, "SMS yuborib bo'lmadi", Toast.LENGTH_SHORT).show()
                                }
                                selectedRenters = emptySet()
                            },
                            variant = UnifiedButtonVariant.PRIMARY,
                            modifier = Modifier.weight(1.0f)
                        )
                        // O'chirish tugmasi
                        DangerOutlinedButton(
                            label = "O'chir",
                            icon = Icons.Default.Delete,
                            onClick = {
                                selectedRenters.forEach { id -> viewModel.deleteRenter(id) }
                                selectedRenters = emptySet()
                            }
                        )
                    }
                }

                // ===== ТАБЛИЦА АРЕНДАТОРОВ =====
                val dateFmtLocal = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

                val filteredRenters = renters.filter { renter ->
                    val textMatch = renter.name.contains(searchQuery, ignoreCase = true) ||
                        renter.phoneNumber.contains(searchQuery) ||
                        (renter.scooterName != null && renter.scooterName.contains(searchQuery, ignoreCase = true))
                    val startMillis = dateRangePickerState.selectedStartDateMillis
                    val endMillis = dateRangePickerState.selectedEndDateMillis
                    val dateMatch = if (startMillis != null) {
                        val expiryTime =
                            renter.rentStartDateTimestamp + (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                        if (endMillis != null) expiryTime in startMillis..endMillis
                        else expiryTime >= startMillis
                    } else true
                    // Column filters from side panel
                    val expiryTs = renter.rentStartDateTimestamp + (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                    val filterMatch = renterFilterValues.all { (colId, filterText) ->
                        if (filterText.isBlank()) true
                        else when (colId) {
                            "col_name" -> renter.name.contains(filterText, ignoreCase = true)
                            "col_phone" -> renter.phoneNumber.contains(filterText, ignoreCase = true)
                            "col_scooter" -> (renter.scooterName ?: "").contains(filterText, ignoreCase = true)
                            "col_start" -> dateFmtLocal.format(Date(renter.rentStartDateTimestamp)).contains(filterText, ignoreCase = true)
                            "col_end" -> dateFmtLocal.format(Date(expiryTs)).contains(filterText, ignoreCase = true)
                            "col_balance" -> renter.balance.toLong().toString().contains(filterText, ignoreCase = true)
                            "col_passport" -> renter.passportData.contains(filterText, ignoreCase = true)
                            "col_address" -> renter.address.contains(filterText, ignoreCase = true)
                            "col_pinfl" -> renter.pinfl.contains(filterText, ignoreCase = true)
                            else -> true
                        }
                    }
                    textMatch && dateMatch && filterMatch
                }.let { list ->
                    // New 4-state sort: NONE → ASC → NONE → DESC → NONE
                    val col = renterSortState.activeColumn
                    val state = renterSortState.stateFor(col ?: "")
                    if (state == SortState.NONE) {
                        // Default: sort by status (expiry time) ASC
                        list.sortedWith(compareBy {
                            it.rentStartDateTimestamp + (it.rentDurationDays * 24L * 60 * 60 * 1000)
                        })
                    } else {
                        val comparator = when (col) {
                            "col_name" -> compareBy<Renter> { it.name.lowercase() }
                            "col_phone" -> compareBy<Renter> { it.phoneNumber }
                            "col_scooter" -> compareBy<Renter> { it.scooterName ?: "" }
                            "col_start" -> compareBy<Renter> { it.rentStartDateTimestamp }
                            "col_end" -> compareBy<Renter> { it.rentStartDateTimestamp + (it.rentDurationDays * 24L * 60 * 60 * 1000) }
                            "col_balance" -> compareBy<Renter> { it.balance }
                            "col_passport" -> compareBy<Renter> { it.passportData }
                            "col_address" -> compareBy<Renter> { it.address }
                            "col_pinfl" -> compareBy<Renter> { it.pinfl }
                            else -> compareBy<Renter> { it.rentStartDateTimestamp }
                        }
                        if (state == SortState.ASCENDING) list.sortedWith(comparator)
                        else list.sortedWith(comparator.reversed())
                    }
                }

                RenterTable(
                    renters = filteredRenters,
                    selected = selectedRenters,
                    sortState = renterSortState,
                    onSortClick = { colId ->
                        renterSortState = renterSortState.click(colId)
                    },
                    onSelect = { id, checked ->
                        val newSet = selectedRenters.toMutableSet()
                        if (checked) newSet.add(id) else newSet.remove(id)
                        selectedRenters = newSet
                    },
                    onClick = { renter ->
                        // Клик по строке → экран истории контрактов
                        navState = NavigationState.RenterHistory(renter)
                    }
                )
            } else {
                // Вкладка «Скутеры» — unified search bar
                UnifiedSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Skuter qidirish",
                    onCalendarClick = null,  // у скутеров нет дат — календарь не нужен
                    onFilterClick = { showScooterFilterPanel = true },
                    filterActive = scooterFilterValues.any { it.value.isNotBlank() }
                )

                // Filter side panel
                FilterSidePanel(
                    columns = scooterFilterColumns,
                    filterValues = scooterFilterValues,
                    onFilterChange = { colId, value ->
                        scooterFilterValues = scooterFilterValues.toMutableMap().apply { put(colId, value) }
                    },
                    onSearch = { /* filters applied reactively */ },
                    onReset = { scooterFilterValues = emptyMap() },
                    onDismiss = { showScooterFilterPanel = false },
                    visible = showScooterFilterPanel
                )

                if (selectedScooters.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${selectedScooters.size} ta tanlandi",
                            color = ClaudeText,
                            modifier = Modifier.weight(1f)
                        )
                        DangerButton(
                            label = "O'chir",
                            icon = Icons.Default.Delete,
                            onClick = {
                                scooters.filter { it.id in selectedScooters }.forEach {
                                    scooterViewModel.deleteScooter(it)
                                }
                                selectedScooters = setOf()
                            }
                        )
                    }
                }

                val filteredScooters = scooters.filter { scooter ->
                    val textMatch = scooter.name.contains(searchQuery, ignoreCase = true)
                    val filterMatch = scooterFilterValues.all { (colId, filterText) ->
                        if (filterText.isBlank()) true
                        else when (colId) {
                            "col_name" -> scooter.name.contains(filterText, ignoreCase = true)
                            "col_doc" -> (scooter.documentedNumber ?: "").contains(filterText, ignoreCase = true)
                            "col_status" -> {
                                val status = scooterStatusLabel(scooterStatusOf(scooter.id, renters))
                                status.contains(filterText, ignoreCase = true)
                            }
                            else -> true
                        }
                    }
                    textMatch && filterMatch
                }.let { list ->
                    val col = scooterSortState.activeColumn
                    val state = scooterSortState.stateFor(col ?: "")
                    if (state == SortState.NONE) {
                        list.sortedBy { it.name.lowercase() }
                    } else {
                        val comparator = when (col) {
                            "col_name" -> compareBy<Scooter> { it.name.lowercase() }
                            else -> compareBy<Scooter> { it.name.lowercase() }
                        }
                        if (state == SortState.ASCENDING) list.sortedWith(comparator)
                        else list.sortedWith(comparator.reversed())
                    }
                }

                ScooterTable(
                    scooters = filteredScooters,
                    renters = renters,
                    selected = selectedScooters,
                    sortState = scooterSortState,
                    onSortClick = { colId ->
                        scooterSortState = scooterSortState.click(colId)
                    },
                    onSelect = { id, checked ->
                        val newSet = selectedScooters.toMutableSet()
                        if (checked) newSet.add(id) else newSet.remove(id)
                        selectedScooters = newSet
                    },
                    onClick = { scooter ->
                        // Клик по скутеру → экран истории контрактов скутера
                        navState = NavigationState.ScooterHistory(scooter)
                    }
                )
            }
        }

        // ===== Диалог создания/редактирования арендатора =====
        if (showAddDialog || renterToEdit != null) {
            val isEdit = renterToEdit != null
            val weekly by settingsViewModel.weeklyPrice.collectAsStateWithLifecycle()
            val monthly by settingsViewModel.monthlyPrice.collectAsStateWithLifecycle()

            RenterFormDialog(
                initialRenter = renterToEdit,
                weeklyPrice = weekly,
                monthlyPrice = monthly,
                scooters = scooters,
                activeRenters = renters,
                onDismiss = {
                    showAddDialog = false
                    renterToEdit = null
                },
                onSave = { result ->
                    if (isEdit) {
                        renterToEdit?.let {
                            // Используем новую функцию с авто-корректировкой контрактов
                            viewModel.updateRenterWithContracts(
                                existing = it,
                                newName = result.name,
                                newPhone = result.phone,
                                newDebt = result.debt,
                                newDuration = result.duration,
                                newStartTimestamp = result.startTimestamp,
                                newScooterId = result.scooterId,
                                newScooterName = result.scooterName,
                                newIsActive = result.isActive,
                                weeklyPrice = weekly,
                                passportData = result.passportData,
                                address = result.address,
                                pinfl = result.pinfl
                            )
                        }
                    } else {
                        viewModel.addRenter(
                            name = result.name,
                            phone = result.phone,
                            debt = result.debt,
                            duration = result.duration,
                            startTimestamp = result.startTimestamp,
                            scooterId = result.scooterId,
                            scooterName = result.scooterName,
                            weeklyPrice = weekly,
                            passportData = result.passportData,
                            address = result.address,
                            pinfl = result.pinfl
                        )
                    }
                    showAddDialog = false
                    renterToEdit = null
                }
            )
        }

        if (showAddScooterDialog || scooterToEdit != null) {
            val isEditScooter = scooterToEdit != null
            ScooterFormDialog(
                initialScooter = scooterToEdit,
                existingScooters = scooters,
                onDismiss = {
                    showAddScooterDialog = false
                    scooterToEdit = null
                },
                onSave = { name, docNum, vin, engine, serial, batt1, batt2, extra ->
                    if (isEditScooter) {
                        scooterToEdit?.let {
                            scooterViewModel.updateScooter(
                                it.copy(
                                    name = name,
                                    documentedNumber = docNum,
                                    vinNumber = vin,
                                    engineNumber = engine,
                                    scooterSerialNumber = serial,
                                    batteryId1 = batt1,
                                    batteryId2 = batt2,
                                    additionalInfo = extra
                                )
                            )
                        }
                    } else {
                        scooterViewModel.addScooter(
                            name = name,
                            documentedNumber = docNum,
                            vinNumber = vin,
                            engineNumber = engine,
                            scooterSerialNumber = serial,
                            batteryId1 = batt1,
                            batteryId2 = batt2,
                            additionalInfo = extra
                        )
                    }
                    showAddScooterDialog = false
                    scooterToEdit = null
                }
            )
        }

        if (showSettings) {
            val template by settingsViewModel.smsTemplate.collectAsStateWithLifecycle()
            val weekly by settingsViewModel.weeklyPrice.collectAsStateWithLifecycle()
            val monthly by settingsViewModel.monthlyPrice.collectAsStateWithLifecycle()
            SettingsDialog(
                currentTemplate = template,
                currentWeeklyPrice = weekly,
                currentMonthlyPrice = monthly,
                updateInfo = updateInfo,
                isCheckingUpdate = isCheckingUpdate,
                isUpToDate = isUpToDate,
                updateState = updateState,
                onStartUpdate = { info ->
                    coroutineScope.launch {
                        if (!updateManager.canInstallFromUnknownSources()) {
                            updateManager.openInstallPermissionSettings()
                            Toast.makeText(localContext, "Ilova sozlamalaridan \"Noma'lum manbalardan o'rnatish\" ruxsatini bering", Toast.LENGTH_LONG).show()
                        } else {
                            updateManager.downloadAndInstall(info)
                        }
                    }
                },
                onDismiss = { showSettings = false },
                onSave = { newTemplate, newWeekly, newMonthly, _, _ ->
                    settingsViewModel.updateTemplate(newTemplate)
                    settingsViewModel.updatePrices(newWeekly, newMonthly)
                    showSettings = false
                },
                onLogout = {
                    showSettings = false
                },
                onCheckUpdate = {
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        coroutineScope.launch {
                            val checker = UpdateChecker(localContext)
                            val (result, info) = checker.checkForUpdate()
                            when (result) {
                                UpdateCheckResult.UPDATE_AVAILABLE -> {
                                    updateInfo = info
                                    isUpToDate = false
                                }
                                UpdateCheckResult.UP_TO_DATE -> {
                                    updateInfo = null
                                    isUpToDate = true
                                }
                                UpdateCheckResult.ERROR -> {
                                    updateInfo = null
                                    isUpToDate = false
                                }
                            }
                            isCheckingUpdate = false
                        }
                    }
                }
            )
        }

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
                    title = { Text("Muddati bo'yicha filter", modifier = Modifier.padding(16.dp)) },
                    headline = { Text("Davrni tanlang", modifier = Modifier.padding(16.dp)) }
                )
            }
        }

        // SMS natijalari — dialog o'chirildi, faqat Toast ko'rsatiladi
        LaunchedEffect(Unit) {
            viewModel.smsResults.collect { result ->
                if (result.success) {
                    Toast.makeText(localContext, result.message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(localContext, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/* ============================================================================
   ТАБЛИЦА АРЕНДАТОРОВ
   ============================================================================ */

// Старый HeaderCell удалён — заменён на SortableHeaderCell / NonSortableHeaderCell
// из ui.components.UnifiedTable для унификации дизайна во всех таблицах.

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RenterTable(
    renters: List<Renter>,
    selected: Set<Int>,
    sortState: TableSortState,
    onSortClick: (String) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onClick: (Renter) -> Unit
) {
    // ── Две компоновки ───────────────────────────────────────────────────
    // 1) БЕЗ опциональных колонок: weight-based Row, БЕЗ горизонтального
    //    скролла. Все 6 базовых колонок (Name, Phone, Scooter, Start, End,
    //    Balance) умещаются на экране телефона пропорционально. Так
    //    Balans и Tugash (= data poslednego kontrakta) всегда видны.
    // 2) С опциональными колонками: fixed widths + horizontalScroll.
    //    Базовые 6 + Passport/Address/Pinfl — пользователь скроллит вправо.
    val wName     = 110.dp
    val wPhone    = 100.dp
    val wScoot    = 80.dp
    val wStart    = 85.dp
    val wEnd      = 85.dp
    val wDebt     = 70.dp
    val wPassport = 110.dp
    val wAddress  = 140.dp
    val wPinfl    = 95.dp

    // Weight-based widths для базовых колонок (когда нет extras).
    val fName  = 1.4f
    val fPhone = 1.0f
    val fScoot = 0.8f
    val fStart = 1.0f
    val fEnd   = 1.1f
    val fDebt  = 0.9f

    // Определяем, какие опциональные колонки нужно показать (хотя бы у одного
    // арендатора есть непустое значение в этом поле).
    val hasPassport = remember(renters) { renters.any { it.passportData.isNotBlank() } }
    val hasAddress  = remember(renters) { renters.any { it.address.isNotBlank() } }
    val hasPinfl    = remember(renters) { renters.any { it.pinfl.isNotBlank() } }
    val hasAnyExtra = hasPassport || hasAddress || hasPinfl

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val hScrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Заголовок ────────────────────────────────────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .then(if (hasAnyExtra) Modifier.horizontalScroll(hScrollState) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAnyExtra) {
                    // Fixed-width версия — со скроллом
                    SortableHeaderCellFixed(Icons.Default.Person,               wName,  "col_name",      sortState) { onSortClick("col_name") }
                    SortableHeaderCellFixed(Icons.Default.Phone,                wPhone, "col_phone",     sortState) { onSortClick("col_phone") }
                    SortableHeaderCellFixed(Icons.Default.DirectionsBike,       wScoot, "col_scooter",   sortState) { onSortClick("col_scooter") }
                    SortableHeaderCellFixed(Icons.Default.CalendarToday,        wStart, "col_start",     sortState) { onSortClick("col_start") }
                    SortableHeaderCellFixed(Icons.Default.Event,                wEnd,   "col_end",       sortState) { onSortClick("col_end") }
                    SortableHeaderCellFixed(Icons.Default.AccountBalanceWallet, wDebt,  "col_balance",   sortState) { onSortClick("col_balance") }
                    if (hasPassport) SortableHeaderCellFixed(Icons.Default.CreditCard,  wPassport, "col_passport", sortState) { onSortClick("col_passport") }
                    if (hasAddress)  SortableHeaderCellFixed(Icons.Default.Home,       wAddress,  "col_address",  sortState) { onSortClick("col_address") }
                    if (hasPinfl)    SortableHeaderCellFixed(Icons.Default.Fingerprint, wPinfl,    "col_pinfl",    sortState) { onSortClick("col_pinfl") }
                } else {
                    // Weight-based версия — все 6 базовых колонок помещаются на экране
                    SortableHeaderCell(Icons.Default.Person,               fName,  "col_name",      sortState) { onSortClick("col_name") }
                    SortableHeaderCell(Icons.Default.Phone,                fPhone, "col_phone",     sortState) { onSortClick("col_phone") }
                    SortableHeaderCell(Icons.Default.DirectionsBike,       fScoot, "col_scooter",   sortState) { onSortClick("col_scooter") }
                    SortableHeaderCell(Icons.Default.CalendarToday,        fStart, "col_start",     sortState) { onSortClick("col_start") }
                    SortableHeaderCell(Icons.Default.Event,                fEnd,   "col_end",       sortState) { onSortClick("col_end") }
                    SortableHeaderCell(Icons.Default.AccountBalanceWallet, fDebt,  "col_balance",   sortState) { onSortClick("col_balance") }
                }
            }
        }
        HorizontalDivider(color = ClaudeDivider)

        if (renters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Mijozlar yo'q",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(renters, key = { it.id }) { renter ->
                val isSelected = selected.contains(renter.id)
                val status = statusOf(renter)
                val sColor = statusColor(status)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .then(if (hasAnyExtra) Modifier.horizontalScroll(hScrollState) else Modifier.fillMaxWidth())
                            .border(
                                width = if (isSelected) 2.dp else 1.5.dp,
                                color = sColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(
                                if (isSelected) Color(0xFFF3F4F6) else Color.White
                            )
                            .combinedClickable(
                                onClick = { if (isSelected) onSelect(renter.id, false) else onClick(renter) },
                                onLongClick = { onSelect(renter.id, !isSelected) }
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mijoz
                        Text(
                            renter.name,
                            modifier = Modifier
                                .then(if (hasAnyExtra) Modifier.width(wName) else Modifier.weight(fName))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        // Tel
                        Text(
                            renter.phoneNumber,
                            modifier = Modifier
                                .then(if (hasAnyExtra) Modifier.width(wPhone) else Modifier.weight(fPhone))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary,
                            maxLines = 1
                        )
                        // Skuter
                        Text(
                            renter.scooterName ?: "—",
                            modifier = Modifier
                                .then(if (hasAnyExtra) Modifier.width(wScoot) else Modifier.weight(fScoot))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeText,
                            maxLines = 1
                        )
                        // Boshlanish
                        Text(
                            dateFmt.format(Date(renter.rentStartDateTimestamp)),
                            modifier = Modifier
                                .then(if (hasAnyExtra) Modifier.width(wStart) else Modifier.weight(fStart))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeText,
                            maxLines = 1
                        )
                        // Tugash (= data poslednego kontrakta)
                        val expiry = renter.rentStartDateTimestamp +
                            (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                        Text(
                            dateFmt.format(Date(expiry)),
                            modifier = Modifier
                                .then(if (hasAnyExtra) Modifier.width(wEnd) else Modifier.weight(fEnd))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeText,
                            maxLines = 1
                        )
                        // Balans (может быть отрицательным = долг, положительным = аванс)
                        val balanceColor = when {
                            renter.balance < 0 -> StatusOverdue
                            renter.balance > 0 -> StatusOk
                            else -> ClaudeText
                        }
                        Text(
                            renter.balance.toLong().toString(),
                            modifier = Modifier
                                .then(if (hasAnyExtra) Modifier.width(wDebt) else Modifier.weight(fDebt))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = balanceColor,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            maxLines = 1
                        )
                        // ── Опциональные колонки (только если есть данные) ────
                        if (hasPassport) {
                            Text(
                                renter.passportData.ifBlank { "—" },
                                modifier = Modifier.width(wPassport).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (hasAddress) {
                            Text(
                                renter.address.ifBlank { "—" },
                                modifier = Modifier.width(wAddress).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (hasPinfl) {
                            Text(
                                renter.pinfl.ifBlank { "—" },
                                modifier = Modifier.width(wPinfl).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ============================================================================
   ДИАЛОГ ИСТОРИИ УВЕДОМЛЕНИЙ
   ============================================================================ */

@Composable
fun NotificationHistoryDialog(
    history: List<NotificationHistoryEntity>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = ClaudeAccent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bildirishnomalar tarixi", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Hech qanday bildirishnoma yo'q",
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                ) {
                    items(history, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        entry.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClaudeText,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        dateFmt.format(Date(entry.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClaudeTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (history.isNotEmpty()) {
                    TextActionButton(
                        label = "Tozalash",
                        icon = Icons.Default.Clear,
                        onClick = onClear
                    )
                }
                TextActionButton(
                    label = "Yopish",
                    icon = Icons.Default.Close,
                    onClick = onDismiss
                )
            }
        }
    )
}

/* ============================================================================
   ФОРМЫ СОЗДАНИЯ / РЕДАКТИРОВАНИЯ
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenterFormDialog(
    initialRenter: Renter?,
    weeklyPrice: Double,
    monthlyPrice: Double,
    scooters: List<Scooter> = emptyList(),
    activeRenters: List<Renter> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (RenterFormResult) -> Unit
) {
    var name by remember { mutableStateOf(initialRenter?.name ?: "") }
    var phone by remember {
        mutableStateOf(initialRenter?.phoneNumber?.filter { it.isDigit() }?.takeLast(9) ?: "")
    }
    var debt by remember {
        // Показываем долг = -balance (если balance < 0), иначе debtAmount
        val displayDebt = if ((initialRenter?.balance ?: 0.0) < 0) -initialRenter!!.balance
                          else initialRenter?.debtAmount ?: 0.0
        mutableStateOf(displayDebt.toString())
    }
    var duration by remember {
        mutableStateOf(initialRenter?.rentDurationDays?.toString() ?: "7")
    }
    var isActive by remember { mutableStateOf(initialRenter?.isReturned != true) }

    var startTimestamp by remember {
        mutableStateOf(initialRenter?.rentStartDateTimestamp ?: System.currentTimeMillis())
    }
    var showStartDatePicker by remember { mutableStateOf(false) }
    val startDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = startTimestamp
    )

    // ── PDF-реквизиты арендатора ────────────────────────────────────────
    var passportData by remember { mutableStateOf(initialRenter?.passportData ?: "") }
    var address by remember { mutableStateOf(initialRenter?.address ?: "") }
    var pinfl by remember { mutableStateOf(initialRenter?.pinfl ?: "") }

    // Примечание: реквизиты скутера (VIN, двигатель, ID, аккумы, доп. инфо)
    // заполняются в ScooterFormDialog — это атрибуты скутера, а не арендатора.
    // При создании контракта они автоматически подтягиваются из БД по scooterId.

    val durationOptions = listOf(
        "1 Hafta" to 7, "2 Hafta" to 14, "3 Hafta" to 21,
        "1 Oy" to 30, "2 Oy" to 60, "3 Oy" to 90, "4 Oy" to 120
    )
    var selectedDurationText by remember {
        mutableStateOf(
            durationOptions.find { it.second.toString() == duration }?.first ?: "1 Hafta"
        )
    }
    var expandedDuration by remember { mutableStateOf(false) }

    var selectedScooterId by remember { mutableStateOf<Int?>(initialRenter?.scooterId) }
    var expandedScooter by remember { mutableStateOf(false) }

    val isEdit = initialRenter != null

    // Вычисляем ID скутеров, которые уже арендованы активными арендаторами
    // (исключаем текущего арендатора при редактировании — его скутер должен быть доступен)
    val rentedScooterIds = activeRenters
        .filter { it.scooterId != null && !it.isReturned && it.id != initialRenter?.id }
        .mapNotNull { it.scooterId }
        .toSet()

    // Доступные скутеры = не арендованные + текущий скутер арендатора (при редактировании)
    val availableScooters = scooters.filter { scooter ->
        scooter.id !in rentedScooterIds
    }

    // Если выбранный скутер уже арендован другим — сбрасываем выбор
    LaunchedEffect(rentedScooterIds) {
        if (selectedScooterId != null && selectedScooterId in rentedScooterIds) {
            selectedScooterId = null
        }
    }

    val scrollState = rememberScrollState()

    // Автоматически разворачиваем блок доп. полей при редактировании, если хотя бы одно поле заполнено
    val hasAnyExtraPrefilled = remember(initialRenter) {
        val r = initialRenter
        r != null && (
            r.passportData.isNotBlank() || r.address.isNotBlank() || r.pinfl.isNotBlank()
        )
    }
    var showExtraFields by remember { mutableStateOf(hasAnyExtraPrefilled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEdit) "Mijozni tahrirlash" else "Yangi ijarachi",
                style = MaterialTheme.typography.titleLarge,
                color = ClaudeText
            )
        },
        containerColor = ClaudeCard,
        textContentColor = ClaudeText,
        titleContentColor = ClaudeText,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Секция: Шахсий маълумотлар ──────────────────────────
                SectionLabel("Шахсий маълумотлар")

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("To'liq ism (ФИШ)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeTextSecondary
                    )
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { newValue ->
                        phone = newValue.filter { it.isDigit() }.take(9)
                    },
                    label = { Text("Telefon raqami") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    visualTransformation = UzPhoneVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
                SectionLabel("Ижара шартлари")

                // Кнопка выбора даты начала аренды
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStartDatePicker = true },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ClaudeDivider)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = ClaudeAccent)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Ijara boshlash sanasi",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                            Text(
                                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                                    .format(Date(startTimestamp)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText
                            )
                        }
                    }
                }

                if (isEdit) {
                    Text(
                        "Holat: ${if (initialRenter?.isReturned == true) "Qaytarilgan" else "Faol"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = expandedDuration,
                    onExpandedChange = { expandedDuration = !expandedDuration }
                ) {
                    OutlinedTextField(
                        value = selectedDurationText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Ijara muddati") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDuration)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDuration,
                        onDismissRequest = { expandedDuration = false }
                    ) {
                        durationOptions.forEach { (label, days) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedDurationText = label
                                    duration = days.toString()
                                    expandedDuration = false
                                }
                            )
                        }
                    }
                }

                val selectedScooter = availableScooters.find { it.id == selectedScooterId }
                    ?: scooters.find { it.id == selectedScooterId }
                val scooterText = selectedScooter?.name ?: "Tanlanmagan"

                ExposedDropdownMenuBox(
                    expanded = expandedScooter,
                    onExpandedChange = { expandedScooter = !expandedScooter }
                ) {
                    OutlinedTextField(
                        value = scooterText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Skuter (ixtiyoriy)") },
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
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan") },
                            onClick = {
                                selectedScooterId = null
                                expandedScooter = false
                            }
                        )
                        availableScooters.forEach { scooter ->
                            DropdownMenuItem(
                                text = { Text(scooter.name) },
                                onClick = {
                                    selectedScooterId = scooter.id
                                    expandedScooter = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = debt,
                    onValueChange = { debt = it },
                    label = { Text("Qarz miqdori") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // ── Кнопка-тумблер: «Қўшимча маълумотлар» ───────────────────
                // Разворачивает/сворачивает 9 PDF-реквизитов (паспорт, манзил,
                // ПИНФЛ, VIN, двигатель, ID, аккумы, доп. инфо). По умолчанию
                // свёрнуты — пользователь сам решает заполнять их или нет.
                HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showExtraFields = !showExtraFields },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (showExtraFields) ClaudeText else ClaudeDivider)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showExtraFields) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = ClaudeText
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (showExtraFields) "Қўшимча маълумотларни яшириш" else "Қўшимча маълумотларни кўрсатиш",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ── Развёрнутый блок 9 PDF-полей ───────────────────────────
                if (showExtraFields) {
                    SectionLabel("Шахсий қўшимча маълумотлар")
                    OutlinedTextField(
                        value = passportData,
                        onValueChange = { passportData = it },
                        label = { Text("Паспорт: серия, рақам, олинган сана") },
                        placeholder = { Text("Masalan: AA 1234567, 15.01.2023") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Манзил") },
                        placeholder = { Text("Masalan: Тошкент ш., Юнусобод тумани, ...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = pinfl,
                        onValueChange = { pinfl = it.filter { ch -> ch.isDigit() }.take(14) },
                        label = { Text("ЖШШИР (ПИНФЛ)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                onClick = {
                    val debtValue = debt.toDoubleOrNull() ?: 0.0
                    val durationValue = duration.toIntOrNull() ?: 7
                    val phoneToSave = "+998$phone"
                    val scooterName = scooters.find { it.id == selectedScooterId }?.name
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        onSave(
                            RenterFormResult(
                                name = name,
                                phone = phoneToSave,
                                debt = debtValue,
                                duration = durationValue,
                                startTimestamp = startTimestamp,
                                scooterId = selectedScooterId,
                                scooterName = scooterName,
                                isActive = isActive,
                                passportData = passportData.trim(),
                                address = address.trim(),
                                pinfl = pinfl.trim()
                            )
                        )
                    }
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

    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextActionButton(
                    label = "Tanlash",
                    icon = Icons.Default.Check,
                    onClick = {
                        startDatePickerState.selectedDateMillis?.let { startTimestamp = it }
                        showStartDatePicker = false
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Bekor",
                    icon = Icons.Default.Close,
                    onClick = { showStartDatePicker = false }
                )
            }
        ) {
            DatePicker(state = startDatePickerState)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = ClaudeAccent,
        fontWeight = FontWeight.SemiBold
    )
}

data class RenterFormResult(
    val name: String,
    val phone: String,
    val debt: Double,
    val duration: Int,
    val startTimestamp: Long,
    val scooterId: Int?,
    val scooterName: String?,
    val isActive: Boolean,
    val passportData: String,
    val address: String,
    val pinfl: String
)

@Composable
fun SettingsDialog(
    currentTemplate: String,
    currentWeeklyPrice: Double,
    currentMonthlyPrice: Double,
    updateInfo: UpdateInfo? = null,
    isCheckingUpdate: Boolean = false,
    isUpToDate: Boolean = false,
    updateState: InAppUpdateState = InAppUpdateState.Idle,
    onStartUpdate: (UpdateInfo) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, String, String) -> Unit,
    onLogout: () -> Unit = {},
    onCheckUpdate: () -> Unit = {}
) {
    var template by remember { mutableStateOf(currentTemplate) }
    var weekly by remember {
        mutableStateOf(if (currentWeeklyPrice > 0) currentWeeklyPrice.toString() else "")
    }
    var monthly by remember {
        mutableStateOf(if (currentMonthlyPrice > 0) currentMonthlyPrice.toString() else "")
    }
    val settingsContext = LocalContext.current
    val settingsRepo = remember { com.example.data.SettingsRepository(settingsContext) }
    var paymeLink by remember { mutableStateOf(settingsRepo.paymeLink) }
    var callCenter by remember { mutableStateOf(settingsRepo.callCenter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sozlamalar", style = MaterialTheme.typography.titleLarge) },
        containerColor = ClaudeCard,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Tariflar", style = MaterialTheme.typography.labelMedium, color = ClaudeText)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = weekly,
                        onValueChange = { weekly = it },
                        label = { Text("Haftalik tarif narxi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = monthly,
                        onValueChange = { monthly = it },
                        label = { Text("Oylik tarif narxi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Column {
                    Text("SMS Shabloni", style = MaterialTheme.typography.labelMedium, color = ClaudeText)
                    Text(
                        "Mavjud teglar: {name}, {days}, {debt}, {payme}, {call}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClaudeTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = ClaudeDivider,
                            focusedBorderColor = ClaudeTextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = paymeLink,
                        onValueChange = { paymeLink = it },
                        label = { Text("Payme to'lov havolasi ({payme})") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = callCenter,
                        onValueChange = { callCenter = it },
                        label = { Text("Call center raqami ({call})") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                }

                HorizontalDivider()

                // ── SIM karta tanlash ───────────────────────
                Column {
                    Text(
                        "SIM karta",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClaudeText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "SMS yuborish uchun SIM kartani tanlang. 2 ta SIM bo'lsa, tanlamasangiz xato chiqishi mumkin (GENERIC_FAILURE).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val simContext = LocalContext.current
                    val simCards = remember {
                        com.example.worker.SimHelper.getActiveSimCards(simContext)
                    }
                    val settingsRepo = remember { com.example.data.SettingsRepository(simContext) }
                    var selectedSimSubId by remember {
                        mutableStateOf(settingsRepo.selectedSimSubscriptionId)
                    }

                    if (simCards.isEmpty()) {
                        // Permission yo'q yoki SIM topilmadi
                        val hasPermission = com.example.worker.SimHelper.hasPhoneStatePermission(simContext)
                        if (!hasPermission) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "SIM ma'lumotlarini olish uchun ruxsat kerak",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF000000)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SecondaryButton(
                                        label = "Ochish",
                                        icon = Icons.Default.OpenInNew,
                                        onClick = {
                                            // Permission ni qo'lda so'rash
                                            simContext.startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    android.net.Uri.fromParts("package", simContext.packageName, null)
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        } else {
                            Text(
                                "SIM karta topilmadi (faqat 1 ta SIM yoki emulyator)",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary
                            )
                        }
                    } else if (simCards.size == 1) {
                        // 1 ta SIM — avto-tanlangan
                        Text(
                            "✓ ${simCards[0].fullDisplayName} (avto-tanlangan)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF000000)
                        )
                    } else {
                        // 2+ ta SIM — tanlash
                        simCards.forEach { sim ->
                            val isSelected = selectedSimSubId == sim.subscriptionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFF0F0F0) else Color.Transparent)
                                    .clickable {
                                        selectedSimSubId = sim.subscriptionId
                                        settingsRepo.selectedSimSubscriptionId = sim.subscriptionId
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedSimSubId = sim.subscriptionId
                                        settingsRepo.selectedSimSubscriptionId = sim.subscriptionId
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF000000)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        sim.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF000000) else ClaudeText
                                    )
                                    if (!sim.phoneNumber.isNullOrBlank()) {
                                        Text(
                                            sim.phoneNumber,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClaudeTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // ── Update section ───────────────────────────
                Column {
                    Text(
                        "Ilova yangilanishlari",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClaudeText
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    when (updateState) {
                        is InAppUpdateState.Downloading -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Yuklab olinmoqda... ${(updateState.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF000000)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { updateState.progress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF000000),
                                        trackColor = Color(0xFFE5E5E5)
                                    )
                                }
                            }
                        }
                        is InAppUpdateState.Installing -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color(0xFF000000),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("O'rnatilmoqda...", color = Color(0xFF000000))
                                }
                            }
                        }
                        is InAppUpdateState.Error -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    updateState.message,
                                    modifier = Modifier.padding(12.dp),
                                    color = Color(0xFF000000),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        else -> {
                            // Idle / ReadyToInstall
                            if (updateInfo != null) {
                                // Доступно обновление
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "Yangi versiya: v${updateInfo.versionName}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF000000)
                                        )
                                        if (updateInfo.releaseNotes.isNotBlank()) {
                                            Text(
                                                updateInfo.releaseNotes.take(200),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF000000)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SuccessButton(
                                            label = "Yangila",
                                            icon = Icons.Default.Refresh,
                                            onClick = { onStartUpdate(updateInfo) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            } else if (isUpToDate) {
                                // Приложение актуально
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = Color(0xFF000000),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Ilova eng so'nggi versiyada",
                                            color = Color(0xFF000000),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            } else {
                                // Ещё не проверяли или ошибка
                                SecondaryButton(
                                    label = if (isCheckingUpdate) "Kutish" else "Tekshir",
                                    icon = Icons.Default.Refresh,
                                    onClick = onCheckUpdate,
                                    enabled = !isCheckingUpdate,
                                    loading = isCheckingUpdate,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                DangerOutlinedButton(
                    label = "Chiqish",
                    icon = Icons.Default.Logout,
                    onClick = { onLogout() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                onClick = {
                    val wPrice = weekly.toDoubleOrNull() ?: 0.0
                    val mPrice = monthly.toDoubleOrNull() ?: 0.0
                    settingsRepo.paymeLink = paymeLink.trim().ifBlank {
                        com.example.data.SettingsRepository.DEFAULT_PAYME_LINK
                    }
                    settingsRepo.callCenter = callCenter.trim().ifBlank {
                        com.example.data.SettingsRepository.DEFAULT_CALL_CENTER
                    }
                    onSave(template, wPrice, mPrice, paymeLink, callCenter)
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


class UzPhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val trimmed = if (text.text.length >= 9) text.text.substring(0, 9) else text.text
        var out = "+998 "
        for (i in trimmed.indices) {
            when (i) {
                0 -> out += "("
                2 -> out += ") "
                5 -> out += "-"
                7 -> out += "-"
            }
            out += trimmed[i]
        }
        val phoneNumberOffsetTranslator = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 5
                if (offset <= 2) return 6 + offset
                if (offset <= 5) return 8 + offset
                if (offset <= 7) return 9 + offset
                if (offset <= 9) return 10 + offset
                return 19
            }
            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 5) return 0
                if (offset <= 8) return offset - 6
                if (offset <= 13) return offset - 8
                if (offset <= 16) return offset - 9
                if (offset <= 19) return offset - 10
                return 9
            }
        }
        return TransformedText(AnnotatedString(out), phoneNumberOffsetTranslator)
    }
}

/* ============================================================================
   ТАБЛИЦА СКУТЕРОВ (с колонкой состояния)
   ============================================================================ */

private enum class ScooterStatus { RENTED, IN_BASE }

private fun scooterStatusOf(scooterId: Int, renters: List<Renter>): ScooterStatus {
    val active = renters.any { it.scooterId == scooterId && !it.isReturned }
    return if (active) ScooterStatus.RENTED else ScooterStatus.IN_BASE
}

private fun scooterStatusColor(s: ScooterStatus): Color = when (s) {
    ScooterStatus.RENTED  -> StatusOverdue
    ScooterStatus.IN_BASE -> StatusOk
}

private fun scooterStatusLabel(s: ScooterStatus): String = when (s) {
    ScooterStatus.RENTED  -> "Ijarada"
    ScooterStatus.IN_BASE -> "Bazada"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScooterTable(
    scooters: List<Scooter>,
    renters: List<Renter>,
    selected: Set<Int>,
    sortState: TableSortState,
    onSortClick: (String) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onClick: (Scooter) -> Unit
) {
    val wName  = 2.0f
    val wStat  = 1.0f

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок — только иконки
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SortableHeaderCell(Icons.Default.Label, wName, "col_name",  sortState) { onSortClick("col_name") }
                NonSortableHeaderCell(Icons.Default.Info, wStat, "Holat")
            }
        }
        HorizontalDivider(color = ClaudeDivider)

        if (scooters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Skuterlar yo'q",
                    color = ClaudeTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(scooters, key = { it.id }) { scooter ->
                val isSelected = selected.contains(scooter.id)
                val status = scooterStatusOf(scooter.id, renters)
                val sColor = scooterStatusColor(status)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 2.dp else 1.5.dp,
                                color = sColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(if (isSelected) Color(0xFFF3F4F6) else Color.White)
                            .combinedClickable(
                                onClick = { if (isSelected) onSelect(scooter.id, false) else onClick(scooter) },
                                onLongClick = { onSelect(scooter.id, !isSelected) }
                            )
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            scooter.name,
                            modifier = Modifier
                                .weight(wName)
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Row(
                            modifier = Modifier
                                .weight(wStat)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(sColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                scooterStatusLabel(status),
                                style = MaterialTheme.typography.labelSmall,
                                color = sColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Диалог создания / редактирования скутера.
 * При создании автоматически предлагает следующий свободный «BC-NNN» —
 * формат фиксированный и сохраняется при сохранении.
 */
@Composable
fun ScooterFormDialog(
    initialScooter: Scooter?,
    existingScooters: List<Scooter>,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        documentedNumber: String?,
        vinNumber: String,
        engineNumber: String,
        scooterSerialNumber: String,
        batteryId1: String,
        batteryId2: String,
        additionalInfo: String
    ) -> Unit
) {
    val initialName = remember(initialScooter, existingScooters) {
        if (initialScooter != null) {
            initialScooter.name
        } else {
            val nextN = (existingScooters
                .mapNotNull { it.name.removePrefix("BC-").trimStart('0').toIntOrNull() }
                .maxOrNull() ?: 0) + 1
            "BC-" + nextN.toString().padStart(3, '0')
        }
    }
    var name by remember { mutableStateOf(initialName) }
    var documentedNumber by remember {
        mutableStateOf(initialScooter?.documentedNumber ?: "")
    }

    // ── Реквизиты скутера и аккумуляторов для PDF-договора ──────────────
    var vinNumber by remember { mutableStateOf(initialScooter?.vinNumber ?: "") }
    var engineNumber by remember { mutableStateOf(initialScooter?.engineNumber ?: "") }
    var scooterSerialNumber by remember { mutableStateOf(initialScooter?.scooterSerialNumber ?: "") }
    var batteryId1 by remember { mutableStateOf(initialScooter?.batteryId1 ?: "") }
    var batteryId2 by remember { mutableStateOf(initialScooter?.batteryId2 ?: "") }
    var additionalInfo by remember { mutableStateOf(initialScooter?.additionalInfo ?: "") }

    // Все доп. поля свёрнуты по умолчанию. При редактировании существующего
    // скутера автоматически разворачиваются, если хотя бы одно значение уже есть.
    val hasAnyExtraPrefilled = remember(initialScooter) {
        val s = initialScooter
        s != null && (
            !s.documentedNumber.isNullOrBlank() ||
            s.vinNumber.isNotBlank() || s.engineNumber.isNotBlank() ||
            s.scooterSerialNumber.isNotBlank() || s.batteryId1.isNotBlank() ||
            s.batteryId2.isNotBlank() || s.additionalInfo.isNotBlank()
        )
    }
    var showExtraFields by remember { mutableStateOf(hasAnyExtraPrefilled) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialScooter != null) "Skuterni tahrirlash" else "Yangi skuter",
                style = MaterialTheme.typography.titleLarge,
                color = ClaudeText
            )
        },
        containerColor = ClaudeCard,
        textContentColor = ClaudeText,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skuter nomi (BC- formatida)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    supportingText = {
                        if (initialScooter == null) {
                            Text(
                                "Avtomatik raqamlandi. Istalgan nom bilan almashtirishingiz mumkin.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                        }
                    }
                )

                // ── Кнопка-тумблер «Қўшимча маълумотлар» ──────────────────
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showExtraFields = !showExtraFields },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (showExtraFields) ClaudeText else ClaudeDivider)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showExtraFields) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = ClaudeText
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (showExtraFields) "Қўшимча маълумотларни яшириш" else "Қўшимча маълумотларни кўрсатиш",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (showExtraFields) {
                    SectionLabel("Скутер ва аккумулятор маълумотлари")

                    OutlinedTextField(
                        value = documentedNumber,
                        onValueChange = { documentedNumber = it },
                        label = { Text("Hujjatlashtirilgan raqami (ixtiyoriy)") },
                        placeholder = { Text("Masalan: 01-234 ABC") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = vinNumber,
                        onValueChange = { vinNumber = it },
                        label = { Text("VIN номери") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = engineNumber,
                        onValueChange = { engineNumber = it },
                        label = { Text("Двигатель номери") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = scooterSerialNumber,
                        onValueChange = { scooterSerialNumber = it },
                        label = { Text("ID номери") },
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
                            label = { Text("Аккумулятор ID 1") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = batteryId2,
                            onValueChange = { batteryId2 = it },
                            label = { Text("Аккумулятор ID 2") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = additionalInfo,
                        onValueChange = { additionalInfo = it },
                        label = { Text("Қўшимча маълумот") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            name,
                            documentedNumber.takeIf { it.isNotBlank() },
                            vinNumber.trim(),
                            engineNumber.trim(),
                            scooterSerialNumber.trim(),
                            batteryId1.trim(),
                            batteryId2.trim(),
                            additionalInfo.trim()
                        )
                    }
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
   ДИАЛОГ ИСТОРИИ КОНТРАКТОВ
   ============================================================================ */

@Composable
fun ContractHistoryDialog(
    history: List<com.example.data.ContractHistoryEntry>,
    renters: List<Renter>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val renterNames = remember(renters) { renters.associate { it.id to it.name } }

    fun typeLabel(t: String) = when (t) {
        "CREATED" -> "Yaratildi"
        "PAYMENT" -> "To'lov"
        "AUTO_RENEW" -> "Avtomatik yangilanish"
        "TERMINATED" -> "Tugatildi"
        "RETURNED" -> "Qaytarildi"
        else -> t
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    tint = ClaudeAccent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Kontraktlar tarixi", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = ClaudeCard,
        text = {
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Tarix bo'sh",
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                ) {
                    items(history, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${renterNames[entry.renterId] ?: "Mijoz #${entry.renterId}"} — ${typeLabel(entry.type)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClaudeText,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        dateFmt.format(Date(entry.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary
                                    )
                                }
                                if (entry.amount > 0.0) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "${entry.amount.toBigDecimal().stripTrailingZeros().toPlainString()} UZS",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeText
                                    )
                                }
                                if (!entry.notes.isNullOrBlank()) {
                                    Text(
                                        entry.notes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ClaudeTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (history.isNotEmpty()) {
                    TextActionButton(
                        label = "Tozalash",
                        icon = Icons.Default.Clear,
                        onClick = onClear
                    )
                }
                TextActionButton(
                    label = "Yopish",
                    icon = Icons.Default.Close,
                    onClick = onDismiss
                )
            }
        }
    )
}
