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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
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
import com.example.data.remote.SyncManager
import com.example.data.remote.SyncResult
import com.example.data.remote.UpdateChecker
import com.example.data.remote.UpdateInfo
import com.example.ui.ContractHistoryViewModel
import com.example.ui.LoginState
import com.example.ui.LoginViewModel
import com.example.ui.NotificationHistoryViewModel
import com.example.ui.RenterViewModel
import com.example.ui.SmsResult
import com.example.ui.SettingsViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.UserRole
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.MyApplicationTheme
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

                val loginViewModel: LoginViewModel = viewModel()
                val role by loginViewModel.role.collectAsStateWithLifecycle()
                val loginState by loginViewModel.state.collectAsStateWithLifecycle()

                when (role) {
                    UserRole.NONE -> LoginScreen(
                        state = loginState,
                        onLogin = { url, email, pwd ->
                            loginViewModel.login(url, email, pwd)
                        },
                        onRegister = { url, email, pwd ->
                            loginViewModel.register(url, email, pwd)
                        },
                        onErrorShown = { loginViewModel.resetError() }
                    )
                    else -> MainScreen(
                        userRole = role,
                        loginViewModel = loginViewModel
                    )
                }
            }
        }
    }
}

enum class SortColumn {
    NAME, START_TIME, STATUS, DEBT
}

enum class SortDirection { ASC, DESC }

/**
 * Цвет статус-индикатора:
 *   • серый  — арендатор вернул скутер
 *   • красный — есть долг (просрочена оплата)
 *   • зелёный — активный, оплачено в срок
 */
private enum class RenterStatus { RETURNED, OVERDUE, OK }

private fun statusOf(renter: Renter): RenterStatus = when {
    renter.isReturned -> RenterStatus.RETURNED
    renter.debtAmount > 0.0 -> RenterStatus.OVERDUE
    else -> RenterStatus.OK
}

private fun statusColor(s: RenterStatus): Color = when (s) {
    RenterStatus.RETURNED -> Color(0xFF8E8E93) // серый
    RenterStatus.OVERDUE  -> Color(0xFFE05B44) // красный
    RenterStatus.OK       -> Color(0xFF34C759) // зелёный
}

