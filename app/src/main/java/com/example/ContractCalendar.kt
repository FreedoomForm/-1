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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
    val existingContractId: Int? = null
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

    // ── Текущий статус для новых групп (To'langan / To'lanmagan) ───────
    // По умолчанию "To'langan" (оплаченный). Пользователь может переключить
    // большими квадратными кнопками в шапке календаря перед выбором периода.
    var newGroupIsPaid by remember { mutableStateOf(true) }

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
            // ── Шапка: месяц/год + навигация + статус + стрелка свернуть/развернуть ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Левая часть: навигация ← месяц/год →
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (viewMonth == 0) { viewMonth = 11; viewYear-- } else viewMonth--
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Oldingi oy")
                    }
                    Text(
                        text = monthTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ClaudeText
                    )
                    IconButton(onClick = {
                        if (viewMonth == 11) { viewMonth = 0; viewYear++ } else viewMonth++
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Keyingi oy")
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
                    onNewGroupStatusChange = {
                        newGroupIsPaid = it
                        // Пользователь выбрал статус → теперь можно выбирать
                        // период в календаре.
                        hasSelectedStatus = true
                        // Сбрасываем частично выбранный период, если он был,
                        // чтобы начать заново с новым статусом.
                        pendingStartMs = null
                        onActiveGroupChange(null)
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
                                    // ── Блокировка выбора периода до выбора статуса ──
                                    // Пользователь должен сначала нажать большую
                                    // квадратную кнопку «To'langan» или «To'lanmagan»
                                    // (это установит hasSelectedStatus = true), и
                                    // только потом он сможет тапать по дням для
                                    // выбора периода.
                                    if (editable && !hasSelectedStatus && activeGroupId == null) {
                                        return@DayCell  // игнорируем тап
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
                                            onPeriodCreated = { hasSelectedStatus = false }
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
                val hint = when {
                    activeGroupId != null -> "Kontrakt ${groups.indexOfFirst { it.id == activeGroupId } + 1} tanlandi"
                    !hasSelectedStatus && editable -> {
                        // Подсказка для пользователя: сначала выбери статус
                        "Yuqoridagi «To'langan» yoki «To'lanmagan» tugmasini bosing"
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
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = ClaudeTextSecondary,
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
    onPeriodCreated: () -> Unit = {}
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
            val now = System.currentTimeMillis()
            val isOverdue = (now - ms) > weekMs
            val autoIsPaid = !isOverdue
            val start = ms
            val realEnd = ms + weekMs - 1
            val newId = (groups.maxOfOrNull { it.id } ?: 0) + 1
            val newGroup = ContractGroup(
                id = newId, startMs = start, endMs = realEnd, isPaid = autoIsPaid
            )
            onGroupsChange(groups + newGroup)
            onActiveGroupChange(newId)
            setPendingStart(null)
            onPeriodCreated()  // сбрасываем hasSelectedStatus — нужно снова выбрать
        } else {
            // ── Два разных дня → диапазон с пользовательским статусом ──
            val start = minOf(pendingStartMs, ms)
            val realEnd = maxOf(pendingStartMs, ms) + dayMs - 1
            val newId = (groups.maxOfOrNull { it.id } ?: 0) + 1
            val newGroup = ContractGroup(
                id = newId, startMs = start, endMs = realEnd, isPaid = newGroupIsPaid
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
            val now = System.currentTimeMillis()
            val isOverdue = (now - ms) > weekMs
            val autoIsPaid = !isOverdue
            val start = ms
            val realEnd = ms + weekMs - 1
            // Используем отрицательный id как временный — реальный id
            // присвоит БД при создании контракта. Колбэк onAddGroup должен
            // проигнорировать это поле и использовать startMs/endMs/isPaid.
            val newGroup = ContractGroup(
                id = -1, startMs = start, endMs = realEnd, isPaid = autoIsPaid
            )
            onAddGroup(newGroup)
            setPendingStart(null)
        } else {
            // Два разных дня → диапазон с пользовательским статусом
            val start = minOf(pendingStartMs, ms)
            val realEnd = maxOf(pendingStartMs, ms) + dayMs - 1
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

        when {
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
    onNewGroupStatusChange: (Boolean) -> Unit,
    onActiveGroupChange: (Int?) -> Unit,
    onAddGroup: () -> Unit,
    onRemoveGroup: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Ряд 1: БОЛЬШИЕ КВАДРАТНЫЕ кнопки статуса ─────────────────
        // Пользователь явно попросил сделать кнопки «To'langan» /
        // «To'lanmagan» большими и квадратными. Только после выбора одной
        // из них можно выбирать период в календаре.
        //
        // Кнопка «+» удалена — она больше не нужна: пользователь сразу
        // выбирает статус (оплаченный/неоплаченный), а затем тапает по
        // двум датам в календаре. Не нужно отдельное действие «начать
        // новую группу».
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
                selected = newGroupIsPaid,
                color = StatusOk,
                modifier = Modifier.weight(1f),
                onClick = {
                    onNewGroupStatusChange(true)
                    onAddGroup()  // переводит календарь в режим выбора периода
                }
            )

            // Кнопка статуса «To'lanmagan» (неоплаченный) — большая квадратная
            BigStatusTile(
                label = "To'lanmagan",
                selected = !newGroupIsPaid,
                color = StatusOverdue,
                modifier = Modifier.weight(1f),
                onClick = {
                    onNewGroupStatusChange(false)
                    onAddGroup()  // переводит календарь в режим выбора периода
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── Ряд 2: список существующих контрактов ─────────────────────
        // Каждый контракт показан как отдельная карточка со:
        //   • индикатором статуса (зелёная/красная полоса слева),
        //   • датами начала и окончания,
        //   • кнопкой «O'chir» (удалить).
        // Список вертикальный — у каждой карточки своя строка, без
        // горизонтальной прокрутки. Если контрактов нет — показываем
        // подсказку, призывающую выбрать статус выше.
        if (groups.isEmpty()) {
            Text(
                text = "Kontraktlar yo'q — yuqoridagi tugmalardan birini bosing",
                fontSize = 12.sp,
                color = ClaudeTextSecondary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { g ->
                    ContractListItem(
                        group = g,
                        isActive = g.id == activeGroupId,
                        onClick = { onActiveGroupChange(if (g.id == activeGroupId) null else g.id) },
                        onRemove = { onRemoveGroup(g.id) }
                    )
                }
            }
        }
    }
}

/* ── Большая квадратная плитка статуса ─────────────────────────────────── */
@Composable
private fun BigStatusTile(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(64.dp)  // большая высота → квадратная форма при weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) color.copy(alpha = 0.35f) else ClaudeAccentBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = color,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
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
                    .background(color)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (selected) ClaudeText else ClaudeTextSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/* ── Карточка контракта в списке под календарём ────────────────────────── */
@Composable
private fun ContractListItem(
    group: ContractGroup,
    isActive: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val color = if (group.isPaid) StatusOk else StatusOverdue
    val dateFmt = remember {
        java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
    }
    val startStr = dateFmt.format(java.util.Date(group.startMs))
    val endStr = dateFmt.format(java.util.Date(group.endMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) color.copy(alpha = 0.15f) else ClaudeAccentBg)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Левая цветная полоса-индикатор статуса
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )

        // Даты начала → окончания
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$startStr → $endStr",
                fontSize = 13.sp,
                color = ClaudeText,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (group.isPaid) "To'langan" else "To'lanmagan",
                fontSize = 11.sp,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }

        // Кнопка удаления контракта
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(ClaudeAccentBg)
                .border(1.dp, ClaudeDivider, CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Kontraktni o'chirish",
                tint = StatusOverdue,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}


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
