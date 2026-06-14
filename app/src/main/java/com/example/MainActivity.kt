package com.example

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.Renter
import com.example.data.Scooter
import com.example.ui.RenterViewModel
import com.example.ui.SettingsViewModel
import com.example.ui.ScooterViewModel
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.MyApplicationTheme
import com.example.worker.NotificationHelper
import com.example.worker.PaymentCheckWorker
import com.example.worker.SmsWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Создаём канал уведомлений при старте приложения.
        NotificationHelper.createChannel(applicationContext)

        // Существующий SMS-воркер для просроченных арендаторов — оставляем как есть.
        val smsWorkRequest = PeriodicWorkRequestBuilder<SmsWorker>(4, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "OverdueSmsWork",
            ExistingPeriodicWorkPolicy.KEEP,
            smsWorkRequest
        )

        // Новый воркер: каждый час проверяет, не наступил ли срок оплаты,
        // и шлёт локальное уведомление.
        val paymentCheckRequest =
            PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "PaymentCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            paymentCheckRequest
        )

        setContent {
            MyApplicationTheme {
                var hasSmsPermission by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasSmsPermission = isGranted
                }

                // Запрашиваем SMS и (на Android 13+) разрешение на уведомления.
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.SEND_SMS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                MainScreen()
            }
        }
    }
}

enum class SortColumn {
    NAME, START_TIME, STATUS, DEBT, SCOOTER_NAME
}

enum class SortDirection {
    ASC, DESC
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: RenterViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    scooterViewModel: ScooterViewModel = viewModel()
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