private fun statusLabel(s: RenterStatus): String = when (s) {
    RenterStatus.RETURNED -> "Qaytgan"
    RenterStatus.OVERDUE  -> "Qarzdor"
    RenterStatus.OK       -> "Faol"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    userRole: UserRole,
    loginViewModel: LoginViewModel,
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
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showContractHistoryDialog by remember { mutableStateOf(false) }

    var sortColumn by remember { mutableStateOf(SortColumn.STATUS) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASC) }
    var scooterSortColumn by remember { mutableStateOf(SortColumn.NAME) }
    var scooterSortDirection by remember { mutableStateOf(SortDirection.ASC) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<SyncResult?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Авто-синхронизация (smart merge, каждые 30 секунд)
    DisposableEffect(Unit) {
        viewModel.startAutoSync()
        onDispose { viewModel.stopAutoSync() }
    }

    val context = LocalContext.current
    val scooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()

    // Авто-проверка обновлений при запуске
    LaunchedEffect(Unit) {
        try {
            val checker = UpdateChecker(context)
            val info = checker.checkForUpdate()
            if (info != null) {
                updateInfo = info
                Log.d("MainScreen", "Update available: v${info.versionName}")
            }
        } catch (e: Exception) {
            Log.w("MainScreen", "Auto-update check failed", e)
        }
    }

    val renters by viewModel.rentersList.collectAsStateWithLifecycle()
    val history by historyViewModel.history.collectAsStateWithLifecycle()
    val contractHistory by contractHistoryViewModel.history.collectAsStateWithLifecycle()

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
                    // Колокольчик — история уведомлений
                    IconButton(
                        onClick = { showHistoryDialog = true },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(40.dp)
                            .background(Color.White, CircleShape)
                            .border(1.dp, ClaudeDivider, CircleShape)
                    ) {
                        Box {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = "Bildirishnomalar tarixi",
                                modifier = Modifier.size(20.dp)
                            )
                            if (history.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(16.dp)
                                        .background(Color(0xFFE05B44), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        history.size.coerceAtMost(99).toString(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                    // 🔄 Синхронизация
                    IconButton(
                        onClick = {
                            if (!isSyncing) {
                                isSyncing = true
                                coroutineScope.launch {
                                    val sync = SyncManager(context)
                                    syncResult = sync.syncAll()
                                    isSyncing = false
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(end = 4.dp, start = 4.dp)
                            .size(40.dp)
                            .background(Color.White, CircleShape)
                            .border(1.dp, ClaudeDivider, CircleShape)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = ClaudeAccent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("🔄", fontSize = 18.sp)
                        }
                    }
                    // 📜 Контрактлар тарихи (рядом с ⚙️)
                    IconButton(
                        onClick = { showContractHistoryDialog = true },
                        modifier = Modifier
                            .padding(end = 4.dp, start = 4.dp)
                            .size(40.dp)
                            .background(Color.White, CircleShape)
                            .border(1.dp, ClaudeDivider, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = "Kontraktlar tarixi",
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
            if (userRole == UserRole.ADMIN) {
                FloatingActionButton(
                    onClick = {
                        if (currentTab == 0) showAddDialog = true
                        else showAddScooterDialog = true
                    },
                    containerColor = ClaudeAccent,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = if (currentTab == 0) "Ijarachi qo'shish" else "Skuter qo'shish",
                        modifier = Modifier.size(32.dp)
                    )
                }
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
                        indicatorColor = ClaudeCard
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
                        indicatorColor = ClaudeCard
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
            // ── Баннер обновления ──────────────────────────
            if (updateInfo != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(updateInfo!!.downloadUrl)
                            )
                            context.startActivity(intent)
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Yangi versiya: v${updateInfo!!.versionName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Bosing — yuklab olish",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF388E3C)
                            )
                        }
                    }
                }
            }

            if (currentTab == 0) {
                // Поиск
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Mijoz yoki skuter qidirish", color = ClaudeTextSecondary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Qidirish",
                            tint = ClaudeTextSecondary
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showDateRangePicker = true }) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = "Sana bo'yicha filter",
                                tint = if (dateRangePickerState.selectedStartDateMillis != null) ClaudeAccent else ClaudeTextSecondary
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ClaudeDivider,
                        focusedBorderColor = ClaudeAccent,
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White
                    ),
                    singleLine = true
                )

                if (selectedRenters.isNotEmpty() && userRole == UserRole.ADMIN) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.payWeeklyForRenters(selectedRenters)
                                Toast.makeText(context, "To'lov qabul qilindi", Toast.LENGTH_SHORT).show()
                                selectedRenters = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.4f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                "1 hafta to'lov",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.terminateRenters(selectedRenters)
                                Toast.makeText(context, "Kontrakt tugatildi", Toast.LENGTH_SHORT).show()
                                selectedRenters = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.4f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                "Kontraktni uzish",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Button(
                            onClick = {
                                selectedRenters.forEach { id -> viewModel.deleteRenter(id) }
                                selectedRenters = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, ClaudeDivider),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = ClaudeTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // ===== ТАБЛИЦА АРЕНДАТОРОВ =====
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
                    textMatch && dateMatch
                }.sortedWith { a, b ->
                    val result = when (sortColumn) {
                        SortColumn.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                        SortColumn.START_TIME -> a.rentStartDateTimestamp.compareTo(b.rentStartDateTimestamp)
                        SortColumn.STATUS -> {
                            val timeA = a.rentStartDateTimestamp + (a.rentDurationDays * 24L * 60 * 60 * 1000)
                            val timeB = b.rentStartDateTimestamp + (b.rentDurationDays * 24L * 60 * 60 * 1000)
                            timeA.compareTo(timeB)
                        }
                        SortColumn.DEBT -> a.debtAmount.compareTo(b.debtAmount)
                        else -> 0
                    }
                    if (sortDirection == SortDirection.ASC) result else -result
                }

                RenterTable(
                    renters = filteredRenters,
                    selected = selectedRenters,
                    sortColumn = sortColumn,
                    sortDirection = sortDirection,
                    onSort = { col ->
                        if (sortColumn == col) {
                            sortDirection =
                                if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                        } else {
                            sortColumn = col
                            sortDirection = SortDirection.ASC
                        }
                    },
                    onSelect = { id, checked ->
                        val newSet = selectedRenters.toMutableSet()
                        if (checked) newSet.add(id) else newSet.remove(id)
                        selectedRenters = newSet
                    },
                    onClick = { renterToEdit = it }
                )
            } else {
                // Вкладка «Скутеры»
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Skuter qidirish") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Qidirish") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                if (selectedScooters.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${selectedScooters.size} ta tanlandi", color = ClaudeText)
                        Button(
                            onClick = {
                                scooters.filter { it.id in selectedScooters }.forEach {
                                    scooterViewModel.deleteScooter(it)
                                }
                                selectedScooters = setOf()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                        ) {
                            Text("O'chirish", color = ClaudeCard)
                        }
                    }
                }

                val filteredScooters = scooters.filter {
                    it.name.contains(searchQuery, ignoreCase = true)
                }.sortedWith { a, b ->
                    val r = if (scooterSortColumn == SortColumn.NAME) a.name.compareTo(b.name, true) else 0
                    if (scooterSortDirection == SortDirection.ASC) r else -r
                }

                ScooterTable(
                    scooters = filteredScooters,
                    renters = renters,
                    selected = selectedScooters,
                    sortColumn = scooterSortColumn,
                    sortDirection = scooterSortDirection,
                    onSort = { col ->
                        if (scooterSortColumn == col) {
                            scooterSortDirection =
                                if (scooterSortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                        } else {
                            scooterSortColumn = col
                            scooterSortDirection = SortDirection.ASC
                        }
                    },
                    onSelect = { id, checked ->
                        val newSet = selectedScooters.toMutableSet()
                        if (checked) newSet.add(id) else newSet.remove(id)
                        selectedScooters = newSet
                    },
                    onClick = { scooterToEdit = it }
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
                onDismiss = {
                    showAddDialog = false
                    renterToEdit = null
                },
                onSave = { name, phone, debt, duration, startTimestamp, scooterId, scooterName, active ->
                    if (isEdit) {
                        renterToEdit?.let {
                            var newTimestamp = it.rentStartDateTimestamp
                            var returned = !active
                            if (it.isReturned && active) {
                                newTimestamp = startTimestamp
                                returned = false
                            } else if (!it.isReturned && active) {
                                newTimestamp = startTimestamp
                            }
                            viewModel.updateRenter(
                                it.copy(
                                    name = name,
                                    phoneNumber = phone,
                                    debtAmount = debt,
                                    rentDurationDays = duration,
                                    rentStartDateTimestamp = newTimestamp,
                                    scooterId = scooterId,
                                    scooterName = scooterName,
                                    isReturned = returned,
                                    isOverdueSmsSent = if (it.isReturned && active) false else it.isOverdueSmsSent
                                )
                            )
                        }
                    } else {
                        viewModel.addRenter(
                            name = name,
                            phone = phone,
                            debt = debt,
                            duration = duration,
                            startTimestamp = startTimestamp,
                            scooterId = scooterId,
                            scooterName = scooterName,
                            weeklyPrice = weekly
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
                onSave = { name, docNum ->
                    if (isEditScooter) {
                        scooterToEdit?.let {
                            scooterViewModel.updateScooter(
                                it.copy(name = name, documentedNumber = docNum)
                            )
                        }
                    } else {
                        scooterViewModel.addScooter(name, docNum)
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
                onDismiss = { showSettings = false },
                onSave = { newTemplate, newWeekly, newMonthly ->
                    settingsViewModel.updateTemplate(newTemplate)
                    settingsViewModel.updatePrices(newWeekly, newMonthly)
                    showSettings = false
                },
                onLogout = {
                    showSettings = false
                    loginViewModel.logout()
                },
                onCheckUpdate = {
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        coroutineScope.launch {
                            val checker = UpdateChecker(context)
                            updateInfo = checker.checkForUpdate()
                            isCheckingUpdate = false
                        }
                    }
                }
            )
        }

        if (syncResult != null) {
            SyncResultDialog(
                result = syncResult!!,
                onDismiss = { syncResult = null }
            )
        }

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
                    title = { Text("Muddati bo'yicha filter", modifier = Modifier.padding(16.dp)) },
                    headline = { Text("Davrni tanlang", modifier = Modifier.padding(16.dp)) }
                )
            }
        }

        // ===== Диалог истории уведомлений (внутри приложения) =====
        if (showHistoryDialog) {
            NotificationHistoryDialog(
                history = history,
                onDismiss = { showHistoryDialog = false },
                onClear = {
                    historyViewModel.clear()
                    Toast.makeText(context, "Tozalandi", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Окно истории контрактов
        if (showContractHistoryDialog) {
            ContractHistoryDialog(
                history = contractHistory,
                renters = renters,
                onDismiss = { showContractHistoryDialog = false },
                onClear = {
                    contractHistoryViewModel.clear()
                    Toast.makeText(context, "Tozalandi", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // ===== Диалог результата SMS =====
        // Подписываемся на результаты SMS из ViewModel
        var smsResultDialog by remember { mutableStateOf<com.example.ui.SmsResult?>(null) }
        LaunchedEffect(Unit) {
            viewModel.smsResults.collect { result ->
                smsResultDialog = result
            }
        }
        if (smsResultDialog != null) {
            SmsResultDialog(
                result = smsResultDialog!!,
                onDismiss = { smsResultDialog = null }
            )
        }
    }
}

/* ============================================================================
   ТАБЛИЦА АРЕНДАТОРОВ
   ============================================================================ */

/**
 * Ячейка заголовка таблицы. Должна быть расширением RowScope, чтобы
 * внутри был доступен `Modifier.weight(...)`.
 */
@Composable
fun RowScope.HeaderCell(
    title: String,
    weightValue: Float,
    col: SortColumn,
    currentSortColumn: SortColumn,
    currentSortDirection: SortDirection,
    onSortClick: (SortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .weight(weightValue)
            .clickable { onSortClick(col) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeText,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        if (currentSortColumn == col) {
            Icon(
                if (currentSortDirection == SortDirection.ASC) Icons.Default.ArrowDropUp
                else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = ClaudeAccent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RenterTable(
    renters: List<Renter>,
    selected: Set<Int>,
    sortColumn: SortColumn,
    sortDirection: SortDirection,
    onSort: (SortColumn) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onClick: (Renter) -> Unit
) {
    // Веса колонок подобраны под портретный экран телефона.
    val wName  = 1.4f
    val wPhone = 1.2f
    val wScoot = 1.0f
    val wStart = 1.1f
    val wEnd   = 1.1f
    val wDebt  = 0.8f

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок
        Surface(
            color = ClaudeCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("Mijoz",      wName,  SortColumn.NAME,       sortColumn, sortDirection, onSort)
                HeaderCell("Tel",        wPhone, SortColumn.NAME,       sortColumn, sortDirection, onSort)
                HeaderCell("Skuter",     wScoot, SortColumn.NAME,       sortColumn, sortDirection, onSort)
                HeaderCell("Boshlanish", wStart, SortColumn.START_TIME, sortColumn, sortDirection, onSort)
                HeaderCell("Tugash",     wEnd,   SortColumn.START_TIME, sortColumn, sortDirection, onSort)
                HeaderCell("Qarz",       wDebt,  SortColumn.DEBT,        sortColumn, sortDirection, onSort)
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

                // Цветной контур вокруг строки показывает статус:
                //   красный — есть долг, зелёный — ок, серый — вернул скутер
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
                            modifier = Modifier.weight(wName).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        // Tel
                        Text(
                            renter.phoneNumber,
                            modifier = Modifier.weight(wPhone).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary,
                            maxLines = 1
                        )
                        // Skuter
                        Text(
                            renter.scooterName ?: "—",
                            modifier = Modifier.weight(wScoot).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeText,
                            maxLines = 1
                        )
                        // Boshlanish
                        Text(
                            dateFmt.format(Date(renter.rentStartDateTimestamp)),
                            modifier = Modifier.weight(wStart).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeText,
                            maxLines = 1
                        )
                        // Tugash
                        val expiry = renter.rentStartDateTimestamp +
                            (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                        Text(
                            dateFmt.format(Date(expiry)),
                            modifier = Modifier.weight(wEnd).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeText,
                            maxLines = 1
                        )
                        // Qarz
                        Text(
                            renter.debtAmount.toBigDecimal().stripTrailingZeros().toPlainString(),
                            modifier = Modifier.weight(wDebt).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeText,
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
                    TextButton(onClick = onClear) {
                        Text("Tozalash", color = ClaudeTextSecondary)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Yopish", color = ClaudeAccent)
                }
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
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Int, Long, Int?, String?, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialRenter?.name ?: "") }
    var phone by remember {
        mutableStateOf(initialRenter?.phoneNumber?.filter { it.isDigit() }?.takeLast(9) ?: "")
    }
    var debt by remember {
        mutableStateOf(initialRenter?.debtAmount?.toString() ?: "0.0")
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("To'liq ism") },
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

                val selectedScooter = scooters.find { it.id == selectedScooterId }
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
                        scooters.forEach { scooter ->
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val debtValue = debt.toDoubleOrNull() ?: 0.0
                    val durationValue = duration.toIntOrNull() ?: 7
                    val phoneToSave = "+998$phone"
                    val scooterName = scooters.find { it.id == selectedScooterId }?.name
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        onSave(
                            name, phoneToSave, debtValue, durationValue,
                            startTimestamp, selectedScooterId, scooterName, isActive
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeText),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Saqlash", color = ClaudeCard)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = ClaudeTextSecondary)
            ) {
                Text("Bekor qilish")
            }
        }
    )

    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startDatePickerState.selectedDateMillis?.let { startTimestamp = it }
                    showStartDatePicker = false
                }) { Text("Tanlash") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text("Bekor qilish")
                }
            }
        ) {
            DatePicker(state = startDatePickerState)
        }
    }
}

@Composable
fun SettingsDialog(
    currentTemplate: String,
    currentWeeklyPrice: Double,
    currentMonthlyPrice: Double,
    updateInfo: UpdateInfo? = null,
    isCheckingUpdate: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double) -> Unit,
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
                        "Mavjud teglar: {name}, {days}, {debt}",
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
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "SIM ma'lumotlarini olish uchun ruxsat kerak",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFE65100)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            // Permission ni qo'lda so'rash
                                            simContext.startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    android.net.Uri.fromParts("package", simContext.packageName, null)
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Sozlamalarni ochish", color = Color(0xFFE65100))
                                    }
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
                            color = Color(0xFF2E7D32)
                        )
                    } else {
                        // 2+ ta SIM — tanlash
                        simCards.forEach { sim ->
                            val isSelected = selectedSimSubId == sim.subscriptionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFE8F5E9) else Color.Transparent)
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
                                        selectedColor = Color(0xFF2E7D32)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        sim.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF2E7D32) else ClaudeText
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

                // ── Update check button ───────────────────
                Column {
                    Text(
                        "Ilova yangilanishlari",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClaudeText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Yangi versiya mavjud bo'lsa avtomatik yuklab olish",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (updateInfo != null) {
                        // Доступно обновление
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Yangi versiya: v${updateInfo.versionName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF2E7D32)
                                )
                                if (updateInfo.releaseNotes.isNotBlank()) {
                                    Text(
                                        updateInfo.releaseNotes.take(200),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF388E3C)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(updateInfo.downloadUrl)
                                        )
                                        onDismiss()
                                        settingsContext.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Sync,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Yangilash (yuklab olish)", color = Color.White)
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onCheckUpdate,
                            enabled = !isCheckingUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = ClaudeAccent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tekshirilmoqda...")
                            } else {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Yangilanishni tekshirish")
                            }
                        }
                    }
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = { onLogout() },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0xFFE05B44)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Chiqish (boshqa akkauntga o'tish)",
                        color = Color(0xFFE05B44),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val wPrice = weekly.toDoubleOrNull() ?: 0.0
                    val mPrice = monthly.toDoubleOrNull() ?: 0.0
                    onSave(template, wPrice, mPrice)
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeText),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Saqlash", color = ClaudeCard)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = ClaudeTextSecondary)
            ) {
                Text("Bekor qilish")
            }
        }
    )
}

