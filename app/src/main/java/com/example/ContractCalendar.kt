package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusOkBg
import com.example.ui.theme.StatusOverdue
import com.example.ui.theme.StatusOverdueBg
import com.example.ui.theme.StatusReturned
import com.example.ui.theme.StatusReturnedBg
import java.util.Calendar
import java.util.Locale

/* ============================================================================
   CONTRACT CALENDAR — календарь с поддержкой групп контрактов
   ----------------------------------------------------------------------------
   Используется в двух местах:
     1) RenterFormDialog — выбор периодов (групп контрактов). Пользователь:
        • Нажимает "+" → выбирает две даты → создаётся группа с ТЕКУЩИМ
          статусом (кнопки "To'langan" / "To'lanmagan" сверху календаря).
        • Может выбрать одну дату дважды — система создаёт однодневный
          период (как старый календарь выбора одной даты).
        • Можно создать несколько групп (вкладки 1, 2, 3...).
        • У каждой вкладки есть "x" для удаления группы.
     2) RenterContractHistoryScreen — отображение + редактирование:
        • В режиме просмотра дни раскрашиваются по статусу контракта.
        • В режиме редактирования пользователь может добавить новые группы
          контрактов с выбранным статусом, которые сразу создаются в БД.

   Для режима (1) используется `editable = true` + onGroupsChange callback.
   Для режима (2) используется `editable = false` + `dayStatusFor` callback,
      но при этом также доступны кнопки статуса — для добавления новых
      контрактов через onAddGroup callback.
   ============================================================================ */

/* ── Палитра для кнопок Stop / Resume ─────────────────────────────── */
private val StopBg   = Color(0xFF1E3A8A) // тёмно-синий (Tailwind blue-900)
private val StopFg   = Color(0xFFBFDBFE) // светло-голубой текст/иконка
private val ResumeBg = Color(0xFFFACC15) // жёлтый (Tailwind yellow-400)
private val ResumeFg = Color(0xFF78350F) // тёмно-коричневый текст/иконка

/** Один диапазон дат = одна группа контрактов. */
data class ContractGroup(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    /** true = оплаченная группа (зелёная), false = долг (красная). */
    val isPaid: Boolean = true,
    /**
     * ID существующего контракта в БД (ContractHistoryEntry.id).
     * - null = новая группа, созданная пользователем в календаре (ещё не сохранена).
     * - non-null = группа загружена из существующего контракта арендатора.
     *
     * Используется в RenterFormDialog при редактировании арендатора:
     * календарь показывает все существующие контракты, и пользователь может
     * их удалять/редактировать. При сохранении формы existingContractId позволяет
     * корректно различить «удалить существующий» от «добавить новый».
     */
    val existingContractId: Int? = null,
    /**
     * Маркер «остановки» аренды на этот день. Не является контрактом — это
     * сигнал для RenterViewModel, что начиная с этого дня аренда приостановлена
     * и автоматическое создание неоплаченных контрактов должно остановиться
     * на этом дне. Сохраняется как ContractHistoryEntry с type=TERMINATED.
     */
    val isStopMarker: Boolean = false,
    /**
     * Маркер «возобновления» аренды с этого дня. Не является контрактом — это
     * сигнал для RenterViewModel, что начиная с этого дня автоматическое
     * создание неоплаченных контрактов возобновляется (после предыдущего Stop).
     * Сохраняется как ContractHistoryEntry с type=RETURNED и isResumeMarker=true
     * (используется поле notes для хранения флага, чтобы не менять схему БД).
     */
    val isResumeMarker: Boolean = false,
    /**
     * ID скутера, привязанного к этой группе/контракту.
     * - Для новых групп, созданных в календаре формы: capturing текущий
     *   selectedScooterId в момент тапа по второй дате (или по дню для маркера).
     *   Это позволяет ПОКАЗЫВАТЬ имя скутера над каждым периодом в списке
     *   контрактов под календарём, чтобы было видно, к какому скутеру
     *   относится каждый контракт — как просил пользователь.
     * - Для существующих контрактов (loaded из БД): ID скутера берётся из
     *   Renter.scooterId (или из scooterName контракта, если доступно).
     * - null = скутер не выбран (для STOP/RESUME маркеров, где скутер опционален).
     */
    val scooterId: Int? = null,
    /**
     * Имя скутера, привязанного к этой группе. Используется ТОЛЬКО для
     * отображения в UI (список контрактов под календарём в форме и в
     * раскрытой карточке арендатора на странице арендаторов). При сохранении
     * в БД берётся из Scooter по scooterId (или из renter.scooterName).
     */
    val scooterName: String? = null
) {
    /** Цветовая метка группы (циклический выбор по id). */
    val colorIndex: Int get() = ((id - 1).coerceAtLeast(0)) % 6
}

/** Статус дня в режиме просмотра (страница деталей арендатора). */
enum class DayStatus {
    /** Оплаченный день — зелёный фон. */
    PAID,
    /** Неоплаченный день (контракт есть, но isPaid=false) — красный фон. */
    UNPAID,
    /** Приостановленный контракт (TERMINATED) — серый фон. */
    SUSPENDED,
    /** День «возобновления» — жёлтый фон. */
    RESUMED,
    /** Обычный день — белый фон. */
    EMPTY
}

/** Палитра цветов для меток групп. */
private val GroupColors = listOf(
    Color(0xFFC14E24), // terracotta
    Color(0xFF255E52), // teal
    Color(0xFFB8862B), // gold
    Color(0xFF7E22CE), // purple
    Color(0xFF1D4ED8), // blue
    Color(0xFF15803D)  // green-dark
)