    var sortColumn by remember { mutableStateOf(SortColumn.STATUS) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASC) }
    var scooterSortColumn by remember { mutableStateOf(SortColumn.SCOOTER_NAME) }
    var scooterSortDirection by remember { mutableStateOf(SortDirection.ASC) }

    val scooters by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val renters by viewModel.rentersList.collectAsStateWithLifecycle()

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
                    // Кнопка теперь открывает системные настройки уведомлений приложения,
                    // чтобы пользователь видел, куда приходят напоминания о платежах.
                    IconButton(
                        onClick = {
                            openAppNotificationSettings(context)
                        },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(40.dp)
                            .background(Color.White, CircleShape)
                            .border(1.dp, ClaudeDivider, CircleShape)
                    ) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = "Bildirishnoma sozlamalari",
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
            if (currentTab == 0) {
                // Search field
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

                if (selectedRenters.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedRenters.forEach { id ->
                                    renters.find { it.id == id }?.let { r ->
                                        viewModel.markReturned(r)
                                    }
                                }
                                selectedRenters = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Qaytarilgan deb belgilash",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Button(
                            onClick = {
                                selectedRenters.forEach { id ->
                                    viewModel.deleteRenter(id)
                                }
                                selectedRenters = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, ClaudeDivider),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = ClaudeTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "O'chirish",
                                color = ClaudeTextSecondary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, ClaudeDivider, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Barchasi",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ClaudeText
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))

                    val onSort: (SortColumn) -> Unit = { col ->
                        if (sortColumn == col) {
                            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                        } else {
                            sortColumn = col
                            sortDirection = SortDirection.ASC
                        }
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SortableHeader("Mijoz", 1.4f, SortColumn.NAME, sortColumn, sortDirection, onSort)
                        SortableHeader(
                            "Ijara muddati",
                            1.5f,
                            SortColumn.START_TIME,
                            sortColumn,
                            sortDirection,
                            onSort
                        )
                        SortableHeader("Holat", 0.8f, SortColumn.STATUS, sortColumn, sortDirection, onSort)
                        SortableHeader("Qarz", 0.8f, SortColumn.DEBT, sortColumn, sortDirection, onSort)
                        Text(
                            "Amallar",
                            modifier = Modifier.weight(0.5f),
                            style = MaterialTheme.typography.labelMedium,
                            color = ClaudeTextSecondary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    val filteredRenters = renters.filter { renter ->
                        val textMatch = renter.name.contains(searchQuery, ignoreCase = true) ||
                            renter.phoneNumber.contains(searchQuery) ||
                            (renter.scooterName != null && renter.scooterName.contains(searchQuery, ignoreCase = true))

                        val startMillis = dateRangePickerState.selectedStartDateMillis
                        val endMillis = dateRangePickerState.selectedEndDateMillis

                        val dateMatch = if (startMillis != null) {
                            val expiryTime =
                                renter.rentStartDateTimestamp + (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                            if (endMillis != null) {
                                expiryTime in startMillis..endMillis
                            } else {
                                expiryTime >= startMillis
                            }
                        } else {
                            true
                        }

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

                    items(filteredRenters, key = { it.id }) { renter ->
                        RenterCardItem(
                            renter = renter,
                            isSelected = selectedRenters.contains(renter.id),
                            onSelect = { checked ->
                                val newSet = selectedRenters.toMutableSet()
                                if (checked) newSet.add(renter.id) else newSet.remove(renter.id)
                                selectedRenters = newSet
                            },
                            onClick = { renterToEdit = renter }
                        )
                    }
                }
            } else {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, ClaudeDivider, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Barchasi",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ClaudeText
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))

                    val onScooterSort: (SortColumn) -> Unit = { col ->
                        if (scooterSortColumn == col) {
                            scooterSortDirection =
                                if (scooterSortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                        } else {
                            scooterSortColumn = col
                            scooterSortDirection = SortDirection.ASC
                        }
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SortableHeader(
                            "Nomi",
                            1f,
                            SortColumn.SCOOTER_NAME,
                            scooterSortColumn,
                            scooterSortDirection,
                            onScooterSort
                        )
                        Text(
                            "Amallar",
                            modifier = Modifier.weight(0.5f),
                            style = MaterialTheme.typography.labelMedium,
                            color = ClaudeTextSecondary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    val filteredScooters = scooters.filter { scooter ->
                        scooter.name.contains(searchQuery, ignoreCase = true)
                    }.sortedWith { a, b ->
                        val result = when (scooterSortColumn) {
                            SortColumn.SCOOTER_NAME -> a.name.compareTo(b.name, ignoreCase = true)
                            else -> 0
                        }
                        if (scooterSortDirection == SortDirection.ASC) result else -result
                    }

                    items(filteredScooters, key = { it.id }) { scooter ->
                        ScooterCardItem(
                            scooter = scooter,
                            isSelected = selectedScooters.contains(scooter.id),
                            onSelect = { checked ->
                                val newSet = selectedScooters.toMutableSet()
                                if (checked) newSet.add(scooter.id) else newSet.remove(scooter.id)
                                selectedScooters = newSet
                            },
                            onClick = { scooterToEdit = scooter }
                        )
                    }
                }
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
                                // При редактировании активного арендатора — обновляем дату старта,
                                // если её явно изменили в форме.
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
                        viewModel.addRenter(name, phone, debt, duration, startTimestamp, scooterId, scooterName)
                    }
                    showAddDialog = false
                    renterToEdit = null
                },
                onPaymentReceived = { renter ->
                    viewModel.markPaymentReceived(renter)
                    Toast.makeText(context, "To'lov qabul qilindi", Toast.LENGTH_SHORT).show()
                },
                onRemindInOneHour = { renter ->
                    viewModel.scheduleOneHourReminder(context, renter)
                    Toast.makeText(
                        context,
                        "1 soatdan so'ng eslatma yuboriladi",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }

        // ===== Диалог создания/редактирования скутера (только имя) =====
        if (showAddScooterDialog || scooterToEdit != null) {
            val isEditScooter = scooterToEdit != null
            ScooterFormDialog(
                initialScooter = scooterToEdit,
                onDismiss = {
                    showAddScooterDialog = false
                    scooterToEdit = null
                },
                onSave = { name ->
                    if (isEditScooter) {
                        scooterToEdit?.let {
                            scooterViewModel.updateScooter(it.copy(name = name))
                        }
                    } else {
                        scooterViewModel.addScooter(name)
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
                onDismiss = { showSettings = false },
                onSave = { newTemplate, newWeekly, newMonthly ->
                    settingsViewModel.updateTemplate(newTemplate)
                    settingsViewModel.updatePrices(newWeekly, newMonthly)
                    showSettings = false
                }
            )
        }

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
                    title = { Text("Muddati bo'yicha filter", modifier = Modifier.padding(16.dp)) },
                    headline = { Text("Davrni tanlang", modifier = Modifier.padding(16.dp)) }
                )
            }
        }
    }
}

/**
 * Открывает системные настройки уведомлений для нашего приложения.
 * Работает на Android 5+. На старых API фолбэк на общие настройки приложения.
 */