@Composable
fun SyncResultDialog(
    result: SyncResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (result.isFullSuccess) "Sinxronizatsiya muvaffaqiyatli"
                else if (result.success) "Sinxronizatsiya tugadi"
                else "Sinxronizatsiya xatosi",
                style = MaterialTheme.typography.titleLarge
            )
        },
        containerColor = ClaudeCard,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.errorMessage != null) {
                    Text(
                        result.errorMessage!!,
                        color = Color(0xFFE05B44),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Yuborilgan: ${result.totalPushed} ta yozuv",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClaudeText
                )
                if (result.totalFailed > 0) {
                    Text(
                        "Xatolik: ${result.totalFailed} ta yozuv",
                        color = Color(0xFFE05B44),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                HorizontalDivider()
                Text(
                    "Skuterlar: ${result.scootersPushed} yuborildi" +
                            if (result.scootersFailed > 0) ", ${result.scootersFailed} xato" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
                Text(
                    "Ijarachilar: ${result.rentersPushed} yuborildi" +
                            if (result.rentersFailed > 0) ", ${result.rentersFailed} xato" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
                Text(
                    "Kontraktlar: ${result.historyPushed} yuborildi" +
                            if (result.historyFailed > 0) ", ${result.historyFailed} xato" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
                Text(
                    "Bildirishnomalar: ${result.notificationsPushed} yuborildi" +
                            if (result.notificationsFailed > 0) ", ${result.notificationsFailed} xato" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
                HorizontalDivider()
                Text(
                    if (result.pullSuccess) "Serverdan ma'lumotlar yuklandi" else "Serverdan yuklash xatosi",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.pullSuccess) Color(0xFF34C759) else Color(0xFFE05B44)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeText),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("OK", color = ClaudeCard)
            }
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
    ScooterStatus.RENTED  -> Color(0xFFE05B44) // красный — в аренде
    ScooterStatus.IN_BASE -> Color(0xFF34C759) // зелёный — в базе
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
    sortColumn: SortColumn,
    sortDirection: SortDirection,
    onSort: (SortColumn) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onClick: (Scooter) -> Unit
) {
    val wName  = 2.0f
    val wStat  = 1.0f

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("Nomi",  wName, SortColumn.NAME, sortColumn, sortDirection, onSort)
                Text(
                    "Holat",
                    modifier = Modifier
                        .weight(wStat)
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
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
    onSave: (String, String?) -> Unit
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                OutlinedTextField(
                    value = documentedNumber,
                    onValueChange = { documentedNumber = it },
                    label = { Text("Hujjatlashtirilgan raqami (ixtiyoriy)") },
                    placeholder = { Text("Masalan: 01-234 ABC") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) onSave(name, documentedNumber.takeIf { it.isNotBlank() })
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeText),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Saqlash", color = ClaudeCard)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Bekor qilish", color = ClaudeTextSecondary)
            }
        }
    )
}

/* ============================================================================
   ЛОГИН-ЭКРАН
   ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    state: LoginState,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onErrorShown: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { com.example.data.SettingsRepository(context.applicationContext) }
    var serverUrl by remember { mutableStateOf(settingsRepo.apiBaseUrl) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClaudeBackground),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ClaudeCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.DirectionsBike,
                    contentDescription = null,
                    tint = ClaudeAccent,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    "Skuter Ijarasi",
                    style = MaterialTheme.typography.headlineSmall,
                    color = ClaudeText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (registerMode) "Ro'yxatdan o'tish" else "Tizimga kirish",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClaudeTextSecondary
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        settingsRepo.apiBaseUrl = it.trim()
                        onErrorShown()
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text(com.example.data.SettingsRepository.DEFAULT_SERVER_URL) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        onErrorShown()
                    },
                    label = { Text("Email") },
                    placeholder = { Text("admin@example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onErrorShown()
                    },
                    label = { Text("Parol") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                val isLoading = state is LoginState.Loading
                Button(
                    onClick = {
                        if (serverUrl.isBlank() || email.isBlank() || password.isBlank()) return@Button
                        if (registerMode) {
                            onRegister(serverUrl.trim(), email.trim(), password)
                        } else {
                            onLogin(serverUrl.trim(), email.trim(), password)
                        }
                    },
                    enabled = !isLoading &&
                        serverUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(if (registerMode) "Ro'yxatdan o'tish" else "Kirish", color = Color.White)
                    }
                }

                if (state is LoginState.Error) {
                    Text(
                        state.message,
                        color = Color(0xFFE05B44),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TextButton(
                    onClick = { registerMode = !registerMode; onErrorShown() }
                ) {
                    Text(
                        if (registerMode) "Kirishga o'tish"
                        else "Akkauntingiz yo'qmi? Ro'yxatdan o'ting",
                        color = ClaudeAccent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Text(
                    "Birinchi ro'yxatdan o'tgan foydalanuvchi admin bo'ladi.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
            }
        }
    }
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
                    TextButton(onClick = onClear) {
                        Text("Tozalash", color = ClaudeTextSecondary)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Yopish", color = ClaudeAccent)
                }
            }
        }
    )
}

/**
 * Диалог с результатом SMS-отправки.
 *
 * Показывает:
 * - Успех: зелёная иконка и короткое сообщение
 * - Ошибка: красная иконка, код ошибки, класс исключения, полное сообщение
 *
 * Текст ошибки можно выделить и скопировать (SelectionContainer).
 */
@Composable
fun SmsResultDialog(
    result: SmsResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Text(
                if (result.success) "\u2713" else "\u2717",
                color = if (result.success) Color(0xFF34C759) else Color(0xFFE05B44),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        },
        title = {
            Text(
                if (result.success) "SMS yuborildi" else "SMS xatosi",
                color = if (result.success) Color(0xFF34C759) else Color(0xFFE05B44),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClaudeText
                )
                if (!result.success) {
                    HorizontalDivider(color = ClaudeDivider)
                    if (result.errorCode != null) {
                        Row {
                            Text(
                                "Xato kodi: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                result.errorCode,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE05B44),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (result.exceptionClass != null) {
                        Row {
                            Text(
                                "Exception: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                result.exceptionClass,
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (result.exceptionMessage != null) {
                        Text(
                            result.exceptionMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                    }
                    HorizontalDivider(color = ClaudeDivider)
                    Text(
                        "Ushbu xatolik kodini nusxalab internetda qidiring",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", color = ClaudeAccent)
            }
        },
        containerColor = ClaudeCard
    )
}