@Composable
fun ContractCalendar(
    modifier: Modifier = Modifier,
    /** Режим редактирования: true = выбор периодов (форма), false = просмотр (детали). */
    editable: Boolean = false,
    /**
     * Начальное состояние календаря: true = развёрнут (полная сетка дней),
     * false = свёрнут в одну строку (месяц + статус + стрелка).
     * Пользователь может переключать состояние стрелкой в шапке.
     */
    initiallyExpanded: Boolean = true,
    /**
     * Выбран ли скутер в форме арендатора.
     * По требованию пользователя: «пользователь после выборки из двух кнопок
     * статуса оплаченный и неоплаченный должен будет в обязательном порядке
     * выбрать скутер с помощью нашей кнопки и только потом в календаре выбрать
     * период». Если скутер не выбран (false) — тапы по дням календаря
     * игнорируются, и пользователю показывается подсказка «Skuterni tanlang».
     */
    scooterSelected: Boolean = true,
    /**
     * ID выбранного скутера в форме арендатора.
     * Передаётся в каждую новую группу, создаваемую в календаре, чтобы
     * потом в списке контрактов под календарём показать имя скутера над
     * каждым периодом (требование пользователя: «в календаре должен
     * показываться скутер имя скутера сверху выбранного периода контракта
     * к которому прикреплён этот скутер»).
     * null = скутер не выбран (для STOP/RESUME маркеров опционален).
     */
    selectedScooterId: Int? = null,
    /**
     * Имя выбранного скутера — для отображения в новых группах.
     * Берётся из Scooter.name по selectedScooterId в родителе.
     */
    selectedScooterName: String? = null,
    /**
     * Текущие группы.
     * - Для editable=true (форма): локальный список, который пользователь собирает.
     *   «x» на вкладке удаляет группу из локального state через onGroupsChange.
     * - Для editable=false (страница деталей): существующие контракты из БД.
     *   «x» на вкладке вызывает onRemoveGroup (родитель удаляет контракт из БД).
     */
    groups: List<ContractGroup> = emptyList(),
    /** Активная группа (вкладка, выбранная пользователем). null = новая группа в процессе создания. */
    activeGroupId: Int? = null,
    /** Callback при изменении списка групп (только для editable=true). */
    onGroupsChange: (List<ContractGroup>) -> Unit = {},
    /** Callback при изменении активной группы. */
    onActiveGroupChange: (Int?) -> Unit = {},
    /** Статус дня (для editable=false). */
    dayStatusFor: (Long) -> DayStatus = { DayStatus.EMPTY },
    /**
     * Доп. callback при добавлении новой группы (для editable=false).
     * В режиме просмотра, когда пользователь выбирает период и статус,
     * этот callback вызывается вместо onGroupsChange — родитель должен
     * создать реальные контракты в БД (через ContractHistoryViewModel).
     */
    onAddGroup: ((ContractGroup) -> Unit)? = null,
    /**
     * Callback при удалении существующей группы (для editable=false).
     * В режиме просмотра «x» на вкладке существующего контракта вызывает
     * этот callback — родитель должен удалить контракт из БД.
     * Для editable=true этот callback не используется (вместо него работает
     * onGroupsChange с обновлённым списком).
     */
    onRemoveGroup: ((ContractGroup) -> Unit)? = null,
    /**
     * Callback при тапе на день, который попадает в существующий контракт
     * (для editable=false). Родитель должен открыть диалог редактирования
     * контракта, которому принадлежит этот день. Если null или день пустой —
     * работает обычная логика выбора диапазона (для создания нового контракта).
     */
    onEditDayContract: ((Long) -> Unit)? = null,
    /** Доп. callback при тапе на день (опционально, для просмотра деталей). */
    onDayClick: (Long) -> Unit = {}
) {
    val cal = remember { Calendar.getInstance() }
    var viewYear by remember { mutableStateOf(cal.get(Calendar.YEAR)) }
    var viewMonth by remember { mutableStateOf(cal.get(Calendar.MONTH)) }

    // ── Состояние развёрнутости календаря ──────────────────────────────
    // true  = видна полная сетка дней и панель управления.
    // false = видна только строка-сводка (месяц + статус-пилюли + стрелка).
    // Пользователь переключает состояние стрелкой в шапке календаря.
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    // ── Состояние выбора для новой группы ──────────────────────────────
    // В режиме editable: при тапе на день в "новой группе" (activeGroupId == null)
    // пользователь выбирает сначала start, потом end (или ту же дату дважды
    // для однодневного периода). После выбора end группа создаётся и активной
    // становится следующая "новая" (activeGroupId = null).
    var pendingStartMs by remember { mutableStateOf<Long?>(null) }

    // ── Текущий статус для новых групп (To'langan / To'lanmagan / Stop / Resume) ──
    // По умолчанию "To'langan" (оплаченный). Пользователь может переключить
    // большими квадратными кнопками в шапке календаря перед выбором периода.
    var newGroupIsPaid by remember { mutableStateOf(true) }

    // ── Режим маркера дня (Stop / Resume) ────────────────────────────
    // 0 = обычный режим выбора периода (по кнопкам Paid/Unpaid)
    // 1 = режим Stop — тап по дате создаёт маркер «остановлен»
    // 2 = режим Resume — тап по дате создаёт маркер «возобновлён» и
    //     запускает авто-создание неоплаченных контрактов от этой даты
    //     до ближайшего Stop или до следующей недели.
    var dayMarkerMode by remember { mutableStateOf(0) } // 0=none, 1=stop, 2=resume

    // ── Пользователь явно нажал кнопку статуса? ────────────────────────
    // Только после этого можно тапать по дням календаря для выбора периода.
    // Пользователь явно попросил: «сделай большими квадратными и только после
    // нажатия одного из можно будет выбрать период в календаре иначе нельзя».
    var hasSelectedStatus by remember { mutableStateOf(false) }

    val monthTitle = remember(viewYear, viewMonth) {
        val fmt = java.text.SimpleDateFormat("LLLL yyyy", Locale.getDefault())
        cal.set(viewYear, viewMonth, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        fmt.format(cal.time).replaceFirstChar { it.uppercase() }
    }

    val days = remember(viewYear, viewMonth) {
        buildList {
            // ── Нормализация к началу дня ─────────────────────────────────
            // Ранее cal.set(year, month, 1) НЕ сбрасывал HOUR/MINUTE/SECOND,
            // поэтому все 42 ячейки несли текущее время (например 14:30).
            // Это приводило к багу: контракт 04.08→11.08 (с weekStart=00:00)
            // отображался в календаре как 07.08→11.08, потому что сравнение
            // dayMs >= g.startMs для ячейки 4 августа (14:30) было TRUE, но
            // ячейка 6 августа (14:30) >= contract.endMs (23:59:59.999 11.08)
            // ломалось при пересечении месяцев. Сброс времени к 00:00:00.000
            // устраняет эту путаницу — все ячейки и контракты сравниваются
            // по день-начала.
            cal.set(viewYear, viewMonth, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            // Понедельник = первый день недели
            val leadings = (firstDayOfWeek - Calendar.MONDAY + 7) % 7
            cal.add(Calendar.DAY_OF_MONTH, -leadings)
            // 6 недель × 7 дней = 42 ячейки — всегда стабильная высота
            for (i in 0 until 42) {
                add(cal.time.time)
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ClaudeCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // ── Шапка: стрелки навигации по месяцам + месяц/год + статус + стрелка свернуть/развернуть ──
            // Возвращены стрелки «‹» и «›» для перехода между месяцами — пользователь
            // случайно лишился их в прошлой правке. Теперь они снова работают:
            //   • ‹ → viewMonth -= 1 (с авто-переходом через декабрь/январь)
            //   • › → viewMonth += 1
            // Кнопки квадратные (28dp), в стиле остальных кнопок календаря.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Левая часть: стрелка ‹ + месяц/год + стрелка ›
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Стрелка влево (предыдущий месяц)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ClaudeAccentBg)
                            .clickable {
                                if (viewMonth == 0) {
                                    viewMonth = 11
                                    viewYear -= 1
                                } else {
                                    viewMonth -= 1
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Oldingi oy",
                            tint = ClaudeAccent,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(90f) // ← поворачиваем «вниз» в «влево»
                        )
                    }

                    Text(
                        text = monthTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ClaudeText,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    // Стрелка вправо (следующий месяц)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ClaudeAccentBg)
                            .clickable {
                                if (viewMonth == 11) {
                                    viewMonth = 0
                                    viewYear += 1
                                } else {
                                    viewMonth += 1
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Keyingi oy",
                            tint = ClaudeAccent,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(-90f) // ← поворачиваем «вниз» в «вправо»
                        )
                    }
                }

                // Правая часть: статус-пилюли + стрелка свернуть/развернуть
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Сводка по группам: число оплаченных и неоплаченных
                    val paidCount = groups.count { it.isPaid }
                    val unpaidCount = groups.count { !it.isPaid }
                    if (paidCount > 0) {
                        StatusPill(count = paidCount, color = StatusOk)
                    }
                    if (unpaidCount > 0) {
                        StatusPill(count = unpaidCount, color = StatusOverdue)
                    }

                    // Стрелка свернуть/развернуть календарь
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ClaudeAccentBg)
                            .clickable { expanded = !expanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                          else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Yig'ish" else "Yoyish",
                            tint = ClaudeAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── Тело календаря — только когда развёрнут ────────────────────
            if (expanded) {
                Spacer(Modifier.height(4.dp))

                // ── Панель управления (только в editable-режиме или при наличии onAddGroup) ──
                // Содержит:
                //   • Кнопку "+" для начала новой группы
                //   • Кнопки статуса "To'langan" / "To'lanmagan" — выбирают статус
                //     для следующей группы
                //   • Вкладки существующих групп (1, 2, 3...) с кнопкой "x"
                if (editable || onAddGroup != null) {
                GroupsPanel(
                    groups = groups,
                    activeGroupId = activeGroupId,
                    newGroupIsPaid = newGroupIsPaid,
                    dayMarkerMode = dayMarkerMode,
                    hasSelectedStatus = hasSelectedStatus,
                    onNewGroupStatusChange = {
                        newGroupIsPaid = it
                        // Пользователь выбрал статус → теперь можно выбирать
                        // период в календаре.
                        hasSelectedStatus = true
                        dayMarkerMode = 0 // сброс режима Stop/Resume
                        // Сбрасываем частично выбранный период, если он был,
                        // чтобы начать заново с новым статусом.
                        pendingStartMs = null
                        onActiveGroupChange(null)
                    },
                    onDayMarkerModeChange = { mode ->
                        dayMarkerMode = mode
                        if (mode != 0) {
                            // При выборе Stop/Resume сбрасываем выбор статуса
                            // Paid/Unpaid — эти режимы взаимоисключающие.
                            hasSelectedStatus = false
                            pendingStartMs = null
                            onActiveGroupChange(null)
                        }
                    },
                    onActiveGroupChange = onActiveGroupChange,
                    onAddGroup = {
                        // Больше не используется — выбор статуса автоматически
                        // переводит календарь в режим выбора периода.
                        onActiveGroupChange(null)
                        pendingStartMs = null
                    },
                    onRemoveGroup = { gid ->
                        // Для editable=true: удаляем из локального state.
                        // Для editable=false (onRemoveGroup != null): родитель
                        // удалит контракт из БД.
                        val groupToRemove = groups.firstOrNull { it.id == gid }
                        if (onRemoveGroup != null && groupToRemove != null) {
                            onRemoveGroup.invoke(groupToRemove)
                        } else {
                            val updated = groups.filterNot { it.id == gid }
                            onGroupsChange(updated)
                        }
                        if (activeGroupId == gid) onActiveGroupChange(null)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Заголовки дней недели ──────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth()) {
                val dayNames = listOf("Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya")
                dayNames.forEach { d ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = d,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = ClaudeTextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Сетка дней (6 × 7) ─────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                for (weekIdx in 0 until 6) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (dayIdx in 0 until 7) {
                            val dayMs = days[weekIdx * 7 + dayIdx]
                            DayCell(
                                dayMs = dayMs,
                                viewMonth = viewMonth,
                                editable = editable || onAddGroup != null,
                                groups = groups,
                                activeGroupId = activeGroupId,
                                pendingStartMs = pendingStartMs,
                                dayStatusFor = dayStatusFor,
                                onDayClick = { ms ->
                                    // ── Режим Stop/Resume маркера дня ──────────────
                                    // Если включён режим Stop (1) или Resume (2),
                                    // тап по дате создаёт однодневный «маркерный»
                                    // контракт с особым флагом isStopMarker /
                                    // isResumeMarker.
                                    //
                                    // Для Resume-маркера дополнительно АВТОСОЗДАЮТСЯ
                                    // неоплаченные недельные контракты:
                                    //   • Forward: от Resume-дня до ближайшего Stop
                                    //     (если Stop в пределах +7 дней) или до +7 дней.
                                    //   • Backward: от Resume-1 назад до сегодня
                                    //     недельными контрактами, ЕСЛИ в [today, R-1]
                                    //     нет Stop-маркера.
                                    // Это позволяет пользователю ВИДЕТЬ контракты в
                                    // календаре и в списке ниже сразу после тапа —
                                    // не нужно ждать сохранения формы. При сохранении
                                    // RenterViewModel распознаёт уже существующие
                                    // группы и НЕ создаёт дубликаты (см. проверку
                                    // в reconcileContractsFromGroups / addRenter).
                                    if (editable && dayMarkerMode != 0) {
                                        val dayMs = 24L * 60 * 60 * 1000
                                        val weekMs = 7L * dayMs
                                        val newIdBase = (groups.maxOfOrNull { it.id } ?: 0)
                                        val dayEnd = ms + dayMs - 1
                                        val markerGroup = ContractGroup(
                                            id = newIdBase + 1,
                                            startMs = ms,
                                            endMs = dayEnd,
                                            isPaid = false,
                                            isStopMarker = dayMarkerMode == 1,
                                            isResumeMarker = dayMarkerMode == 2,
                                            // Для STOP/RESUME маркеров скутер опционален,
                                            // но если выбран — фиксируем его в группе,
                                            // чтобы показать имя над периодом и чтобы
                                            // auto-контракты после RESUME создавались
                                            // с этим скутером.
                                            scooterId = selectedScooterId,
                                            scooterName = selectedScooterName
                                        )
                                        var newGroups = groups + markerGroup

                                        // ── Если STOP — удаляем конфликтующие маркеры ──
                                        // Это критично для логики архива: если у арендатора
                                        // есть STOP_MARKER в прошлом и после него НЕТ
                                        // RESUME_MARKER — арендатор архивируется.
                                        //
                                        // Проблема: после restore-from-archive в БД остаётся
                                        // синтетический RESUME_MARKER на сегодня (с timestamp
                                        // НОВЕЕ исходного STOP). Если пользователь хочет снова
                                        // остановить арендатора и ставит новый STOP, старый
                                        // RESUME «перекрывает» STOP → isRenterArchived=false.
                                        //
                                        // Удаляем три категории маркеров:
                                        //   1. RESUME на/после нового STOP (startMs >= ms):
                                        //      пользователь явно сигнализирует «стоп с этой
                                        //      даты» — любые будущие RESUME не имеют смысла.
                                        //      Включая тот же день — RESUME и STOP на один
                                        //      день не имеют смысла вместе.
                                        //   2. RESUME на тот же день что и ЛЮБОЙ существующий
                                        //      STOP: это синтетический RESUME от restore-from-
                                        //      archive (он ставится на today, а STOP тоже был
                                        //      на today или раньше). Удаляем чтобы новый STOP
                                        //      не «перекрывался» старым RESUME.
                                        //   3. Существующие STOP на тот же день что и новый
                                        //      STOP: новый STOP заменяет старый (у старого
                                        //      timestamp меньше, и если его оставить —
                                        //      reconcile пропустит вставку нового STOP из-за
                                        //      existingStopKeys, и старый STOP с старым
                                        //      timestamp останется в БД).
                                        if (dayMarkerMode == 1) {
                                            val existingStopDates = groups
                                                .filter { it.isStopMarker }
                                                .map { it.startMs }
                                                .toSet()
                                            newGroups = newGroups.filterNot { g ->
                                                // 1. RESUME на/после нового STOP
                                                (g.isResumeMarker && g.startMs >= ms) ||
                                                // 2. RESUME на тот же день что и любой STOP
                                                (g.isResumeMarker && g.startMs in existingStopDates) ||
                                                // 3. Существующий STOP на день нового STOP
                                                //    (НЕ удаляем сам новый markerGroup)
                                                (g.isStopMarker && g.startMs == ms && g.id != markerGroup.id)
                                            }
                                        }

                                        // ── Если Resume — авто-создаём недельные контракты ──
                                        if (dayMarkerMode == 2) {
                                            // Forward: до ближайшего Stop в [ms, ms+7d]
                                            // или до ms+7d если Stop нет.
                                            // Модель «отель/ночь»: endMs = start + N*dayMs
                                            // (БЕЗ -1), чтобы дата окончания отображалась
                                            // как «день выезда» (check-out), а не «день до».
                                            val stopDays = groups
                                                .filter { it.isStopMarker }
                                                .map { it.startMs }
                                                .sorted()
                                            val nextStop = stopDays.firstOrNull { it > ms }
                                            val forwardEnd = when {
                                                nextStop != null && nextStop <= ms + weekMs -> nextStop
                                                else -> ms + weekMs
                                            }
                                            if (forwardEnd > ms) {
                                                var idCounter = newIdBase + 2
                                                newGroups = newGroups + ContractGroup(
                                                    id = idCounter,
                                                    startMs = ms,
                                                    endMs = forwardEnd,
                                                    isPaid = false,
                                                    scooterId = selectedScooterId,
                                                    scooterName = selectedScooterName
                                                )
                                            }

                                            // Backward: недельные контракты от R до
                                            // сегодня, если в [today, R-1] нет Stop.
                                            // Модель «отель/ночь»: каждый backward-контракт
                                            // = 7 дней, end = start + 7d. Контракты делят
                                            // конечные точки (check-out = check-in следующего):
                                            //   [R-7d, R], [R-14d, R-7d], [R-21d, R-14d], ...
                                            val todayStart = run {
                                                val cal = java.util.Calendar.getInstance()
                                                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                                                cal.set(java.util.Calendar.MINUTE, 0)
                                                cal.set(java.util.Calendar.SECOND, 0)
                                                cal.set(java.util.Calendar.MILLISECOND, 0)
                                                cal.timeInMillis
                                            }
                                            if (ms > todayStart) {
                                                val stopInGap = stopDays.any { it in (todayStart until ms) }
                                                if (!stopInGap) {
                                                    var end = ms
                                                    var guard = 0
                                                    var idCounter = (newGroups.maxOfOrNull { it.id } ?: 0) + 1
                                                    while (end > todayStart && guard < 60) {
                                                        val ws = end - 7 * dayMs
                                                        val realWs = maxOf(ws, todayStart)
                                                        newGroups = newGroups + ContractGroup(
                                                            id = idCounter++,
                                                            startMs = realWs,
                                                            endMs = end,
                                                            isPaid = false,
                                                            scooterId = selectedScooterId,
                                                            scooterName = selectedScooterName
                                                        )
                                                        end = realWs
                                                        guard++
                                                    }
                                                }
                                            }
                                        }

                                        onGroupsChange(newGroups)
                                        onActiveGroupChange(markerGroup.id)
                                        // Сбрасываем режим маркера после установки
                                        dayMarkerMode = 0
                                        return@DayCell
                                    }
                                    // ── Блокировка выбора периода до выбора статуса ──
                                    // Пользователь должен сначала нажать большую
                                    // квадратную кнопку «To'langan» или «To'lanmagan»
                                    // (это установит hasSelectedStatus = true), и
                                    // только потом он сможет тапать по дням для
                                    // выбора периода.
                                    if (editable && !hasSelectedStatus && activeGroupId == null) {
                                        return@DayCell  // игнорируем тап
                                    }
                                    // ── Блокировка выбора периода до выбора скутера ──
                                    // По требованию пользователя: после выбора статуса
                                    // (Paid/Unpaid) пользователь ОБЯЗАТЕЛЬНО должен
                                    // выбрать скутер, и только потом — период в календаре.
                                    // Если скутер не выбран — тап игнорируется.
                                    if (editable && !scooterSelected && dayMarkerMode == 0) {
                                        return@DayCell
                                    }
                                    if (editable) {
                                        handleDayClick(
                                            ms = ms,
                                            pendingStartMs = pendingStartMs,
                                            setPendingStart = { pendingStartMs = it },
                                            activeGroupId = activeGroupId,
                                            groups = groups,
                                            newGroupIsPaid = newGroupIsPaid,
                                            onGroupsChange = onGroupsChange,
                                            onActiveGroupChange = onActiveGroupChange,
                                            onPeriodCreated = { hasSelectedStatus = false },
                                            // Передаём скутер в новую группу — это позволит
                                            // показать имя скутера над периодом в списке
                                            // контрактов под календарём.
                                            scooterId = selectedScooterId,
                                            scooterName = selectedScooterName
                                        )
                                    } else if (onAddGroup != null) {
                                        // Режим просмотра с возможностью добавления —
                                        // логика та же, но новая группа передаётся в
                                        // onAddGroup (для создания контрактов в БД).
                                        // ОДНАКО: если включён onEditDayContract и тап
                                        // пришёлся на день, который уже входит в
                                        // существующий контракт — открываем диалог
                                        // редактирования контракта вместо выбора
                                        // нового диапазона.
                                        val inExisting = groups.firstOrNull { g ->
                                            ms >= g.startMs && ms <= g.endMs
                                        }
                                        if (inExisting != null && onEditDayContract != null) {
                                            onEditDayContract.invoke(ms)
                                        } else {
                                            handleDayClickWithAddCallback(
                                                ms = ms,
                                                pendingStartMs = pendingStartMs,
                                                setPendingStart = { pendingStartMs = it },
                                                activeGroupId = activeGroupId,
                                                newGroupIsPaid = newGroupIsPaid,
                                                onAddGroup = onAddGroup
                                            )
                                        }
                                    } else {
                                        onDayClick(ms)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ── Легенда (только в режиме просмотра без onAddGroup) ──────
            if (!editable && onAddGroup == null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LegendItem("To'langan", StatusOkBg, StatusOk)
                    LegendItem("To'lanmagan", StatusOverdueBg, StatusOverdue)
                    LegendItem("To'xtatilgan", StatusReturnedBg, StatusReturned)
                    LegendItem("Bo'sh", ClaudeCard, ClaudeTextSecondary)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                // ── Подсказка с явным отображением текущего шага ──
                // Пользователь жаловался что «при нажатии статуса ничего не
                // происходит». Чтобы было видно что статус выбран, подсказка
                // динамически меняется и показывает ВЫБРАННЫЙ статус зелёным
                // или красным цветом. Также явный счётчик шагов: ① статус →
                // ② скутер → ③ период.
                val hint = when {
                    activeGroupId != null -> "Kontrakt ${groups.indexOfFirst { it.id == activeGroupId } + 1} tanlandi"
                    !hasSelectedStatus && editable && dayMarkerMode == 0 -> {
                        // Шаг 1: статус не выбран → попросить выбрать
                        "① Yuqoridagi «To'langan» yoki «To'lanmagan» tugmasini bosing"
                    }
                    hasSelectedStatus && editable && !scooterSelected && dayMarkerMode == 0 -> {
                        // Шаг 2: статус выбран, но скутер — нет.
                        // Показываем ВЫБРАННЫЙ статус для подтверждения.
                        val st = if (newGroupIsPaid) "To'langan ✓" else "To'lanmagan ✓"
                        "② Status: $st. Endi skuterni tanlang — keyin davrni belgilang"
                    }
                    hasSelectedStatus && editable && scooterSelected && dayMarkerMode == 0 && pendingStartMs == null -> {
                        // Шаг 3: статус + скутер выбраны, ждём первую дату
                        "③ Status va skuter tanlandi. Endi birinchi sanani tanlang"
                    }
                    hasSelectedStatus && editable && scooterSelected && dayMarkerMode == 0 && pendingStartMs != null -> {
                        // Шаг 4: первая дата выбрана, ждём вторую
                        "④ Ikkinchi sanani tanlang — davr yopiladi"
                    }
                    dayMarkerMode != 0 && editable -> {
                        // Режим Stop/Resume: тап по дню ставит маркер
                        val marker = if (dayMarkerMode == 1) "To'xtash (STOP)" else "Davom (RESUME)"
                        "Sanani tanlang — $marker markeri qo'yiladi"
                    }
                    pendingStartMs == null -> {
                        if (!editable && onAddGroup != null) {
                            "«To'langan» / «To'lanmagan» tugmasini bosing — yangi kontrakt boshlash uchun"
                        } else {
                            "Birinchi sanani tanlang — davr boshlanishi"
                        }
                    }
                    else -> "Ikkinchi sanani tanling — davr yopiladi"
                }
                // Цвет подсказки: серый по умолчанию, зелёный когда статус выбран
                val hintColor = when {
                    hasSelectedStatus && dayMarkerMode == 0 -> StatusOk
                    dayMarkerMode != 0 -> if (dayMarkerMode == 1) Color(0xFF1E3A8A) else Color(0xFFFACC15)
                    else -> ClaudeTextSecondary
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = hintColor,
                    fontWeight = if (hasSelectedStatus || dayMarkerMode != 0) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            } // end of if (expanded)
        }
    }
}

/* ── Логика тапа по дню в режиме редактирования (editable=true) ──────── */
private fun handleDayClick(
    ms: Long,
    pendingStartMs: Long?,
    setPendingStart: (Long?) -> Unit,
    activeGroupId: Int?,
    groups: List<ContractGroup>,
    newGroupIsPaid: Boolean,
    onGroupsChange: (List<ContractGroup>) -> Unit,
    onActiveGroupChange: (Int?) -> Unit,
    onPeriodCreated: () -> Unit = {},
    /** ID выбранного скутера — копируется в новую группу, чтобы потом
     *  показать имя скутера над периодом в списке контрактов. */
    scooterId: Int? = null,
    /** Имя выбранного скутера — копируется в новую группу для UI. */
    scooterName: String? = null
) {
    // Если открыта существующая группа — ничего не делаем (только просмотр).
    if (activeGroupId != null) return

    val dayMs = 24L * 60 * 60 * 1000
    val weekMs = 7L * dayMs

    if (pendingStartMs == null) {
        // Первый тап — сохраняем старт
        setPendingStart(ms)
    } else {
        // ── Старая логика однодневного выбора (legacy) ──────────────────
        // При двойном клике на одну и ту же дату — автоопределение статуса
        // по возрасту даты (старше недели = неоплаченный, иначе оплаченный).
        val isSameDayTap = isSameDay(pendingStartMs, ms)
        if (isSameDayTap) {
            // ── Двойной клик на одну дату → недельный контракт с авто-статусом ──
            // Модель «отель/ночь»: 7 дней = день 1 (чт) → день 8 (след. чт).
            // realEnd = start + 7d (БЕЗ -1) — отображаемая дата окончания
            // становится «следующим четвергом», а не «средой перед ним».
            // Сумма: ceil((start+7d - start)/dayMs) = 7 → 7×60000 = 420000.
            val now = System.currentTimeMillis()
            val isOverdue = (now - ms) > weekMs
            val autoIsPaid = !isOverdue
            val start = ms
            val realEnd = ms + weekMs
            val newId = (groups.maxOfOrNull { it.id } ?: 0) + 1
            val newGroup = ContractGroup(
                id = newId, startMs = start, endMs = realEnd, isPaid = autoIsPaid,
                scooterId = scooterId, scooterName = scooterName
            )
            onGroupsChange(groups + newGroup)
            onActiveGroupChange(newId)
            setPendingStart(null)
            onPeriodCreated()  // сбрасываем hasSelectedStatus — нужно снова выбрать
        } else {
            // ── Два разных дня → диапазон с пользовательским статусом ──
            // ВАЖНО: realEnd = начало последнего выбранного дня (БЕЗ +dayMs-1).
            // Это реализует модель «отель/ночь»: период 1..7 = 6 дней аренды
            // (день возврата не оплачивается). Ранее было `+ dayMs - 1`,
            // что давало endMs = конец дня 7 → ceil((endMs-startMs)/dayMs) = 7
            // → сумма 7×60000 = 420000 вместо ожидаемых 6×60000 = 360000.
            // UI-подсветка дней остаётся корректной: проверка
            // `dayMs <= g.endMs` всё ещё включает день 7 (его ms = start_of_day7
            // = new endMs), но не день 8.
            val start = minOf(pendingStartMs, ms)
            val realEnd = maxOf(pendingStartMs, ms)
            val newId = (groups.maxOfOrNull { it.id } ?: 0) + 1
            val newGroup = ContractGroup(
                id = newId, startMs = start, endMs = realEnd, isPaid = newGroupIsPaid,
                scooterId = scooterId, scooterName = scooterName
            )
            onGroupsChange(groups + newGroup)
            onActiveGroupChange(newId)
            setPendingStart(null)
            onPeriodCreated()  // сбрасываем hasSelectedStatus — нужно снова выбрать
        }
    }
}

/* ── Логика тапа для режима просмотра с onAddGroup callback ──────────── */
private fun handleDayClickWithAddCallback(
    ms: Long,
    pendingStartMs: Long?,
    setPendingStart: (Long?) -> Unit,
    activeGroupId: Int?,
    newGroupIsPaid: Boolean,
    onAddGroup: (ContractGroup) -> Unit
) {
    if (activeGroupId != null) return

    val dayMs = 24L * 60 * 60 * 1000
    val weekMs = 7L * dayMs

    if (pendingStartMs == null) {
        setPendingStart(ms)
    } else {
        // ── Та же legacy-логика автоопределения статуса по дате ──────
        val isSameDayTap = isSameDay(pendingStartMs, ms)
        if (isSameDayTap) {
            // Модель «отель/ночь»: realEnd = ms + weekMs (БЕЗ -1),
            // конец = следующий четверг. См. комментарий в handleDayClick.
            val now = System.currentTimeMillis()
            val isOverdue = (now - ms) > weekMs
            val autoIsPaid = !isOverdue
            val start = ms
            val realEnd = ms + weekMs
            // Используем отрицательный id как временный — реальный id
            // присвоит БД при создании контракта. Колбэк onAddGroup должен
            // проигнорировать это поле и использовать startMs/endMs/isPaid.
            val newGroup = ContractGroup(
                id = -1, startMs = start, endMs = realEnd, isPaid = autoIsPaid
            )
            onAddGroup(newGroup)
            setPendingStart(null)
        } else {
            // Два разных дня → диапазон с пользовательским статусом.
            // realEnd = начало последнего выбранного дня (без +dayMs-1) —
            // модель «ночь/отель»: 1..7 = 6 дней аренды (см. комментарий
            // в handleDayClick выше).
            val start = minOf(pendingStartMs, ms)
            val realEnd = maxOf(pendingStartMs, ms)
            val newGroup = ContractGroup(
                id = -1, startMs = start, endMs = realEnd, isPaid = newGroupIsPaid
            )
            onAddGroup(newGroup)
            setPendingStart(null)
        }
    }
}

/* ── Ячейка дня (объявлена как RowScope для доступа к Modifier.weight) ─── */
@Composable
private fun RowScope.DayCell(
    dayMs: Long,
    viewMonth: Int,
    editable: Boolean,
    groups: List<ContractGroup>,
    activeGroupId: Int?,
    pendingStartMs: Long?,
    dayStatusFor: (Long) -> DayStatus,
    onDayClick: (Long) -> Unit
) {
    val cal = remember { Calendar.getInstance() }
    cal.timeInMillis = dayMs
    val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
    val isCurrentMonth = cal.get(Calendar.MONTH) == viewMonth
    val isToday = isSameDay(dayMs, System.currentTimeMillis())

    val bgColor: Color
    val fgColor: Color
    val borderColor: Color? = if (isToday) ClaudeAccent else null

    if (editable) {
        val inActiveGroup = groups.firstOrNull { g ->
            dayMs >= g.startMs && dayMs <= g.endMs && g.id == activeGroupId
        }
        val inAnyGroup = groups.firstOrNull { g ->
            dayMs >= g.startMs && dayMs <= g.endMs
        }
        val inPending = pendingStartMs != null && isSameDay(dayMs, pendingStartMs!!)

        // ── Приоритет маркеров Stop/Resume над обычными контрактами ──
        // Stop-маркер рисуется тёмно-синим, Resume-маркер — жёлтым.
        val stopMarker = groups.firstOrNull { g ->
            g.isStopMarker && dayMs >= g.startMs && dayMs <= g.endMs
        }
        val resumeMarker = groups.firstOrNull { g ->
            g.isResumeMarker && dayMs >= g.startMs && dayMs <= g.endMs
        }

        when {
            stopMarker != null -> {
                bgColor = StopBg.copy(alpha = 0.55f)
                fgColor = StopFg
            }
            resumeMarker != null -> {
                bgColor = ResumeBg.copy(alpha = 0.55f)
                fgColor = ResumeFg
            }
            inActiveGroup != null -> {
                // Активная группа: цвет по isPaid (зелёный/красный),
                // альфа 0.45 для явной видимости.
                val base = if (inActiveGroup.isPaid) StatusOk else StatusOverdue
                bgColor = base.copy(alpha = 0.45f)
                fgColor = ClaudeText
            }
            inAnyGroup != null -> {
                // Неактивная группа: цвет по isPaid, альфа 0.20.
                val base = if (inAnyGroup.isPaid) StatusOk else StatusOverdue
                bgColor = base.copy(alpha = 0.20f)
                fgColor = ClaudeText
            }
            inPending -> {
                bgColor = ClaudeAccent.copy(alpha = 0.30f)
                fgColor = ClaudeText
            }
            else -> {
                bgColor = if (isCurrentMonth) ClaudeCard else ClaudeBackground
                fgColor = if (isCurrentMonth) ClaudeText else ClaudeTextSecondary
            }
        }
    } else {
        when (dayStatusFor(dayMs)) {
            DayStatus.PAID -> { bgColor = StatusOkBg; fgColor = StatusOk }
            DayStatus.UNPAID -> { bgColor = StatusOverdueBg; fgColor = StatusOverdue }
            DayStatus.SUSPENDED -> { bgColor = StatusReturnedBg; fgColor = StatusReturned }
            DayStatus.RESUMED -> { bgColor = ResumeBg.copy(alpha = 0.45f); fgColor = ResumeFg }
            DayStatus.EMPTY -> {
                bgColor = if (isCurrentMonth) ClaudeCard else ClaudeBackground
                fgColor = if (isCurrentMonth) ClaudeText else ClaudeTextSecondary
            }
        }
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable { onDayClick(dayMs) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayOfMonth.toString(),
            fontSize = 12.sp,
            color = fgColor,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

/* ── Панель групп: большие квадратные кнопки статуса + список контрактов ── */
@Composable
private fun GroupsPanel(
    groups: List<ContractGroup>,
    activeGroupId: Int?,
    newGroupIsPaid: Boolean,
    /** 0 = обычный режим, 1 = Stop-маркер, 2 = Resume-маркер. */
    dayMarkerMode: Int = 0,
    /** true = пользователь уже нажал одну из кнопок статуса (Paid/Unpaid/Stop/Resume).
     *  Используется для визуального отклика: пока false — НИ одна из кнопок
     *  Paid/Unpaid не выглядит «выбранной» (даже если newGroupIsPaid=true по
     *  умолчанию). Раньше To'langan отображалась выбранной сразу при открытии
     *  формы, и пользователь при нажатии не видел никаких изменений — ему
     *  казалось что «кнопка не работает». */
    hasSelectedStatus: Boolean = false,
    onNewGroupStatusChange: (Boolean) -> Unit,
    onDayMarkerModeChange: (Int) -> Unit = {},
    onActiveGroupChange: (Int?) -> Unit,
    onAddGroup: () -> Unit,
    onRemoveGroup: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Ряд 1: БОЛЬШИЕ КВАДРАТНЫЕ кнопки Paid / Unpaid ─────────────
        // Пользователь явно попросил сделать кнопки «To'langan» /
        // «To'lanmagan» большими и квадратными. Только после выбора одной
        // из них можно выбирать период в календаре.
        //
        // ВАЖНО про hasSelectedStatus: пока пользователь НЕ нажал ни одну
        // из кнопок (hasSelectedStatus=false), ОБЕ кнопки отображаются как
        // «невыбранные» — даже если newGroupIsPaid=true по умолчанию. Это
        // даёт явный визуальный отклик при нажатии: кнопка «загорается».
        // Раньше To'langan была «загорена» с самого начала, и при тапе
        // пользователь не видел изменений — думал что кнопка сломана.
        val paidSelected = hasSelectedStatus && newGroupIsPaid && dayMarkerMode == 0
        val unpaidSelected = hasSelectedStatus && !newGroupIsPaid && dayMarkerMode == 0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Кнопка статуса «To'langan» (оплаченный) — большая квадратная
            BigStatusTile(
                label = "To'langan",
                selected = paidSelected,
                color = StatusOk,
                modifier = Modifier.weight(1f),
                onClick = {
                    onNewGroupStatusChange(true)
                    onDayMarkerModeChange(0)
                    onAddGroup()  // переводит календарь в режим выбора периода
                }
            )

            // Кнопка статуса «To'lanmagan» (неоплаченный) — большая квадратная
            BigStatusTile(
                label = "To'lanmagan",
                selected = unpaidSelected,
                color = StatusOverdue,
                modifier = Modifier.weight(1f),
                onClick = {
                    onNewGroupStatusChange(false)
                    onDayMarkerModeChange(0)
                    onAddGroup()
                }
            )
        }

        // ── Ряд 2: БОЛЬШИЕ КВАДРАТНЫЕ кнопки Stop / Resume ─────────────
        // По просьбе пользователя: «добав сверху календаря снизу кнопок
        // оплаченный и неоплаченный кнопки остановить и возобновить в точно
        // таком же стиле квадратном но только фон этих кнопок другой
        // кнопка остановить имеет темно синий а кнопка возобновить желтий фон».
        //
        // Логика:
        //   • Stop — тап по дате ставит «остановленный» маркер (TERMINATED).
        //     На этот день аренда приостанавливается, скутер освобождается.
        //   • Resume — тап по дате ставит «возобновлённый» маркер и запускает
        //     авто-создание неоплаченных контрактов от этой даты вперёд
        //     до ближайшего Stop или до следующей недели.
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Кнопка «To'xtash» (Stop) — тёмно-синий фон
            BigStatusTile(
                label = "To'xtash",
                selected = dayMarkerMode == 1,
                color = StopBg,
                solidBg = StopBg,
                solidFg = StopFg,
                modifier = Modifier.weight(1f),
                onClick = {
                    onDayMarkerModeChange(if (dayMarkerMode == 1) 0 else 1)
                }
            )

            // Кнопка «Davom» (Resume) — жёлтый фон
            BigStatusTile(
                label = "Davom",
                selected = dayMarkerMode == 2,
                color = ResumeBg,
                solidBg = ResumeBg,
                solidFg = ResumeFg,
                modifier = Modifier.weight(1f),
                onClick = {
                    onDayMarkerModeChange(if (dayMarkerMode == 2) 0 else 2)
                }
            )
        }

        // ── Список существующих контрактов удалён из GroupsPanel ───────
        // Раньше список контрактов дублировался: один раз здесь (внутри
        // календаря, под кнопками статуса) и второй раз — в MainActivity.kt
        // в блоке «Kontraktlar ro'yxati (N)» под календарём. Пользователь
        // просил оставить только нижний список. Подсказка о пустом списке
        // тоже убрана — её заменяет текст-хелп в самом календаре ниже
        // (см. блок «Yuqoridagi «To'langan» yoki «To'lanmagan» tugmasini
        // bosing» в строках ~469-490).
    }
}

/* ── Большая квадратная плитка статуса ─────────────────────────────────── */
@Composable
private fun BigStatusTile(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    /** Если задано — фон кнопки заливается этим цветом (для Stop/Resume). */
    solidBg: Color? = null,
    /** Если задано — текст/иконка рисуются этим цветом (для Stop/Resume). */
    solidFg: Color? = null,
    onClick: () -> Unit
) {
    // ── Тактильная отдача при тапе ──
    // Пользователь жаловался что «при нажатии статуса ничего не происходит».
    // Помимо визуального отклика (changing selected) добавляем лёгкую
    // вибрацию — это даёт явное физическое подтверждение что тап зарегистрирован,
    // даже если визуально состояние почти не изменилось (например, когда
    // кнопка уже была selected и пользователь тапает повторно).
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // ── Вычисление цветов с учётом solidBg / solidFg ──
    // Для Stop/Resume используем сплошной фон независимо от selected.
    // Для Paid/Unpaid — УСИЛЕННАЯ визуальная обратная связь: при selected
    // фон заливается цветом статуса с alpha 0.55 (заметно ярче, чем 0.35),
    // плюс рамка 3dp вместо 2dp. Это решает жалобу «при нажатии статуса
    // ничего не происходит» — теперь выбор очевиден.
    val bgColor = when {
        solidBg != null -> if (selected) solidBg else solidBg.copy(alpha = 0.45f)
        selected -> color.copy(alpha = 0.55f)
        else -> ClaudeAccentBg
    }
    val fgColor = when {
        solidFg != null -> solidFg
        selected -> ClaudeText
        else -> ClaudeTextSecondary
    }
    val borderColor = when {
        solidBg != null -> solidBg
        selected -> color
        else -> ClaudeDivider  // невыбранная кнопка — серая рамка (была color)
    }
    val indicatorColor = solidFg ?: color

    Box(
        modifier = modifier
            .height(64.dp)  // большая высота → квадратная форма при weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                )
                onClick()
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Цветной кружок-индикатор статуса
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                color = fgColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = indicatorColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}


/* ── Карточка контракта в списке (удалена — список теперь рисуется только
   в MainActivity.kt в блоке «Kontraktlar ro'yxati» под календарём). ──── */


/* ── Легенда ────────────────────────────────────────────────────────────── */
@Composable
private fun LegendItem(label: String, bg: Color, fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(bg)
                .border(1.dp, fg, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = ClaudeTextSecondary
        )
    }
}

/* ── Пилюля статуса для свёрнутой шапки календаря ──────────────────────── */
/* Показывает число групп с данным статусом (оплачено / не оплачено).       */
/* Используется в шапке календаря (видна и в свёрнутом, и в развёрнутом     */
/* состоянии), чтобы пользователь сразу видел, сколько у него контрактов    */
/* каждого типа — без необходимости разворачивать календарь.                */
@Composable
private fun StatusPill(count: Int, color: Color) {
    Row(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = count.toString(),
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/* ── Утилиты ────────────────────────────────────────────────────────────── */
private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
           ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH) &&
           ca.get(Calendar.DAY_OF_MONTH) == cb.get(Calendar.DAY_OF_MONTH)
}
