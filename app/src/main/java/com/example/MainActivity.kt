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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
import com.example.ui.theme.ClaudeAccentDark
import com.example.ui.theme.ClaudeAccentMuted
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeGold
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.ClaudeTeal
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOkBg
import com.example.ui.theme.StatusOverdue
import com.example.ui.theme.StatusOverdueBg
import com.example.ui.theme.StatusReturned
import com.example.ui.theme.StatusReturnedBg
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

        // ── Немедленный однократный запуск PaymentCheckWorker при старте ──
        // Периодический Worker срабатывает раз в час, но первый запуск может
        // быть отложен Android'ом на час или дольше (Doze mode). При открытии
        // приложения пользователем мы хотим сразу проверить, не истёк ли срок
        // аренды у каких-либо активных арендаторов, и при необходимости создать
        // новые контракты AUTO_RENEW на +7 дней.
        // ExistingWorkPolicy.KEEP — если предыдущий one-time запуск ещё в работе
        // (или уже в очереди), не плодим параллельные. Уникальное имя
        // "app_start_renew" отличает его от "post_import_renew" (который
        // ставится после импорта резервной копии).
        try {
            val oneTimeCheck = OneTimeWorkRequestBuilder<PaymentCheckWorker>().build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "app_start_renew",
                ExistingWorkPolicy.KEEP,
                oneTimeCheck
            )
        } catch (_: Exception) { /* WorkManager не критичен для запуска UI */ }

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
     * Обрабатывает intent от нативных виджетов и уведомлений:
     *   open_tab=N — переключает на вкладку N
     *   widget_action=create_renter/scooter/contract/transaction — открывает диалог создания
     *   widget_action=send_sms + renter_id — открывает экран арендатора для отправки SMS
     *   widget_action=pay_for_days + renter_id — открывает диалог выбора дней
     *   renterId (camelCase, из NotificationHelper) — переводится в
     *   widget_action=pay_for_days + renter_id, чтобы открывался диалог оплаты.
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
        // ── Обработка tap по телу уведомления о наступлении срока оплаты ──
        // NotificationHelper.putPaymentDueNotification ставит в Intent
        // extra "renterId" (camelCase) — этот intent открывает MainActivity.
        // Раньше это extra игнорировалось (читался только "renter_id" snake_case).
        // Теперь при наличии "renterId" без явного widget_action мы открываем
        // диалог выбора дней для оплаты — именно этого ожидает пользователь
        // при тапе на уведомление.
        val action = intent.getStringExtra("widget_action")
        if (action != null) {
            WidgetActionBus.widgetAction = action
            WidgetActionBus.renterId = intent.getIntExtra("renter_id", -1)
        } else if (intent.hasExtra("renterId")) {
            val rid = intent.getIntExtra("renterId", -1)
            if (rid != -1) {
                WidgetActionBus.widgetAction = "pay_for_days"
                WidgetActionBus.renterId = rid
            }
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

/**
 * Форматирует сумму контракта в узбекском стиле: разделитель тысяч — пробел.
 *   420000L  → "420 000"
 *   1250000L → "1 250 000"
 *   0L       → "0"
 *
 * Используется в карточке скутера (вложенные контракты) и в карточке
 * арендатора. Раньше в карточке скутера сумма выводилась как «${cAmount} so'm»
 * с фиксированной шириной 100dp — при крупных суммах текст переносился по
 * символам и превращался в вертикальный столбец («4\n2\n0\n0\n0\n0\n…»).
 * Форматирование с пробелом + убирание «so'm» в отдельную строку ниже
 * полностью устраняет этот артефакт.
 */
private fun formatContractAmount(amount: Long): String {
    val s = amount.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder(s.length + s.length / 3)
    val firstGroupLen = s.length % 3
    if (firstGroupLen > 0) {
        sb.append(s, 0, firstGroupLen)
        if (s.length > firstGroupLen) sb.append(' ')
    }
    var i = firstGroupLen
    while (i < s.length) {
        sb.append(s, i, i + 3)
        i += 3
        if (i < s.length) sb.append(' ')
    }
    return sb.toString()
}

/**
 * Кнопка вкладки нижней навигации — ВИЗУАЛЬНО И ПОВЕДЕНИЕМ идентична
 * «универсальным кнопкам» из TopAppBar (Camera / SMS / + / ✎ / 🗑 / Search):
 * квадрат 56dp со скруглением 8dp, цветной фон, цветная иконка 28dp.
 *
 * ВАЖНО: это НЕ Material3 NavigationBarItem. Раньше использовался
 * NavigationBarItem с indicatorColor = Color.Transparent, но у него три
 * побочных эффекта, из-за которых нижние кнопки отличались от верхних:
 *   1. НЕОГРАНИЧЕННАЯ ripple — кругом расходилась за пределы кнопки
 *      («странный круглый эффект» при тапе).
 *   2. Внутренние padding'и (6dp h / 8dp v) + слот иконки 32dp.
 *   3. Pill-индикатор занимал layout-место даже при прозрачном цвете.
 *
 * Теперь это обычный Box + combinedClickable с bounded ripple
 * (LocalIndication.current) — точно как верхние кнопки. Ripple остаётся
 * внутри квадратной кнопки 56dp, никаких круглых артефактов.
 *
 * Каждая вкладка имеет СОБСТВЕННЫЙ акцентный цвет [accent] — как у верхних
 * универсальных кнопок, где Camera = бежевый, Add = оранжевый, Delete =
 * зелёный/красный и т. д.
 *
 * Состояния:
 *   • Выбрано:     фон accent@22%, рамка accent@100%, иконка accent@100%.
 *   • Невыбрано:   фон accent@10%, рамка accent@45%,  иконка accent@75%.
 *
 * Прозрачности подобраны так, чтобы даже невыбранная вкладка сохраняла
 * узнаваемый цвет (акцентный «маяк»), но не конкурировала за внимание
 * с выбранной.
 *
 * @param isSelected     выбрана ли вкладка (currentTab == index)
 * @param onClick        что делать при тапе (currentTab = index)
 * @param accent         акцентный цвет вкладки (ClaudeAccent, StatusOk,
 *                       ClaudeGold, ClaudeAccentDark, ClaudeTeal,
 *                       StatusOverdue, ClaudeTextSecondary)
 * @param icon           Material-иконка вкладки
 * @param contentDescription описание для screen-reader'а
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavTabButton(
    isSelected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    icon: ImageVector,
    contentDescription: String
) {
    val bgAlpha = if (isSelected) 0.22f else 0.10f
    val borderAlpha = if (isSelected) 1.0f else 0.45f
    val iconAlpha = if (isSelected) 1.0f else 0.75f
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = bgAlpha), RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = borderAlpha), RoundedCornerShape(8.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = accent.copy(alpha = iconAlpha),
            modifier = Modifier.size(28.dp)
        )
    }
}

/* ============================================================================
   КОМПАКТНАЯ ПОИСКОВАЯ ПАНЕЛЬ ДЛЯ TOPAPPBAR
   ============================================================================
   Квадратная (RoundedCornerShape(8.dp)) панель, которая показывается в actions
   TopAppBar когда пользователь нажал круглую кнопку «Поиск». Заменяет собой
   все универсальные кнопки (scanner / SMS / + / ✎ / 🗑). Содержит:
     • иконку-лупу (leading)
     • поле ввода (BasicTextField, placeholder «Поиск»)
     • кнопку фильтров (Icons.Default.Tune)
     • кнопку календаря (Icons.Default.DateRange)
   Долгое нажатие на панель возвращает универсальные кнопки (onLongClickDismiss).
   Обычный тап не делает ничего — текст набирается в поле, иконки срабатывают
   по своему onClick.
   ============================================================================ */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CompactSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onCalendarClick: () -> Unit,
    calendarActive: Boolean,
    onFilterClick: () -> Unit,
    filterActive: Boolean,
    onLongClickDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val panelInteractionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .padding(end = 12.dp, start = 4.dp)
            .height(80.dp)
            .width(480.dp)
            .combinedClickable(
                interactionSource = panelInteractionSource,
                indication = null,
                onClick = {},
                onLongClick = onLongClickDismiss
            ),
        shape = RoundedCornerShape(16.dp),  // Квадратная форма (углы 16dp, увеличено пропорционально)
        color = Color.White,
        border = BorderStroke(1.dp, ClaudeDivider)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = ClaudeTextSecondary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ClaudeText),
                cursorBrush = SolidColor(ClaudeAccent),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            "Поиск",
                            color = ClaudeTextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    innerTextField()
                }
            )
            IconButton(onClick = onFilterClick, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Filtrlash",
                    tint = if (filterActive) ClaudeAccent else ClaudeTextSecondary,
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = onCalendarClick, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = "Sana",
                    tint = if (calendarActive) ClaudeAccent else ClaudeTextSecondary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    // ── Trash mode (v36+) ────────────────────────────────────────────────
    // false = обычный режим (показываются активные объекты, кнопка «Удалить»
    //            зелёная, кнопка «+» создаёт, «✎» редактирует, «🗑» soft-delete).
    // true  = режим корзины (показываются только удалённые объекты, кнопка
    //            «Удалить» красная, кнопка «+» восстанавливает выбранные,
    //            «✎» редактирует данные удалённых, «🗑» окончательно удаляет).
    //
    // Переключается долгим нажатием на универсальную кнопку «Удалить» в
    // TopAppBar. При входе в trash mode выделение сбрасывается (чтобы старые
    // selectedRenters/Scooters/etc. из обычного режима не «протекали» в trash).
    var isTrashMode by remember { mutableStateOf(false) }
    // ── Archive mode ─────────────────────────────────────────────────────
    // Аналог trash mode, но для архивных арендаторов: тех, у кого есть
    // STOP-маркер в прошлом (weekStart < сегодня), после которого нет
    // RESUME-маркера. Это означает «аренда приостановлена и не возобновлена».
    //
    // Включается ДОЛГИМ нажатием на кнопку «✎» (Tahrirlash). При входе
    // в archive mode:
    //   • Кнопка «✎» красится в серый (но остаётся рабочей — можно
    //     редактировать архивные записи).
    //   • Кнопка «+» меняется на «↩» (Restore) — возвращает арендатора
    //     из архива в активные (ставит RESUME-маркер на сегодня).
    //   • Источник данных меняется с liveRenters на archivedRenters.
    //
    // В archive mode кнопка «🗑» (удалить) работает как обычно: мягкое
    // удаление в корзину (как и в обычном режиме). Trash mode имеет
    // приоритет — если isTrashMode=true, archive mode не активируется.
    var isArchiveMode by remember { mutableStateOf(false) }

    // ── Навигация ────────────────────────────────────────────────────
    var navState by remember { mutableStateOf<NavigationState>(NavigationState.MainView) }

    // ── ID арендатора для диалога оплаты (открывается из уведомления) ──
    // Когда пользователь тапает по телу уведомления о наступлении срока
    // оплаты, MainActivity.handleWidgetIntent ставит widget_action="pay_for_days"
    // и renter_id. LaunchedEffect выше читает это и записывает ID сюда.
    // MainScreen отрисовывает DayPickerPaymentDialog, пока это поле не null.
    var pendingPaymentRenterId by remember { mutableStateOf<Int?>(null) }

    var renterSortState by remember { mutableStateOf(TableSortState()) }
    var scooterSortState by remember { mutableStateOf(TableSortState()) }
    // Filter panel state
    var showRenterFilterPanel by remember { mutableStateOf(false) }
    var showScooterFilterPanel by remember { mutableStateOf(false) }
    var renterFilterValues by remember { mutableStateOf(mapOf<String, String>()) }
    var scooterFilterValues by remember { mutableStateOf(mapOf<String, String>()) }

    // ── Режим поиска в TopAppBar ───────────────────────────────────────
    // Пользователь просил убрать «Skuter Ijarasi» из верхней панели и
    // добавить круглую кнопку-иконку «Поиск» в группу универсальных кнопок.
    // Тап по ней скрывает ВСЕ универсальные кнопки и показывает вместо них
    // квадратную поисковую панель (с кнопками календарь + фильтры).
    // Долгое нажатие на эту панель возвращает универсальные кнопки на место.
    var isSearchMode by remember { mutableStateOf(false) }

    // ── Диалог предупреждения о включении авто-отправки SMS ────────────
    // Показывается при ДОЛГОМ нажатии на универсальную кнопку SMS, когда
    // авто-отправка ещё выключена. В диалоге две кнопки:
    //   • «Orqaga» (Back) — закрыть диалог, ничего не меняя.
    //   • «Tasdiqlash» (Confirm) — включить авто-отправку SMS.
    // Краткое нажатие на ту же кнопку при включённой авто-отправке —
    // выключает её без дополнительного предупреждения (безопасное действие).
    // Краткое нажатие при выключенной авто-отправке — отправляет SMS
    // выбранным арендаторам (selectedRenters) на вкладке «Ijarachilar».
    var showSmsAutoSendConfirmDialog by remember { mutableStateOf(false) }

    // ── Поднятые состояния поиска для вложенных экранов ────────────────
    // Раньше каждый экран (Kontraktlar / Tranzaksiya / Otchetlar / Finansi)
    // держал свой searchQuery во внутреннем state. Теперь поиск живёт
    // в TopAppBar, поэтому поднимаем по одному searchQuery на каждую вкладку.
    var contractSearchQuery by remember { mutableStateOf("") }
    var transactionSearchQuery by remember { mutableStateOf("") }
    var reportSearchQuery by remember { mutableStateOf("") }
    var finansiSearchQuery by remember { mutableStateOf("") }

    // Триггеры для открытия календаря/фильтра вложенных экранов из TopAppBar.
    // Та же механика, что и у createTrigger/editTrigger/deleteTrigger:
    // MainActivity увеличивает значение → вложенный экран реагирует.
    var contractCalendarTrigger by remember { mutableStateOf(0) }
    var contractFilterTrigger by remember { mutableStateOf(0) }
    var transactionCalendarTrigger by remember { mutableStateOf(0) }
    var transactionFilterTrigger by remember { mutableStateOf(0) }
    var reportCalendarTrigger by remember { mutableStateOf(0) }
    var reportFilterTrigger by remember { mutableStateOf(0) }
    var finansiCalendarTrigger by remember { mutableStateOf(0) }
    var finansiFilterTrigger by remember { mutableStateOf(0) }
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
            FilterColumn("col_renewal",  "Status",           "Qo'llanma / Avtomatik"),
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
    // ── Список всех релизов для страницы настроек ────────────────────────
    // Пользователь выбирает версию из списка (а не получает только «latest»).
    // Загружается по требованию — когда пользователь открывает список версий.
    var allReleases by remember { mutableStateOf<List<UpdateInfo>>(emptyList()) }
    var isLoadingReleases by remember { mutableStateOf(false) }
    // ── Текст ошибки загрузки списка релизов (null = ошибки нет) ──────────
    // Когда fetchAllReleasesDetailed() возвращает не-Success, сюда кладётся
    // человекочитаемое сообщение на узбекском. UI показывает его с кнопкой
    // «Qayta urinish» (Retry). Пустая строка = «ошибки нет, просто список
    // пустой» — отдельный случай (не должно случаться для этого репо).
    var releasesError by remember { mutableStateOf<String?>(null) }
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

    // ── Одноразовая миграция: добавляем RESUME-маркеры ───────────────────
    // на последние дни последних контрактов всем существующим арендаторам.
    // По требованию пользователя: у всех прошлых арендаторов из прошлой
    // базы должен быть день «Davom» (RESUME) на последнем дне окончания
    // их последнего контракта. Миграция выполняется один раз — флаг
    // v1_done хранится в SharedPreferences и переживает перезапуски.
    // Внутри backfillResumeMarkersForAllRenters() есть защита от дубликатов:
    // если маркер уже стоит на эту дату — пропускает.
    LaunchedEffect(Unit) {
        viewModel.backfillResumeMarkersForAllRenters()
    }

    val renters by viewModel.rentersList.collectAsStateWithLifecycle()
    val liveRenters by viewModel.liveRenters.collectAsStateWithLifecycle()
    val trashedRenters by viewModel.trashedRenters.collectAsStateWithLifecycle()
    val archivedRenters by viewModel.archivedRenters.collectAsStateWithLifecycle()
    val liveScooters by scooterViewModel.liveScooters.collectAsStateWithLifecycle()
    val trashedScooters by scooterViewModel.trashedScooters.collectAsStateWithLifecycle()
    val liveCards by finansiViewModel.liveCards.collectAsStateWithLifecycle()
    val trashedCards by finansiViewModel.trashedCards.collectAsStateWithLifecycle()
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
            "pay_for_days" -> {
                // ── Открытие диалога выбора дней из уведомления ──────
                // Пользователь тапнул по телу уведомления о наступлении
                // срока оплаты. Открываем диалог DayPickerPaymentDialog
                // для этого арендатора. Используем pendingPaymentRenterId
                // для хранения ID арендатора до тех пор, пока диалог не
                // отрисуется в MainScreen (через if (pendingPaymentRenterId != null)).
                val rid = WidgetActionBus.renterId
                if (rid != -1) {
                    pendingPaymentRenterId = rid
                }
            }
        }
        WidgetActionBus.widgetAction = null
        WidgetActionBus.renterId = -1
    }

    // ── Диалог выбора дней для оплаты (открывается из уведомления) ──────
    // pendingPaymentRenterId != null, когда пользователь тапнул по телу
    // уведомления о наступлении срока оплаты. Открываем DayPickerPaymentDialog
    // для этого арендатора. После подтверждения или отмены — сбрасываем в null.
    if (pendingPaymentRenterId != null) {
        val rid = pendingPaymentRenterId!!
        val renterForPayment = renters.firstOrNull { it.id == rid }
        if (renterForPayment != null) {
            val repoForDialog = com.example.data.SettingsRepository(localContext)
            val dailyForDialog = repoForDialog.dailyPrice.let { p ->
                if (p > 0) p else com.example.data.SettingsRepository.DEFAULT_DAILY_PRICE
            }
            // ── Загружаем суммарное количество неоплаченных дней ──────
            // Нужно для кнопки «Barcha to'lanmagan kunlarni tanlash (N)»
            // в DayPickerPaymentDialog. Используем produceState для асинхронной
            // загрузки из БД — иначе Main thread заблокируется на I/O.
            val unpaidDaysForDialog by androidx.compose.runtime.produceState(
                initialValue = 0,
                rid
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val db = com.example.data.AppDatabase.getDatabase(localContext)
                    val unpaid = db.contractHistoryDao().getUnpaidContractsForRenter(rid)
                    val dayMs = 24L * 60 * 60 * 1000
                    unpaid.sumOf { c ->
                        val ws = c.weekStart ?: return@sumOf 0L
                        val we = c.weekEnd ?: return@sumOf 0L
                        val diff = we - ws
                        if (diff <= 0) 1L else ((diff + dayMs - 1) / dayMs)
                    }.toInt().coerceAtLeast(0)
                }
            }
            DayPickerPaymentDialog(
                renterName = renterForPayment.name,
                dailyPrice = dailyForDialog,
                unpaidDays = unpaidDaysForDialog,
                onConfirm = { days ->
                    viewModel.payForDaysForRenters(setOf(rid), days)
                    Toast.makeText(
                        localContext,
                        "To'lov qabul qilindi ($days kun) — ${renterForPayment.name}",
                        Toast.LENGTH_LONG
                    ).show()
                    pendingPaymentRenterId = null
                },
                onDismiss = { pendingPaymentRenterId = null }
            )
        } else {
            // Арендатор не найден (возможно, удалён) — закрываем диалог
            pendingPaymentRenterId = null
        }
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
                val dailyForHistory by settingsViewModel.dailyPrice.collectAsStateWithLifecycle()
                // ── Загружаем существующие контракты арендатора для календаря ──
                // Без этого календарь в RenterFormDialog показывает «Kontraktlar yo'q»,
                // хотя у арендатора в БД есть контракты. Аналогично коду ниже
                // (строка ~1993) для формы из главного списка.
                val editRenterIdForHistory = renterToEdit?.id ?: -1
                val existingContractsForHistory by contractHistoryViewModel
                    .contractsForRenter(editRenterIdForHistory)
                    .collectAsStateWithLifecycle()
                val existingContractsForHistorySafe: List<com.example.data.ContractHistoryEntry> =
                    if (renterToEdit != null) existingContractsForHistory else emptyList()
                RenterFormDialog(
                    initialRenter = renterToEdit,
                    weeklyPrice = weekly,
                    monthlyPrice = monthly,
                    dailyPrice = dailyForHistory,
                    scooters = scooters,
                    activeRenters = renters,
                    archivedRenterIds = archivedRenters.map { it.id }.toSet(),
                    existingContracts = existingContractsForHistorySafe,
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
                                pinfl = result.pinfl,
                                autoRenewMode = result.autoRenewMode,
                                contractGroupsWithIds = result.contractGroupsWithIds
                            )
                        }
                        renterToEdit = null
                    },
                    // Inline-создание скутера доступно и из экрана истории
                    // арендатора — там тоже может быть сценарий, когда нужно
                    // перевыбрать скутер, а нужного нет в списке.
                    // Лямбда suspend, возвращает id свежесозданного скутера.
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
                    },
                    // Каскадное удаление существующего контракта из формы
                    // (кнопка ✕ в списке контрактов под календарём).
                    onDeleteExistingContract = { contractId ->
                        contractHistoryViewModel.deleteContract(contractId)
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
            val dailyPrice by settingsViewModel.dailyPrice.collectAsStateWithLifecycle()
            val smsAutoSend by settingsViewModel.smsAutoSendEnabled.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTemplate = template,
                currentWeeklyPrice = dailyPrice,
                currentMonthlyPrice = dailyPrice * 30.0,
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
                allReleases = allReleases,
                isLoadingReleases = isLoadingReleases,
                releasesError = releasesError,
                onLoadReleases = {
                    if (!isLoadingReleases) {
                        isLoadingReleases = true
                        releasesError = null
                        coroutineScope.launch {
                            val checker = UpdateChecker(localContext)
                            val result = checker.fetchAllReleasesDetailed()
                            when (result) {
                                is com.example.data.remote.FetchReleasesResult.Success -> {
                                    allReleases = result.releases
                                    releasesError = null
                                }
                                else -> {
                                    allReleases = emptyList()
                                    releasesError = checker.userFacingMessage(result)
                                }
                            }
                            isLoadingReleases = false
                        }
                    }
                },
                onRetryReleases = {
                    if (!isLoadingReleases) {
                        isLoadingReleases = true
                        releasesError = null
                        coroutineScope.launch {
                            val checker = UpdateChecker(localContext)
                            // Чистим кэш чтобы гарантированно сделать свежий запрос
                            checker.clearCache()
                            val result = checker.fetchAllReleasesDetailed()
                            when (result) {
                                is com.example.data.remote.FetchReleasesResult.Success -> {
                                    allReleases = result.releases
                                    releasesError = null
                                }
                                else -> {
                                    allReleases = emptyList()
                                    releasesError = checker.userFacingMessage(result)
                                }
                            }
                            isLoadingReleases = false
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
                onBack = { navState = NavigationState.MainView },
                isTrashMode = isTrashMode
            )
            return
        }
        NavigationState.MainView -> { /* продолжаем — основной Scaffold ниже */ }
    }

    // ── Статичная верхняя панель (TopAppBar) ───────────────────────────
    // Пользователь хочет: верхняя полоса с универсальными кнопками (TopAppBar)
    // должна быть ВСЕГДА ВИДНА сверху экрана (закреплена в Scaffold.topBar).
    // А полоса поиска и полоса доп.кнопок (To'lov/Uzish/SMS) — скроллятся
    // вместе с таблицей (часть контента), уходят под TopAppBar при скролле.
    // TopAppBar перекрывает их когда они оказываются под ней.
    //
    // ВАЖНО: по просьбе пользователя убран текст «Skuter Ijarasi» из заголовка
    // и добавлена круглая кнопка-иконка «Поиск». Тап по ней скрывает ВСЕ
    // универсальные кнопки и показывает квадратную поисковую панель с
    // календарём и фильтрами. Долгое нажатие на эту панель возвращает
    // универсальные кнопки на место.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ClaudeBackground,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClaudeBackground,
                    titleContentColor = ClaudeText,
                    actionIconContentColor = ClaudeText
                ),
                actions = {
                    if (!isSearchMode) {
                        // ── ОБЫЧНЫЙ РЕЖИМ: универсальные кнопки + кнопка «Поиск» ──

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
                                .size(56.dp)
                                .background(ClaudeAccentBg, RoundedCornerShape(8.dp))
                                .border(1.dp, ClaudeAccent, RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Skaner",
                                tint = ClaudeAccent,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // ── Кнопка-переключатель режима SMS (редизайн) ────────────────
                        // Круглая, рядом с «+». Красная = QO'LLANMA (авто-отправка
                        // выключена), зелёная = AVTO (авто-отправка включена).
                        //
                        // Поведение (по запросу пользователя):
                        //   • Краткое нажатие при ВЫКЛЮЧЕННОЙ авто-отправке:
                        //     отправляет SMS выбранным арендаторам (selectedRenters)
                        //     на вкладке «Ijarachilar» (currentTab == 0). На других
                        //     вкладках кнопка неактивна (но визуально остаётся).
                        //   • Краткое нажатие при ВКЛЮЧЁННОЙ авто-отправке:
                        //     выключает авто-отправку БЕЗ дополнительного диалога
                        //     (это безопасное действие, не требующее подтверждения).
                        //   • Долгое нажатие при ВЫКЛЮЧЕННОЙ авто-отправке:
                        //     показывает диалог предупреждения с двумя кнопками:
                        //       – «Orqaga» (Back) — закрыть диалог без изменений.
                        //       – «Tasdiqlash» (Confirm) — включает авто-отправку.
                        //   • Долгое нажатие при ВКЛЮЧЁННОЙ авто-отправке:
                        //     также выключает её (симметрично с кратким нажатием).
                        val smsAutoSend by settingsViewModel.smsAutoSendEnabled.collectAsStateWithLifecycle()
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(56.dp)
                                .background(
                                    if (smsAutoSend) StatusOk else StatusOverdue,
                                    RoundedCornerShape(8.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (smsAutoSend) {
                                            // Авто-отправка включена → выключаем без диалога.
                                            settingsViewModel.updateSmsAutoSend(false)
                                            Toast.makeText(
                                                localContext,
                                                "SMS avto-yuborish o'chirildi",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            // Авто-отправка выключена → отправляем SMS
                                            // выбранным арендаторам. Работает только на
                                            // вкладке «Ijarachilar» (currentTab == 0),
                                            // где у нас есть selectedRenters. На других
                                            // вкладках показываем подсказку.
                                            if (currentTab == 0) {
                                                if (selectedRenters.isEmpty()) {
                                                    Toast.makeText(
                                                        localContext,
                                                        "Avval mijozni tanlang",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    val rentersToSend = renters.filter { it.id in selectedRenters }
                                                    coroutineScope.launch {
                                                        var sentCount = 0
                                                        var failCount = 0
                                                        val db = com.example.data.AppDatabase.getDatabase(localContext)
                                                        val contractDao = db.contractHistoryDao()
                                                        for (renter in rentersToSend) {
                                                            val settingsRepo = com.example.data.SettingsRepository(localContext)
                                                            val phone = com.example.worker.SimHelper.normalizePhoneNumber(renter.phoneNumber)
                                                            val unpaidContracts = contractDao.getUnpaidContractsForRenter(renter.id)
                                                            val unpaidCount = unpaidContracts.size
                                                            val dayMs = 24L * 60 * 60 * 1000
                                                            val unpaidDays = unpaidContracts.sumOf { c ->
                                                                val ws = c.weekStart ?: return@sumOf 0L
                                                                val we = c.weekEnd ?: return@sumOf 0L
                                                                val diff = we - ws
                                                                if (diff <= 0) 1L else ((diff + dayMs - 1) / dayMs)
                                                            }.toInt().coerceAtLeast(1)
                                                            val dailyPrice = settingsRepo.dailyPrice.let {
                                                                if (it > 0) it else com.example.data.SettingsRepository.DEFAULT_DAILY_PRICE
                                                            }
                                                            val debt = unpaidDays * dailyPrice
                                                            val message = settingsRepo.smsTemplate
                                                                .replace("{name}", renter.name.trim().lowercase())
                                                                .replace("{unpaidDays}", unpaidDays.toString())
                                                                .replace("{unpaidCount}", unpaidCount.toString())
                                                                .replace("{days}", unpaidDays.toString())
                                                                .replace("{debt}", debt.toLong().toString())
                                                                .replace("{payme}", settingsRepo.paymeLink)
                                                                .replace("{call}", settingsRepo.callCenter)
                                                            val smsManager = com.example.worker.SimHelper.getSmsManagerForSim(localContext)
                                                            if (smsManager != null) {
                                                                try {
                                                                    com.example.worker.SimHelper.sendSmsAuto(smsManager, phone, message, null, null)
                                                                    if (unpaidCount > 0 && !renter.isOverdueSmsSent) {
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
                                                    }
                                                }
                                            } else {
                                                Toast.makeText(
                                                    localContext,
                                                    "SMS yuborish uchun «Ijarachilar» varaqiga o'ting",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (smsAutoSend) {
                                            // Уже включено → долгое нажатие тоже выключает.
                                            settingsViewModel.updateSmsAutoSend(false)
                                            Toast.makeText(
                                                localContext,
                                                "SMS avto-yuborish o'chirildi",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            // Выключено → показываем диалог подтверждения.
                                            showSmsAutoSendConfirmDialog = true
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Sms,
                                contentDescription = if (smsAutoSend) "SMS avto" else "SMS qo'llanma",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
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
                        // В обычном режиме: создаёт новую сущность (открывает диалог).
                        // В trash mode: ВОССТАНАВЛИВАЕТ выбранные удалённые объекты
                        //               (как просил пользователь: «+» теперь restore).
                        //               Иконка меняется с «+» на «↩» (Restore).
                        // В archive mode (только для арендаторов, tab=0): тоже
                        //               Restore — возвращает арендатора из архива
                        //               в активные (ставит RESUME-маркер на сегодня).
                        //               Иконка меняется с «+» на «↩» (Restore).
                        if (currentTab != 4 && currentTab != 6) {
                            // В archive mode кнопка «+» как Restore работает
                            // только на вкладке арендаторов (0). На остальных
                            // вкладках archive mode неактивен, поэтому
                            // restoreEnabled = false, и кнопка просто disabled.
                            val isRestoreMode = isTrashMode || (isArchiveMode && currentTab == 0)
                            val addEnabled = if (isRestoreMode) {
                                when (currentTab) {
                                    0 -> selectedRenters.isNotEmpty()
                                    1 -> selectedScooters.isNotEmpty()
                                    2 -> selectedContracts.isNotEmpty()
                                    3 -> selectedTxs.isNotEmpty()
                                    5 -> selectedCardIds.isNotEmpty()
                                    else -> false
                                }
                            } else true
                            IconButton(
                                onClick = {
                                    if (isTrashMode) {
                                        // ── RESTORE (trash mode) ──
                                        when (currentTab) {
                                            0 -> {
                                                selectedRenters.forEach { id -> viewModel.restoreRenterFromTrash(id) }
                                                selectedRenters = emptySet()
                                            }
                                            1 -> {
                                                selectedScooters.forEach { id -> scooterViewModel.restoreScooterFromTrash(id) }
                                                selectedScooters = emptySet()
                                            }
                                            2 -> contractHistoryViewModel.restoreContractsFromTrash(selectedContracts.toList()).also { selectedContracts = emptySet() }
                                            3 -> {
                                                val liveTxIds = transactionViewModel.liveTransactions.value.map { it.id }.toSet()
                                                selectedTxs.forEach { id ->
                                                    val isCardTx = id !in liveTxIds
                                                    if (isCardTx) finansiViewModel.restoreTransactionFromTrash(id)
                                                    else transactionViewModel.restoreFromTrash(id)
                                                }
                                                selectedTxs = emptySet()
                                            }
                                            5 -> {
                                                selectedCardIds.forEach { id -> finansiViewModel.restoreCardFromTrash(id) }
                                                selectedCardIds = emptySet()
                                            }
                                        }
                                    } else if (isArchiveMode && currentTab == 0) {
                                        // ── RESTORE FROM ARCHIVE ──
                                        // Возвращает выбранных арендаторов из архива
                                        // в активные: ставит RESUME-маркер на сегодня,
                                        // что автоматически выводит арендатора из
                                        // archivedRenters (он возвращается в liveRenters).
                                        selectedRenters.forEach { id -> viewModel.restoreRenterFromArchive(id) }
                                        selectedRenters = emptySet()
                                    } else {
                                        // ── CREATE (normal mode) ──
                                        when (currentTab) {
                                            0 -> showAddDialog = true
                                            1 -> showAddScooterDialog = true
                                            2 -> contractCreateTrigger++
                                            3 -> transactionCreateTrigger++
                                            5 -> cardCreateTrigger++
                                        }
                                    }
                                },
                                enabled = addEnabled,
                                modifier = Modifier
                                    .padding(end = 6.dp, start = 4.dp)
                                    .size(56.dp)
                                    .background(
                                        if (isRestoreMode) {
                                            if (addEnabled) StatusOk else StatusOk.copy(alpha = 0.4f)
                                        } else ClaudeAccent,
                                        RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Icon(
                                    if (isRestoreMode) Icons.Default.Restore else Icons.Default.Add,
                                    contentDescription = if (isRestoreMode) "Qayta tiklash" else "Qo'shish",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        // ── Кнопка «✎ Tahrirlash» — скрыта на «Отчётах» (4) и «Sozlamalar» (6)
                        // Поведение:
                        //   • КРАТКОЕ нажатие: открывает диалог редактирования
                        //     выбранной строки. Активна только если выбрано РОВНО 1.
                        //     Работает ВО ВСЕХ режимах (обычный, trash, archive).
                        //   • ДОЛГОЕ нажатие: переключает isArchiveMode (только на
                        //     вкладке арендаторов, tab=0). При входе в archive mode:
                        //       - кнопка красится в серый (но остаётся рабочей);
                        //       - источник данных меняется на archivedRenters;
                        //       - кнопка «+» превращается в «↩» (Restore).
                        //     Trash mode имеет приоритет — если isTrashMode=true,
                        //     долгое нажатие на «✎» не делает ничего (чтобы не
                        //     путать режимы).
                        if (currentTab != 4 && currentTab != 6) {
                            val editEnabled = when (currentTab) {
                                0 -> selectedRenters.size == 1
                                1 -> selectedScooters.size == 1
                                2 -> selectedContracts.size == 1
                                3 -> selectedTxs.size == 1
                                5 -> selectedCardIds.size == 1
                                else -> false
                            }
                            // В archive mode кнопка красится в серый, но
                            // остаётся рабочей (editEnabled по-прежнему зависит
                            // от выделения).
                            val editBgColor = when {
                                isArchiveMode && currentTab == 0 ->
                                    if (editEnabled) ClaudeDivider else ClaudeDivider.copy(alpha = 0.5f)
                                else ->
                                    if (editEnabled) Color.White else Color.White.copy(alpha = 0.5f)
                            }
                            val editBorderColor = when {
                                isArchiveMode && currentTab == 0 -> ClaudeTextSecondary
                                else -> ClaudeDivider
                            }
                            val editTint = when {
                                isArchiveMode && currentTab == 0 ->
                                    if (editEnabled) Color.DarkGray else ClaudeTextSecondary
                                else ->
                                    if (editEnabled) ClaudeAccent else ClaudeTextSecondary
                            }
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(56.dp)
                                    .background(editBgColor, RoundedCornerShape(8.dp))
                                    .border(1.dp, editBorderColor, RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (!editEnabled) return@combinedClickable
                                            when (currentTab) {
                                                0 -> {
                                                    selectedRenters.firstOrNull()?.let { id ->
                                                        // В archive mode арендатор берётся из
                                                        // archivedRenters, в trash — из trashedRenters,
                                                        // иначе из liveRenters. Источник rentersSource
                                                        // уже учёл режим, но здесь используем
                                                        // объединённый renters (allRenters), чтобы
                                                        // найти по id независимо от режима.
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
                                        onLongClick = {
                                            // ── TOGGLE ARCHIVE MODE ──
                                            // Только на вкладке арендаторов (0) и только
                                            // если не в trash mode (чтобы режимы не
                                            // конфликтовали).
                                            if (currentTab != 0) {
                                                Toast.makeText(
                                                    localContext,
                                                    "Arxiv rejimi faqat ijarachilar tabida",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@combinedClickable
                                            }
                                            if (isTrashMode) {
                                                Toast.makeText(
                                                    localContext,
                                                    "Avval chiqindixon rejimidan chiqing",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@combinedClickable
                                            }
                                            isArchiveMode = !isArchiveMode
                                            // Сбрасываем выделение при переключении режима.
                                            selectedRenters = emptySet()
                                            selectedScooters = emptySet()
                                            selectedContracts = emptySet()
                                            selectedTxs = emptySet()
                                            selectedCardIds = emptySet()
                                            Toast.makeText(
                                                localContext,
                                                if (isArchiveMode) "Arxiv rejimi yoqildi"
                                                else "Arxiv rejimi o'chirildi",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Tahrirlash",
                                    tint = editTint,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // ── Кнопка «🗑 O'chir» — универсальная + переключатель trash mode ─
                            // Поведение:
                            //   • КРАТКОЕ нажатие в обычном режиме (кнопка ЗЕЛЁНАЯ):
                            //     soft-delete выбранных объектов (перемещение в корзину).
                            //     Кнопка активна только если есть выделение.
                            //   • КРАТКОЕ нажатие в trash mode (кнопка КРАСНАЯ):
                            //     окончательное удаление выбранных объектов из БД.
                            //     Кнопка активна только если есть выделение.
                            //   • ДОЛГОЕ нажатие (в любом режиме, даже без выделения):
                            //     переключает isTrashMode. При входе в trash mode
                            //     кнопка меняет фон green → red, при выходе red → green.
                            //     Выделение при этом сбрасывается (чтобы старые selected*
                            //     из обычного режима не «протекали» в trash и наоборот).
                            //
                            // Цвета:
                            //   • Обычный режим: фон StatusOk (зелёный), иконка белая.
                            //   • Trash mode:    фон StatusOverdue (красный), иконка белая.
                            //   • Disabled (нет выделения): фон серый с прозрачностью.
                            val deleteEnabled = when (currentTab) {
                                0 -> selectedRenters.isNotEmpty()
                                1 -> selectedScooters.isNotEmpty()
                                2 -> selectedContracts.isNotEmpty()
                                3 -> selectedTxs.isNotEmpty()
                                5 -> selectedCardIds.isNotEmpty()
                                else -> false
                            }
                            val deleteBgColor = when {
                                isTrashMode -> if (deleteEnabled) StatusOverdue else StatusOverdue.copy(alpha = 0.4f)
                                else        -> if (deleteEnabled) StatusOk else StatusOk.copy(alpha = 0.4f)
                            }
                            val deleteBorderColor = when {
                                isTrashMode -> StatusOverdue
                                else        -> StatusOk
                            }
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(56.dp)
                                    .background(deleteBgColor, RoundedCornerShape(8.dp))
                                    .border(1.dp, deleteBorderColor, RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (!deleteEnabled) return@combinedClickable
                                            if (isTrashMode) {
                                                // ── PERMANENT DELETE (trash mode) ──
                                                when (currentTab) {
                                                    0 -> {
                                                        selectedRenters.forEach { id -> viewModel.permanentlyDeleteRenter(id) }
                                                        selectedRenters = emptySet()
                                                    }
                                                    1 -> {
                                                        selectedScooters.forEach { id -> scooterViewModel.permanentlyDeleteScooter(id) }
                                                        selectedScooters = emptySet()
                                                    }
                                                    2 -> contractHistoryViewModel.permanentlyDeleteContracts(selectedContracts.toList()).also { selectedContracts = emptySet() }
                                                    3 -> {
                                                        val liveTxIds = transactionViewModel.liveTransactions.value.map { it.id }.toSet()
                                                        selectedTxs.forEach { id ->
                                                            val isCardTx = id !in liveTxIds
                                                            if (isCardTx) finansiViewModel.permanentlyDeleteTransaction(id)
                                                            else transactionViewModel.permanentlyDelete(id)
                                                        }
                                                        selectedTxs = emptySet()
                                                    }
                                                    5 -> {
                                                        trashedCards.filter { it.id in selectedCardIds }.forEach { card ->
                                                            finansiViewModel.permanentlyDeleteCard(card)
                                                        }
                                                        selectedCardIds = emptySet()
                                                    }
                                                }
                                            } else {
                                                // ── SOFT DELETE (normal mode → move to trash) ──
                                                when (currentTab) {
                                                    0 -> {
                                                        selectedRenters.forEach { id -> viewModel.moveRenterToTrash(id) }
                                                        selectedRenters = emptySet()
                                                    }
                                                    1 -> {
                                                        selectedScooters.forEach { id -> scooterViewModel.moveScooterToTrash(id) }
                                                        selectedScooters = emptySet()
                                                    }
                                                    2 -> contractHistoryViewModel.moveContractsToTrash(selectedContracts.toList()).also { selectedContracts = emptySet() }
                                                    3 -> {
                                                        val liveTxIds = transactionViewModel.liveTransactions.value.map { it.id }.toSet()
                                                        selectedTxs.forEach { id ->
                                                            val isCardTx = id !in liveTxIds
                                                            if (isCardTx) finansiViewModel.moveTransactionToTrash(id)
                                                            else transactionViewModel.moveToTrash(id)
                                                        }
                                                        selectedTxs = emptySet()
                                                    }
                                                    5 -> {
                                                        selectedCardIds.forEach { id -> finansiViewModel.moveCardToTrash(id) }
                                                        selectedCardIds = emptySet()
                                                    }
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            // ── TOGGLE TRASH MODE ──
                                            isTrashMode = !isTrashMode
                                            // Если был в archive mode — выходим из него
                                            // (режимы не должны сосуществовать).
                                            if (isTrashMode && isArchiveMode) {
                                                isArchiveMode = false
                                            }
                                            // Сбрасываем выделение при переключении режима,
                                            // чтобы selected* из обычного режима не «протекали»
                                            // в trash mode (где они указывают на не те объекты).
                                            selectedRenters = emptySet()
                                            selectedScooters = emptySet()
                                            selectedContracts = emptySet()
                                            selectedTxs = emptySet()
                                            selectedCardIds = emptySet()
                                            Toast.makeText(
                                                localContext,
                                                if (isTrashMode) "Chiqindixon rejimi yoqildi"
                                                else "Chiqindixon rejimi o'chirildi",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = if (isTrashMode) "Butunlay o'chirish" else "O'chirish",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // ── Кнопка «Поиск» (новая) ──────────────────────────────
                        // Круглая, с иконкой-лупой. Тап скрывает ВСЕ универсальные
                        // кнопки и показывает квадратную поисковую панель на их месте.
                        // Не показывается на вкладке «Sozlamalar» (6) — там нет поиска.
                        if (currentTab != 6) {
                            IconButton(
                                onClick = { isSearchMode = true },
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(56.dp)
                                    .background(ClaudeAccentBg, RoundedCornerShape(8.dp))
                                    .border(1.dp, ClaudeAccent, RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Qidiruv",
                                    tint = ClaudeAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        // Кнопка «Настройки» удалена из TopAppBar — теперь
                        // настройки доступны как 7-я вкладка нижней навигации
                        // (Tab 6 = Sozlamalar), на одной линии с остальными
                        // главными страницами.
                    } else {
                        // ── РЕЖИМ ПОИСКА: квадратная поисковая панель ──────────
                        // Все универсальные кнопки скрыты. Показываем компактную
                        // квадратную панель с полем ввода, кнопкой календаря и
                        // кнопкой фильтров. Долгое нажатие на панель возвращает
                        // универсальные кнопки.
                        when (currentTab) {
                            0 -> CompactSearchPanel(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onCalendarClick = { showDateRangePicker = true },
                                calendarActive = dateRangePickerState.selectedStartDateMillis != null,
                                onFilterClick = { showRenterFilterPanel = true },
                                filterActive = renterFilterValues.any { it.value.isNotBlank() },
                                onLongClickDismiss = { isSearchMode = false }
                            )
                            1 -> CompactSearchPanel(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onCalendarClick = { showScooterDateRangePicker = true },
                                calendarActive = scooterDateRangePickerState.selectedStartDateMillis != null,
                                onFilterClick = { showScooterFilterPanel = true },
                                filterActive = scooterFilterValues.any { it.value.isNotBlank() },
                                onLongClickDismiss = { isSearchMode = false }
                            )
                            2 -> CompactSearchPanel(
                                query = contractSearchQuery,
                                onQueryChange = { contractSearchQuery = it },
                                onCalendarClick = { contractCalendarTrigger++ },
                                calendarActive = false,
                                onFilterClick = { contractFilterTrigger++ },
                                filterActive = false,
                                onLongClickDismiss = { isSearchMode = false }
                            )
                            3 -> CompactSearchPanel(
                                query = transactionSearchQuery,
                                onQueryChange = { transactionSearchQuery = it },
                                onCalendarClick = { transactionCalendarTrigger++ },
                                calendarActive = false,
                                onFilterClick = { transactionFilterTrigger++ },
                                filterActive = false,
                                onLongClickDismiss = { isSearchMode = false }
                            )
                            4 -> CompactSearchPanel(
                                query = reportSearchQuery,
                                onQueryChange = { reportSearchQuery = it },
                                onCalendarClick = { reportCalendarTrigger++ },
                                calendarActive = false,
                                onFilterClick = { reportFilterTrigger++ },
                                filterActive = false,
                                onLongClickDismiss = { isSearchMode = false }
                            )
                            5 -> CompactSearchPanel(
                                query = finansiSearchQuery,
                                onQueryChange = { finansiSearchQuery = it },
                                onCalendarClick = { finansiCalendarTrigger++ },
                                calendarActive = false,
                                onFilterClick = { finansiFilterTrigger++ },
                                filterActive = false,
                                onLongClickDismiss = { isSearchMode = false }
                            )
                            // На вкладке «Sozlamalar» (6) поиска нет — выходим
                            // из режима поиска автоматически.
                            else -> {
                                LaunchedEffect(Unit) { isSearchMode = false }
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            // ── Нижняя навигация — иконки визуально идентичны ───────────────
            // универсальным кнопкам TopAppBar (Camera/Add/Edit/Delete/Search):
            //   • Размер Box-а 56dp — ТОЧНО как сверху.
            //   • Форма — RoundedCornerShape(8.dp) (квадратная, как сверху).
            //   • БЕЗ текстовых подписей (как и универсальные кнопки TopAppBar).
            //     Имя вкладки сохранено только в contentDescription иконки
            //     для screen-reader'а.
            //   • Дефолтный Material 3 pill-индикатор скрыт (indicatorColor =
            //     Color.Transparent), чтобы не было двух фонов подряд.
            //
            // КАЖДАЯ вкладка имеет свой акцентный цвет — как у верхних
            // универсальных кнопок, где Camera = бежевый, Add = оранжевый,
            // Delete = зелёный/красный и т. д. Это устраняет прежний
            // «бледный» вид, когда все невыбранные вкладки были одинаково
            // белыми с серой рамкой и выглядели «мертвыми» по сравнению с
            // цветными кнопками верхнего бара.
            //
            // Палитра (соответствует semantic роли вкладки):
            //   0 Ijarachilar  → ClaudeAccent        (terracotta — главная CTA)
            //   1 Skuterlar    → StatusOk           (green — активная аренда)
            //   2 Kontraktlar  → ClaudeGold          (gold — официальный документ)
            //   3 Tranzaksiya  → ClaudeAccentDark    (dark terracotta — записи)
            //   4 Otchetlar    → ClaudeTeal          (teal — альт-акцент, отчёты)
            //   5 Finansi      → StatusOverdue       (red — деньги/финансы)
            //   6 Sozlamalar   → ClaudeTextSecondary (muted brown — утилитарное)
            // ── Кастомная нижняя навигация ─────────────────────────────────
            // Раньше здесь был Material3 NavigationBar+NavigationBarItem, но у
            // него три побочных эффекта, отличающих нижние кнопки от верхних
            // (Camera/SMS/+//✎/🗑):
            //   1. НЕОГРАНИЧЕННАЯ ripple — при тапе расходится кругом далеко
            //      за пределы кнопки («странный круглый эффект», на который
            //      жаловался пользователь).
            //   2. Внутренние padding'и (6dp h / 8dp v) + слот иконки 32dp —
            //      кнопка выглядит меньше и смещена по вертикали.
            //   3. Pill-индикатор (Shape.Top) — даже с indicatorColor =
            //      Color.Transparent занимает layout-место.
            //
            // Решение: Surface + Row с такими же кнопками-Box'ами 56dp +
            // RoundedCornerShape(8.dp), как в верхнем баре. Ripple — bounded
            // (LocalIndication.current), остаётся внутри квадратной кнопки.
            Surface(
                color = ClaudeCard,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavTabButton(
                        isSelected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        accent = ClaudeAccent,
                        icon = Icons.Default.List,
                        contentDescription = "Ijarachilar"
                    )
                    NavTabButton(
                        isSelected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        accent = StatusOk,
                        icon = Icons.Default.DirectionsBike,
                        contentDescription = "Skuterlar"
                    )
                    NavTabButton(
                        isSelected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        accent = ClaudeGold,
                        icon = Icons.Default.Description,
                        contentDescription = "Kontraktlar"
                    )
                    NavTabButton(
                        isSelected = currentTab == 3,
                        onClick = { currentTab = 3 },
                        accent = ClaudeAccentDark,
                        icon = Icons.Default.RequestQuote,
                        contentDescription = "Tranzaksiyalar"
                    )
                    NavTabButton(
                        isSelected = currentTab == 4,
                        onClick = { currentTab = 4 },
                        accent = ClaudeTeal,
                        icon = Icons.Default.Assessment,
                        contentDescription = "Otchetlar"
                    )
                    NavTabButton(
                        isSelected = currentTab == 5,
                        onClick = { currentTab = 5 },
                        accent = StatusOverdue,
                        icon = Icons.Default.AccountBalanceWallet,
                        contentDescription = "Finansi"
                    )
                    NavTabButton(
                        isSelected = currentTab == 6,
                        onClick = { currentTab = 6 },
                        accent = ClaudeTextSecondary,
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Sozlamalar"
                    )
                }
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

            // ── Контракты по скутерам (для раскрытой строки на вкладке Skuterlar) ──
            // Объявлено ВНЕ if (currentTab == ...) блоков, потому что используется
            // только во вкладке 1 (Skuterlar), а contractHistory там не виден.
            // Группировка по scooterName (ContractHistoryEntry не имеет scooterId).
            val contractHistoryForScooters by contractHistoryViewModel.history
                .collectAsStateWithLifecycle()
            val contractsByScooterName: Map<String, List<com.example.data.ContractHistoryEntry>> =
                remember(contractHistoryForScooters) {
                    contractHistoryForScooters
                        .asSequence()
                        .filter {
                            it.type == com.example.data.ContractHistoryEntry.TYPE_CREATED ||
                            it.type == com.example.data.ContractHistoryEntry.TYPE_AUTO_RENEW
                        }
                        .filter { !it.scooterName.isNullOrBlank() }
                        .groupBy { it.scooterName!! }
                        .mapValues { (_, entries) ->
                            entries.sortedBy { it.weekStart ?: it.timestamp }
                        }
                }

            // ── Latest contract per renter (ВНЕ блоков if (currentTab == ...)) ──
            // Нужен как для вкладки арендаторов (даты старта/конца последнего
            // контракта, статус оплаты, цветная полоса), так и для вкладки
            // скутеров — чтобы определять, свободен ли скутер, если у
            // арендатора последний контракт TERMINATED (Stop-маркер).
            val contractHistoryAll by contractHistoryViewModel.history
                .collectAsStateWithLifecycle()
            val latestContractByRenter: Map<Int, com.example.data.ContractHistoryEntry> =
                remember(contractHistoryAll) {
                    contractHistoryAll
                        .asSequence()
                        .filter {
                            it.type == com.example.data.ContractHistoryEntry.TYPE_CREATED ||
                            it.type == com.example.data.ContractHistoryEntry.TYPE_AUTO_RENEW ||
                            it.type == com.example.data.ContractHistoryEntry.TYPE_TERMINATED ||
                            it.type == com.example.data.ContractHistoryEntry.TYPE_RETURNED
                        }
                        .filter { it.renterId > 0 }
                        .groupBy { it.renterId }
                        .mapValues { (_, entries) ->
                            entries.maxByOrNull { it.weekEnd ?: it.timestamp }!!
                        }
                }

            if (currentTab == 0) {
                // ===== ТАБЛИЦА АРЕНДАТОРОВ =====
                // Полоса поиска (UnifiedSearchBar) и панель доп.кнопок (To'lov/
                // Uzish/SMS) больше НЕ рендерятся отдельно в Column контента —
                // они переданы в RenterTable как `header` и рендерятся как
                // ПЕРВЫЙ item LazyColumn. Это нужно чтобы они скроллились
                // вместе с таблицей и уходили под статичную TopAppBar при
                // скролте вниз (по запросу пользователя: универсальные кнопки
                // всегда видны сверху, а поиск/доп.кнопки — часть контента).
                // FilterSidePanel остаётся в Column (это overlay, не часть
                // скроллящегося контента).
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
                //
                // ВАЖНО: latestContractByRenter объявлен ВНЕ if (currentTab == 0)
                // — теперь он используется и в RenterTable, и в ScooterTable
                // (для определения, свободен ли скутер, если последний контракт
                // TERMINATED). Здесь просто используем его.
                val contractHistory by contractHistoryViewModel.history
                    .collectAsStateWithLifecycle()

                // ── Все контракты по арендаторам (для раскрывающейся таблицы) ──
                // Группировка по renterId, только CREATED + AUTO_RENEW (без PAYMENT/
                // TERMINATED/RETURNED — это транзакции, не контракты). Сортировка
                // ASC по weekStart — чтобы в раскрывающейся таблице контракты шли
                // в хронологическом порядке.
                val contractsByRenter: Map<Int, List<com.example.data.ContractHistoryEntry>> =
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
                                entries.sortedBy { it.weekStart ?: it.timestamp }
                            }
                    }

                // Helper: даты последнего контракта (с fallback на поля Renter).
                fun latestStartTs(r: Renter): Long =
                    latestContractByRenter[r.id]?.weekStart ?: r.rentStartDateTimestamp
                fun latestEndTs(r: Renter): Long =
                    latestContractByRenter[r.id]?.weekEnd
                        ?: (r.rentStartDateTimestamp + (r.rentDurationDays * 24L * 60 * 60 * 1000))

                // ── Источник данных зависит от isTrashMode / isArchiveMode ──
                // В обычном режиме показываем активных арендаторов (isDeleted=0),
                // в trash mode — только удалённых (isDeleted=1),
                // в archive mode — только архивные (STOP без RESUME в прошлом).
                // Trash mode имеет приоритет над archive mode.
                val rentersSource = when {
                    isTrashMode -> trashedRenters
                    isArchiveMode -> archivedRenters
                    else -> liveRenters
                }
                val filteredRenters = rentersSource.filter { renter ->
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
                            "col_renewal" -> {
                                // Qo'llanma / Avtomatik — режим авто-продления
                                // контракта. Используется для фильтрации по статусу
                                // контракта (Manual / Auto).
                                val label = if (renter.autoRenewMode == com.example.data.RenterAutoRenewMode.AUTO)
                                    "Avtomatik" else "Qo'llanma"
                                label.contains(filterText, ignoreCase = true)
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
                        // ── Sort comparators для ВСЕХ столбцов таблицы арендаторов ──
                        // По запросу пользователя: все столбцы (включая Status)
                        // теперь сортируемые — от меньшего к большему и наоборот.
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
                            "col_renewal" -> compareBy<Renter> { it.autoRenewMode }
                            else -> compareBy<Renter> { latestEndTs(it) }
                        }
                        if (state == SortState.ASCENDING) list.sortedWith(comparator)
                        else list.sortedWith(comparator.reversed())
                    }
                }

                // ── ВАЖНО: RenterTable + FilterSidePanel оборачиваем в Box ──
                // Раньше FilterSidePanel была sibling-ом RenterTable в Column.
                // RenterTable (LazyColumn) потреблял всю высоту (fillMaxSize),
                // и FilterSidePanel получал 0dp → кнопка фильтра не работала.
                // В Box FilterSidePanel рендерится поверх RenterTable как overlay.
                Box(modifier = Modifier.fillMaxSize()) {
                    RenterTable(
                        renters = filteredRenters,
                        selected = selectedRenters,
                        sortState = renterSortState,
                        columnVisibility = renterColumnVisibility,
                        latestContractByRenter = latestContractByRenter,
                        contractsByRenter = contractsByRenter,
                        // ── Переключение статуса контракта из раскрытой строки ──
                        // Делегируем в ContractHistoryViewModel.updateContract —
                        // она уже правильно обрабатывает смену isPaid:
                        //   • Удаляет старую Transaction(и) контракта
                        //   • Реверсит CardTransaction на главной карте
                        //   • Создаёт новую Transaction с актуальным знаком
                        //   • Корректирует баланс арендатора
                        //   • Добавляет аудита-запись TYPE_PAYMENT в историю
                        //   • Зачисляет на карту если стал оплачен
                        onToggleContractStatus = { contract ->
                            contractHistoryViewModel.updateContract(
                                contract.copy(isPaid = !contract.isPaid)
                            )
                        },
                        // ── Каскадное удаление контракта из раскрытой строки ──
                        // Делегируется в contractHistoryViewModel.deleteContract,
                        // который каскадно удаляет контракт + связанные
                        // Transaction + CardTransaction + корректирует баланс
                        // арендатора и главной карты (ренверс суммы контракта
                        // и ренверс доходов карты).
                        onDeleteContract = { contractId ->
                            contractHistoryViewModel.deleteContract(contractId)
                        },
                        // ── Поисковая панель и панель действий (To'lov/Uzish/SMS)
                        // полностью удалены из контента таблицы арендаторов.
                        // Поиск живёт в TopAppBar (CompactSearchPanel), который
                        // переключается кнопкой «Поиск» в группе универсальных
                        // кнопок. Панель действий (To'lov / Uzish / SMS) тоже
                        // удалена — все эти действия выполняются через
                        // универсальные кнопки TopAppBar или экран истории
                        // контрактов арендатора.
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

                    // Filter side panel — overlay поверх RenterTable.
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
                }
            } else if (currentTab == 1) {
                // Вкладка «Скутеры» — поиск теперь в TopAppBar (CompactSearchPanel),
                // фильтры и календарь открываются оттуда же. FilterSidePanel
                // рендерится как overlay поверх ScooterTable (внутри Box).
                // ── Источник данных: в trash mode показываем только удалённые скутеры.
                val scootersSource = if (isTrashMode) trashedScooters else liveScooters
                val filteredScooters = scootersSource.filter { scooter ->
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
                                val status = scooterStatusLabel(scooterStatusOf(scooter.id, renters, latestContractByRenter))
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
                        // ── Sort comparators для ВСЕХ столбцов таблицы скутеров ──
                        // Раньше сортировался только col_name, остальные столбцы
                        // были NonSortable. По запросу пользователя все столбцы
                        // теперь сортируемые (от меньшего к большему и наоборот).
                        val comparator = when (col) {
                            "col_name"   -> compareBy<Scooter> { it.name.lowercase() }
                            "col_doc"    -> compareBy<Scooter> { (it.documentedNumber ?: "").lowercase() }
                            "col_vin"    -> compareBy<Scooter> { it.vinNumber.lowercase() }
                            "col_engine" -> compareBy<Scooter> { it.engineNumber.lowercase() }
                            "col_serial" -> compareBy<Scooter> { it.scooterSerialNumber.lowercase() }
                            "col_batt1"  -> compareBy<Scooter> { it.batteryId1.lowercase() }
                            "col_batt2"  -> compareBy<Scooter> { it.batteryId2.lowercase() }
                            "col_extra"  -> compareBy<Scooter> { it.additionalInfo.lowercase() }
                            "col_status" -> compareBy<Scooter> {
                                // Ijarada (rented) > Bazada (in_base) — сортируем по статусу
                                if (scooterStatusOf(it.id, renters, latestContractByRenter) == ScooterStatus.RENTED) 1 else 0
                            }
                            else -> compareBy<Scooter> { it.name.lowercase() }
                        }
                        if (state == SortState.ASCENDING) list.sortedWith(comparator)
                        else list.sortedWith(comparator.reversed())
                    }
                }

                // ── Box: ScooterTable + FilterSidePanel как overlay ──
                Box(modifier = Modifier.fillMaxSize()) {
                    ScooterTable(
                        scooters = filteredScooters,
                        renters = renters,
                        selected = selectedScooters,
                        sortState = scooterSortState,
                        columnVisibility = scooterColumnVisibility,
                        contractsByScooterName = contractsByScooterName,
                        // Передаём карту последних контрактов по арендаторам,
                        // чтобы ScooterTable мог освободить скутер, если у
                        // арендатора последний контракт TERMINATED (Stop-маркер).
                        latestContractByRenter = latestContractByRenter,
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

                    // Filter side panel — overlay поверх ScooterTable.
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
                }
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
                    },
                    searchQuery = contractSearchQuery,
                    onSearchQueryChange = { contractSearchQuery = it },
                    calendarTrigger = contractCalendarTrigger,
                    filterTrigger = contractFilterTrigger,
                    isTrashMode = isTrashMode
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
                    onSelectedTxsChange = { selectedTxs = it },
                    searchQuery = transactionSearchQuery,
                    onSearchQueryChange = { transactionSearchQuery = it },
                    calendarTrigger = transactionCalendarTrigger,
                    filterTrigger = transactionFilterTrigger,
                    isTrashMode = isTrashMode
                )
            } else if (currentTab == 4) {
                // ── Вкладка «Otchetlar» — дашборд с инфографикой ────────
                ReportsScreen(
                    renterViewModel = viewModel,
                    scooterViewModel = scooterViewModel,
                    contractHistoryViewModel = contractHistoryViewModel,
                    transactionViewModel = transactionViewModel,
                    finansiViewModel = finansiViewModel,
                    searchQuery = reportSearchQuery,
                    onSearchQueryChange = { reportSearchQuery = it },
                    calendarTrigger = reportCalendarTrigger,
                    filterTrigger = reportFilterTrigger
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
                    },
                    searchQuery = finansiSearchQuery,
                    onSearchQueryChange = { finansiSearchQuery = it },
                    calendarTrigger = finansiCalendarTrigger,
                    filterTrigger = finansiFilterTrigger,
                    isTrashMode = isTrashMode
                )
            } else if (currentTab == 6) {
                // ── Вкладка «Sozlamalar» ─────────────────────────────────
                // Раньше была отдельная страница, открываемая через кнопку
                // в TopAppBar. Теперь — 7-я вкладка нижней навигации.
                val template by settingsViewModel.smsTemplate.collectAsStateWithLifecycle()
                val dailyPrice by settingsViewModel.dailyPrice.collectAsStateWithLifecycle()
                val smsAutoSend by settingsViewModel.smsAutoSendEnabled.collectAsStateWithLifecycle()
                SettingsScreen(
                    currentTemplate = template,
                    currentWeeklyPrice = dailyPrice,
                    currentMonthlyPrice = dailyPrice * 30.0,
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
                    allReleases = allReleases,
                    isLoadingReleases = isLoadingReleases,
                    releasesError = releasesError,
                    onLoadReleases = {
                        if (!isLoadingReleases) {
                            isLoadingReleases = true
                            releasesError = null
                            coroutineScope.launch {
                                val checker = UpdateChecker(localContext)
                                val result = checker.fetchAllReleasesDetailed()
                                when (result) {
                                    is com.example.data.remote.FetchReleasesResult.Success -> {
                                        allReleases = result.releases
                                        releasesError = null
                                    }
                                    else -> {
                                        allReleases = emptyList()
                                        releasesError = checker.userFacingMessage(result)
                                    }
                                }
                                isLoadingReleases = false
                            }
                        }
                    },
                    onRetryReleases = {
                        if (!isLoadingReleases) {
                            isLoadingReleases = true
                            releasesError = null
                            coroutineScope.launch {
                                val checker = UpdateChecker(localContext)
                                checker.clearCache()
                                val result = checker.fetchAllReleasesDetailed()
                                when (result) {
                                    is com.example.data.remote.FetchReleasesResult.Success -> {
                                        allReleases = result.releases
                                        releasesError = null
                                    }
                                    else -> {
                                        allReleases = emptyList()
                                        releasesError = checker.userFacingMessage(result)
                                    }
                                }
                                isLoadingReleases = false
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
            val dailyForForm by settingsViewModel.dailyPrice.collectAsStateWithLifecycle()

            // ── Загружаем существующие контракты арендатора для календаря ──
            // В режиме редактирования календарь в RenterFormDialog должен
            // показывать все текущие контракты (как цветные периоды) и список
            // под календарём. Для этого родитель собирает StateFlow через
            // contractHistoryViewModel.forRenter(renterId).
            //
            // ВАЖНО: используем forRenter (а НЕ contractsForRenter), потому что
            // forRenter возвращает ВСЕ записи контрактов — включая Stop/Resume
            // маркеры (TYPE_TERMINATED с notes="STOP_MARKER" и TYPE_RETURNED с
            // notes="RESUME_MARKER"). Если использовать contractsForRenter, он
            // фильтрует только CREATED/AUTO_RENEW — и тогда маркеры НЕ
            // загружаются в форму, что приводит к багу: после сохранения
            // маркер «исчезает» из календаря при повторном открытии формы.
            // С forRenter маркеры корректно загружаются и отображаются.
            //
            // ВАЖНО: collectAsStateWithLifecycle должен вызываться безусловно
            // (правила Compose — хуки нельзя вызывать в ветках if). Поэтому
            // используем renterToEdit?.id ?: -1 — для id=-1 репозиторий вернёт
            // пустой список (нет арендатора с таким id), что и нужно в режиме
            // создания.
            val editRenterId = renterToEdit?.id ?: -1
            val existingContractsForForm by contractHistoryViewModel
                .forRenter(editRenterId)
                .collectAsStateWithLifecycle()
            // В режиме создания (renterToEdit == null) принудительно пустой список,
            // чтобы не показать «фантомные» контракты для id=-1 (на случай если
            // репозиторий что-то вернёт).
            val existingContractsForFormSafe: List<com.example.data.ContractHistoryEntry> =
                if (renterToEdit != null) existingContractsForForm else emptyList()

            RenterFormDialog(
                initialRenter = renterToEdit,
                weeklyPrice = weekly,
                monthlyPrice = monthly,
                dailyPrice = dailyForForm,
                scooters = scooters,
                activeRenters = renters,
                archivedRenterIds = archivedRenters.map { it.id }.toSet(),
                existingContracts = existingContractsForFormSafe,
                onDismiss = {
                    showAddDialog = false
                    renterToEdit = null
                },
                onSave = { result ->
                    if (isEdit) {
                        renterToEdit?.let {
                            // Используем новую функцию с авто-корректировкой контрактов.
                            // Передаём также contractGroupsWithIds — это позволяет
                            // функции реконсиалировать контракты: удалить отсутствующие,
                            // добавить новые, обновить статус оплаты.
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
                                pinfl = result.pinfl,
                                autoRenewMode = result.autoRenewMode,
                                contractGroupsWithIds = result.contractGroupsWithIds
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
                            autoRenewMode = result.autoRenewMode,
                            contractGroups = result.contractGroups,
                            // Передаём полный список групп с маркерами Stop/Resume —
                            // addRenter использует его (scenario 5) для:
                            //   • сохранения Stop-маркеров как TYPE_TERMINATED
                            //   • сохранения Resume-маркеров как TYPE_RETURNED
                            //   • авто-генерации неоплаченных weekly-контрактов от
                            //     каждого Resume-маркера вперёд до ближайшего Stop
                            //     (или +7 дней) и назад до сегодня (если нет Stop).
                            contractGroupsWithMarkers = result.contractGroupsWithIds
                        )
                    }
                    showAddDialog = false
                    renterToEdit = null
                },
                // ── Inline-создание скутера ────────────────────────────────
                // Пользователь может внутри формы арендатора создать новый
                // скутер, не выходя из диалога. Форма автоматически выберет
                // свежесозданный скутер в качестве scooterId для арендатора.
                // Лямбда suspend, возвращает id свежесозданного скутера.
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
                },
                // Каскадное удаление существующего контракта из формы
                // (кнопка ✕ в списке контрактов под календарём).
                onDeleteExistingContract = { contractId ->
                    contractHistoryViewModel.deleteContract(contractId)
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
                        // addScooter теперь suspend (возвращает id) — запускаем
                        // в coroutineScope из MainScreen. ID здесь не нужен —
                        // просто создаём скутер для вкладки «Скутеры».
                        coroutineScope.launch {
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

        // ── Диалог подтверждения включения авто-отправки SMS ──────────────
        // Появляется при долгом нажатии на универсальную кнопку SMS.
        // Содержит две кнопки: «Orqaga» (Back) и «Tasdiqlash» (Confirm).
        // Подтверждение включает авто-отправку SMS, после чего краткое
        // нажатие на ту же кнопку выключит её без диалога (безопасно).
        if (showSmsAutoSendConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showSmsAutoSendConfirmDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = StatusOverdue
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "SMS avto-yuborishni yoqish",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                text = {
                    Text(
                        "Siz avto-yuborishni yoqishga tayyorsiz. " +
                            "Yoqilgandan so'ng, tizim mijozlarga SMS " +
                            "xabarlarini avtomatik yuboradi.\n\n" +
                            "Davom etish uchun «Tasdiqlash» tugmasini bosing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                containerColor = ClaudeCard,
                confirmButton = {
                    PrimaryButton(
                        label = "Tasdiqlash",
                        icon = Icons.Default.Check,
                        onClick = {
                            settingsViewModel.updateSmsAutoSend(true)
                            Toast.makeText(
                                localContext,
                                "SMS avto-yuborish yoqildi",
                                Toast.LENGTH_SHORT
                            ).show()
                            showSmsAutoSendConfirmDialog = false
                        }
                    )
                },
                dismissButton = {
                    SecondaryButton(
                        label = "Orqaga",
                        icon = Icons.Default.Close,
                        onClick = { showSmsAutoSendConfirmDialog = false }
                    )
                }
            )
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
    /**
     * Все контракты арендаторов, сгруппированные по renterId.
     * Используется для отображения раскрывающейся таблицы контрактов под
     * строкой арендатора (когда пользователь нажимает на стрелку раскрытия
     * в первом столбце). Сортировка — ASC по weekStart.
     */
    contractsByRenter: Map<Int, List<com.example.data.ContractHistoryEntry>> = emptyMap(),
    /**
     * Callback переключения статуса контракта (paid ↔ unpaid).
     * Вызывается при нажатии на кнопку «−» рядом с контрактом в раскрытой
     * строке арендатора. Родитель (MainScreen) делегирует вызов в
     * ContractHistoryViewModel.updateContract — которая уже правильно
     * создаёт/удаляет Transaction, корректирует баланс и аудит-запись.
     */
    onToggleContractStatus: ((com.example.data.ContractHistoryEntry) -> Unit)? = null,
    /**
     * Каскадное удаление контракта по id.
     *
     * Вызывается при нажатии кнопки ✕ на строке контракта в раскрывающейся
     * таблице контрактов под арендатором. Родитель делегирует в
     * contractHistoryViewModel.deleteContract(id), который каскадно удаляет:
     *   • сам контракт;
     *   • все Transaction с contractId = id (история платежей);
     *   • все CardTransaction с contractId = id (доходы на главной карте);
     *   • корректирует баланс арендатора (ренверс суммы контракта);
     *   • корректирует баланс главной карты (ренверс CardTransaction.amount).
     *
     * Если callback не передан — кнопка ✕ не отображается.
     */
    onDeleteContract: ((Int) -> Unit)? = null,
    /**
     * Опциональный header-блок, который рендерится как ПЕРВЫЙ элемент
     * LazyColumn (перед строками арендаторов). Используется чтобы полоса
     * поиска и панель доп.кнопок (To'lov/Uzish/SMS) скроллились вместе с
     * таблицей и уходили под статичную TopAppBar при скролле вниз.
     * Если не передан — ничего не рендерится (обратная совместимость).
     */
    header: @Composable () -> Unit = {},
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
    val showRenewal  = false // столбец «Status (Manual/Auto)» удалён из UI
                                // по просьбе пользователя. Логика авто-продления
                                // теперь управляется через маркеры Stop/Resume
                                // в календаре формы арендатора.
    val showPassport = isColVisible("col_passport")
    val showAddress  = isColVisible("col_address")
    val showPinfl    = isColVisible("col_pinfl")

    // ── Компоновка ──────────────────────────────────────────────────────
    // ВСЕГДА используем fixed widths + горизонтальный скролл. Раньше при
    // скрытых extra-колонках применялся weight-based layout без скролла,
    // из-за чего колонка «Mijoz» сжималась до ~85dp и имя «Akmal Karimov»
    // обрезалось до «Akmal…» (maxLines=1). Теперь каждая колонка имеет
    // фиксированную ширину, достаточную для полного отображения данных,
    // а пользователь скроллит таблицу по горизонтали если колонок много.
    val hasAnyExtraVisible = showPassport || showAddress || showPinfl

    val wExpand   = 40.dp    // ← стрелка раскрытия контрактов (новая колонка перед №)
    val wNum      = 40.dp    // № — порядковый номер строки
    val wName     = 200.dp   // увеличено с 160 — вмещает «Имя Фамилия Отчество» без обрезки
    val wPhone    = 115.dp
    val wScoot    = 90.dp
    val wStart    = 90.dp
    val wEnd      = 90.dp
    val wDebt     = 80.dp
    val wRenewal  = 110.dp   // Status — Qo'llanma / Avtomatik
    val wPassport = 115.dp
    val wAddress  = 150.dp
    val wPinfl    = 110.dp

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val hScrollState = rememberScrollState()

    // ── Раскрытые строки ───────────────────────────────────────────────
    // Множество ID арендаторов, у которых раскрыта встроенная таблица
    // контрактов. Управляется кнопкой-стрелкой в первом столбце. Локальный
    // state — не персистится между перезапусками, что приемлемо: это
    // вспомогательная навигация, а не пользовательские данные.
    var expandedRenterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Заголовок ────────────────────────────────────────────────────
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(hScrollState)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Пустая ячейка в столбце стрелки раскрытия (шапка)
                Spacer(modifier = Modifier.width(wExpand))
                NonSortableHeaderCellFixed(Icons.Default.Numbers, wNum, "№")
                if (showName)     SortableHeaderCellFixed(Icons.Default.Person,               wName,     "col_name",     sortState) { onSortClick("col_name") }
                if (showPhone)    SortableHeaderCellFixed(Icons.Default.Phone,                wPhone,    "col_phone",    sortState) { onSortClick("col_phone") }
                if (showScooter)  SortableHeaderCellFixed(Icons.Default.DirectionsBike,       wScoot,    "col_scooter",  sortState) { onSortClick("col_scooter") }
                if (showStart)    SortableHeaderCellFixed(Icons.Default.CalendarToday,        wStart,    "col_start",    sortState) { onSortClick("col_start") }
                if (showEnd)      SortableHeaderCellFixed(Icons.Default.Event,                wEnd,      "col_end",      sortState) { onSortClick("col_end") }
                if (showBalance)  SortableHeaderCellFixed(Icons.Default.AccountBalanceWallet, wDebt,     "col_balance",  sortState) { onSortClick("col_balance") }
                if (showRenewal)  SortableHeaderCellFixed(Icons.Default.Refresh,            wRenewal,  "col_renewal", sortState) { onSortClick("col_renewal") }
                if (showPassport) SortableHeaderCellFixed(Icons.Default.CreditCard,           wPassport, "col_passport", sortState) { onSortClick("col_passport") }
                if (showAddress)  SortableHeaderCellFixed(Icons.Default.Home,                 wAddress,  "col_address",  sortState) { onSortClick("col_address") }
                if (showPinfl)    SortableHeaderCellFixed(Icons.Default.Fingerprint,          wPinfl,    "col_pinfl",    sortState) { onSortClick("col_pinfl") }
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
            // ── Header (поиск + доп.кнопки) — скроллится вместе с таблицей ──
            // Рендерится как первый item LazyColumn. При скролле вниз уходит
            // под статичную TopAppBar (Scaffold.topBar), которая перекрывает его.
            item {
                header()
            }
            itemsIndexed(renters, key = { _, it -> it.id }) { idx, renter ->
                val isSelected = selected.contains(renter.id)
                val isExpanded = expandedRenterIds.contains(renter.id)
                // ── Статус арендатора для цветной полосы слева ─────────────────
                // Возвращено по просьбе пользователя: тонкая вертикальная
                // полоса (4dp / 5dp если выбран) красного (просрочен) или
                // зелёного (всё ок) цвета вдоль левого края строки.
                val status = statusOf(renter)
                val sColor = statusColor(status)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScrollState),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ── Стрелка раскрытия контрактов ──────────────────
                        // Поведение (по запросу пользователя):
                        //   • Обычное состояние → стрелка ВПРАВО (KeyboardArrowRight).
                        //   • Раскрыто (isExpanded) → стрелка ВНИЗ (повёрнута на 90°).
                        //   • Выбран (isSelected) → стрелка ВВЕРХ (повёрнута на -90°).
                        //     Это визуальный индикатор выбора, дополняющий цветную
                        //     рамку и серый фон строки.
                        // Логика приоритета: isSelected > isExpanded (если арендатор
                        // выбран и одновременно раскрыт — показываем стрелку вверх,
                        // т.к. выбор важнее для пользователя).
                        val arrowRotation = when {
                            isSelected -> -90f   // вверх
                            isExpanded -> 90f    // вниз
                            else -> 0f           // вправо (исходное состояние иконки)
                        }
                        val arrowTint = when {
                            isSelected -> ClaudeAccent
                            isExpanded -> ClaudeAccent
                            else -> ClaudeTextSecondary
                        }
                        Box(
                            modifier = Modifier
                                .width(wExpand)
                                .height(40.dp)
                                .clickable {
                                    // Переключаем раскрытие. Клик по стрелке НЕ
                                    // вызывает onClick строки и НЕ переключает
                                    // выбор — это отдельное действие.
                                    expandedRenterIds = if (isExpanded) {
                                        expandedRenterIds - renter.id
                                    } else {
                                        expandedRenterIds + renter.id
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = if (isExpanded) "Yig'ish" else "Kontraktlar",
                                tint = arrowTint,
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(arrowRotation)
                            )
                        }

                        // ── Основная строка арендатора (№ + Mijoz + Tel + ...) ──
                        // Цветная вертикальная полоса статуса слева (drawBehind):
                        //   • зелёная (StatusOk) — аренда активна, долгов нет
                        //   • красная (StatusOverdue) — просрочка / долг
                        //   • серая (ClaudeDivider) — возвращён / неактивен
                        // Ширина полосы: 4dp (или 5dp если строка выбрана —
                        // визуально выделяет выбранные строки).
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) Color(0xFFF3F4F6) else Color.White
                                )
                                .drawBehind {
                                    val stripeW = if (isSelected) 5.dp.toPx() else 4.dp.toPx()
                                    drawRect(
                                        color = sColor,
                                        topLeft = Offset.Zero,
                                        size = Size(stripeW, size.height)
                                    )
                                }
                                .combinedClickable(
                                    onClick = { if (isSelected) onSelect(renter.id, false) else onClick(renter) },
                                    onLongClick = { onSelect(renter.id, !isSelected) }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // ── № — порядковый номер строки ──
                        Text(
                            "${idx + 1}",
                            modifier = Modifier
                                .width(wNum)
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        // Mijoz — Имя + Фамилия + Отчество.
                        // maxLines = 2 — длинные имена переносятся на вторую строку
                        // (softWrap = true). Раньше maxLines = Int.MAX_VALUE +
                        // overflow = Visible — это могло вызывать визуальное
                        // расширение колонки при очень длинных именах. Теперь имя
                        // жёстко ограничено 2 строками с ellipsis на третьей.
                        if (showName) {
                            Text(
                                renter.name,
                                modifier = Modifier
                                    .width(wName)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // Tel
                        if (showPhone) {
                            Text(
                                renter.phoneNumber,
                                modifier = Modifier
                                    .width(wPhone)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeTextSecondary,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        // Skuter — имя скутера из ПОСЛЕДНЕГО КОНТРАКТА
                        // По просьбе пользователя: «в таблице арендаторов столбце
                        // скутеров будет показывать текст скутера который
                        // подключен к этому арендатору с помощью контракта на
                        // сегодняшний день».
                        // Берём scooterName из последнего контракта арендатора
                        // (вместо устаревшего поля Renter.scooterName, которое
                        // может указывать на старый скутер, не отражая смену
                        // статуса Stop/Resume). Если последний контракт
                        // TERMINATED (Stop-маркер) → показываем «Tanlanmagan»,
                        // signalling что скутер свободен.
                        if (showScooter) {
                            val latest = latestContractByRenter[renter.id]
                            val scooterDisplay = when {
                                latest == null -> renter.scooterName ?: "—"
                                latest.type == com.example.data.ContractHistoryEntry.TYPE_TERMINATED -> "Tanlanmagan"
                                else -> latest.scooterName ?: renter.scooterName ?: "—"
                            }
                            Text(
                                scooterDisplay,
                                modifier = Modifier
                                    .width(wScoot)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (latest?.type == com.example.data.ContractHistoryEntry.TYPE_TERMINATED)
                                    ClaudeTextSecondary else ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        // Boshlanish (дата начала ПОСЛЕДНЕГО контракта)
                        if (showStart) {
                            val latest = latestContractByRenter[renter.id]
                            val startTs = latest?.weekStart ?: renter.rentStartDateTimestamp
                            Text(
                                dateFmt.format(Date(startTs)),
                                modifier = Modifier
                                    .width(wStart)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        // Tugash (дата окончания ПОСЛЕДНЕГО контракта)
                        if (showEnd) {
                            val latest = latestContractByRenter[renter.id]
                            val endTs = latest?.weekEnd
                                ?: (renter.rentStartDateTimestamp +
                                    (renter.rentDurationDays * 24L * 60 * 60 * 1000))
                            Text(
                                dateFmt.format(Date(endTs)),
                                modifier = Modifier
                                    .width(wEnd)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
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
                                    .width(wDebt)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = balanceColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        // ── Status (Manual / Auto) ──────────────────────────────
                        // Показывает режим авто-продления контракта:
                        //   • «Avtomatik» (зелёным) — система создаёт контракты
                        //     автоматически при окончании последнего.
                        //   • «Qo'llanma» (серым) — система НЕ создаёт контракты,
                        //     пользователь создаёт их вручную.
                        if (showRenewal) {
                            val isAuto = renter.autoRenewMode == com.example.data.RenterAutoRenewMode.AUTO
                            val renewalLabel = if (isAuto) "Avtomatik" else "Qo'llanma"
                            val renewalColor = if (isAuto) StatusOk else ClaudeTextSecondary
                            Row(
                                modifier = Modifier
                                    .width(wRenewal)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(renewalColor, CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    renewalLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = renewalColor,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // ── Опциональные колонки (показываются если включены) ─
                        if (showPassport) {
                            Text(
                                renter.passportData.ifBlank { "—" },
                                modifier = Modifier.width(wPassport).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showAddress) {
                            Text(
                                renter.address.ifBlank { "—" },
                                modifier = Modifier.width(wAddress).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showPinfl) {
                            Text(
                                renter.pinfl.ifBlank { "—" },
                                modifier = Modifier.width(wPinfl).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        } // ── конец основной строки (inner Row с combinedClickable) ──
                    } // ── конец outer Row (стрелка + основная строка) ──

                    // ── Раскрывающаяся таблица контрактов ──────────────────
                    // Показывается под строкой арендатора, когда пользователь
                    // нажал на стрелку раскрытия (isExpanded = true).
                    // Отступ слева = wExpand + wNum + 8.dp (стрелка + № + padding),
                    // чтобы таблица визуально начиналась со 2-го столбца после
                    // номера арендатора (как просил пользователь: «на два столбцов
                    // вправо после столбца номер арендатора»).
                    //
                    // ВАЖНО: используем СОБСТВЕННЫЙ ScrollState (contractsScrollState),
                    // а НЕ общий hScrollState с основной строкой. Раньше общий
                    // state ломал горизонтальный скролл основной таблицы: оба
                    // scrollable-блока с разной шириной контента конфликтовали
                    // за maxValue общего ScrollState, и пользователь «упирался
                    // в стену» контрактов, не мог свайпнуть вправо чтобы увидеть
                    // остальные столбцы арендатора. Теперь каждый блок скроллится
                    // независимо — «стены» нет.
                    //
                    // Ширина Surface фиксирована (= сумма колонок + padding),
                    // а не fillMaxWidth() — fillMaxWidth в horizontalScroll
                    // растягивает Surface на viewport, что ещё больше ломало
                    // расчёт scrollable-ширины.
                    if (isExpanded) {
                        val contracts = contractsByRenter[renter.id].orEmpty()
                            .filter {
                                it.type == com.example.data.ContractHistoryEntry.TYPE_CREATED ||
                                it.type == com.example.data.ContractHistoryEntry.TYPE_AUTO_RENEW
                            }
                            .sortedBy { it.weekStart ?: it.timestamp }

                        // ── Контракты в раскрытой строке арендатора оформлены
                        // ТОЧНО так же, как на странице «Об арендаторе»
                        // (RenterContractHistoryScreen): 4-колоночная карточка
                        // (№ | # | Muddat (hafta) с датой-стрелкой-датой + статус
                        // точкой-текстом + примечание | Summa). Раньше здесь была
                        // 10-колоночная таблица в стиле ContractListScreen —
                        // пользователь явно попросил сделать визуально такой же,
                        // как в деталях арендатора. ─────────────────────────────

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 4.dp)
                        ) {
                            // Отступ: стрелка (wExpand) + № (wNum) + 8dp — блок
                            // контрактов визуально начинается со 2-го столбца
                            // после № арендатора.
                            Spacer(modifier = Modifier.width(wExpand + wNum + 8.dp))
                            // ── Карточка с контрактами ──────────────────────
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFAFAF7),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    ClaudeDivider
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Kontraktlar (${contracts.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClaudeAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (contracts.isEmpty()) {
                                        Text(
                                            text = "Kontraktlar yo'q",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClaudeTextSecondary
                                        )
                                    } else {
                                        // ── Заголовок таблицы (как на странице
                                        // «Об арендаторе»: № | # | Muddat (hafta)
                                        // | Summa) ──
                                        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                NonSortableHeaderCell(Icons.Default.Numbers,   0.3f, "№")
                                                NonSortableHeaderCell(Icons.Default.Numbers,   0.4f, "#")
                                                NonSortableHeaderCell(Icons.Default.DateRange, 1.8f, "Muddat (hafta)")
                                                NonSortableHeaderCell(Icons.Default.Payments,  1.0f, "Summa")
                                                // Колонка для кнопки переключения статуса (минус).
                                                // Ширина совпадает с кнопкой (36dp + padding).
                                                if (onToggleContractStatus != null) {
                                                    Spacer(modifier = Modifier.width(44.dp))
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = ClaudeDivider)

                                        // ── Строки контрактов (как на странице
                                        // «Об арендаторе»: 4 колонки с весами,
                                        // дата-стрелка-дата + статус-точка + сумма) ──
                                        contracts.forEachIndexed { idx, c ->
                                            val statusColor = if (c.isPaid) StatusOk else StatusOverdue
                                            val statusLabel = if (c.isPaid) "To'langan" else "To'lanmagan"

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(ClaudeCard)
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // № — порядковый номер строки
                                                Text(
                                                    "${idx + 1}",
                                                    modifier = Modifier.weight(0.3f),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ClaudeTextSecondary,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                                // #ID
                                                Text(
                                                    "#${c.id}",
                                                    modifier = Modifier.weight(0.4f),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ClaudeTextSecondary,
                                                    maxLines = 1
                                                )
                                                // Muddat (hafta) — дата → дата
                                                // + статус-точка + примечание
                                                // СВЕРХУ периода: имя скутера
                                                // (требование пользователя: «в
                                                // календаре должен показываться
                                                // скутер имя скутера сверху
                                                // выбранного периода контракта»).
                                                Column(modifier = Modifier.weight(1.8f)) {
                                                    // ── Имя скутера над периодом ──
                                                    if (!c.scooterName.isNullOrBlank()) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.DirectionsBike,
                                                                contentDescription = null,
                                                                tint = ClaudeAccent,
                                                                modifier = Modifier.size(11.dp)
                                                            )
                                                            Text(
                                                                text = c.scooterName!!,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = ClaudeAccent,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        Spacer(Modifier.height(2.dp))
                                                    }
                                                    Text(
                                                        text = buildString {
                                                            c.weekStart?.let { append(dateFmt.format(Date(it))) }
                                                            if (c.weekEnd != null) append(" → ")
                                                            c.weekEnd?.let { append(dateFmt.format(Date(it))) }
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
                                                        if (!c.notes.isNullOrBlank()) {
                                                            Spacer(Modifier.width(6.dp))
                                                            Text(
                                                                "• ${c.notes}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = ClaudeTextSecondary,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                }
                                                // Summa — цветная жирная
                                                Text(
                                                    "${c.amount.toLong()}",
                                                    modifier = Modifier.weight(1.0f),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = statusColor,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.End,
                                                    maxLines = 1
                                                )
                                                // ── Кнопка переключения статуса ──
                                                // Иконка отражает ТЕКУЩИЙ статус:
                                                //   • Оплачен → Check (зелёная галочка)
                                                //   • Неоплачен → Remove (красный минус)
                                                // Раньше всегда стоял Remove, и пользователь
                                                // не мог отличить оплаченный контракт от
                                                // неоплаченного по форме иконки (только по
                                                // цвету). Теперь форма иконки тоже несёт
                                                // смысл. Нажатие переключает isPaid и
                                                // вызывает onToggleContractStatus, который
                                                // через ContractHistoryViewModel
                                                // .updateContract корректно создаёт/удаляет
                                                // Transaction, корректирует баланс и
                                                // аудит-запись в истории контрактов.
                                                if (onToggleContractStatus != null) {
                                                    Spacer(Modifier.width(8.dp))
                                                    val toggleTint = if (c.isPaid) StatusOk else StatusOverdue
                                                    val toggleBg = if (c.isPaid) StatusOk.copy(alpha = 0.15f)
                                                                   else StatusOverdue.copy(alpha = 0.15f)
                                                    val toggleIcon = if (c.isPaid) Icons.Default.Check
                                                                     else Icons.Default.Remove
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(toggleBg)
                                                            .border(1.dp, ClaudeDivider, RoundedCornerShape(8.dp))
                                                            .clickable {
                                                                onToggleContractStatus.invoke(
                                                                    c.copy(isPaid = !c.isPaid)
                                                                )
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = toggleIcon,
                                                            contentDescription = "Statusni almashtirish",
                                                            tint = toggleTint,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                // ── Кнопка ✕ — каскадное удаление ──
                                                // Удаляет контракт + связанные
                                                // транзакции + корректирует баланс
                                                // арендатора и главной карты (через
                                                // contractHistoryViewModel.deleteContract).
                                                // Визуально отличается от кнопки
                                                // переключения статуса (минус):
                                                // красный X на фоне ClaudeAccentBg.
                                                if (onDeleteContract != null) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(ClaudeAccentBg)
                                                            .border(1.dp, ClaudeDivider, RoundedCornerShape(8.dp))
                                                            .clickable {
                                                                onDeleteContract.invoke(c.id)
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Kontraktni o'chirish",
                                                            tint = StatusOverdue,
                                                            modifier = Modifier.size(20.dp)
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
    /** Дневная ставка из настроек — нужна для расчёта суммы контракта в списке
     *  контрактов под календарём (dailyPrice × days). Берётся из SettingsRepository. */
    dailyPrice: Double = 0.0,
    scooters: List<Scooter> = emptyList(),
    activeRenters: List<Renter> = emptyList(),
    /**
     * ID арендаторов, которые находятся в архиве (STOP_MARKER в прошлом
     * без последующего RESUME_MARKER). Их скутеры считаются СВОБОДНЫМИ
     * для выбора новым арендатором, потому что аренда фактически остановлена.
     *
     * Без этого параметра: скутер «зависает» за архивным арендатором
     * (isReturned=false, но STOP в прошлом) и пользователь не может
     * выбрать его в форме — выглядит как «все скутеры заняты».
     */
    archivedRenterIds: Set<Int> = emptySet(),
    /**
     * Существующие контракты арендатора (ContractHistoryEntry с type=CREATED/AUTO_RENEW).
     * Передаются родителем из contractHistoryViewModel.contractsForRenter(renterId).
     *
     * Используются для инициализации календаря в режиме редактирования: каждый
     * контракт отображается как цветная группа в календаре (зелёная = оплачен,
     * красная = долг) и как элемент в списке под календарём. Пользователь может
     * удалять существующие контракты и добавлять новые — все изменения
     * применяются при сохранении формы.
     *
     * В режиме создания (initialRenter == null) список должен быть пустым.
     */
    existingContracts: List<com.example.data.ContractHistoryEntry> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (RenterFormResult) -> Unit,
    // ── Inline-создание скутера ────────────────────────────────────────────
    // Когда пользователь нажимает «+ Yangi skuter yaratish» в конце списка
    // скутеров, внизу формы арендатора появляются поля для ввода данных
    // нового скутера. При сохранении вызывается этот callback — родитель
    // вызывает scooterViewModel.addScooter (теперь suspend, возвращает id
    // свежесозданной записи), и мы СРАЗУ привязываем renter.scooterId к
    // этому id. Раньше скутер создавался асинхронно, и renter сохранялся
    // с scooterId=null — баг, который мы здесь исправляем.
    //
    // Возвращает id нового скутера (>0) или -1/null при ошибке.
    onCreateScooterInline: suspend (
        name: String,
        documentedNumber: String?,
        vinNumber: String,
        engineNumber: String,
        scooterSerialNumber: String,
        batteryId1: String,
        batteryId2: String,
        additionalInfo: String
    ) -> Int = { _, _, _, _, _, _, _, _ -> -1 },
    /**
     * Каскадное удаление существующего контракта из БД.
     *
     * Вызывается, когда пользователь нажимает «✕» на существующем контракте
     * (existingContractId != null) в списке под календарём. Родитель
     * делегирует в contractHistoryViewModel.deleteContract(id), который
     * каскадно удаляет: сам контракт + все Transaction с contractId = id +
     * все CardTransaction с contractId = id + корректирует баланс арендатора
     * и баланс главной карты (ренверс суммы контракта и реверс доходов карты).
     *
     * После завершения форма удаляет контракт из локального state
     * (contractGroups), чтобы UI сразу отразил удаление.
     *
     * Для новых контрактов (existingContractId == null) этот callback НЕ
     * вызывается — они ещё не в БД, достаточно удалить из локального state.
     */
    onDeleteExistingContract: suspend (Int) -> Unit = {}
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

    // ── Поле autoRenewMode удалено из UI ────────────────────────────────
    // Переключатель Manual/Auto и его столбец в таблице арендаторов удалены
    // по просьбе пользователя. Логика авто-продления теперь управляется
    // через маркеры Stop/Resume в календаре (см. ContractCalendar).
    // Поле Renter.autoRenewMode в БД сохраняется для совместимости — всегда
    // defaults to AUTO (старое поведение), но больше не редактируется из UI.
    val autoRenewMode: String = com.example.data.RenterAutoRenewMode.AUTO

    // ── Группы контрактов (новый календарь) ───────────────────────────
    // Список групп, выбранных пользователем в календаре. Если список не пуст,
    // он имеет приоритет над автоматической логикой по выбранной дате.
    //
    // В режиме редактирования инициализируется из existingContracts (загружаются
    // родителем из БД через contractHistoryViewModel.contractsForRenter). Каждый
    // существующий контракт становится группой с existingContractId — это
    // позволяет при сохранении отличить «удалить существующий» от «добавить новый».
    //
    // В режиме создания список пуст — пользователь собирает группы с нуля.
    var contractGroups by remember(initialRenter?.id, existingContracts) {
        val initial: List<ContractGroup> = if (initialRenter != null) {
            existingContracts
                .filter { it.weekStart != null && it.weekEnd != null }
                .sortedBy { it.weekStart ?: 0L }
                .mapIndexed { index, entry ->
                    // ── Нормализация к началу дня ─────────────────────────
                    // Контракты в БД могут иметь weekStart с произвольным часом
                    // (например 14:30, если созданы через календарь в 14:30).
                    // Это приводило к багу: контракт 04.08→11.08 отображался
                    // как 07.08→11.08, потому что ячейки календаря теперь имеют
                    // timestamp = 00:00, и сравнение dayMs >= g.startMs ломалось
                    // (ячейка 4 авг 00:00 < контракт 4 авг 14:30 → ячейка не
                    // попадала в период). Нормализуем к началу дня.
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = entry.weekStart!!
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    val normStart = cal.timeInMillis
                    cal.timeInMillis = entry.weekEnd!!
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                    cal.set(java.util.Calendar.MINUTE, 59)
                    cal.set(java.util.Calendar.SECOND, 59)
                    cal.set(java.util.Calendar.MILLISECOND, 999)
                    val normEnd = cal.timeInMillis
                    // ── Распознавание Stop/Resume маркеров ────────────────
                    // Записи TYPE_TERMINATED с notes="STOP_MARKER" и
                    // TYPE_RETURNED с notes="RESUME_MARKER" загружаются в форму
                    // как маркерные группы (isStopMarker / isResumeMarker = true).
                    // Это позволяет корректно отобразить их в календаре и в
                    // списке контрактов ниже при повторном открытии формы.
                    // Раньше они загружались как обычные контракты, что
                    // приводило к визуальным багам и потере маркеров при
                    // следующем сохранении.
                    val isStopMarker = entry.type == com.example.data.ContractHistoryEntry.TYPE_TERMINATED &&
                                       entry.notes == "STOP_MARKER"
                    val isResumeMarker = entry.type == com.example.data.ContractHistoryEntry.TYPE_RETURNED &&
                                         entry.notes == "RESUME_MARKER"
                    ContractGroup(
                        id = index + 1,
                        startMs = normStart,
                        endMs = normEnd,
                        isPaid = entry.isPaid,
                        existingContractId = entry.id,
                        isStopMarker = isStopMarker,
                        isResumeMarker = isResumeMarker,
                        // Сохраняем имя скутера из контракта, чтобы показать
                        // его над периодом в списке контрактов под календарём
                        // (требование пользователя). ID скутера берём из
                        // initialRenter.scooterId как fallback (контракт в БД
                        // не хранит scooterId, только scooterName).
                        scooterId = initialRenter?.scooterId,
                        scooterName = entry.scooterName ?: initialRenter?.scooterName
                    )
                }
        } else emptyList()
        mutableStateOf(initial)
    }
    var activeGroupId by remember { mutableStateOf<Int?>(null) }

    // ── PDF-реквизиты арендатора ────────────────────────────────────────
    var passportData by remember { mutableStateOf(initialRenter?.passportData ?: "") }
    var address by remember { mutableStateOf(initialRenter?.address ?: "") }
    var pinfl by remember { mutableStateOf(initialRenter?.pinfl ?: "") }

    // ── Проверка дубликатов (красная рамка при совпадении с БД) ────────
    // Каждое поле проверяет свой собственный столбец в existing renters.
    // В режиме редактирования текущий renter (по id) исключается из проверки —
    // иначе поле всегда «горело» бы красным при открытии формы.
    // Сравнение регистронезависимое и по trim — чтобы «Akmal» и « akmal »
    // считались одинаковыми.
    val editRenterId = initialRenter?.id
    val isNameDuplicate = name.trim().isNotEmpty() &&
        activeRenters.any { it.id != editRenterId && it.name.trim().equals(name.trim(), ignoreCase = true) }
    val normalizedPhone = phone.trim().filter { it.isDigit() }
    val isPhoneDuplicate = normalizedPhone.isNotEmpty() &&
        activeRenters.any {
            it.id != editRenterId &&
            it.phoneNumber.trim().filter { d -> d.isDigit() }.takeLast(9) == normalizedPhone
        }
    val isPassportDuplicate = passportData.trim().isNotEmpty() &&
        activeRenters.any {
            it.id != editRenterId &&
            it.passportData.trim().equals(passportData.trim(), ignoreCase = true)
        }
    val isPinflDuplicate = pinfl.trim().isNotEmpty() &&
        activeRenters.any { it.id != editRenterId && it.pinfl.trim() == pinfl.trim() }
    // Цвет рамки для полей с дубликатом — ярко-красный, иначе стандартный.
    val errorBorder = StatusOverdue
    val dupFocused = StatusOverdue
    val dupUnfocused = StatusOverdue

    // Примечание: реквизиты скутера (VIN, двигатель, ID, аккумы, доп. инфо)
    // заполняются в ScooterFormDialog — это атрибуты скутера, а не арендатора.
    // При создании контракта они автоматически подтягиваются из БД по scooterId.

    // ── ПОЛЕ «КОЛИЧЕСТВО НЕДЕЛЬ» УДАЛЕНО ──────────────────────────────────
    // Раньше durationOptions / selectedDurationText / expandedDuration
    // использовались для dropdown-меню «Ijara muddati» (1 Hafta / 2 Hafta /
    // 1 Oy / ...). Удалено по просьбе пользователя — срок аренды теперь
    // задаётся только через календарь контрактов ниже. Поле `duration`
    // остаётся для совместимости с RenterFormResult (по умолчанию "7").

    var selectedScooterId by remember { mutableStateOf<Int?>(initialRenter?.scooterId) }
    var expandedScooter by remember { mutableStateOf(false) }

    // ── Защита от авто-сброса выбранного скутера ──────────────────────
    // Запоминаем «пользовательский выбор» скутера в этой сессии диалога.
    // Используется в LaunchedEffect(rentedScooterIds) ниже, чтобы НЕ
    // сбрасывать selectedScooterId автоматически, если пользователь сам
    // выбрал скутер (даже если он оказался в rentedScooterIds из-за
    // race condition с обновлением Flow). Без этого были бы баги:
    //   • В режиме создания пользователь выбрал скутер → Flow обновился →
    //     скутер попал в rentedScooterIds → выбор сбросился в null.
    //   • Только что созданный через inline-форму скутер мог исчезнуть
    //     из selectedScooterId при первом же обновлении Flow.
    var userPickedScooterId by remember { mutableStateOf<Int?>(null) }

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
                // Запоминаем как пользовательский выбор, чтобы LaunchedEffect
                // сброса не удалил только что созданный пользователем скутер.
                userPickedScooterId = match.id
                pendingScooterName = null
            }
        }
    }

    val isEdit = initialRenter != null

    // Вычисляем ID скутеров, которые уже арендованы активными арендаторами
    // (исключаем текущего арендатора при редактировании — его скутер должен быть доступен).
    //
    // ВАЖНО: арендаторы в архиве (STOP_MARKER в прошлом без RESUME) НЕ считаются
    // «активными арендаторами» для целей выбора скутера — их скутеры фактически
    // свободны, даже если isReturned=false. Без этого исключения пользователь не
    // сможет выбрать скутер, который числится за архивным арендатором.
    val rentedScooterIds = activeRenters
        .filter { it.scooterId != null && !it.isReturned && it.id != initialRenter?.id }
        .filter { it.id !in archivedRenterIds }
        .mapNotNull { it.scooterId }
        .toSet()

    // Доступные скутеры = не арендованные + текущий скутер арендатора (при редактировании)
    val availableScooters = scooters.filter { scooter ->
        scooter.id !in rentedScooterIds
    }

    // Если выбранный скутер уже арендован другим — сбрасываем выбор
    // ТОЛЬКО если пользователь не выбирал его явно. После явного выбора
    // (userPickedScooterId != null) мы доверяем выбору пользователя.
    // Это предотвращает баг: пользователь выбрал скутер → Flow обновился →
    // скутер попал в rentedScooterIds → выбор сбросился в null.
    LaunchedEffect(rentedScooterIds) {
        if (selectedScooterId != null &&
            selectedScooterId in rentedScooterIds &&
            selectedScooterId != userPickedScooterId &&
            selectedScooterId != initialRenter?.scooterId
        ) {
            selectedScooterId = null
        }
    }

    val scrollState = rememberScrollState()
    // Корутин-скоуп для асинхронных операций внутри диалога — в частности,
    // для ожидания завершения inline-создания скутера перед сохранением
    // арендатора (чтобы привязать renter.scooterId к свежесозданному id).
    val dialogScope = rememberCoroutineScope()

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
                    isError = isNameDuplicate,
                    supportingText = {
                        if (isNameDuplicate) {
                            Text(
                                "Bunday ism allaqachon mavjud!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isNameDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isNameDuplicate) dupFocused else ClaudeTextSecondary
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
                    shape = RoundedCornerShape(8.dp),
                    isError = isPhoneDuplicate,
                    supportingText = {
                        if (isPhoneDuplicate) {
                            Text(
                                "Bu raqam allaqachon ro'yxatdan o'tgan!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isPhoneDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isPhoneDuplicate) dupFocused else ClaudeTextSecondary
                    )
                )

                HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
                SectionLabel("Ижара шартлари")

                // ── Календарь с группами контрактов ───────────────────────────
                // Полностью заменяет старую кнопку выбора даты. Пользователь:
                //   • Нажимает «+» в правом верхнем углу календаря.
                //   • Выбирает статус (To'langan / To'lanmagan) кнопками.
                //   • Тап по первой дате → тап по второй → создаётся группа.
                //   • Можно выбрать ту же дату дважды — система создаст
                //     однодневный период (как старый календарь одной даты).
                //   • Можно создать несколько групп (вкладки 1, 2, 3...).
                //   • У каждой вкладки есть «x» для удаления группы.
                // При сохранении формы группы передаются в RenterFormResult
                // и используются в addRenter. Если группы пусты — startTimestamp
                // остаётся как сегодня (default) и работает автоматическая
                // логика по дате.
                // ── Вычисляем выбран ли скутер ДО календаря ─────────────────
                // Нужно для передачи scooterSelected в ContractCalendar —
                // календарь блокирует тапы по дням пока скутер не выбран
                // (по требованию пользователя: «должен будет в обязательном
                // порядке выбрать скутер с помощью нашей кнопки и только
                // потом в календаре выбрать период»).
                val scooterSelectedForCalendar = selectedScooterId != null

                // ── Кнопка выбора скутера (НАД календарём) ──────────────────
                // По просьбе пользователя: «снизу календаря сверху списка
                // контрактов сделал кнопку выбора скутера...». Но позже
                // пользователь уточнил: скутер должен выбираться ДО выбора
                // периода в календаре (статус → скутер → период). Поэтому
                // кнопка перенесена НАД календарь.
                //
                // Логика работы:
                //   • Пользователь сначала выбирает статус (Paid/Unpaid или
                //     Stop/Resume) кнопками в календаре.
                //   • Затем ОБЯЗАТЕЛЬНО выбирает скутер этой квадратной кнопкой.
                //   • Только потом выбирает период в календаре.
                //   • Созданный контракт привязывается и к скутеру, и к арендатору.
                //   • При Stop-маркере в последний день последнего контракта —
                //     скутер освобождается (см. scooterStatusOf).
                //
                // Для Stop/Resume маркеров скутер можно НЕ выбирать — это
                // опционально. Но если выбран, он укажет, с какого скутера
                // будут создаваться автоматические контракты после Resume.
                val selectedScooter = availableScooters.find { it.id == selectedScooterId }
                    ?: scooters.find { it.id == selectedScooterId }
                val scooterText = selectedScooter?.name ?: "Tanlanmagan"
                val scooterSelected = selectedScooter != null

                // ── ВАЖНО: исправлен баг с невозможностью открыть dropdown скутера ──
                    // Раньше здесь был ExposedDropdownMenuBox с onExpandedChange
                    // { !expandedScooter } И внутренний Box с .clickable
                    // { !expandedScooter } — это вызывало двойной toggle:
                    //   1. .clickable срабатывал → expandedScooter = !expandedScooter
                    //   2. menuAnchor() + onExpandedChange срабатывал тоже →
                    //      expandedScooter = !expandedScooter (ещё раз)
                    // Итог: состояние возвращалось в исходное → dropdown НЕ открывался.
                    //
                    // Решение: вместо ExposedDropdownMenuBox используем простой
                    // Box + DropdownMenu (базовый компонент Material3). Это
                    // надёжнее: нет двойных toggle, нет проблем с menuAnchor()
                    // на Box (вместо OutlinedTextField). Тактильная отдача
                    // добавлена чтобы пользователь физически чувствовал что тап
                    // зарегистрирован (по жалобе «ничего не происходит»).
                val hapticScooter = androidx.compose.ui.platform.LocalHapticFeedback.current

                Box(modifier = Modifier.fillMaxWidth()) {
                    // ── Квадратная кнопка-плитка в стиле Paid/Unpaid ──
                    // Высота 64dp, fillMaxWidth, фон — ClaudeAccentBg если не
                    // выбран, StatusOk если выбран. .clickable открывает/закрывает
                    // dropdown.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (scooterSelected) StatusOk.copy(alpha = 0.35f) else ClaudeAccentBg)
                            .border(
                                width = if (scooterSelected) 2.dp else 1.dp,
                                color = if (scooterSelected) StatusOk else ClaudeDivider,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                hapticScooter.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                )
                                expandedScooter = !expandedScooter
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Иконка скутера
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (scooterSelected) StatusOk else ClaudeAccent)
                                    .border(1.dp, ClaudeDivider, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.DirectionsBike,
                                    contentDescription = null,
                                    tint = if (scooterSelected) ClaudeText else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            // Текст: имя скутера или «Tanlanmagan»
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Skuter",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTextSecondary
                                )
                                Text(
                                    text = scooterText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (scooterSelected) ClaudeText else ClaudeTextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Стрелка выпадающего списка
                            Icon(
                                imageVector = if (expandedScooter) Icons.Default.KeyboardArrowUp
                                              else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expandedScooter) "Yopish" else "Tanlash",
                                tint = ClaudeTextSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    // ── Dropdown со списком скутеров ──
                    // DropdownMenu позиционируется относительно родительского Box.
                    // Ширина — по умолчанию wrap-content, но мы задаём
                    // fillMaxWidth через modifier, чтобы dropdown был не уже кнопки.
                    DropdownMenu(
                        expanded = expandedScooter,
                        onDismissRequest = { expandedScooter = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanlanmagan") },
                            onClick = {
                                selectedScooterId = null
                                userPickedScooterId = null
                                expandedScooter = false
                            }
                        )
                        availableScooters.forEach { scooter ->
                            DropdownMenuItem(
                                text = { Text(scooter.name) },
                                onClick = {
                                    selectedScooterId = scooter.id
                                    // Запоминаем как пользовательский выбор, чтобы
                                    // LaunchedEffect(rentedScooterIds) не сбросил
                                    // его автоматически при обновлении Flow.
                                    userPickedScooterId = scooter.id
                                    expandedScooter = false
                                }
                            )
                        }
                        // ── Занятые скутеры (показываем с пометкой «Band») ────
                        // Пользователь явно просил: «когда тот или иной скутер
                        // будет занят» — то есть показывать занятые скутеры тоже,
                        // чтобы было видно, какие скутеры уже арендованы. Они
                        // отображаются серым цветом с красной пометкой «Band»
                        // (занят) и именем арендатора, который их занял.
                        // disabled — выбрать нельзя.
                        val occupiedScooters = scooters.filter { it.id !in availableScooters.map { s -> s.id } }
                        if (occupiedScooters.isNotEmpty()) {
                            HorizontalDivider(color = ClaudeDivider, thickness = 1.dp)
                            occupiedScooters.forEach { scooter ->
                                val occupier = activeRenters.firstOrNull {
                                    it.scooterId == scooter.id && !it.isReturned
                                }
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                scooter.name,
                                                color = ClaudeTextSecondary,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = StatusOverdueBg
                                            ) {
                                                Text(
                                                    text = "Band",
                                                    color = StatusOverdue,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                            if (occupier != null) {
                                                Text(
                                                    "· ${occupier.name}",
                                                    color = ClaudeTextSecondary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    },
                                    enabled = false,
                                    onClick = {}
                                )
                            }
                        }
                        // ── Кнопка «+ Yangi skuter yaratish» в самом низу ────
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
                } // end outer Box

                // ── Плашка с именем выбранного скутера над календарём ──────
                // По просьбе пользователя: «в календаре должен показываться
                // скутер имя скутера сверху выбранного периода контракта к
                // которому прикреплён этот скутер». Если скутер выбран —
                // показываем компактную полоску с иконкой и именем над
                // календарём, чтобы было видно, к какому скутеру будут
                // привязаны новые периоды.
                if (scooterSelected) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StatusOk.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusOk.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = StatusOk,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tanlangan skuter:",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                            Text(
                                text = scooterText,
                                style = MaterialTheme.typography.labelMedium,
                                color = StatusOk,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                ContractCalendar(
                    editable = true,
                    scooterSelected = scooterSelectedForCalendar,
                    selectedScooterId = selectedScooterId,
                    selectedScooterName = selectedScooter?.name,
                    groups = contractGroups,
                    activeGroupId = activeGroupId,
                    onGroupsChange = { contractGroups = it },
                    onActiveGroupChange = { activeGroupId = it }
                )

                // ── Список контрактов под календарём ──────────────────────────
                // Показывает все группы (как существующие из БД, так и новые,
                // только что созданные пользователем в календаре). Каждая строка:
                //   [статус-пилюля] [дата начала → дата окончания] [✕ удалить]
                //
                // Существующие контракты помечаются «№<id>», новые — «Yangi».
                // Это нужно, чтобы пользователь видел, какие контракты уже есть
                // в БД (и будут сохранены как есть или удалены), а какие только
                // что добавлены (и будут созданы при сохранении формы).
                //
                // Раньше календарь в режиме редактирования показывал «Kontraktlar
                // yo'q» даже если у арендатора были контракты в БД — это был баг:
                // contractGroups инициализировался пустым списком и никогда не
                // загружался из existingContracts. Теперь список загружается
                // родителем через contractHistoryViewModel.contractsForRenter.
                if (contractGroups.isNotEmpty()) {
                    val dateFmtList = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
                    // ── Список контрактов под календарём — стиль раскрытой карточки ──
                    // По просьбе пользователя: «возьми визуально как это выглядит
                    // и примени его к окну создания и изменения арендатора внизу
                    // календаря контрактам их внешнему виду». Используем ту же
                    // структуру что и в RenterListItem при раскрытии: карточка
                    // с заголовком-таблицей (№ | # | Muddat (hafta) | Summa) и
                    // строками, в каждой из которых есть кнопка переключения
                    // статуса (Check/Remove) и кнопка удаления (✕).
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFAFAF7),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Kontraktlar (${contractGroups.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = ClaudeAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                            // ── Заголовок таблицы ──
                            Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("№", modifier = Modifier.weight(0.3f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("#", modifier = Modifier.weight(0.4f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("Muddat", modifier = Modifier.weight(1.8f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("Summa", modifier = Modifier.weight(1.0f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary, fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.End, maxLines = 1)
                                    // Место под 2 кнопки (toggle + delete) — 36dp + 8dp + 36dp
                                    Spacer(modifier = Modifier.width(80.dp))
                                }
                            }
                            HorizontalDivider(color = ClaudeDivider)

                            // ── Строки контрактов ──
                            contractGroups.forEachIndexed { idx, group ->
                                val startDate = dateFmtList.format(java.util.Date(group.startMs))
                                val endDate = dateFmtList.format(java.util.Date(group.endMs))
                                val statusLabel = when {
                                    group.isStopMarker -> "To'xtash"
                                    group.isResumeMarker -> "Davom"
                                    group.isPaid -> "To'langan"
                                    else -> "To'lanmagan"
                                }
                                val statusColor = when {
                                    group.isStopMarker -> Color(0xFF1E3A8A)
                                    group.isResumeMarker -> Color(0xFFFACC15)
                                    group.isPaid -> StatusOk
                                    else -> StatusOverdue
                                }
                                val displayIdLabel = when {
                                    group.isStopMarker -> "STOP"
                                    group.isResumeMarker -> "RESUME"
                                    group.existingContractId != null -> "#${group.existingContractId}"
                                    else -> "Yangi"
                                }
                                // Сумма для обычных контрактов — dailyPrice × days,
                                // для маркеров — 0.
                                val contractSum = when {
                                    group.isStopMarker || group.isResumeMarker -> 0.0
                                    else -> {
                                        val dayMs = 24L * 60 * 60 * 1000
                                        val days = if (group.endMs > group.startMs) {
                                            kotlin.math.ceil((group.endMs - group.startMs).toDouble() / dayMs).toInt()
                                        } else 1
                                        val daily = if (dailyPrice > 0) dailyPrice
                                                    else com.example.data.SettingsRepository.DEFAULT_DAILY_PRICE
                                        daily * days
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ClaudeCard)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // № — порядковый номер
                                    Text(
                                        "${idx + 1}",
                                        modifier = Modifier.weight(0.3f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    // #ID / STOP / RESUME / Yangi
                                    Text(
                                        displayIdLabel,
                                        modifier = Modifier.weight(0.4f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTextSecondary,
                                        maxLines = 1
                                    )
                                    // Muddat — дата → дата + статус-точка
                                    // СВЕРХУ периода: имя скутера, привязанного
                                    // к этому контракту (требование пользователя:
                                    // «в календаре должен показываться скутер имя
                                    // скутера сверху выбранного периода контракта
                                    // к которому прикреплён этот скутер»).
                                    Column(modifier = Modifier.weight(1.8f)) {
                                        // ── Имя скутера над периодом ──
                                        // Берём из группы (для новых — выбранный
                                        // в форме скутер; для существующих — из БД).
                                        val groupScooterName = group.scooterName
                                            ?: selectedScooter?.name
                                            ?: initialRenter?.scooterName
                                        if (!groupScooterName.isNullOrBlank()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DirectionsBike,
                                                    contentDescription = null,
                                                    tint = ClaudeAccent,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Text(
                                                    text = groupScooterName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ClaudeAccent,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                        }
                                        Text(
                                            text = "$startDate → $endDate",
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
                                        }
                                    }
                                    // Summa
                                    Text(
                                        if (contractSum > 0) "${contractSum.toLong()}" else "—",
                                        modifier = Modifier.weight(1.0f),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        maxLines = 1
                                    )
                                    // ── Кнопка переключения статуса ──
                                    // Check = оплачен, Remove = неоплачен,
                                    // PlayArrow = Stop (продолжить), Pause = Resume (остановить).
                                    val toggleIcon = when {
                                        group.isStopMarker -> Icons.Default.PlayArrow
                                        group.isResumeMarker -> Icons.Default.Pause
                                        group.isPaid -> Icons.Default.Check
                                        else -> Icons.Default.Remove
                                    }
                                    val toggleTint = when {
                                        group.isStopMarker -> Color(0xFFFACC15)
                                        group.isResumeMarker -> Color(0xFF1E3A8A)
                                        group.isPaid -> StatusOk
                                        else -> StatusOverdue
                                    }
                                    val toggleBg = when {
                                        group.isStopMarker -> Color(0xFFFACC15).copy(alpha = 0.25f)
                                        group.isResumeMarker -> Color(0xFF1E3A8A).copy(alpha = 0.25f)
                                        group.isPaid -> StatusOk.copy(alpha = 0.15f)
                                        else -> StatusOverdue.copy(alpha = 0.15f)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(toggleBg)
                                            .border(1.dp, ClaudeDivider, RoundedCornerShape(8.dp))
                                            .clickable {
                                                val updated = contractGroups.map { g ->
                                                    if (g.id == group.id) {
                                                        when {
                                                            g.isStopMarker -> g.copy(isStopMarker = false, isResumeMarker = true)
                                                            g.isResumeMarker -> g.copy(isStopMarker = true, isResumeMarker = false)
                                                            else -> g.copy(isPaid = !g.isPaid)
                                                        }
                                                    } else g
                                                }
                                                contractGroups = updated
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = toggleIcon,
                                            contentDescription = "Statusni almashtirish",
                                            tint = toggleTint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    // ── Кнопка удаления контракта (✕) ──
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ClaudeAccentBg)
                                            .border(1.dp, ClaudeDivider, RoundedCornerShape(8.dp))
                                            .clickable {
                                                val existingId = group.existingContractId
                                                if (existingId != null) {
                                                    dialogScope.launch {
                                                        onDeleteExistingContract(existingId)
                                                        contractGroups = contractGroups.filterNot { it.id == group.id }
                                                        if (activeGroupId == group.id) activeGroupId = null
                                                    }
                                                } else {
                                                    contractGroups = contractGroups.filterNot { it.id == group.id }
                                                    if (activeGroupId == group.id) activeGroupId = null
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Kontraktni o'chirish",
                                            tint = StatusOverdue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
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

                // ── ПОЛЕ «КОЛИЧЕСТВО НЕДЕЛЬ» УДАЛЕНО ──────────────────────────
                // Раньше здесь был ExposedDropdownMenuBox с выбором «1 Hafta»
                // (7 дней), «2 Hafta», «1 Oy» и т.д. Пользователь явно попросил
                // убрать это поле — теперь срок аренды определяется только
                // периодами, выбранными в календаре контрактов ниже.
                // Если пользователь не выбрал ни одного периода в календаре,
                // используется startDate по умолчанию + 7 дней (legacy behavior).

                // ── Старый блок выбора скутера удалён ────────────────────────
                // Раньше здесь был ExposedDropdownMenuBox с выбором скутера.
                // По просьбе пользователя кнопка выбора скутера перенесена
                // выше — под календарь, над списком контрактов, в виде
                // большой квадратной плитки (см. код выше после ContractCalendar).
                // Здесь остаётся только поле долга.

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
                    shape = RoundedCornerShape(8.dp),
                    isError = isPassportDuplicate,
                    supportingText = {
                        if (isPassportDuplicate) {
                            Text(
                                "Bu passport allaqachon ro'yxatdan o'tgan!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isPassportDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isPassportDuplicate) dupFocused else ClaudeTextSecondary
                    )
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
                    shape = RoundedCornerShape(8.dp),
                    isError = isPinflDuplicate,
                    supportingText = {
                        if (isPinflDuplicate) {
                            Text(
                                "Bu JSHSHR allaqachon ro'yxatdan o'tgan!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isPinflDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isPinflDuplicate) dupFocused else ClaudeTextSecondary
                    )
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
                    // ── Подсказка: скутер сохранится вместе с арендатором ────
                    // Дублирующие кнопки «Skuterni saqlash» / «Bekor» убраны —
                    // они копируют функционал основных кнопок «Saqla» / «Bekor»
                    // внизу диалога. Теперь при нажатии основной «Saqla»
                    // сначала создаётся скутер (если поля заполнены и
                    // showCreateScooterInline = true), затем арендатор.
                    Text(
                        text = "«Saqla» tugmasi bilan skuter ham saqlanadi",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeTextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
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
                    // ── Inline-создание скутера должно завершиться ДО того, ─────
                    // как мы сохраняем арендатора — иначе renter сохранится с
                    // scooterId=null (старый баг). Запускаем корутину и внутри
                    // неё ждём id свежесозданного скутера, после чего передаём
                    // его в onSave через RenterFormResult.scooterId.
                    dialogScope.launch {
                        var effectiveScooterId = selectedScooterId
                        var effectiveScooterName: String? =
                            scooters.find { it.id == selectedScooterId }?.name

                        if (showCreateScooterInline) {
                            val nameToSave = newScooterName.trim()
                                .ifBlank {
                                    val nextN = (scooters
                                        .mapNotNull {
                                            it.name.removePrefix("Skillmax-")
                                                .trimStart('0').toIntOrNull()
                                        }
                                        .maxOrNull() ?: 0) + 1
                                    "Skillmax-" + nextN.toString().padStart(3, '0')
                                }
                            // Создаём скутер и ждём возврата id.
                            val newScooterId = onCreateScooterInline(
                                nameToSave,
                                newScooterDocNum.trim().ifBlank { null },
                                newScooterVin.trim(),
                                newScooterEngine.trim(),
                                newScooterSerial.trim(),
                                newScooterBatt1.trim(),
                                newScooterBatt2.trim(),
                                newScooterInfo.trim()
                            )
                            if (newScooterId > 0) {
                                effectiveScooterId = newScooterId
                                effectiveScooterName = nameToSave
                                // Обновляем локальный state — чтобы при повторном
                                // сохранении или перерисовке форма показывала
                                // выбранный скутер.
                                selectedScooterId = newScooterId
                                // Запоминаем как пользовательский выбор, чтобы
                                // LaunchedEffect сброса не удалил его.
                                userPickedScooterId = newScooterId
                            }
                            pendingScooterName = nameToSave
                            showCreateScooterInline = false
                        } else if (effectiveScooterName == null && pendingScooterName != null) {
                            // Скутер был создан ранее в этой же сессии диалога,
                            // но selectedScooterId ещё не подхватился из Flow.
                            // Используем pendingScooterName как fallback.
                            effectiveScooterName = pendingScooterName
                        }

                        val debtValue = debt.toDoubleOrNull() ?: 0.0
                        val durationValue = duration.toIntOrNull() ?: 7
                        val phoneToSave = if (phone.isBlank()) "" else "+998$phone"
                        onSave(
                            RenterFormResult(
                                name = name,
                                phone = phoneToSave,
                                debt = debtValue,
                                duration = durationValue,
                                startTimestamp = startTimestamp,
                                // Теперь scooterId реально указывает на
                                // свежесозданный скутер (если он был создан).
                                scooterId = effectiveScooterId,
                                scooterName = effectiveScooterName,
                                isActive = isActive,
                                passportData = passportData.trim(),
                                address = address.trim(),
                                pinfl = pinfl.trim(),
                                autoRenewMode = autoRenewMode,
                                contractGroups = contractGroups.map { Triple(it.startMs, it.endMs, it.isPaid) },
                                // Передаём полный список групп с existingContractId —
                                // updateRenterWithContracts использует его для
                                // корректного реконсиалирования (удаление существующих,
                                // добавление новых, обновление статуса оплаты).
                                contractGroupsWithIds = contractGroups.map {
                                    RenterFormContractGroup(
                                        existingId = it.existingContractId,
                                        startMs = it.startMs,
                                        endMs = it.endMs,
                                        isPaid = it.isPaid,
                                        isStopMarker = it.isStopMarker,
                                        isResumeMarker = it.isResumeMarker,
                                        // Передаём скутер, выбранный пользователем
                                        // в момент создания группы. Для существующих
                                        // контрактов это поле ignored (в БД уже есть
                                        // scooterName). Для новых — сохраняется в БД.
                                        scooterId = it.scooterId ?: effectiveScooterId,
                                        scooterName = it.scooterName ?: effectiveScooterName
                                    )
                                }
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

/**
 * Форматирует ISO 8601 дату публикации релиза GitHub в человекочитаемый вид.
 *
 * GitHub API возвращает время в формате "2025-01-15T12:34:56Z".
 * Преобразуем в "15.01.2025 12:34" (день.месяц.год часы:минуты).
 * При ошибке парсинга возвращает исходную строку.
 */
private fun formatReleaseDate(isoDate: String): String {
    return try {
        // Убираем суффикс 'Z' и парсим как LocalDateTime
        val cleaned = isoDate.replace("Z", "")
        val ldt = java.time.LocalDateTime.parse(cleaned)
        val day = ldt.dayOfMonth.toString().padStart(2, '0')
        val month = ldt.monthValue.toString().padStart(2, '0')
        val year = ldt.year
        val hour = ldt.hour.toString().padStart(2, '0')
        val minute = ldt.minute.toString().padStart(2, '0')
        "$day.$month.$year $hour:$minute"
    } catch (_: Exception) {
        // Fallback: если формат не такой — возвращаем первые 16 символов
        // ("2025-01-15T12:34" — тоже читаемо) или всю строку если короче.
        isoDate.take(16).replace("T", " ")
    }
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
    /**
     * Режим авто-продления контракта:
     *   • [com.example.data.RenterAutoRenewMode.MANUAL] — система НЕ создаёт
     *     контракты автоматически при окончании последнего контракта.
     *   • [com.example.data.RenterAutoRenewMode.AUTO] — система автоматически
     *     создаёт новый контракт (AUTO_RENEW) при наступлении дня окончания
     *     последнего контракта.
     *
     * По умолчанию AUTO — автоматическое создание контрактов для новых арендаторов.
     */
    val autoRenewMode: String = com.example.data.RenterAutoRenewMode.AUTO,
    // Группы контрактов, выбранные в календаре (если пусто — используется
    // автоматическая логика по выбранной дате). Каждая группа =
    // Triple<startMs, endMs, isPaid>.
    //
    // ВАЖНО: для существующих контрактов (загруженных из БД при редактировании)
    // existingContractId содержит ID контракта в БД — это позволяет
    // updateRenterWithContracts отличить «удалить существующий» от «добавить новый».
    // Для новых контрактов (созданных пользователем в календаре) existingContractId = null.
    val contractGroups: List<Triple<Long, Long, Boolean>> = emptyList(),
    /**
     * Полная информация о группах контрактов с existingContractId.
     * Используется в режиме редактирования (updateRenterWithContracts) для
     * корректного применения изменений: удаления существующих, добавления новых,
     * обновления статуса оплаты. Каждый элемент: existingId / startMs / endMs / isPaid.
     *
     * В режиме создания (addRenter) этот список игнорируется — там используются
     * только contractGroups (старый формат Triple).
     */
    val contractGroupsWithIds: List<RenterFormContractGroup> = emptyList()
)

/**
 * Одна группа контрактов из формы арендатора с привязкой к существующему контракту.
 *
 * @param existingId ID контракта в БД (ContractHistoryEntry.id), если группа
 *                   загружена из существующего контракта. null — для новых групп,
 *                   созданных пользователем в календаре (их нужно вставить в БД).
 * @param startMs    Начало периода (миллисекунды).
 * @param endMs      Конец периода (миллисекунды).
 * @param isPaid     true = оплачен (зелёный), false = долг (красный).
 */
data class RenterFormContractGroup(
    val existingId: Int?,
    val startMs: Long,
    val endMs: Long,
    val isPaid: Boolean,
    /**
     * Маркер «остановки» аренды на этот день (однодневный).
     * Сохраняется в БД как ContractHistoryEntry с type=TERMINATED,
     * notes="STOP_MARKER". Скутер при этом освобождается.
     */
    val isStopMarker: Boolean = false,
    /**
     * Маркер «возобновления» аренды с этого дня (однодневный).
     * Сохраняется в БД как ContractHistoryEntry с type=RETURNED,
     * notes="RESUME_MARKER". Запускает авто-создание неоплаченных
     * weekly-контрактов от этого дня вперёд до ближайшего Stop или
     * до +7 дней (см. applyResumeAutoContracts в RenterViewModel).
     */
    val isResumeMarker: Boolean = false,
    /**
     * ID скутера, привязанного к этой группе (для новых контрактов).
     * Для STOP/RESUME маркеров опционален, но если указан —
     * auto-контракты после RESUME будут созданы с этим скутером.
     * Для существующих контрактов ignored (в БД scooterName уже сохранён).
     */
    val scooterId: Int? = null,
    /**
     * Имя скутера для отображения в UI. Сохраняется в ContractHistoryEntry.scooterName.
     */
    val scooterName: String? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // ── Список всех релизов для выбора версии ─────────────────────────────
    // Старый поток «проверить обновление → показать latest → нажать Update»
    // заменён на: пользователь сам выбирает версию из списка всех релизов
    // GitHub. Список загружается по требованию через onLoadReleases().
    allReleases: List<UpdateInfo> = emptyList(),
    isLoadingReleases: Boolean = false,
    /**
     * Текст ошибки загрузки списка релизов на узбекском (null = ошибки нет).
     * Когда не null — UI показывает сообщение и кнопку «Qayta urinish».
     */
    releasesError: String? = null,
    onLoadReleases: () -> Unit = {},
    /**
     * Принудительный retry — чистит дисковый кэш и заново вызывает
     * fetchAllReleasesDetailed(). Используется кнопкой «Qayta urinish».
     */
    onRetryReleases: () -> Unit = {},
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
    // SMS avto-yuborish rejimi — darhol saqlanadi (Save bosishni kutmaydi).
    var smsAutoSend by remember { mutableStateOf(currentSmsAutoSend) }
    val settingsContext = LocalContext.current
    val settingsRepo = remember { com.example.data.SettingsRepository(settingsContext) }
    var paymeLink by remember { mutableStateOf(settingsRepo.paymeLink) }
    var callCenter by remember { mutableStateOf(settingsRepo.callCenter) }

    // ── Состояние выбора версии для обновления ───────────────────────────
    // Старый поток «проверить → latest → обновить» заменён на выбор версии
    // из списка всех релизов GitHub.
    //   isVersionListOpen — раскрыт ли dropdown со списком версий
    //   selectedRelease   — выбранная пользователем версия (или null)
    var isVersionListOpen by remember { mutableStateOf(false) }
    var selectedRelease by remember { mutableStateOf<UpdateInfo?>(null) }
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
    LaunchedEffect(template, weekly, paymeLink, callCenter, scooterPriceUsd, usdToUzsRate) {
        val dailyPrice = weekly.toDoubleOrNull() ?: 0.0
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
        // Передаём dailyPrice как weekly (метод onSave() ожидает 2 double,
        // но SettingsViewModel.updatePrices(weekly, monthly) внутри делит на 7
        // и берёт daily = weekly/7). Передаём weekly=daily*7 и monthly=daily*30
        // для совместимости со старой сигнатурой — SettingsViewModel корректно
        // извлечёт dailyPrice из weekly/7.
        val weeklyFromDaily = dailyPrice * 7.0
        val monthlyFromDaily = dailyPrice * 30.0
        onSave(template, weeklyFromDaily, monthlyFromDaily, paymeLink, callCenter)
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
                        label = { Text("1 kunlik narx (so'm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // ── Подсказка: автоматически рассчитанные ставки ─────────
                    // Показываем, сколько будет стоить неделя/месяц исходя из
                    // введённой дневной ставки. Помогает пользователю убедиться,
                    // что он ввёл правильную цифру (например, 60 000/день =
                    // 420 000/неделя = 1 800 000/месяц).
                    val dailyNum = weekly.toDoubleOrNull() ?: 0.0
                    if (dailyNum > 0) {
                        val week = dailyNum * 7
                        val month = dailyNum * 30
                        Text(
                            "Hisoblanadi: 1 hafta = ${week.toLong()} so'm, 1 oy = ${month.toLong()} so'm",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
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
                        "Mavjud teglar: {name}, {days}, {unpaidDays}, {unpaidCount}, {debt}, {payme}, {call}",
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
                            // Idle / ReadyToInstall — выбор версии из списка
                            // ──────────────────────────────────────────────
                            // Старый поток «проверить обновление → показать
                            // latest → нажать Yangila» удалён. Теперь пользова-
                            // тель сам выбирает версию из списка всех релизов
                            // GitHub. Список загружается по требованию при
                            // первом раскрытии.
                            //
                            // Визуально всё в одной строке:
                            //   1) Кнопка «Выберите версию» (если ничего не
                            //      выбрано) → клик раскрывает список версий
                            //      ниже.
                            //   2) После выбора версии кнопка показывает имя
                            //      выбранной версии, а рядом появляется кнопка
                            //      «Загрузить».
                            //   3) Клик по кнопке с выбранной версией снова
                            //      раскрывает список — можно поменять выбор.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // ── Кнопка выбора версии (или отображения выбранной)
                                OutlinedButton(
                                    onClick = {
                                        isVersionListOpen = !isVersionListOpen
                                        // Ленивая загрузка: подгружаем список
                                        // только при первом раскрытии, если он
                                        // ещё пуст и не загружается прямо сейчас.
                                        if (isVersionListOpen &&
                                            allReleases.isEmpty() &&
                                            !isLoadingReleases
                                        ) {
                                            onLoadReleases()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF000000)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        Color(0xFF000000)
                                    )
                                ) {
                                    if (selectedRelease != null) {
                                        // Внутри кнопки — имя выбранной версии
                                        Text(
                                            "v${selectedRelease!!.versionName}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text(
                                            if (isLoadingReleases) "Yuklanmoqda…"
                                            else "Versiyani tanlang",
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                // ── Кнопка «Загрузить» — появляется только
                                // после выбора версии. Рядом с кнопкой выбора.
                                if (selectedRelease != null) {
                                    SuccessButton(
                                        label = "Yuklab olish",
                                        icon = Icons.Default.FileDownload,
                                        onClick = { onStartUpdate(selectedRelease!!) }
                                    )
                                }
                            }
                            // ── Dropdown-список версий ───────────────────
                            if (isVersionListOpen) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(4.dp)) {
                                        when {
                                            isLoadingReleases -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        color = Color(0xFF000000),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        "Versiyalar ro'yxati yuklanmoqda…",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFF000000)
                                                    )
                                                }
                                            }
                                            allReleases.isEmpty() -> {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp)
                                                ) {
                                                    Text(
                                                        releasesError
                                                            ?: "Versiyalar topilmadi. Internet aloqasini tekshiring.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFFB00020)
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    OutlinedButton(
                                                        onClick = onRetryReleases,
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                            horizontal = 16.dp,
                                                            vertical = 6.dp
                                                        ),
                                                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                                            contentColor = Color(0xFF000000)
                                                        ),
                                                        border = androidx.compose.foundation.BorderStroke(
                                                            1.dp,
                                                            Color(0xFF000000)
                                                        )
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Refresh,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Qayta urinish")
                                                    }
                                                }
                                            }
                                            else -> {
                                                allReleases.forEach { release ->
                                                    val isSelected =
                                                        selectedRelease?.versionCode == release.versionCode
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(
                                                                if (isSelected) Color(0xFFE5E5E5)
                                                                else Color.Transparent
                                                            )
                                                            .clickable {
                                                                selectedRelease = release
                                                                isVersionListOpen = false
                                                            }
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // Галочка для выбранной версии
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = Color(0xFF000000),
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                        }
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            // Строка 1: номер версии + дата
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    "v${release.versionName}",
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color(0xFF000000)
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                if (release.publishDate.isNotBlank()) {
                                                                    Text(
                                                                        formatReleaseDate(release.publishDate),
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = Color(0xFF666666)
                                                                    )
                                                                }
                                                            }
                                                            // Строка 2: название коммита (release name)
                                                            if (release.releaseName.isNotBlank() &&
                                                                release.releaseName != release.versionName
                                                            ) {
                                                                Text(
                                                                    release.releaseName,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = Color(0xFF444444),
                                                                    maxLines = 2
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

/**
 * Определяет статус скутера: арендован или свободен.
 *
 * Новое правило (по просьбе пользователя): скутер считается СВОБОДНЫМ, если
 * у арендатора последний контракт имеет type=TERMINATED (статус «остановлен»).
 * Это соответствует логике Stop-маркера в календаре: если в последний день
 * последнего контракта выбран Stop — скутер освобождается и доступен для
 * аренды другим арендаторам.
 *
 * @param scooterId        ID скутера для проверки.
 * @param renters          Все арендаторы (для поиска активной аренды).
 * @param latestContractByRenter Карта ID арендатора → его последний контракт
 *   (по weekEnd). Используется для проверки, не остановлен ли арендатор.
 */
private fun scooterStatusOf(
    scooterId: Int,
    renters: List<Renter>,
    latestContractByRenter: Map<Int, com.example.data.ContractHistoryEntry> = emptyMap()
): ScooterStatus {
    // Арендатор считается активным, если:
    //   • не возвращён (!isReturned)
    //   • у него есть этот скутер (scooterId match)
    //   • последний контракт НЕ TERMINATED (не остановлен)
    val active = renters.any { renter ->
        renter.scooterId == scooterId &&
        !renter.isReturned &&
        latestContractByRenter[renter.id]?.type != com.example.data.ContractHistoryEntry.TYPE_TERMINATED
    }
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
    /**
     * Все контракты скутеров, сгруппированные по имени скутера (scooterName).
     * Используется для отображения раскрывающейся карточки контрактов под
     * строкой скутера (когда пользователь нажимает на стрелку раскрытия
     * в первом столбце). Сортировка — ASC по weekStart.
     * Аналог contractsByRenter в RenterTable.
     *
     * ВНИМАНИЕ: ContractHistoryEntry не имеет scooterId, только scooterName.
     * Это денормализованное поле, записанное в момент создания контракта —
     * используется и в ScooterContractHistoryScreen.
     */
    contractsByScooterName: Map<String, List<com.example.data.ContractHistoryEntry>> = emptyMap(),
    /**
     * Карта ID арендатора → его последний контракт (по weekEnd).
     * Используется в scooterStatusOf для определения, свободен ли скутер:
     * если у арендатора последний контракт TERMINATED (Stop-маркер в
     * календаре формы арендатора), скутер считается свободным.
     */
    latestContractByRenter: Map<Int, com.example.data.ContractHistoryEntry> = emptyMap(),
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

    // ВСЕГДА используем fixed widths + горизонтальный скролл — даже когда
    // скрыты все extra-колонки. Это гарантирует что имя скутера не будет
    // обрезано (maxLines=2 + softWrap позволяют переносу на 2 строки).
    val wExpand = 40.dp    // ← стрелка раскрытия контрактов (новая колонка перед №)
    val wNum    = 40.dp    // № — порядковый номер строки
    val wName   = 140.dp   // увеличено с 110 — вмещает «Skillmax-001» с запасом
    val wDoc    = 115.dp
    val wVin    = 140.dp
    val wEngine = 115.dp
    val wSerial = 95.dp
    val wBatt1  = 105.dp
    val wBatt2  = 105.dp
    val wExtra  = 150.dp
    val wStat   = 95.dp

    val hasAnyDetailVisible = showDoc || showVin || showEngine || showSerial ||
        showBatt1 || showBatt2 || showExtra
    val hScrollState = rememberScrollState()

    // ── Раскрытые строки ───────────────────────────────────────────────
    // Множество ID скутеров, у которых раскрыта встроенная карточка
    // контрактов. Управляется кнопкой-стрелкой в первом столбце. Локальный
    // state — не персистится между перезапусками (как в RenterTable).
    var expandedScooterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок — всегда horizontalScroll
        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(hScrollState)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Пустая ячейка в столбце стрелки раскрытия (шапка)
                Spacer(modifier = Modifier.width(wExpand))
                NonSortableHeaderCellFixed(Icons.Default.Numbers, wNum, "№")
                if (showName)   SortableHeaderCellFixed(Icons.Default.Label,         wName,   "col_name",  sortState) { onSortClick("col_name") }
                if (showDoc)    SortableHeaderCellFixed(Icons.Default.CreditCard,   wDoc,    "col_doc",    sortState) { onSortClick("col_doc") }
                if (showVin)    SortableHeaderCellFixed(Icons.Default.Numbers,      wVin,    "col_vin",    sortState) { onSortClick("col_vin") }
                if (showEngine) SortableHeaderCellFixed(Icons.Default.Build,         wEngine, "col_engine", sortState) { onSortClick("col_engine") }
                if (showSerial) SortableHeaderCellFixed(Icons.Default.Tag,           wSerial, "col_serial", sortState) { onSortClick("col_serial") }
                if (showBatt1)  SortableHeaderCellFixed(Icons.Default.Bolt,          wBatt1,  "col_batt1",  sortState) { onSortClick("col_batt1") }
                if (showBatt2)  SortableHeaderCellFixed(Icons.Default.Bolt,          wBatt2,  "col_batt2",  sortState) { onSortClick("col_batt2") }
                if (showExtra)  SortableHeaderCellFixed(Icons.Default.Info,          wExtra,  "col_extra",  sortState) { onSortClick("col_extra") }
                if (showStatus) SortableHeaderCellFixed(Icons.Default.Info,          wStat,   "col_status", sortState) { onSortClick("col_status") }
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
            itemsIndexed(scooters, key = { _, it -> it.id }) { idx, scooter ->
                val isSelected = selected.contains(scooter.id)
                val isExpanded = expandedScooterIds.contains(scooter.id)
                val status = scooterStatusOf(scooter.id, renters, latestContractByRenter)
                val sColor = scooterStatusColor(status)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScrollState),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ── Стрелка раскрытия контрактов ──────────────────
                        // Поведение (как в RenterTable):
                        //   • Обычное состояние → стрелка ВПРАВО (KeyboardArrowRight).
                        //   • Раскрыто (isExpanded) → стрелка ВНИЗ (повёрнута на 90°).
                        //   • Выбран (isSelected) → стрелка ВВЕРХ (повёрнута на -90°).
                        val arrowRotation = when {
                            isSelected -> -90f   // вверх
                            isExpanded -> 90f    // вниз
                            else -> 0f           // вправо
                        }
                        val arrowTint = when {
                            isSelected -> ClaudeAccent
                            isExpanded -> ClaudeAccent
                            else -> ClaudeTextSecondary
                        }
                        Box(
                            modifier = Modifier
                                .width(wExpand)
                                .height(40.dp)
                                .clickable {
                                    expandedScooterIds = if (isExpanded) {
                                        expandedScooterIds - scooter.id
                                    } else {
                                        expandedScooterIds + scooter.id
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = if (isExpanded) "Yig'ish" else "Kontraktlar",
                                tint = arrowTint,
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(arrowRotation)
                            )
                        }

                        // ── Основная строка скутера (№ + Name + Doc + ...) ──
                        // Цветная вертикальная полоса статуса слева (drawBehind):
                        //   • зелёная (StatusOk) — в базе, свободен
                        //   • красная (StatusOverdue) — в аренде
                        // Ширина полосы: 4dp (или 5dp если строка выбрана).
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFF3F4F6) else Color.White)
                                .drawBehind {
                                    val stripeW = if (isSelected) 5.dp.toPx() else 4.dp.toPx()
                                    drawRect(
                                        color = sColor,
                                        topLeft = Offset.Zero,
                                        size = Size(stripeW, size.height)
                                    )
                                }
                                .combinedClickable(
                                    onClick = { if (isSelected) onSelect(scooter.id, false) else onClick(scooter) },
                                    onLongClick = { onSelect(scooter.id, !isSelected) }
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // ── № — порядковый номер строки ──
                        Text(
                            "${idx + 1}",
                            modifier = Modifier
                                .width(wNum)
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        if (showName) {
                            Text(
                                scooter.name,
                                modifier = Modifier
                                    .width(wName)
                                    .padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClaudeText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showDoc) {
                            Text(
                                scooter.documentedNumber ?: "—",
                                modifier = Modifier.width(wDoc).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showVin) {
                            Text(
                                scooter.vinNumber.ifBlank { "—" },
                                modifier = Modifier.width(wVin).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showEngine) {
                            Text(
                                scooter.engineNumber.ifBlank { "—" },
                                modifier = Modifier.width(wEngine).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showSerial) {
                            Text(
                                scooter.scooterSerialNumber.ifBlank { "—" },
                                modifier = Modifier.width(wSerial).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showBatt1) {
                            Text(
                                scooter.batteryId1.ifBlank { "—" },
                                modifier = Modifier.width(wBatt1).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showBatt2) {
                            Text(
                                scooter.batteryId2.ifBlank { "—" },
                                modifier = Modifier.width(wBatt2).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showExtra) {
                            Text(
                                scooter.additionalInfo.ifBlank { "—" },
                                modifier = Modifier.width(wExtra).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClaudeText,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Visible
                            )
                        }
                        if (showStatus) {
                            Row(
                                modifier = Modifier
                                    .width(wStat)
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
                                    maxLines = 2,
                                    softWrap = true,
                                    overflow = TextOverflow.Visible
                                )
                            }
                        }
                    } // ← конец основной Row (со стрелкой статуса)
                    } // ← конец wrapper-Row (стрелка раскрытия + основная строка)

                    // ── Раскрытый блок: контракты скутера ──────────────────
                    // Показываем все контракты, в которых участвовал этот скутер,
                    // в виде 4-колоночной карточки — точно так же, как в раскрытой
                    // строке арендатора (см. RenterTable → if (isExpanded) { ... }).
                    if (isExpanded) {
                        val contracts = contractsByScooterName[scooter.name].orEmpty()
                            .sortedBy { it.weekStart ?: it.timestamp }

                        // Общее состояние горизонтального скролла для таблицы
                        // контрактов внутри раскрытой строки скутера. Без этого
                        // на узких экранах колонка «Summa» (100dp) + № (30dp) +
                        // # (40dp) + Muddat (160dp) = 330dp + paddings не помещаются
                        // в доступную ширину (viewport − indent 88dp − paddings),
                        // и weight(1f) на Summa получает отрицательную ширину →
                        // «420 000» рендерится в 0 пикселей и невидим (баг).
                        val cScrollState = rememberScrollState()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 4.dp)
                        ) {
                            // Отступ: стрелка (wExpand) + № (wNum) + 8dp — блок
                            // контрактов визуально начинается со 2-го столбца.
                            Spacer(modifier = Modifier.width(wExpand + wNum + 8.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFAFAF7),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    ClaudeDivider
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Kontraktlar (${contracts.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClaudeAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (contracts.isEmpty()) {
                                        Text(
                                            text = "Kontraktlar yo'q",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ClaudeTextSecondary
                                        )
                                    } else {
                                        // ── Заголовок таблицы (№ | # | Muddat (hafta)
                                        // | Summa) — как в RenterTable ──
                                        Surface(color = ClaudeCard, modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(cScrollState)
                                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                NonSortableHeaderCellFixed(Icons.Default.Numbers, 30.dp, "№")
                                                NonSortableHeaderCellFixed(Icons.Default.Tag, 40.dp, "#")
                                                NonSortableHeaderCellFixed(Icons.Default.DateRange, 160.dp, "Muddat (hafta)")
                                                NonSortableHeaderCellFixed(Icons.Default.Payments, 100.dp, "Summa")
                                            }
                                        }

                                        contracts.forEachIndexed { cIdx, c ->
                                            val cStart = c.weekStart
                                            val cEnd = c.weekEnd
                                            val dateRange = when {
                                                cStart != null && cEnd != null ->
                                                    "${dateFmt.format(Date(cStart))} → ${dateFmt.format(Date(cEnd))}"
                                                cStart != null -> dateFmt.format(Date(cStart))
                                                cEnd != null -> dateFmt.format(Date(cEnd))
                                                else -> "—"
                                            }
                                            val cStatusColor = when (c.type) {
                                                com.example.data.ContractHistoryEntry.TYPE_CREATED -> StatusOk
                                                com.example.data.ContractHistoryEntry.TYPE_AUTO_RENEW -> StatusOk
                                                com.example.data.ContractHistoryEntry.TYPE_TERMINATED -> StatusOverdue
                                                com.example.data.ContractHistoryEntry.TYPE_RETURNED -> ClaudeTextSecondary
                                                else -> ClaudeTextSecondary
                                            }
                                            val cStatusLabel = when (c.type) {
                                                com.example.data.ContractHistoryEntry.TYPE_CREATED -> "Yaratildi"
                                                com.example.data.ContractHistoryEntry.TYPE_AUTO_RENEW -> "Yangilandi"
                                                com.example.data.ContractHistoryEntry.TYPE_TERMINATED -> "Tugatildi"
                                                com.example.data.ContractHistoryEntry.TYPE_RETURNED -> "Qaytarildi"
                                                else -> "—"
                                            }
                                            val cNotes = c.notes?.ifBlank { null }
                                            val cAmount = c.amount

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(ClaudeCard)
                                                    .padding(12.dp, 10.dp)
                                                    .horizontalScroll(cScrollState),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "${cIdx + 1}",
                                                    modifier = Modifier.width(30.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ClaudeTextSecondary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    "#${c.id}",
                                                    modifier = Modifier.width(40.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ClaudeTextSecondary
                                                )
                                                Column(
                                                    modifier = Modifier.width(160.dp)
                                                ) {
                                                    Text(
                                                        dateRange,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = ClaudeText,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .background(cStatusColor, CircleShape)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            cStatusLabel,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = cStatusColor,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        if (cNotes != null) {
                                                            Text(
                                                                " • $cNotes",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = ClaudeTextSecondary
                                                            )
                                                        }
                                                    }
                                                }
                                                // Сумма контракта — форматируем с разделителем
                                                // тысяч (пробел): "420 000" вместо "420000".
                                                // «so'm» кладём строкой ниже мелким шрифтом, чтобы
                                                // не занимать горизонтальное место и не выдавливать
                                                // цифру в вертикальный столбец (прежний баг:
                                                // width(100.dp) + "420000 so'm" → перенос по символам).
                                                //
                                                // ВАЖНО: используем ФИКСИРОВАННУЮ ширину 100dp
                                                // (как в шапке), а не weight(1f). Раньше weight(1f)
                                                // внутри fillMaxWidth() без horizontalScroll получал
                                                // ОТРИЦАТЕЛЬНУЮ ширину (viewport − indent 88dp − paddings −
                                                // 30 − 40 − 160 < 0 на узких экранах) → «420 000»
                                                // рендерился в 0 пикселей и был невидим. Теперь
                                                // horizontalScroll(cScrollState) даёт строке
                                                // естественную ширину контента, а 100dp гарантированно
                                                // вмещает «420 000» (7 символов) шрифтом titleMedium.
                                                Column(
                                                    modifier = Modifier.width(100.dp),
                                                    horizontalAlignment = Alignment.End
                                                ) {
                                                    Text(
                                                        formatContractAmount(cAmount.toLong()),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = cStatusColor,
                                                        fontWeight = FontWeight.Bold,
                                                        textAlign = TextAlign.End,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        "so'm",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = cStatusColor.copy(alpha = 0.7f),
                                                        fontWeight = FontWeight.Normal,
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

    // ── Проверка дубликатов (красная рамка при совпадении с БД) ────────
    // Скутер уникален по: name, vin, engine, serial, batt1, batt2.
    // Если хотя бы одно поле совпадает с существующей записью (исключая
    // текущий редактируемый скутер) — поле подсвечивается красным.
    val editScooterId = initialScooter?.id
    val isScooterNameDuplicate = name.trim().isNotEmpty() &&
        existingScooters.any { it.id != editScooterId && it.name.trim().equals(name.trim(), ignoreCase = true) }
    val isVinDuplicate = vinNumber.trim().isNotEmpty() &&
        existingScooters.any { it.id != editScooterId && it.vinNumber.trim().equals(vinNumber.trim(), ignoreCase = true) }
    val isEngineDuplicate = engineNumber.trim().isNotEmpty() &&
        existingScooters.any { it.id != editScooterId && it.engineNumber.trim().equals(engineNumber.trim(), ignoreCase = true) }
    val isSerialDuplicate = scooterSerialNumber.trim().isNotEmpty() &&
        existingScooters.any { it.id != editScooterId && it.scooterSerialNumber.trim().equals(scooterSerialNumber.trim(), ignoreCase = true) }
    val isBatt1Duplicate = batteryId1.trim().isNotEmpty() &&
        existingScooters.any { it.id != editScooterId && it.batteryId1.trim().equals(batteryId1.trim(), ignoreCase = true) }
    val isBatt2Duplicate = batteryId2.trim().isNotEmpty() &&
        existingScooters.any { it.id != editScooterId && it.batteryId2.trim().equals(batteryId2.trim(), ignoreCase = true) }
    val errorBorder = StatusOverdue
    val dupFocused = StatusOverdue
    val dupUnfocused = StatusOverdue

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
                    isError = isScooterNameDuplicate,
                    supportingText = {
                        if (isScooterNameDuplicate) {
                            Text(
                                "Bunday nomdagi skuter allaqachon mavjud!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (initialScooter == null) {
                            Text(
                                "Avtomatik raqamlandi. Istalgan nom bilan almashtirishingiz mumkin.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClaudeTextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isScooterNameDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isScooterNameDuplicate) dupFocused else ClaudeTextSecondary
                    )
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
                    shape = RoundedCornerShape(8.dp),
                    isError = isVinDuplicate,
                    supportingText = {
                        if (isVinDuplicate) {
                            Text(
                                "Bu VIN allaqachon mavjud!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isVinDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isVinDuplicate) dupFocused else ClaudeTextSecondary
                    )
                )
                OutlinedTextField(
                    value = engineNumber,
                    onValueChange = { engineNumber = it },
                    label = { Text("Двигатель номери") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    isError = isEngineDuplicate,
                    supportingText = {
                        if (isEngineDuplicate) {
                            Text(
                                "Bu dvigatel raqami allaqachon mavjud!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isEngineDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isEngineDuplicate) dupFocused else ClaudeTextSecondary
                    )
                )
                OutlinedTextField(
                    value = scooterSerialNumber,
                    onValueChange = { scooterSerialNumber = it },
                    label = { Text("ID номери") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    isError = isSerialDuplicate,
                    supportingText = {
                        if (isSerialDuplicate) {
                            Text(
                                "Bu ID raqami allaqachon mavjud!",
                                color = StatusOverdue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (isSerialDuplicate) errorBorder else ClaudeDivider,
                        focusedBorderColor = if (isSerialDuplicate) dupFocused else ClaudeTextSecondary
                    )
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
                        shape = RoundedCornerShape(8.dp),
                        isError = isBatt1Duplicate,
                        supportingText = {
                            if (isBatt1Duplicate) {
                                Text(
                                    "Mavjud!",
                                    color = StatusOverdue,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = if (isBatt1Duplicate) errorBorder else ClaudeDivider,
                            focusedBorderColor = if (isBatt1Duplicate) dupFocused else ClaudeTextSecondary
                        )
                    )
                    OutlinedTextField(
                        value = batteryId2,
                        onValueChange = { batteryId2 = it },
                        label = { Text("Аккумулятор ID 2") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        isError = isBatt2Duplicate,
                        supportingText = {
                            if (isBatt2Duplicate) {
                                Text(
                                    "Mavjud!",
                                    color = StatusOverdue,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = if (isBatt2Duplicate) errorBorder else ClaudeDivider,
                            focusedBorderColor = if (isBatt2Duplicate) dupFocused else ClaudeTextSecondary
                        )
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
