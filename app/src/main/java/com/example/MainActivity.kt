package com.example

import android.Manifest
import android.content.Intent
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
import androidx.compose.material.icons.filled.CameraAlt
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.ui.TransactionViewModel
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

        // ── Обработка intent от нативных виджетов ──────────────────────
        // Виджеты передают extras для:
        //   open_tab — какая вкладка открыта (0=Ijarachilar, 1=Skuterlar,
        //              2=Kontraktlar, 3=Tranzaksiya, 4=Otchetlar)
        //   widget_action — какое действие выполнить (create_renter,
        //              create_scooter, create_contract, create_transaction,
        //              send_sms)
        //   renter_id — ID арендатора для send_sms
        handleWidgetIntent(intent)

        // SMS-воркер для просроченных (как раньше).
        //
        // ВАЖНО: schedule only if user has AVTO mode ON. Если пользователь
        // переключил тумблер в ручной режим (красный), SettingsViewModel
        // вызывает cancelUniqueWork("OverdueSmsWork"). Если бы мы тут слепо
        // вызвали enqueueUniquePeriodicWork с KEEP, то при отсутствии
        // существующей работы (она отменена) KEEP создал бы НОВУЮ работу —
        // и авто-отправка возобновилась бы вопреки выбору пользователя.
        // Поэтому сначала читаем флаг из DataStore, и только если AVTO —
        // планируем. SmsWorker.doWork() дополнительно проверяет флаг на
        // случай, если он изменится в течение 4 часов между запусками.
        val settingsRepo = com.example.data.SettingsRepository(applicationContext)
        if (settingsRepo.smsAutoSendEnabled) {
            val smsWorkRequest = PeriodicWorkRequestBuilder<SmsWorker>(4, TimeUnit.HOURS).build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "OverdueSmsWork",
                ExistingPeriodicWorkPolicy.KEEP,
                smsWorkRequest
            )
        } else {
            // На всякий случай убеждаемся, что работы нет — возможно, флаг
            // был изменён в DataStore напрямую, минуя SettingsViewModel.
            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork("OverdueSmsWork")
        }

        // Периодическая проверка наступления срока оплаты (раз в час)
        val paymentCheckRequest =
            PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "PaymentCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            paymentCheckRequest
        )

        // ── Принудительное обновление нативных виджетов при старте приложения ──
        // Виджеты на главном экране Android могут показывать "не удалось загрузить
        // виджет" если они не получили RemoteViews после установки/перезагрузки.
        // Системный onUpdate вызывается раз в 30 минут — слишком редко. Дёрнем
        // обновление вручную при каждом открытии приложения, чтобы виджеты
        // гарантированно получили свежие данные.
        try {
            com.example.widget.WidgetUpdater.updateAll(applicationContext)
        } catch (_: Exception) {}

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    /**
     * Обрабатывает intent от нативных виджетов:
     *   open_tab=N — переключает на вкладку N
     *   widget_action=create_renter/scooter/contract/transaction — открывает диалог создания
     *   widget_action=send_sms + renter_id — открывает экран арендатора для отправки SMS
     *
     * Используется статический объект WidgetActionBus, который MainScreen
     * читает в LaunchedEffect для выполнения действий после onCreate/onNewIntent.
     */
    private fun handleWidgetIntent(intent: Intent?) {
        if (intent == null) return
        val openTab = intent.getIntExtra("open_tab", -1)
        if (openTab in 0..4) {
            WidgetActionBus.openTab = openTab
        }
        val action = intent.getStringExtra("widget_action")
        if (action != null) {
            WidgetActionBus.widgetAction = action
            WidgetActionBus.renterId = intent.getIntExtra("renter_id", -1)
        }
    }
}

/**
 * Простой шина для передачи действий от виджетов в Composable.
 * MainScreen читает widgetAction/openTab в LaunchedEffect при запуске
 * и сбрасывает после обработки.
 */