private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
    }
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Sozlamalarni ochib bo'lmadi", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RenterCardItem(
    renter: Renter,
    isSelected: Boolean,
    onSelect: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val isActive = !renter.isReturned
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { if (isSelected) onSelect(false) else onClick() },
                onLongClick = { onSelect(!isSelected) }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFF3F4F6) else Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFFF2F2F7), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        renter.name.take(2).uppercase(),
                        color = ClaudeTextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .background(Color.White, CircleShape)
                            .padding(2.dp)
                    ) {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF34C759), CircleShape))
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1.4f)) {
                Text(
                    renter.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    renter.phoneNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Skuter: ${renter.scooterName ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
            }

            Column(modifier = Modifier.weight(1.5f)) {
                val sdf = remember {
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                }
                val startDateStr = sdf.format(Date(renter.rentStartDateTimestamp))
                val expiryTime =
                    renter.rentStartDateTimestamp + (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                val endDateStr = sdf.format(Date(expiryTime))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF8E8E93), CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Bos.: $startDateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeText,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier
                        .size(6.dp)
                        .background(ClaudeText, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tug.: $endDateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeText,
                        maxLines = 1
                    )
                }
            }

            Column(modifier = Modifier.weight(0.8f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isActive) Color(0xFF34C759) else ClaudeTextSecondary,
                            CircleShape
                        ))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isActive) "Faol" else "Qaytgan",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeText
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = ClaudeTextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${renter.rentDurationDays} kun",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            }

            Column(modifier = Modifier.weight(0.8f)) {
                Text(
                    "Qarz",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    renter.debtAmount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeText
                )
                Text("UZS", style = MaterialTheme.typography.labelSmall, color = ClaudeTextSecondary)
            }

            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = ClaudeTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenterFormDialog(
    initialRenter: Renter?,
    weeklyPrice: Double,
    monthlyPrice: Double,
    scooters: List<Scooter> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Int, Long, Int?, String?, Boolean) -> Unit,
    onPaymentReceived: (Renter) -> Unit = {},
    onRemindInOneHour: (Renter) -> Unit = {}
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

    // Дата начала аренды — пользователь выбирает через DatePicker.
    var startTimestamp by remember {
        mutableStateOf(initialRenter?.rentStartDateTimestamp ?: System.currentTimeMillis())
    }
    var showStartDatePicker by remember { mutableStateOf(false) }
    val startDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = startTimestamp
    )

    val durationOptions = listOf(
        "1 Hafta" to 7,
        "2 Hafta" to 14,
        "3 Hafta" to 21,
        "1 Oy" to 30,
        "2 Oy" to 60,
        "3 Oy" to 90,
        "4 Oy" to 120
    )
    var selectedDurationText by remember { mutableStateOf("1 Hafta") }
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

                // ===== Кнопка выбора даты начала аренды =====
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
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = ClaudeAccent
                        )
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mijoz holati:", style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isActive) "Ijarada" else "Qaytarilgan",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeTextSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = isActive,
                                onCheckedChange = { isActive = it }
                            )
                        }
                    }
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
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDuration,
                        onDismissRequest = { expandedDuration = false }
                    ) {
                        durationOptions.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption.first) },
                                onClick = {
                                    selectedDurationText = selectionOption.first
                                    duration = selectionOption.second.toString()
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
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
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

                // ===== Кнопки действий только в режиме редактирования =====
                if (isEdit && initialRenter != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onPaymentReceived(initialRenter)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "To'lov qabul qilindi",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onRemindInOneHour(initialRenter)
                                onDismiss()
                            },
                            border = BorderStroke(1.dp, ClaudeAccent),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = ClaudeAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "1 soat eslatma",
                                color = ClaudeAccent,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
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
                            name,
                            phoneToSave,
                            debtValue,
                            durationValue,
                            startTimestamp,
                            selectedScooterId,
                            scooterName,
                            isActive
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

    // ===== DatePicker для даты начала аренды =====
    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startDatePickerState.selectedDateMillis?.let { startTimestamp = it }
                    showStartDatePicker = false
                }) {
                    Text("Tanlash")
                }
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
    onDismiss: () -> Unit,
    onSave: (String, Double, Double) -> Unit
) {
    var template by remember { mutableStateOf(currentTemplate) }
    var weekly by remember {
        mutableStateOf(if (currentWeeklyPrice > 0) currentWeeklyPrice.toString() else "")
    }
    var monthly by remember {
        mutableStateOf(if (currentMonthlyPrice > 0) currentMonthlyPrice.toString() else "")
    }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScooterCardItem(
    scooter: Scooter,
    isSelected: Boolean,
    onSelect: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { if (isSelected) onSelect(false) else onClick() },
                onLongClick = { onSelect(!isSelected) }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFF3F4F6) else Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFF2F2F7), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DirectionsBike,
                    contentDescription = null,
                    tint = ClaudeTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))
            Text(
                scooter.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = ClaudeText
            )

            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = ClaudeTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Диалог создания/редактирования скутера — теперь только поле «Имя».
 * Поле «Номер» удалено по запросу.
 */
@Composable
fun ScooterFormDialog(
    initialScooter: Scooter?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialScooter?.name ?: "") }

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
                    label = { Text("Skuter nomi") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name)
                    }
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

@Composable
fun RowScope.SortableHeader(
    title: String,
    weight: Float,
    column: SortColumn,
    currentSortColumn: SortColumn,
    currentSortDirection: SortDirection,
    onSortClick: (SortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .clickable { onSortClick(column) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = ClaudeTextSecondary,
            fontWeight = FontWeight.Bold
        )
        if (currentSortColumn == column) {
            Icon(
                if (currentSortDirection == SortDirection.ASC) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = ClaudeTextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