object WidgetActionBus {
    var openTab: Int = -1
    var widgetAction: String? = null
    var renterId: Int = -1
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
 *   • CardHistory      — история транзакций конкретной виртуальной карты
 *   • Settings         — отдельная страница настроек (не диалог)
 */
sealed class NavigationState {
    data object MainView : NavigationState()
    data class RenterHistory(val renter: Renter) : NavigationState()
    data class ScooterHistory(val scooter: Scooter) : NavigationState()
    data class CardHistory(val card: com.example.data.VirtualCard) : NavigationState()
    data class ContractTransactionHistory(val contract: com.example.data.ContractHistoryEntry) : NavigationState()
    data object Settings : NavigationState()
    /** Экран сканера документов с Mistral OCR — доступен с любой вкладки. */
    data object Scanner : NavigationState()
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
    contractHistoryViewModel: ContractHistoryViewModel = viewModel(),
    transactionViewModel: TransactionViewModel = viewModel(),
    finansiViewModel: com.example.ui.FinansiViewModel = viewModel()
) {
    var currentTab by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddScooterDialog by remember { mutableStateOf(false) }
    var renterToEdit by remember { mutableStateOf<Renter?>(null) }
    var scooterToEdit by remember { mutableStateOf<Scooter?>(null) }
    var contractToEdit by remember { mutableStateOf<com.example.data.ContractHistoryEntry?>(null) }
    var selectedRenters by remember { mutableStateOf(setOf<Int>()) }
    var selectedScooters by remember { mutableStateOf(setOf<Int>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()
    // Separate state for the scooters tab calendar — filters scooters by
    // their active renter's contract start date. Kept independent so that
    // switching between tabs does not cross-pollute the date ranges.
    var showScooterDateRangePicker by remember { mutableStateOf(false) }
    val scooterDateRangePickerState = rememberDateRangePickerState()
    // Триггеры для открытия диалога создания на вкладках Kontraktlar / Tranzaksiya.
    // Увеличиваем значение → экран внутри открывает свой showCreateDialog.
    var contractCreateTrigger by remember { mutableStateOf(0) }
    var transactionCreateTrigger by remember { mutableStateOf(0) }
    // Универсальное выделение/редактирование/удаление для вкладок Kontraktlar /
    // Tranzaksiya. Раньше эти вкладки управляли выделением сами, через внутренние
    // неуниверсальные кнопки "Tahrirlash / O'chir" над таблицей. Теперь выделение
    // поднято сюда, чтобы универсальные ✎/🗑 в верхней панели работали на всех
    // вкладках одинаково, а неуниверсальные кнопки-дубликаты удалены.
    var selectedContracts by remember { mutableStateOf(setOf<Int>()) }
    var selectedTxs by remember { mutableStateOf(setOf<Int>()) }
    var contractEditTrigger by remember { mutableStateOf(0) }
    var contractDeleteTrigger by remember { mutableStateOf(0) }
    var transactionEditTrigger by remember { mutableStateOf(0) }
    var transactionDeleteTrigger by remember { mutableStateOf(0) }
    // Триггеры для вкладки Finansi: создание/редактирование/удаление карт.
    var cardCreateTrigger by remember { mutableStateOf(0) }
    var cardEditTrigger by remember { mutableStateOf(0) }
    var cardDeleteTrigger by remember { mutableStateOf(0) }
    var selectedCardIds by remember { mutableStateOf(setOf<Int>()) }

    // ── Навигация ────────────────────────────────────────────────────
    var navState by remember { mutableStateOf<NavigationState>(NavigationState.MainView) }

    var renterSortState by remember { mutableStateOf(TableSortState()) }
    var scooterSortState by remember { mutableStateOf(TableSortState()) }
    // Filter panel state
    var showRenterFilterPanel by remember { mutableStateOf(false) }
    var showScooterFilterPanel by remember { mutableStateOf(false) }
    var renterFilterValues by remember { mutableStateOf(mapOf<String, String>()) }
    var scooterFilterValues by remember { mutableStateOf(mapOf<String, String>()) }
    // Column visibility state (default: all visible). When user unchecks a
    // column in the filter side panel, the column disappears from the table
    // even if it has data — this replaces the old "auto-hide empty columns"
    // logic. User now has full manual control.
    var renterColumnVisibility by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var scooterColumnVisibility by remember { mutableStateOf(mapOf<String, Boolean>()) }

    // Filter column definitions (shared between search bar and filter panel)
    // Базовые колонки + ВСЕ опциональные колонки. Видимость каждой управляется
    // чекбоксом в FilterSidePanel — по умолчанию все включены.
    val renterFilterColumns = remember {
        listOf(
            FilterColumn("col_name",     "Mijoz",            "Ism bo'yicha"),
            FilterColumn("col_phone",    "Telefon",          "+998..."),
            FilterColumn("col_scooter",  "Skuter",           "Skuter nomi"),
            FilterColumn("col_start",    "Boshlanish sanasi","dd.MM.yyyy"),
            FilterColumn("col_end",      "Tugash sanasi",    "dd.MM.yyyy"),
            FilterColumn("col_balance",  "Balans",           "summa"),
            FilterColumn("col_status",   "Holat",            "Faol / Qaytgan / Qarzdor"),
            FilterColumn("col_passport", "Pasport",          "AA 1234567"),
            FilterColumn("col_address",  "Manzil",           "Manzil bo'yicha"),
            FilterColumn("col_pinfl",    "JSHSHIR",          "14 raqam")
        )
    }
    val scooterFilterColumns = remember {
        listOf(
            FilterColumn("col_name",    "Nomi",            "Skuter nomi"),
            FilterColumn("col_doc",     "Hujjat raqami",   "Doc #"),
            FilterColumn("col_vin",     "VIN",             "VIN raqami"),
            FilterColumn("col_engine",  "Dvigatel",        "Dvigatel raqami"),
            FilterColumn("col_serial",  "ID raqami",       "ID"),
            FilterColumn("col_batt1",   "Akkumulyator 1",  "Batt ID 1"),
            FilterColumn("col_batt2",   "Akkumulyator 2",  "Batt ID 2"),
            FilterColumn("col_extra",   "Qo'shimcha",      "Qo'shimcha ma'lumot"),
            FilterColumn("col_status",  "Holat",           "Ijarada / Bosh")
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

    // ── Авто-восстановление из публичной папки Downloads ──────────────────
    // При первом запуске (если БД пуста и есть бэкап в Downloads/ScooterRent/)
    // автоматически восстанавливаем данные. Это работает после удаления и
    // переустановки приложения — файл .xlsx в публичной папке переживает
    // удаление приложения.
    var autoRestoreMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val settingsRepo = com.example.data.SettingsRepository(localContext)
            // Авто-восстановление выполняется только один раз — при первой
            // установке. Флаг autoRestoreAttempted переживает переустановку
            // через Auto Backup, поэтому при втором запуске мы не пытаемся
            // восстановиться снова (это было бы бессмысленно — данные уже есть).
            if (!settingsRepo.autoRestoreAttempted) {
                settingsRepo.autoRestoreAttempted = true
                val localBackupManager = com.example.data.LocalBackupManager(localContext)
                // Ждём пока БД загрузится — проверяем renters. Если renters
                // пуста, значит это fresh install — ищем бэкап.
                kotlinx.coroutines.delay(500) // даём Room время загрузиться
                val rentersCount = viewModel.rentersList.value.size
                val scootersCount = scooterViewModel.scootersList.value.size
                if (rentersCount == 0 && scootersCount == 0) {
                    Log.d("MainScreen", "DB is empty — checking for backup in Downloads/ScooterRent/")
                    val hasBackup = localBackupManager.hasBackup()
                    if (hasBackup) {
                        Log.d("MainScreen", "Backup found — auto-restoring...")
                        val result = localBackupManager.restoreBackup()
                        if (result != null && !result.startsWith("Xato")) {
                            autoRestoreMessage = "Ma'lumotlar avtomatik tiklandi: $result"
                            Log.d("MainScreen", "Auto-restore success: $result")
                        } else {
                            autoRestoreMessage = "Avto-tiklash amalga oshmadi: ${result ?: "noma'lum xato"}"
                            Log.w("MainScreen", "Auto-restore failed: $result")
                        }
                    } else {
                        Log.d("MainScreen", "No backup found — fresh install, nothing to restore")
                    }
                } else {
                    Log.d("MainScreen", "DB not empty (renters=$rentersCount, scooters=$scootersCount) — skipping auto-restore")
                }
            }
        } catch (e: Exception) {
            Log.w("MainScreen", "Auto-restore check failed", e)
        }
    }

    // Показываем Toast с результатом авто-восстановления
    LaunchedEffect(autoRestoreMessage) {
        autoRestoreMessage?.let { msg ->
            Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show()
            autoRestoreMessage = null
        }
    }

    // ── Авто-сохранение в Downloads после изменений данных (debounced) ─────
    // Следим за изменениями в renters/scooters/history. После каждого изменения
    // ждём 2 секунды (debounce) и пишем бэкап в Downloads/ScooterRent/.
    val rentersForBackup by viewModel.rentersList.collectAsStateWithLifecycle()
    val scootersForBackup by scooterViewModel.scootersList.collectAsStateWithLifecycle()
    LaunchedEffect(rentersForBackup, scootersForBackup) {
        try {
            val settingsRepo = com.example.data.SettingsRepository(localContext)
            if (settingsRepo.autoBackupEnabled) {
                // Debounce: ждём 2 секунды. Если за это время пришли новые
                // изменения, LaunchedEffect перезапустится и таймер начнётся
                // заново — бэкап пишется только после "успокоения" данных.
                kotlinx.coroutines.delay(2000)
                // Не пишем бэкап если БД пуста — это либо fresh install
                // (нет смысла писать пустой бэкап), либо после ручной очистки.
                if (rentersForBackup.isNotEmpty() || scootersForBackup.isNotEmpty()) {
                    val localBackupManager = com.example.data.LocalBackupManager(localContext)
                    val success = localBackupManager.writeBackup()
                    if (success) {
                        Log.d("MainScreen", "Auto-backup written to Downloads/ScooterRent/")
                    } else {
                        Log.w("MainScreen", "Auto-backup failed — will retry on next change")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainScreen", "Auto-backup failed", e)
        }
    }

    // ── Обработка действий от нативных виджетов ──────────────────────
    // Читаем WidgetActionBus при запуске и выполняем соответствующее действие:
    //   open_tab — переключаемся на нужную вкладку
    //   widget_action — открываем диалог создания или экран арендатора
    LaunchedEffect(Unit) {
        if (WidgetActionBus.openTab in 0..4) {
            currentTab = WidgetActionBus.openTab
            WidgetActionBus.openTab = -1
        }
        when (WidgetActionBus.widgetAction) {
            "create_renter" -> showAddDialog = true
            "create_scooter" -> showAddScooterDialog = true
            "create_contract" -> contractCreateTrigger++
            "create_transaction" -> transactionCreateTrigger++
            "send_sms" -> {
                // Открываем экран истории арендатора для отправки SMS
                val rid = WidgetActionBus.renterId
                if (rid != -1) {
                    val r = renters.firstOrNull { it.id == rid }
                    if (r != null) navState = NavigationState.RenterHistory(r)
                }
            }
        }
        WidgetActionBus.widgetAction = null
        WidgetActionBus.renterId = -1
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
                    },
                    // Inline-создание скутера доступно и из экрана истории
                    // арендатора — там тоже может быть сценарий, когда нужно
                    // перевыбрать скутер, а нужного нет в списке.
                    onCreateScooterInline = { name, docNum, vin, engine, serial, batt1, batt2, info ->
                        scooterViewModel.addScooter(
                            name = name,
                            documentedNumber = docNum,
                            vinNumber = vin,
                            engineNumber = engine,
                            scooterSerialNumber = serial,
                            batteryId1 = batt1,
                            batteryId2 = batt2,
                            additionalInfo = info
                        )
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
        is NavigationState.CardHistory -> {
            // ── Экран истории транзакций виртуальной карты ─────────────
            // Шаблон — RenterContractHistoryScreen. Показывает входящие
            // и исходящие транзакции карты в отдельных вкладках.
            CardTransactionHistoryScreen(
                card = st.card,
                onBack = { navState = NavigationState.MainView },
                onEditCard = {
                    // Возврат на вкладку Finansi — пользователь может
                    // долго нажать на карту + ✎ для редактирования.
                    currentTab = 5
                    navState = NavigationState.MainView
                },
                finansiViewModel = finansiViewModel
            )
            return
        }
        is NavigationState.ContractTransactionHistory -> {
            // ── Экран истории транзакций контракта ─────────────────────
            // Шаблон — CardTransactionHistoryScreen. Показывает входящие
            // (PAYMENT, RETURNED) и исходящие (TERMINATED, PENALTY, REPAIR)
            // транзакции, связанные с конкретным контрактом, в отдельных вкладках.
            ContractTransactionHistoryScreen(
                contract = st.contract,
                onBack = { navState = NavigationState.MainView },
                onEditContract = { contractToEdit = st.contract },
                transactionViewModel = transactionViewModel,
                contractHistoryViewModel = contractHistoryViewModel,
                renterViewModel = viewModel,
                scooterViewModel = scooterViewModel
            )
            // ── Диалог редактирования контракта (поверх экрана истории) ──
            // Используется тот же EditContractDialog, что и в ContractListScreen.
            contractToEdit?.let { entry ->
                val allRentersList by viewModel.rentersList.collectAsStateWithLifecycle()
                val allScootersList by scooterViewModel.scootersList.collectAsStateWithLifecycle()
                EditContractDialog(
                    entry = entry,
                    allRenters = allRentersList,
                    allScooters = allScootersList,
                    onDismiss = { contractToEdit = null },
                    onSave = { updated ->
                        contractHistoryViewModel.updateContract(updated)
                        contractToEdit = null
                        Toast.makeText(localContext, "Kontrakt yangilandi", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = {
                        contractHistoryViewModel.deleteContract(entry.id)
                        contractToEdit = null
                        navState = NavigationState.MainView
                        Toast.makeText(localContext, "Kontrakt o'chirildi", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            return
        }
        NavigationState.Settings -> {
            // ── Отдельная страница настроек (не диалог) ────────────────
            // Раньше был AlertDialog с verticalScroll — был риск, что нижние
            // секции (SIM, Update, Logout) обрезаются. Теперь это полная
            // страница с TopAppBar, кнопкой «Saqla» в аппбаре и кнопкой
            // «← Orqaga» для возврата.
            val template by settingsViewModel.smsTemplate.collectAsStateWithLifecycle()
            val weekly by settingsViewModel.weeklyPrice.collectAsStateWithLifecycle()
            val monthly by settingsViewModel.monthlyPrice.collectAsStateWithLifecycle()
            val smsAutoSend by settingsViewModel.smsAutoSendEnabled.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTemplate = template,
                currentWeeklyPrice = weekly,
                currentMonthlyPrice = monthly,
                currentSmsAutoSend = smsAutoSend,
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
                onResetUpdate = { updateManager.reset() },
                onBack = { navState = NavigationState.MainView; currentTab = 6 },
                onSave = { newTemplate, newWeekly, newMonthly, _, _ ->
                    settingsViewModel.updateTemplate(newTemplate)
                    settingsViewModel.updatePrices(newWeekly, newMonthly)
                    navState = NavigationState.MainView
                    currentTab = 6
                },
                onSmsAutoSendChange = { enabled ->
                    settingsViewModel.updateSmsAutoSend(enabled)
                    Toast.makeText(
                        localContext,
                        if (enabled) "SMS avto-yuborish yoqildi"
                        else "SMS qo'llanma rejimiga o'tdi",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onLogout = {
                    navState = NavigationState.MainView
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
                },
                onExportBackup = { uri ->
                    coroutineScope.launch {
                        val msg = com.example.data.BackupManager.exportToExcel(localContext, uri)
                        Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onImportBackup = { uri ->
                    coroutineScope.launch {
                        val msg = com.example.data.BackupManager.importFromExcel(localContext, uri)
                        Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
            return
        }
        NavigationState.Scanner -> {
            // ── Экран сканера документов с Mistral OCR ────────────────────
            // Отдельный full-screen экран с камерой. Пользователь делает
            // фото списка (арендаторы / скутеры / транзакции / контракты /
            // виртуальные карты), фото уходит в Mistral OCR → Mistral Large
            // → JSON-команды → CommandExecutor создаёт сущности в БД.
            //
            // Кнопка сканера (иконка Camera) — в верхнем баре рядом с
            // переключателем SMS-режима, доступна с любой вкладки.
            ScannerScreen(
                onBack = { navState = NavigationState.MainView }
            )
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
                    // ── Кнопка сканера (Mistral OCR) ──────────────────────────────
                    // Иконка камеры, доступна с любой вкладки. Открывает экран
                    // сканера документов: пользователь фотографирует список
                    // (арендаторы / скутеры / транзакции / контракты / карты),
                    // фото уходит в Mistral OCR → Mistral Large → JSON-команды,
                    // которые автоматически создают сущности в БД.
                    IconButton(
                        onClick = { navState = NavigationState.Scanner },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .background(ClaudeAccentBg, CircleShape)
                            .border(1.dp, ClaudeAccent, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Skaner",
                            tint = ClaudeAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // ── Кнопка-переключатель режима SMS ───────────────────────────
                    // Круглая, рядом с «+». Красная = QO'LLANMA (ручной),
                    // зелёная = AVTO (автоматический). Тап переключает режим.
                    val smsAutoSend by settingsViewModel.smsAutoSendEnabled.collectAsStateWithLifecycle()
                    IconButton(
                        onClick = {
                            val newValue = !smsAutoSend
                            settingsViewModel.updateSmsAutoSend(newValue)
                            Toast.makeText(
                                localContext,
                                if (newValue) "SMS avto-yuborish yoqildi"
                                else "SMS qo'llanma rejimiga o'tdi",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .background(
                                if (smsAutoSend) StatusOk else StatusOverdue,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Default.Sms,
                            contentDescription = if (smsAutoSend) "SMS avto" else "SMS qo'llanma",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // ── Универсальные кнопки верхнего бара ──────────────────────
                    // +  — добавление сущности для текущей вкладки (всегда активна)
                    // ✎  — редактирование выбранной строки (активна при выборе 1)
                    // 🗑  — удаление выбранных строк (активна при выборе ≥1)
                    // Все три — одного размера, без текста, круглые, единый стиль.
                    // Кнопка + — залитая акцентом, ✎ и 🗑 — outlined.
                    // На вкладке «Отчёты» (4) кнопка + НЕ показывается — там нет
                    // сущностей для создания (только виджеты). Edit/delete там тоже
                    // не показываются — нет строк для выбора.
                    // ── Кнопка «+» — скрыта на «Отчётах» (4) и «Sozlamalar» (6) ─
                    if (currentTab != 4 && currentTab != 6) {
                        IconButton(
                            onClick = {
                                when (currentTab) {
                                    0 -> showAddDialog = true
                                    1 -> showAddScooterDialog = true
                                    2 -> contractCreateTrigger++
                                    3 -> transactionCreateTrigger++
                                    5 -> cardCreateTrigger++
                                }
                            },
                            modifier = Modifier
                                .padding(end = 6.dp, start = 4.dp)
                                .size(40.dp)
                                .background(ClaudeAccent, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Qo'shish",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // ── Кнопка «✎ Tahrirlash» — скрыта на «Отчётах» (4) и «Sozlamalar» (6)
                    if (currentTab != 4 && currentTab != 6) {
                        val editEnabled = when (currentTab) {
                            0 -> selectedRenters.size == 1
                            1 -> selectedScooters.size == 1
                            2 -> selectedContracts.size == 1
                            3 -> selectedTxs.size == 1
                            5 -> selectedCardIds.size == 1
                            else -> false
                        }
                        IconButton(
                            onClick = {
                                when (currentTab) {
                                    0 -> {
                                        selectedRenters.firstOrNull()?.let { id ->
                                            renterToEdit = renters.firstOrNull { it.id == id }
                                        }
                                    }
                                    1 -> {
                                        selectedScooters.firstOrNull()?.let { id ->
                                            scooterToEdit = scooters.firstOrNull { it.id == id }
                                        }
                                    }
                                    2 -> contractEditTrigger++
                                    3 -> transactionEditTrigger++
                                    5 -> cardEditTrigger++
                                }
                            },
                            enabled = editEnabled,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(40.dp)
                                .background(
                                    if (editEnabled) Color.White else Color.White.copy(alpha = 0.5f),
                                    CircleShape
                                )
                                .border(1.dp, ClaudeDivider, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Tahrirlash",
                                tint = if (editEnabled) ClaudeAccent else ClaudeTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // ── Кнопка «🗑 O'chir» — универсальная ──────────────────
                        val deleteEnabled = when (currentTab) {
                            0 -> selectedRenters.isNotEmpty()
                            1 -> selectedScooters.isNotEmpty()
                            2 -> selectedContracts.isNotEmpty()
                            3 -> selectedTxs.isNotEmpty()
                            5 -> selectedCardIds.isNotEmpty()
                            else -> false
                        }
                        IconButton(
                            onClick = {
                                when (currentTab) {
                                    0 -> {
                                        selectedRenters.forEach { id -> viewModel.deleteRenter(id) }
                                        selectedRenters = emptySet()
                                    }
                                    1 -> {
                                        scooters.filter { it.id in selectedScooters }.forEach {
                                            scooterViewModel.deleteScooter(it)
                                        }
                                        selectedScooters = emptySet()
                                    }
                                    2 -> contractDeleteTrigger++
                                    3 -> transactionDeleteTrigger++
                                    5 -> cardDeleteTrigger++
                                }
                            },
                            enabled = deleteEnabled,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(40.dp)
                                .background(
                                    if (deleteEnabled) Color.White else Color.White.copy(alpha = 0.5f),
                                    CircleShape
                                )
                                .border(1.dp, if (deleteEnabled) StatusOverdue else ClaudeDivider, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "O'chirish",
                                tint = if (deleteEnabled) StatusOverdue else ClaudeTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // Кнопка «Настройки» удалена из TopAppBar — теперь
                    // настройки доступны как 7-я вкладка нижней навигации
                    // (Tab 6 = Sozlamalar), на одной линии с остальными
                    // главными страницами.
                }
            )
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
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Description, contentDescription = "Kontraktlar") },
                    label = { Text("Kontraktlar") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClaudeAccent,
                        unselectedIconColor = ClaudeTextSecondary,
                        selectedTextColor = ClaudeAccent,
                        unselectedTextColor = ClaudeTextSecondary,
                        indicatorColor = ClaudeAccentBg
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.RequestQuote, contentDescription = "Tranzaksiyalar") },
                    label = { Text("Tranzaksiya") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClaudeAccent,
                        unselectedIconColor = ClaudeTextSecondary,
                        selectedTextColor = ClaudeAccent,
                        unselectedTextColor = ClaudeTextSecondary,
                        indicatorColor = ClaudeAccentBg
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 4,
                    onClick = { currentTab = 4 },
                    icon = { Icon(Icons.Default.RequestQuote, contentDescription = "Otchetlar") },
                    label = { Text("Otchetlar") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClaudeAccent,
                        unselectedIconColor = ClaudeTextSecondary,
                        selectedTextColor = ClaudeAccent,
                        unselectedTextColor = ClaudeTextSecondary,
                        indicatorColor = ClaudeAccentBg
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 5,
                    onClick = { currentTab = 5 },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Finansi") },
                    label = { Text("Finansi") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClaudeAccent,
                        unselectedIconColor = ClaudeTextSecondary,
                        selectedTextColor = ClaudeAccent,
                        unselectedTextColor = ClaudeTextSecondary,
                        indicatorColor = ClaudeAccentBg
                    )
                )
                // ── 7-я вкладка: Sozlamalar ──────────────────────────────────
                // Раньше была кнопка-иконка в TopAppBar. Теперь — полноценная
                // вкладка внизу, рядом с остальными главными страницами.
                NavigationBarItem(
                    selected = currentTab == 6,
                    onClick = { currentTab = 6 },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = "Sozlamalar") },
                    label = { Text("Sozlamalar") },
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
                    // После запуска системного установщика (ACTION_VIEW) мы не
                    // получаем обратный вызов о результате. Если пользователь
                    // отменил установку в системном диалоге, он вернётся в
                    // приложение, и спиннер останется висеть. Поэтому даём
                    // кнопку «Yopish» (Close), чтобы пользователь мог сам
                    // закрыть баннер.
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFF000000),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Tizim o'rnatuvchisini tasdiqlang...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF000000)
                                )
                            }
                            TextActionButton(
                                label = "Yopish",
                                icon = Icons.Default.Close,
                                onClick = { updateManager.reset() }
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
                    visible = showRenterFilterPanel,
                    columnVisibility = renterColumnVisibility,
                    onColumnVisibilityChange = { colId, isVisible ->
                        renterColumnVisibility = renterColumnVisibility.toMutableMap().apply { put(colId, isVisible) }
                    }
                )

                // ── Панель действий — ВСЕГДА ВИДНА (Task 3) ─────────────
                // Кнопки To'lov / Uzish / SMS / O'chir всегда присутствуют.
                // Чтобы выполнить действие, нужно выбрать хотя бы 1 арендатора
                // (долгое нажатие по строке). Без выбора кнопки отображаются
                // серыми (disabled) — но они видны всегда. Текст "X ta
                // tanlandi" убран по просьбе пользователя.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasSelection = selectedRenters.isNotEmpty()
                    SuccessButton(
                        label = "To'lov",
                        icon = Icons.Default.Payments,
                        enabled = hasSelection,
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
                        enabled = hasSelection,
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
                        enabled = hasSelection,
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
                    // Кнопка "O'chir" удалена — её заменяет универсальная 🗑
                    // в верхней панели (TopAppBar), которая теперь работает для
                    // всех вкладок. To'lov / Uzish / SMS остаются — это уникальные
                    // действия, которых нет в верхней панели.
                }

                // ===== ТАБЛИЦА АРЕНДАТОРОВ =====
                val dateFmtLocal = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

                // ── Latest contract per renter ─────────────────────────────────
                // Для каждой строки таблицы нужны даты ПОСЛЕДНЕГО (самого нового)
                // контракта арендатора, а не первого. Раньше в колонках
                // «Boshlanish» / «Tugash» показывались дата создания арендатора
                // (rentStartDateTimestamp) и конец первоначального периода
                // (start + duration × dayMs) — это даты ПЕРВОГО контракта.
                // Теперь берём из истории контрактов самую свежую запись
                // (CREATED или AUTO_RENEW) с наибольшим weekEnd и используем её
                // weekStart / weekEnd. Если истории нет — fallback на поля Renter.
                val contractHistory by contractHistoryViewModel.history
                    .collectAsStateWithLifecycle()
                val latestContractByRenter: Map<Int, com.example.data.ContractHistoryEntry> =
                    remember(contractHistory) {
                        contractHistory
                            .asSequence()
                            .filter {
                                it.type == com.example.data.ContractHistoryEntry.TYPE_CREATED ||
                                it.type == com.example.data.ContractHistoryEntry.TYPE_AUTO_RENEW
                            }
                            .filter { it.renterId > 0 }
                            .groupBy { it.renterId }
                            .mapValues { (_, entries) ->
                                entries.maxByOrNull { it.weekEnd ?: it.timestamp }!!
                            }
                    }

                // Helper: даты последнего контракта (с fallback на поля Renter).
                fun latestStartTs(r: Renter): Long =
                    latestContractByRenter[r.id]?.weekStart ?: r.rentStartDateTimestamp
                fun latestEndTs(r: Renter): Long =
                    latestContractByRenter[r.id]?.weekEnd
                        ?: (r.rentStartDateTimestamp + (r.rentDurationDays * 24L * 60 * 60 * 1000))

                val filteredRenters = renters.filter { renter ->
                    val textMatch = renter.name.contains(searchQuery, ignoreCase = true) ||
                        renter.phoneNumber.contains(searchQuery) ||
                        (renter.scooterName != null && renter.scooterName.contains(searchQuery, ignoreCase = true))
                    val startMillis = dateRangePickerState.selectedStartDateMillis
                    val endMillis = dateRangePickerState.selectedEndDateMillis
                    val dateMatch = if (startMillis != null) {
                        // Фильтр по дате окончания ПОСЛЕДНЕГО контракта
                        // (раньше — по концу первоначального периода аренды).
                        val expiryTime = latestEndTs(renter)
                        if (endMillis != null) expiryTime in startMillis..endMillis
                        else expiryTime >= startMillis
                    } else true
                    // Column filters from side panel
                    val filterMatch = renterFilterValues.all { (colId, filterText) ->
                        if (filterText.isBlank()) true
                        else when (colId) {
                            "col_name" -> renter.name.contains(filterText, ignoreCase = true)
                            "col_phone" -> renter.phoneNumber.contains(filterText, ignoreCase = true)
                            "col_scooter" -> (renter.scooterName ?: "").contains(filterText, ignoreCase = true)
                            "col_start" -> dateFmtLocal.format(Date(latestStartTs(renter))).contains(filterText, ignoreCase = true)
                            "col_end" -> dateFmtLocal.format(Date(latestEndTs(renter))).contains(filterText, ignoreCase = true)
                            "col_balance" -> renter.balance.toLong().toString().contains(filterText, ignoreCase = true)
                            "col_status" -> {
                                // Faol / Qaytgan / Qarzdor — по статусу арендатора.
                                val s = statusOf(renter)
                                statusLabel(s).contains(filterText, ignoreCase = true)
                            }
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
                        // Default: sort by status (latest contract end) ASC
                        list.sortedWith(compareBy { latestEndTs(it) })
                    } else {
                        val comparator = when (col) {
                            "col_name" -> compareBy<Renter> { it.name.lowercase() }
                            "col_phone" -> compareBy<Renter> { it.phoneNumber }
                            "col_scooter" -> compareBy<Renter> { it.scooterName ?: "" }
                            "col_start" -> compareBy<Renter> { latestStartTs(it) }
                            "col_end" -> compareBy<Renter> { latestEndTs(it) }
                            "col_balance" -> compareBy<Renter> { it.balance }
                            "col_passport" -> compareBy<Renter> { it.passportData }
                            "col_address" -> compareBy<Renter> { it.address }
                            "col_pinfl" -> compareBy<Renter> { it.pinfl }
                            else -> compareBy<Renter> { latestEndTs(it) }
                        }
                        if (state == SortState.ASCENDING) list.sortedWith(comparator)
                        else list.sortedWith(comparator.reversed())
                    }
                }

                RenterTable(
                    renters = filteredRenters,
                    selected = selectedRenters,
                    sortState = renterSortState,
                    columnVisibility = renterColumnVisibility,
                    latestContractByRenter = latestContractByRenter,
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
            } else if (currentTab == 1) {
                // Вкладка «Скутеры» — unified search bar с календарём
                // (фильтр по дате начала активного контракта скутера) и фильтром.
                UnifiedSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Skuter qidirish",
                    onCalendarClick = { showScooterDateRangePicker = true },
                    calendarActive = scooterDateRangePickerState.selectedStartDateMillis != null,
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
                    visible = showScooterFilterPanel,
                    columnVisibility = scooterColumnVisibility,
                    onColumnVisibilityChange = { colId, isVisible ->
                        scooterColumnVisibility = scooterColumnVisibility.toMutableMap().apply { put(colId, isVisible) }
                    }
                )

                // ── Счётчик скутеров ────────────────────────────────
                // Раньше здесь была панель с кнопками "Tahrirlash / O'chir",
                // но они дублировали универсальные ✎/🗑 в верхней панели.
                // Неуниверсальные кнопки-дубликаты удалены — остался только
                // счётчик количества скутеров.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Jami: ${scooters.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }

                val filteredScooters = scooters.filter { scooter ->
                    val textMatch = scooter.name.contains(searchQuery, ignoreCase = true)
                    // Calendar filter — by active renter's contract start date.
                    // A scooter with no active renter never matches a date filter
                    // (matches the "no contract in this range" semantic).
                    val scooterStartMillis = renters
                        .firstOrNull { it.scooterId == scooter.id && !it.isReturned }
                        ?.rentStartDateTimestamp
                    val startMillis = scooterDateRangePickerState.selectedStartDateMillis
                    val endMillis = scooterDateRangePickerState.selectedEndDateMillis
                    val dateMatch = if (startMillis != null && scooterStartMillis != null) {
                        if (endMillis != null) scooterStartMillis in startMillis..endMillis
                        else scooterStartMillis >= startMillis
                    } else true
                    val filterMatch = scooterFilterValues.all { (colId, filterText) ->
                        if (filterText.isBlank()) true
                        else when (colId) {
                            "col_name"   -> scooter.name.contains(filterText, ignoreCase = true)
                            "col_doc"    -> (scooter.documentedNumber ?: "").contains(filterText, ignoreCase = true)
                            "col_vin"    -> scooter.vinNumber.contains(filterText, ignoreCase = true)
                            "col_engine" -> scooter.engineNumber.contains(filterText, ignoreCase = true)
                            "col_serial" -> scooter.scooterSerialNumber.contains(filterText, ignoreCase = true)
                            "col_batt1"  -> scooter.batteryId1.contains(filterText, ignoreCase = true)
                            "col_batt2"  -> scooter.batteryId2.contains(filterText, ignoreCase = true)
                            "col_extra"  -> scooter.additionalInfo.contains(filterText, ignoreCase = true)
                            "col_status" -> {
                                val status = scooterStatusLabel(scooterStatusOf(scooter.id, renters))
                                status.contains(filterText, ignoreCase = true)
                            }
                            else -> true
                        }
                    }
                    textMatch && dateMatch && filterMatch
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
                    columnVisibility = scooterColumnVisibility,
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
            } else if (currentTab == 2) {
                // ── Вкладка «Kontraktlar» — все контракты всех арендаторов ──
                ContractListScreen(
                    contractHistoryViewModel = contractHistoryViewModel,
                    renterViewModel = viewModel,
                    scooterViewModel = scooterViewModel,
                    createTrigger = contractCreateTrigger,
                    editTrigger = contractEditTrigger,
                    deleteTrigger = contractDeleteTrigger,
                    selectedContracts = selectedContracts,
                    onSelectedContractsChange = { selectedContracts = it },
                    onContractClick = { entry ->
                        navState = NavigationState.ContractTransactionHistory(entry)
                    }
                )
            } else if (currentTab == 3) {
                // ── Вкладка «Tranzaksiya» — все транзакции ──────────────
                // Показывает и платежи по контрактам (Transaction), и переводы
                // между виртуальными картами (CardTransaction) в одной ленте.
                TransactionListScreen(
                    transactionViewModel = transactionViewModel,
                    renterViewModel = viewModel,
                    scooterViewModel = scooterViewModel,
                    contractHistoryViewModel = contractHistoryViewModel,
                    finansiViewModel = finansiViewModel,
                    createTrigger = transactionCreateTrigger,
                    editTrigger = transactionEditTrigger,
                    deleteTrigger = transactionDeleteTrigger,
                    selectedTxs = selectedTxs,
                    onSelectedTxsChange = { selectedTxs = it }
                )
            } else if (currentTab == 4) {
                // ── Вкладка «Otchetlar» — дашборд с инфографикой ────────
                ReportsScreen(
                    renterViewModel = viewModel,
                    scooterViewModel = scooterViewModel,
                    contractHistoryViewModel = contractHistoryViewModel,
                    transactionViewModel = transactionViewModel,
                    finansiViewModel = finansiViewModel
                )
            } else if (currentTab == 5) {
                // ── Вкладка «Finansi» — виртуальные карты + переводы ────
                FinansiPanel(
                    viewModel = finansiViewModel,
                    externalCreateTrigger = cardCreateTrigger,
                    externalEditTrigger = cardEditTrigger,
                    externalDeleteTrigger = cardDeleteTrigger,
                    selectedCardIds = selectedCardIds,
                    onSelectedCardIdsChange = { selectedCardIds = it },
                    onCardClick = { card ->
                        navState = NavigationState.CardHistory(card)
                    }
                )
            } else if (currentTab == 6) {
                // ── Вкладка «Sozlamalar» ─────────────────────────────────
                // Раньше была отдельная страница, открываемая через кнопку
                // в TopAppBar. Теперь — 7-я вкладка нижней навигации.
                val template by settingsViewModel.smsTemplate.collectAsStateWithLifecycle()
                val weekly by settingsViewModel.weeklyPrice.collectAsStateWithLifecycle()
                val monthly by settingsViewModel.monthlyPrice.collectAsStateWithLifecycle()
                val smsAutoSend by settingsViewModel.smsAutoSendEnabled.collectAsStateWithLifecycle()
                SettingsScreen(
                    currentTemplate = template,
                    currentWeeklyPrice = weekly,
                    currentMonthlyPrice = monthly,
                    currentSmsAutoSend = smsAutoSend,
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
                    onResetUpdate = { updateManager.reset() },
                    onBack = { currentTab = 0 },
                    onSave = { newTemplate, newWeekly, newMonthly, _, _ ->
                        // Автосохранение — Toast на каждое нажатие клавиши был бы назойливым.
                        settingsViewModel.updateTemplate(newTemplate)
                        settingsViewModel.updatePrices(newWeekly, newMonthly)
                    },
                    onSmsAutoSendChange = { enabled ->
                        settingsViewModel.updateSmsAutoSend(enabled)
                        Toast.makeText(
                            localContext,
                            if (enabled) "SMS avto-yuborish yoqildi"
                            else "SMS qo'llanma rejimiga o'tdi",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onLogout = {
                        // Просто возврат на главную вкладку — реального logout нет
                        currentTab = 0
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
                    },
                    onExportBackup = { uri ->
                        coroutineScope.launch {
                            val msg = com.example.data.BackupManager.exportToExcel(localContext, uri)
                            Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    onImportBackup = { uri ->
                        coroutineScope.launch {
                            val msg = com.example.data.BackupManager.importFromExcel(localContext, uri)
                            Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    // Вкладка внутри MainView — НЕ рендерим собственный TopAppBar,
                    // т.к. внешний Scaffold уже даёт «Skuter Ijarasi» + универсальные
                    // кнопки. Это убирает пустое пространство сверху (дублирующий
                    // TopAppBar «Sozlamalar») и снизу (contentWindowInsets).
                    showTopBar = false
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
                            pinfl = result.pinfl,
                            contractGroups = result.contractGroups
                        )
                    }
                    showAddDialog = false
                    renterToEdit = null
                },
                // ── Inline-создание скутера ────────────────────────────────
                // Пользователь может внутри формы арендатора создать новый
                // скутер, не выходя из диалога. Форма автоматически выберет
                // свежесозданный скутер в качестве scooterId для арендатора.
                onCreateScooterInline = { name, docNum, vin, engine, serial, batt1, batt2, info ->
                    scooterViewModel.addScooter(
                        name = name,
                        documentedNumber = docNum,
                        vinNumber = vin,
                        engineNumber = engine,
                        scooterSerialNumber = serial,
                        batteryId1 = batt1,
                        batteryId2 = batt2,
                        additionalInfo = info
                    )
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

        if (showDateRangePicker) {
            com.example.ui.components.DateRangeFilterDialog(
                state = dateRangePickerState,
                onDismiss = { showDateRangePicker = false },
                title = "Kontrakt tugash sanasi bo'yicha filter"
            )
        }

        if (showScooterDateRangePicker) {
            com.example.ui.components.DateRangeFilterDialog(
                state = scooterDateRangePickerState,
                onDismiss = { showScooterDateRangePicker = false },
                title = "Kontrakt boshlanishi bo'yicha filter"
            )
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
    columnVisibility: Map<String, Boolean>,
    latestContractByRenter: Map<Int, com.example.data.ContractHistoryEntry>,
    onSortClick: (String) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onClick: (Renter) -> Unit
) {
    // ── Видимость столбцов ───────────────────────────────────────────────
    // Каждая колонка по умолчанию видна (true), если в columnVisibility нет
    // явного значения false. Пользователь управляет видимостью через
    // FilterSidePanel (чекбоксы). Это заменяет старую логику "скрыть столбец
    // если все значения пустые" — теперь пользователь сам решает что видеть.
    fun isColVisible(colId: String): Boolean = columnVisibility[colId] ?: true
    val showName     = isColVisible("col_name")
    val showPhone    = isColVisible("col_phone")
    val showScooter  = isColVisible("col_scooter")
    val showStart    = isColVisible("col_start")
    val showEnd      = isColVisible("col_end")
    val showBalance  = isColVisible("col_balance")
    val showPassport = isColVisible("col_passport")
    val showAddress  = isColVisible("col_address")
    val showPinfl    = isColVisible("col_pinfl")

    // ── Компоновка ──────────────────────────────────────────────────────
    // Если видны только 6 базовых колонок (без extras) — используем
    // weight-based Row без скролла. Если видна хотя бы одна extra-колонка —
    // fixed widths + горизонтальный скролл.
    val hasAnyExtraVisible = showPassport || showAddress || showPinfl

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

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val hScrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Заголовок ────────────────────────────────────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .then(if (hasAnyExtraVisible) Modifier.horizontalScroll(hScrollState) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAnyExtraVisible) {
                    if (showName)     SortableHeaderCellFixed(Icons.Default.Person,               wName,     "col_name",     sortState) { onSortClick("col_name") }
                    if (showPhone)    SortableHeaderCellFixed(Icons.Default.Phone,                wPhone,    "col_phone",    sortState) { onSortClick("col_phone") }
                    if (showScooter)  SortableHeaderCellFixed(Icons.Default.DirectionsBike,       wScoot,    "col_scooter",  sortState) { onSortClick("col_scooter") }
                    if (showStart)    SortableHeaderCellFixed(Icons.Default.CalendarToday,        wStart,    "col_start",    sortState) { onSortClick("col_start") }
                    if (showEnd)      SortableHeaderCellFixed(Icons.Default.Event,                wEnd,      "col_end",      sortState) { onSortClick("col_end") }
                    if (showBalance)  SortableHeaderCellFixed(Icons.Default.AccountBalanceWallet, wDebt,     "col_balance",  sortState) { onSortClick("col_balance") }
                    if (showPassport) SortableHeaderCellFixed(Icons.Default.CreditCard,           wPassport, "col_passport", sortState) { onSortClick("col_passport") }
                    if (showAddress)  SortableHeaderCellFixed(Icons.Default.Home,                 wAddress,  "col_address",  sortState) { onSortClick("col_address") }
                    if (showPinfl)    SortableHeaderCellFixed(Icons.Default.Fingerprint,          wPinfl,    "col_pinfl",    sortState) { onSortClick("col_pinfl") }
                } else {
                    if (showName)     SortableHeaderCell(Icons.Default.Person,               fName,  "col_name",     sortState) { onSortClick("col_name") }
                    if (showPhone)    SortableHeaderCell(Icons.Default.Phone,                fPhone, "col_phone",    sortState) { onSortClick("col_phone") }
                    if (showScooter)  SortableHeaderCell(Icons.Default.DirectionsBike,       fScoot, "col_scooter",  sortState) { onSortClick("col_scooter") }
                    if (showStart)    SortableHeaderCell(Icons.Default.CalendarToday,        fStart, "col_start",    sortState) { onSortClick("col_start") }
                    if (showEnd)      SortableHeaderCell(Icons.Default.Event,                fEnd,   "col_end",      sortState) { onSortClick("col_end") }
                    if (showBalance)  SortableHeaderCell(Icons.Default.AccountBalanceWallet, fDebt,  "col_balance",  sortState) { onSortClick("col_balance") }
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
                            .then(if (hasAnyExtraVisible) Modifier.horizontalScroll(hScrollState) else Modifier.fillMaxWidth())
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
                        if (showName) {
                            Text(
                                renter.name,
                                modifier = Modifier
                                    .then(if (hasAnyExtraVisible) Modifier.width(wName) else Modifier.weight(fName))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        // Tel
                        if (showPhone) {
                            Text(
                                renter.phoneNumber,
                                modifier = Modifier
                                    .then(if (hasAnyExtraVisible) Modifier.width(wPhone) else Modifier.weight(fPhone))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                maxLines = 1
                            )
                        }
                        // Skuter
                        if (showScooter) {
                            Text(
                                renter.scooterName ?: "—",
                                modifier = Modifier
                                    .then(if (hasAnyExtraVisible) Modifier.width(wScoot) else Modifier.weight(fScoot))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        // Boshlanish (дата начала ПОСЛЕДНЕГО контракта)
                        if (showStart) {
                            val latest = latestContractByRenter[renter.id]
                            val startTs = latest?.weekStart ?: renter.rentStartDateTimestamp
                            Text(
                                dateFmt.format(Date(startTs)),
                                modifier = Modifier
                                    .then(if (hasAnyExtraVisible) Modifier.width(wStart) else Modifier.weight(fStart))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        // Tugash (дата окончания ПОСЛЕДНЕГО контракта)
                        // Берётся weekEnd самой свежей записи (CREATED/AUTO_RENEW).
                        // Раньше показывался конец ПЕРВОГО контракта
                        // (rentStartDateTimestamp + rentDurationDays × dayMs),
                        // что не соответствовало реальной текущей дате контракта.
                        if (showEnd) {
                            val latest = latestContractByRenter[renter.id]
                            val endTs = latest?.weekEnd
                                ?: (renter.rentStartDateTimestamp +
                                    (renter.rentDurationDays * 24L * 60 * 60 * 1000))
                            Text(
                                dateFmt.format(Date(endTs)),
                                modifier = Modifier
                                    .then(if (hasAnyExtraVisible) Modifier.width(wEnd) else Modifier.weight(fEnd))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        // Balans
                        if (showBalance) {
                            val balanceColor = when {
                                renter.balance < 0 -> StatusOverdue
                                renter.balance > 0 -> StatusOk
                                else -> ClaudeText
                            }
                            Text(
                                renter.balance.toLong().toString(),
                                modifier = Modifier
                                    .then(if (hasAnyExtraVisible) Modifier.width(wDebt) else Modifier.weight(fDebt))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = balanceColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                        }
                        // ── Опциональные колонки (показываются если включены) ─
                        if (showPassport) {
                            Text(
                                renter.passportData.ifBlank { "—" },
                                modifier = Modifier.width(wPassport).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showAddress) {
                            Text(
                                renter.address.ifBlank { "—" },
                                modifier = Modifier.width(wAddress).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showPinfl) {
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
    onSave: (RenterFormResult) -> Unit,
    // ── Inline-создание скутера ────────────────────────────────────────────
    // Когда пользователь нажимает «+ Yangi skuter yaratish» в конце списка
    // скутеров, внизу формы арендатора появляются поля для ввода данных
    // нового скутера. При сохранении вызывается этот callback — родитель
    // вызывает scooterViewModel.addScooter, после чего список `scooters`
    // обновляется через Flow, и LaunchedEffect ниже автоматически выбирает
    // свежесозданный скутер в качестве selectedScooterId.
    onCreateScooterInline: (
        name: String,
        documentedNumber: String?,
        vinNumber: String,
        engineNumber: String,
        scooterSerialNumber: String,
        batteryId1: String,
        batteryId2: String,
        additionalInfo: String
    ) -> Unit = { _, _, _, _, _, _, _, _ -> }
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

    // ── Группы контрактов (новый календарь) ───────────────────────────
    // Список групп, выбранных пользователем в календаре. Если список не пуст,
    // он имеет приоритет над автоматической логикой по выбранной дате.
    var contractGroups by remember { mutableStateOf<List<ContractGroup>>(emptyList()) }
    var activeGroupId by remember { mutableStateOf<Int?>(null) }

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

    // ── Inline-создание скутера: state ───────────────────────────────────
    // showCreateScooterInline — раскрыта ли внизу формы секция создания скутера.
    // pendingScooterName — имя скутера, который только что был создан через
    //   onCreateScooterInline. После того как `scooters` обновится (Flow
    //   репозитрия) и в нём появится скутер с таким именем, мы автоматически
    //   выбираем его в selectedScooterId и сбрасываем pending.
    var showCreateScooterInline by remember { mutableStateOf(false) }
    var pendingScooterName by remember { mutableStateOf<String?>(null) }

    // Поля формы создания скутера. Авто-нумерация имени — берём следующий
    // свободный номер после префикса "Skillmax-".
    val initialScooterName = remember(scooters) {
        if (showCreateScooterInline) {
            val nextN = (scooters
                .mapNotNull { it.name.removePrefix("Skillmax-").trimStart('0').toIntOrNull() }
                .maxOrNull() ?: 0) + 1
            "Skillmax-" + nextN.toString().padStart(3, '0')
        } else ""
    }
    var newScooterName by remember(showCreateScooterInline) {
        mutableStateOf(initialScooterName)
    }
    var newScooterDocNum by remember(showCreateScooterInline) { mutableStateOf("") }
    var newScooterVin by remember(showCreateScooterInline) { mutableStateOf("") }
    var newScooterEngine by remember(showCreateScooterInline) { mutableStateOf("") }
    var newScooterSerial by remember(showCreateScooterInline) { mutableStateOf("") }
    var newScooterBatt1 by remember(showCreateScooterInline) { mutableStateOf("") }
    var newScooterBatt2 by remember(showCreateScooterInline) { mutableStateOf("") }
    var newScooterInfo by remember(showCreateScooterInline) { mutableStateOf("") }

    // Авто-выбор свежесозданного скутера, как только он появится в списке.
    LaunchedEffect(scooters, pendingScooterName) {
        val pending = pendingScooterName
        if (pending != null) {
            val match = scooters.firstOrNull { it.name.equals(pending, ignoreCase = true) }
            if (match != null) {
                selectedScooterId = match.id
                pendingScooterName = null
            }
        }
    }

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

    // Дополнительные поля (паспорт/адрес/ПИНФЛ) теперь ВСЕГДА видны и обязательны
    // — пользователь явно попросил убрать кнопку «More»/«Yashirish» и сделать
    // эти поля такими же, как остальные обязательные поля формы.

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

                // ── Новый календарь с группами контрактов ───────────────────
                // Пользователь может выбрать один или несколько периодов
                // (групп контрактов). Кнопка «+» в правом верхнем углу календаря
                // запускает выбор новой группы: тап по первой дате, тап по второй.
                // Созданные группы отображаются вкладками (1, 2, 3...) с кнопкой
                // «x» для удаления. При сохранении формы группы передаются в
                // RenterFormResult.contractGroups и используются в addRenter.
                Spacer(modifier = Modifier.height(8.dp))
                ContractCalendar(
                    editable = true,
                    groups = contractGroups,
                    activeGroupId = activeGroupId,
                    onGroupsChange = { contractGroups = it },
                    onActiveGroupChange = { activeGroupId = it }
                )

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
                        // ── Кнопка «+ Yangi skuter yaratish» в самом низу ────
                        // Сценарий: пользователь открывает список скутеров,
                        // не находит нужный — может создать новый, не выходя
                        // из окна создания арендатора. При клике: dropdown
                        // закрывается, и внизу формы разворачивается секция
                        // с полями для ввода данных нового скутера.
                        HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = ClaudeAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Yangi skuter yaratish",
                                        color = ClaudeAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            },
                            onClick = {
                                showCreateScooterInline = true
                                expandedScooter = false
                            }
                        )
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

                // ── Реквизиты арендатора для PDF-договора (всегда видны) ────
                HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
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

                // ── Inline-секция создания нового скутера ────────────────────
                // Появляется только если пользователь нажал «Yangi skuter
                // yaratish» в выпадающем списке скутеров выше. Все поля
                // необязательны — кнопка «Skuterni saqlash» всегда активна.
                if (showCreateScooterInline) {
                    HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
                    SectionLabel("Yangi skuter yaratish")

                    OutlinedTextField(
                        value = newScooterName,
                        onValueChange = { newScooterName = it },
                        label = { Text("Skuter nomi (ixtiyoriy)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newScooterDocNum,
                        onValueChange = { newScooterDocNum = it },
                        label = { Text("Hujjat raqami (ixtiyoriy)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newScooterVin,
                        onValueChange = { newScooterVin = it },
                        label = { Text("VIN (ixtiyoriy)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newScooterEngine,
                        onValueChange = { newScooterEngine = it },
                        label = { Text("Dvigatel (ixtiyoriy)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newScooterSerial,
                        onValueChange = { newScooterSerial = it },
                        label = { Text("ID (ixtiyoriy)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newScooterBatt1,
                            onValueChange = { newScooterBatt1 = it },
                            label = { Text("Akkumulyator 1") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = newScooterBatt2,
                            onValueChange = { newScooterBatt2 = it },
                            label = { Text("Akkumulyator 2") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = newScooterInfo,
                        onValueChange = { newScooterInfo = it },
                        label = { Text("Qo'shimcha ma'lumot (ixtiyoriy)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PrimaryButton(
                            label = "Skuterni saqlash",
                            icon = Icons.Default.Check,
                            enabled = true,
                            onClick = {
                                val nameToSave = newScooterName.trim()
                                    .ifBlank {
                                        // Если имя пустое — генерируем авто-имя,
                                        // иначе скутер сохранится безымянным.
                                        val nextN = (scooters
                                            .mapNotNull {
                                                it.name.removePrefix("Skillmax-")
                                                    .trimStart('0').toIntOrNull()
                                            }
                                            .maxOrNull() ?: 0) + 1
                                        "Skillmax-" + nextN.toString().padStart(3, '0')
                                    }
                                onCreateScooterInline(
                                    nameToSave,
                                    newScooterDocNum.trim().ifBlank { null },
                                    newScooterVin.trim(),
                                    newScooterEngine.trim(),
                                    newScooterSerial.trim(),
                                    newScooterBatt1.trim(),
                                    newScooterBatt2.trim(),
                                    newScooterInfo.trim()
                                )
                                // Запоминаем имя — LaunchedEffect(scooters)
                                // подхватит свежесозданный скутер и установит
                                // selectedScooterId автоматически.
                                pendingScooterName = nameToSave
                                // Скрываем inline-форму.
                                showCreateScooterInline = false
                            }
                        )
                        TextActionButton(
                            label = "Bekor",
                            icon = Icons.Default.Close,
                            onClick = {
                                showCreateScooterInline = false
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось name+phone+passport+address+pinfl — убрано
                // по требованию пользователя: ничего не должно блокировать сохранение.
                enabled = true,
                onClick = {
                    val debtValue = debt.toDoubleOrNull() ?: 0.0
                    val durationValue = duration.toIntOrNull() ?: 7
                    val phoneToSave = if (phone.isBlank()) "" else "+998$phone"
                    val scooterName = scooters.find { it.id == selectedScooterId }?.name
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
                            pinfl = pinfl.trim(),
                            contractGroups = contractGroups.map { it.startMs to it.endMs }
                        )
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
    val pinfl: String,
    // Группы контрактов, выбранные в календаре (если пусто — используется
    // автоматическая логика по выбранной дате).
    val contractGroups: List<Pair<Long, Long>> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTemplate: String,
    currentWeeklyPrice: Double,
    currentMonthlyPrice: Double,
    currentSmsAutoSend: Boolean = true,
    updateInfo: UpdateInfo? = null,
    isCheckingUpdate: Boolean = false,
    isUpToDate: Boolean = false,
    updateState: InAppUpdateState = InAppUpdateState.Idle,
    onStartUpdate: (UpdateInfo) -> Unit = {},
    onResetUpdate: () -> Unit = {},
    onBack: () -> Unit,
    onSave: (String, Double, Double, String, String) -> Unit,
    onSmsAutoSendChange: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onExportBackup: (android.net.Uri) -> Unit = {},
    onImportBackup: (android.net.Uri) -> Unit = {},
    // ── showTopBar ───────────────────────────────────────────────────────
    // true  — рендерить собственный Scaffold + TopAppBar с «Sozlamalar».
    //         Используется когда SettingsScreen открыт как отдельная страница
    //         (NavigationState.Settings) — нужен свой top bar с заголовком.
    // false — НЕ рендерить собственный Scaffold/TopAppBar. Используется когда
    //         SettingsScreen встроен во вкладку MainView (currentTab == 6) —
    //         там уже есть внешний Scaffold с TopAppBar «Skuter Ijarasi» и
    //         универсальными кнопками (сканер, SMS). Без этого параметра
    //         получался nested Scaffold: второй TopAppBar «Sozlamalar»
    //         рисовался ниже внешнего, создавая пустое пространство сверху,
    //         а contentWindowInsets внутреннего Scaffold'а добавлял лишний
    //         отступ снизу перед нижней навигацией.
    showTopBar: Boolean = true
) {
    var template by remember { mutableStateOf(currentTemplate) }
    var weekly by remember {
        mutableStateOf(if (currentWeeklyPrice > 0) currentWeeklyPrice.toString() else "")
    }
    var monthly by remember {
        mutableStateOf(if (currentMonthlyPrice > 0) currentMonthlyPrice.toString() else "")
    }
    // SMS avto-yuborish rejimi — darhol saqlanadi (Save bosishni kutmaydi).
    var smsAutoSend by remember { mutableStateOf(currentSmsAutoSend) }
    val settingsContext = LocalContext.current
    val settingsRepo = remember { com.example.data.SettingsRepository(settingsContext) }
    var paymeLink by remember { mutableStateOf(settingsRepo.paymeLink) }
    var callCenter by remember { mutableStateOf(settingsRepo.callCenter) }
    // ── Поля для страницы Отчёты: стоимость скутера и курс USD ──────────
    var scooterPriceUsd by remember {
        mutableStateOf(settingsRepo.scooterPriceUsd.let {
            if (it > 0) it.toString() else com.example.data.SettingsRepository.DEFAULT_SCOOTER_PRICE_USD.toString()
        })
    }
    var usdToUzsRate by remember {
        mutableStateOf(settingsRepo.usdToUzsRate.let {
            if (it > 0) it.toString() else com.example.data.SettingsRepository.DEFAULT_USD_TO_UZS_RATE.toString()
        })
    }

    // ── Автосохранение — поля сохраняются автоматически при изменении,
    // отдельные кнопки «Saqla» больше не нужны (форма живая).
    LaunchedEffect(template, weekly, monthly, paymeLink, callCenter, scooterPriceUsd, usdToUzsRate) {
        val wPrice = weekly.toDoubleOrNull() ?: 0.0
        val mPrice = monthly.toDoubleOrNull() ?: 0.0
        settingsRepo.paymeLink = paymeLink.trim().ifBlank {
            com.example.data.SettingsRepository.DEFAULT_PAYME_LINK
        }
        settingsRepo.callCenter = callCenter.trim().ifBlank {
            com.example.data.SettingsRepository.DEFAULT_CALL_CENTER
        }
        // Сохраняем цену скутера и курс USD для страницы Отчётов
        settingsRepo.scooterPriceUsd = scooterPriceUsd.toDoubleOrNull()
            ?: com.example.data.SettingsRepository.DEFAULT_SCOOTER_PRICE_USD
        settingsRepo.usdToUzsRate = usdToUzsRate.toDoubleOrNull()
            ?: com.example.data.SettingsRepository.DEFAULT_USD_TO_UZS_RATE
        onSave(template, wPrice, mPrice, paymeLink, callCenter)
    }

    // ── Storage Access Framework launchers для экспорта/импорта Excel ────
    // Используем ACTION_CREATE_DOCUMENT (для экспорта — пользователь выбирает
    // куда сохранить файл) и ACTION_OPEN_DOCUMENT (для импорта — пользователь
    // выбирает какой файл загрузить). Никаких runtime-разрешений не нужно,
    // т.к. доступ к URI выдаётся через SAF.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) onExportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Постоянное разрешение на чтение — на случай если импорт запустится
            // в фоновой корутине и пройдёт какое-то время.
            try {
                settingsContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* ignore */ }
            onImportBackup(uri)
        }
    }
    val backupDateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val defaultBackupName = remember { "scooter_backup_${backupDateFormat.format(java.util.Date())}.xlsx" }

    val settingsScrollState = rememberScrollState()

    // ── Контент страницы настроек ─────────────────────────────────────────
    // Вынесен в отдельную composable-лямбду, чтобы переиспользовать его в двух
    // сценариях:
    //   1. showTopBar = true  → отдельная страница (NavigationState.Settings)
    //      со своим Scaffold + TopAppBar «Sozlamalar»
    //   2. showTopBar = false → вкладка внутри MainView (currentTab == 6),
    //      где внешний Scaffold уже даёт TopAppBar «Skuter Ijarasi» с
    //      универсальными кнопками и нижнюю навигацию. В этом случае мы НЕ
    //      рендерим свой Scaffold — иначе получается nested Scaffold с
    //      дублирующим TopAppBar (лишнее пустое пространство сверху) и
    //      contentWindowInsets (лишний отступ снизу перед bottom nav).
    @Composable
    fun settingsContent(extraPadding: PaddingValues) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(extraPadding)
                .verticalScroll(settingsScrollState)
                // Только горизонтальные отступы по бокам, без вертикальных —
                // содержимое начинается сразу под TopAppBar (или под внешним
                // TopAppBar, если showTopBar=false) и заканчивается у нижней
                // навигации. Между элементами остаётся spacedBy(16.dp).
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scooterPriceUsd,
                        onValueChange = { scooterPriceUsd = it },
                        label = { Text("Skuter narxi (USD) — otchetlar uchun") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = usdToUzsRate,
                        onValueChange = { usdToUzsRate = it },
                        label = { Text("1 USD = ? UZS (kurs)") },
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
                // (Переключатель SMS Avto/Qo'llanma перенесён в верхний бар —
                // круглая SMS-кнопка рядом с «+».)
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
                            // После запуска системного установщика (ACTION_VIEW)
                            // мы не получаем обратный вызов. Даём кнопку
                            // «Yopish» чтобы пользователь мог закрыть баннер,
                            // если отменил установку в системном диалоге.
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = Color(0xFF000000),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Tizim o'rnatuvchisini tasdiqlang...",
                                            color = Color(0xFF000000)
                                        )
                                    }
                                    TextActionButton(
                                        label = "Yopish",
                                        icon = Icons.Default.Close,
                                        onClick = onResetUpdate
                                    )
                                }
                            }
                        }
                        is InAppUpdateState.Error -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        updateState.message,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        color = Color(0xFF000000),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextActionButton(
                                        label = "Yopish",
                                        icon = Icons.Default.Close,
                                        onClick = onResetUpdate
                                    )
                                }
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

                // ── Avto-zaxira (Auto-backup to Downloads) ─────────────────
                // При включении приложение автоматически пишет .xlsx-бэкап в
                // публичную папку Download/ScooterRent/ после каждого изменения
                // данных. Файл переживает удаление приложения — при повторной
                // установке данные автоматически восстанавливаются.
                val settingsRepoForBackup = remember { com.example.data.SettingsRepository(settingsContext) }
                var autoBackupEnabled by remember { mutableStateOf(settingsRepoForBackup.autoBackupEnabled) }

                Text(
                    "Avto-zaxira nusxa",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Avto-saqlash (Download/ScooterRent/)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Har bir o'zgarishdan so'ng ilova .xlsx nusxasini " +
                                    "yuklab olishlar papkasiga saqlaydi. Fayl ilovani " +
                                    "o'chirishdan keyin ham saqlanadi — qayta o'rnatishda " +
                                    "ma'lumotlar avtomatik tiklanadi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled = enabled
                                settingsRepoForBackup.autoBackupEnabled = enabled
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF000000),
                                checkedTrackColor = Color(0xFF666666)
                            )
                        )
                    }
                }

                HorizontalDivider()

                // ── Zaxira nusxa (Backup) ──────────────────────────────────
                // Экспорт всей базы данных в Excel (.xlsx) и импорт обратно.
                // Позволяет перенести данные между устройствами или
                // восстановиться после переустановки приложения.
                // Формат файла: 7 листов (Renters, Scooters, Contracts,
                // Transactions, VirtualCards, CardTx, Notifications).
                Text(
                    "Zaxira nusxa",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    "Butun ma'lumot bazasini Excel (.xlsx) faylga eksport qiling " +
                        "yoki avvalgi zaxiradan tiklang. Fayl barcha 7 jadvalni " +
                        "o'z ichiga oladi: mijozlar, skuterlar, kontraktlar, " +
                        "tranzaksiyalar, kartalar, karta tranzaksiyalari va " +
                        "bildirishnomalar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrimaryButton(
                        label = "Eksport",
                        icon = Icons.Default.ArrowDropDown,
                        onClick = { exportLauncher.launch(defaultBackupName) },
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        label = "Import",
                        icon = Icons.Default.ArrowDropUp,
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-excel"
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "⚠ Import paytida joriy ma'lumotlar O'CHIRILADI va " +
                        "fayldagilar bilan almashtiriladi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusOverdue,
                    modifier = Modifier.padding(top = 8.dp)
                )

        }
        }  // ← конец settingsContent()

    // ── Рендер: с собственным TopAppBar или без ──────────────────────────
    if (showTopBar) {
        // Отдельная страница (NavigationState.Settings) — нужен свой TopAppBar
        // с заголовком «Sozlamalar».
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ClaudeBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Sozlamalar",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ClaudeBackground,
                        titleContentColor = ClaudeText
                    )
                )
            }
        ) { padding ->
            settingsContent(padding)
        }
    } else {
        // Вкладка внутри MainView (currentTab == 6) — внешний Scaffold уже
        // даёт TopAppBar «Skuter Ijarasi» с универсальными кнопками и нижнюю
        // навигацию. Рендерим контент напрямую с нулевым padding, чтобы
        // избежать nested Scaffold и лишних отступов сверху/снизу.
        settingsContent(PaddingValues(0.dp))
    }
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
    columnVisibility: Map<String, Boolean>,
    onSortClick: (String) -> Unit,
    onSelect: (Int, Boolean) -> Unit,
    onClick: (Scooter) -> Unit
) {
    // ── Видимость столбцов ───────────────────────────────────────────────
    // По умолчанию все колонки видны. Пользователь может скрывать их через
    // FilterSidePanel (чекбоксы).
    fun isColVisible(colId: String): Boolean = columnVisibility[colId] ?: true
    val showName   = isColVisible("col_name")
    val showDoc    = isColVisible("col_doc")
    val showVin    = isColVisible("col_vin")
    val showEngine = isColVisible("col_engine")
    val showSerial = isColVisible("col_serial")
    val showBatt1  = isColVisible("col_batt1")
    val showBatt2  = isColVisible("col_batt2")
    val showExtra  = isColVisible("col_extra")
    val showStatus = isColVisible("col_status")

    // Ширины для fixed-width версии (со скроллом). По умолчанию таблица
    // использует weight-based компоновку для Name+Status; если включены
    // доп. колонки — переключаемся на horizontalScroll + fixed widths.
    val wName   = 110.dp
    val wDoc    = 110.dp
    val wVin    = 130.dp
    val wEngine = 110.dp
    val wSerial = 90.dp
    val wBatt1  = 100.dp
    val wBatt2  = 100.dp
    val wExtra  = 140.dp
    val wStat   = 90.dp

    val fName = 2.0f
    val fStat = 1.0f

    val hasAnyDetailVisible = showDoc || showVin || showEngine || showSerial ||
        showBatt1 || showBatt2 || showExtra
    val hScrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .then(if (hasAnyDetailVisible) Modifier.horizontalScroll(hScrollState) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAnyDetailVisible) {
                    if (showName)   SortableHeaderCellFixed(Icons.Default.Label,         wName,   "col_name",  sortState) { onSortClick("col_name") }
                    if (showDoc)    NonSortableHeaderCellFixed(Icons.Default.CreditCard,   wDoc,    "Hujjat raqami")
                    if (showVin)    NonSortableHeaderCellFixed(Icons.Default.Numbers,      wVin,    "VIN")
                    if (showEngine) NonSortableHeaderCellFixed(Icons.Default.Build,         wEngine, "Dvigatel")
                    if (showSerial) NonSortableHeaderCellFixed(Icons.Default.Tag,           wSerial, "ID raqami")
                    if (showBatt1)  NonSortableHeaderCellFixed(Icons.Default.Bolt,          wBatt1,  "Akkumulyator 1")
                    if (showBatt2)  NonSortableHeaderCellFixed(Icons.Default.Bolt,          wBatt2,  "Akkumulyator 2")
                    if (showExtra)  NonSortableHeaderCellFixed(Icons.Default.Info,          wExtra,  "Qo'shimcha")
                    if (showStatus) NonSortableHeaderCellFixed(Icons.Default.Info,          wStat,   "Holat")
                } else {
                    if (showName)   SortableHeaderCell(Icons.Default.Label, fName, "col_name", sortState) { onSortClick("col_name") }
                    if (showStatus) NonSortableHeaderCell(Icons.Default.Info, fStat, "Holat")
                }
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
                            .then(if (hasAnyDetailVisible) Modifier.horizontalScroll(hScrollState) else Modifier.fillMaxWidth())
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
                        if (showName) {
                            Text(
                                scooter.name,
                                modifier = Modifier
                                    .then(if (hasAnyDetailVisible) Modifier.width(wName) else Modifier.weight(fName))
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        if (showDoc) {
                            Text(
                                scooter.documentedNumber ?: "—",
                                modifier = Modifier.width(wDoc).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showVin) {
                            Text(
                                scooter.vinNumber.ifBlank { "—" },
                                modifier = Modifier.width(wVin).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showEngine) {
                            Text(
                                scooter.engineNumber.ifBlank { "—" },
                                modifier = Modifier.width(wEngine).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showSerial) {
                            Text(
                                scooter.scooterSerialNumber.ifBlank { "—" },
                                modifier = Modifier.width(wSerial).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showBatt1) {
                            Text(
                                scooter.batteryId1.ifBlank { "—" },
                                modifier = Modifier.width(wBatt1).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showBatt2) {
                            Text(
                                scooter.batteryId2.ifBlank { "—" },
                                modifier = Modifier.width(wBatt2).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showExtra) {
                            Text(
                                scooter.additionalInfo.ifBlank { "—" },
                                modifier = Modifier.width(wExtra).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 1
                            )
                        }
                        if (showStatus) {
                            Row(
                                modifier = Modifier
                                    .then(if (hasAnyDetailVisible) Modifier.width(wStat) else Modifier.weight(fStat))
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
            // По умолчанию название скутера начинается с "Skillmax-".
            // Автонумерация: ищем максимальный существующий номер после
            // префикса "Skillmax-" и берём следующий.
            val nextN = (existingScooters
                .mapNotNull { it.name.removePrefix("Skillmax-").trimStart('0').toIntOrNull() }
                .maxOrNull() ?: 0) + 1
            "Skillmax-" + nextN.toString().padStart(3, '0')
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

    // Все доп. поля теперь ВСЕГДА видны и обязательны — пользователь явно
    // попросил убрать кнопку «More»/«Yashirish» из диалогов создания и
    // редактирования скутеров.

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
                    label = { Text("Skuter nomi (Skillmax- formatida)") },
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

                // ── Реквизиты скутера и аккумуляторов (всегда видны) ─────────
                SectionLabel("Скутер ва аккумулятор маълумотлари")

                OutlinedTextField(
                    value = documentedNumber,
                    onValueChange = { documentedNumber = it },
                    label = { Text("Hujjatlashtirilgan raqami") },
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
        },
        confirmButton = {
            PrimaryButton(
                label = "Saqla",
                icon = Icons.Default.Save,
                // Все поля необязательны — кнопка всегда активна.
                // Раньше требовалось name+docNum+vin+engine+serial+batt1+batt2 — убрано.
                enabled = true,
                onClick = {
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
