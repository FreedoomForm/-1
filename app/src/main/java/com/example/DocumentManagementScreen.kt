package com.example

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ContractTemplate
import com.example.data.TemplateContent
import com.example.ui.ContractTemplateViewModel
import com.example.ui.components.DangerButton
import com.example.ui.components.PrimaryButton
import com.example.ui.components.TextActionButton
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeGold
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Документооборот» — список шаблонов с раскрытием на версии.
 *
 * Архитектура (по образцу RenterTable в MainActivity.kt:3404):
 *   • 2 верхнеуровневые строки — по одной на каждый TYPE (LIMITED, UNLIMITED)
 *   • Каждая строка: треугольник ▶ + имя шаблона + имя активной версии (с ★)
 *   • Клик по треугольнику → раскрытие списка версий под строкой (без анимации,
 *     простой if (isExpanded) { ... })
 *   • Клик по строке шаблона → переход на VersionsTableScreen (по образцу
 *     NavigationState.RenterHistory → RenterContractHistoryScreen)
 *
 * Universal-кнопки TopAppBar (+/✎/🗑/🔍/★) продолжают работать через
 * [createTrigger], [editTrigger], [deleteTrigger], [searchTrigger].
 */
@Composable
fun DocumentManagementScreen(
    docTemplateViewModel: ContractTemplateViewModel,
    createTrigger: Int = 0,
    editTrigger: Int = 0,
    deleteTrigger: Int = 0,
    searchTrigger: Int = 0,
    onOpenVersionsTable: (String) -> Unit = {},  // type → navigation
    onOpenPdfPreview: (Int) -> Unit = {}          // templateId → navigation
) {
    val viewModel = docTemplateViewModel
    val allTemplates by viewModel.templates.collectAsStateWithLifecycle()
    val activeTemplateId by viewModel.activeTemplateId.collectAsStateWithLifecycle()
    val selectedTemplateId by viewModel.selectedTemplateId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    // ── State для раскрытия шаблонов (Set<String> type) ─────────────────
    // По образцу expandedRenterIds в MainActivity.kt:3521 — можно раскрыть
    // несколько шаблонов одновременно.
    var expandedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ── Триггеры CRUD из TopAppBar (по образцу ContractListScreen.kt:131-181)
    var lastCreate by remember { mutableStateOf(createTrigger) }
    var lastEdit by remember { mutableStateOf(editTrigger) }
    var lastDelete by remember { mutableStateOf(deleteTrigger) }
    var lastSearch by remember { mutableStateOf(searchTrigger) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSearchPanel by remember { mutableStateOf(false) }

    // ── Выбор шаблона (type) для создания новой версии ───────────────
    // Пользователь долго нажимает на строку шаблона → выбирает его.
    // После этого «+» в TopAppBar создаёт новую версию для ВЫБРАННОГО типа.
    var selectedTypeForCreate by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(createTrigger) {
        if (createTrigger > lastCreate) {
            // «+» работает только если выбран шаблон (долгий тап)
            if (selectedTypeForCreate != null) {
                viewModel.selectType(selectedTypeForCreate!!)
                showCreateDialog = true
            }
            lastCreate = createTrigger
        }
    }
    LaunchedEffect(editTrigger) {
        if (editTrigger > lastEdit && selectedTemplateId != null) {
            showEditDialog = true
            lastEdit = editTrigger
        }
    }
    LaunchedEffect(deleteTrigger) {
        if (deleteTrigger > lastDelete && selectedTemplateId != null) {
            showDeleteConfirm = true
            lastDelete = deleteTrigger
        }
    }
    LaunchedEffect(searchTrigger) {
        if (searchTrigger > lastSearch) {
            showSearchPanel = !showSearchPanel
            lastSearch = searchTrigger
        }
    }

    // Группируем шаблоны по type (LIMITED, UNLIMITED)
    val templatesByType = allTemplates.groupBy { it.type }

    // Список типов в фиксированном порядке: UNLIMITED сначала, LIMITED потом
    val types = listOf(ContractTemplate.TYPE_UNLIMITED, ContractTemplate.TYPE_LIMITED)

    // ── Плоский список рендер-айтемов ────────────────────────────────────
    // Compose не разрешает item { } ВНУТРИ items { } блока (вложенные лямбды
    // ломают implicit receiver — Kotlin бросает "cannot be called in this
    // context with an implicit receiver"). Поэтому строим плоский список
    // и рендерим одним items() вызовом.
    //
    // Каждый RenderItem — это либо заголовок типа (template = null),
    // либо конкретная версия (template != null).
    data class RenderItem(
        val type: String,
        val isHeader: Boolean,
        val template: ContractTemplate? = null
    )

    val renderItems = buildList {
        types.forEach { type ->
            add(RenderItem(type = type, isHeader = true))
            if (type in expandedTypes) {
                (templatesByType[type] ?: emptyList()).forEach { template ->
                    add(RenderItem(type = type, isHeader = false, template = template))
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (searchQuery.isNotBlank()) {
            item("search_indicator") {
                Surface(
                    color = ClaudeAccentBg,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Фильтр: \"$searchQuery\"",
                        modifier = Modifier.padding(8.dp),
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(
            items = renderItems,
            key = { item ->
                if (item.isHeader) "header_${item.type}"
                else "version_${item.template?.id}"
            }
        ) { renderItem ->
            if (renderItem.isHeader) {
                val type = renderItem.type
                val typeTemplates = templatesByType[type] ?: emptyList()
                val activeTemplate = typeTemplates.firstOrNull { it.isActive }
                val typeName = when (type) {
                    ContractTemplate.TYPE_UNLIMITED -> "Бесконечная аренда"
                    else -> "Конечная аренда (неделя)"
                }
                val isExpanded = type in expandedTypes

                TemplateRow(
                    typeName = typeName,
                    activeVersionName = activeTemplate?.name ?: "(нет активной)",
                    isActiveSet = activeTemplate != null,
                    isExpanded = isExpanded,
                    isSelected = selectedTypeForCreate == type,
                    onToggleExpand = {
                        expandedTypes = if (isExpanded) {
                            expandedTypes - type
                        } else {
                            expandedTypes + type
                        }
                    },
                    onClick = {
                        // Клик по шаблону → сразу PdfPreview активной версии
                        val active = activeTemplate ?: typeTemplates.firstOrNull()
                        if (active != null) {
                            onOpenPdfPreview(active.id)
                        }
                    },
                    onLongClick = {
                        // Долгий тап → выбираем шаблон для создания новой версии
                        selectedTypeForCreate = if (selectedTypeForCreate == type) null else type
                    }
                )
            } else {
                val template = renderItem.template ?: return@items
                val typeTemplates = templatesByType[renderItem.type] ?: emptyList()
                // Проверяем, что список не пустой — если пусто, показываем placeholder
                if (typeTemplates.isEmpty()) {
                    Surface(
                        color = ClaudeCard,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, top = 2.dp, bottom = 4.dp)
                    ) {
                        Text(
                            "Версий нет. Нажмите «+» в верхнем баре.",
                            modifier = Modifier.padding(12.dp),
                            color = ClaudeTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    VersionRow(
                        template = template,
                        isSelected = template.id == selectedTemplateId,
                        onClick = { onOpenPdfPreview(template.id) },
                        onLongClick = {
                            // Долгий тап → выбираем версию для ✎/🗑
                            viewModel.selectTemplate(template.id)
                        }
                    )
                }
            }
        }
    }

    // ── Диалоги (как раньше) ────────────────────────────────────────────────
    if (showCreateDialog) {
        TemplateEditorDialog(
            title = "Новая версия шаблона",
            initialName = "",
            initialNotes = "",
            initialContent = TemplateContent.DEFAULT_FOR_UNLIMITED.let {
                if (viewModel.selectedType.value == ContractTemplate.TYPE_LIMITED)
                    TemplateContent.DEFAULT_FOR_LIMITED
                else it
            },
            isCreate = true,
            onDismiss = { showCreateDialog = false },
            onSave = { name, content, notes ->
                viewModel.createTemplate(name, content, notes)
                showCreateDialog = false
            }
        )
    }
    if (showEditDialog && selectedTemplateId != null) {
        val templateId = selectedTemplateId!!
        var loadedTemplate by remember { mutableStateOf<ContractTemplate?>(null) }
        LaunchedEffect(templateId) {
            loadedTemplate = viewModel.getById(templateId)
        }
        loadedTemplate?.let { template ->
            val initialContent = viewModel.parseContent(template.contentJson)
            TemplateEditorDialog(
                title = "Редактирование: ${template.name}",
                initialName = template.name,
                initialNotes = template.notes ?: "",
                initialContent = initialContent,
                isCreate = false,
                onDismiss = { showEditDialog = false },
                onSave = { name, content, notes ->
                    viewModel.updateTemplate(templateId, name, content, notes)
                    showEditDialog = false
                }
            )
        }
    }
    if (showDeleteConfirm && selectedTemplateId != null) {
        val templateId = selectedTemplateId!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить версию?") },
            text = { Text("Версия будет перемещена в корзину. Можно восстановить позже.") },
            confirmButton = {
                DangerButton(
                    label = "Удалить",
                    icon = Icons.Default.Delete,
                    onClick = {
                        viewModel.deleteTemplate(templateId) { showDeleteConfirm = false }
                    }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Отмена",
                    icon = Icons.Default.Close,
                    onClick = { showDeleteConfirm = false }
                )
            }
        )
    }
    if (showSearchPanel) {
        AlertDialog(
            onDismissRequest = { showSearchPanel = false },
            title = { Text("Поиск версий") },
            text = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = { Text("Имя версии") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                PrimaryButton(
                    label = "OK",
                    icon = Icons.Default.Search,
                    onClick = { showSearchPanel = false }
                )
            },
            dismissButton = {
                TextActionButton(
                    label = "Сбросить",
                    icon = Icons.Default.Clear,
                    onClick = {
                        viewModel.setSearchQuery("")
                        showSearchPanel = false
                    }
                )
            }
        )
    }
}

// ── Подкомпоненты ─────────────────────────────────────────────────────────

/**
 * Строка верхнеуровневого шаблона (одна на тип договора).
 * По образцу RenterTable строки 3582-3666: треугольник + основная строка.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateRow(
    typeName: String,
    activeVersionName: String,
    isActiveSet: Boolean,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) ClaudeAccentBg else ClaudeCard,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) ClaudeAccent else ClaudeDivider
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Треугольник раскрытия (по образцу MainActivity.kt:3603-3637) ──
            val arrowRotation = if (isExpanded) 90f else 0f
            val arrowTint = if (isExpanded) ClaudeAccent else ClaudeTextSecondary
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(40.dp)
                    .clickable { onToggleExpand() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Свернуть" else "Версии",
                    tint = arrowTint,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(arrowRotation)
                )
            }
            // ── Основная строка (клик = открыть превью, долгий тап = выбрать) ──
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = typeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClaudeText,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Активная версия: $activeVersionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
                if (isActiveSet) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Есть активная версия",
                        tint = ClaudeGold,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Выбран",
                        tint = ClaudeAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Строка версии шаблона (раскрывается под строкой типа).
 * По образцу contract row в RenterTable строки 3973-4118.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionRow(
    template: ContractTemplate,
    isSelected: Boolean,
    onClick: () -> Unit,      // открыть превью PDF
    onLongClick: () -> Unit   // выбрать для ✎/🗑 (долгий тап)
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) ClaudeAccentBg else ClaudeCard,
        border = androidx.compose.foundation.BorderStroke(
            width = if (template.isActive) 2.dp else 1.dp,
            color = if (template.isActive) ClaudeGold else ClaudeDivider
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, top = 2.dp, bottom = 2.dp, end = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (template.isActive) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Активная",
                    tint = ClaudeGold,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClaudeText,
                    fontWeight = if (template.isActive) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = "Создан: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(template.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
                template.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(
                        text = "Примечание: $notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/**
 * Универсальный диалог создания/редактирования версии шаблона.
 * Теперь с дополнительным полем «Примечание» (notes).
 */
@Composable
private fun TemplateEditorDialog(
    title: String,
    initialName: String,
    initialNotes: String,
    initialContent: TemplateContent,
    isCreate: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, TemplateContent, String?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }
    var landlordName by remember { mutableStateOf(initialContent.landlordName) }
    var landlordAddress by remember { mutableStateOf(initialContent.landlordAddress) }
    var landlordBank by remember { mutableStateOf(initialContent.landlordBank) }
    var landlordAccount by remember { mutableStateOf(initialContent.landlordAccount) }
    var landlordMfo by remember { mutableStateOf(initialContent.landlordMfo) }
    var landlordInn by remember { mutableStateOf(initialContent.landlordInn) }
    var landlordPhone by remember { mutableStateOf(initialContent.landlordPhone) }
    var landlordDirector by remember { mutableStateOf(initialContent.landlordDirector) }
    var bodyText by remember { mutableStateOf(initialContent.bodyText) }
    var showPlaceholdersHelp by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = ClaudeText) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя версии") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Примечание (что изменено, зачем)") },
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Реквизиты арендодателя", style = MaterialTheme.typography.titleSmall, color = ClaudeText)
                OutlinedTextField(value = landlordName, onValueChange = { landlordName = it },
                    label = { Text("Название ЯТТ/ИП/ООО") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordAddress, onValueChange = { landlordAddress = it },
                    label = { Text("Адрес") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordBank, onValueChange = { landlordBank = it },
                    label = { Text("Банк") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordAccount, onValueChange = { landlordAccount = it },
                    label = { Text("Расчётный счёт") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordMfo, onValueChange = { landlordMfo = it },
                    label = { Text("МФО") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordInn, onValueChange = { landlordInn = it },
                    label = { Text("ИНН") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordPhone, onValueChange = { landlordPhone = it },
                    label = { Text("Телефон") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landlordDirector, onValueChange = { landlordDirector = it },
                    label = { Text("ФИО директора") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Текст договора", style = MaterialTheme.typography.titleSmall, color = ClaudeText)
                    TextActionButton(
                        label = if (showPlaceholdersHelp) "Скрыть" else "Плейсхолдеры",
                        icon = Icons.Default.HelpOutline,
                        onClick = { showPlaceholdersHelp = !showPlaceholdersHelp }
                    )
                }
                if (showPlaceholdersHelp) {
                    Card(colors = CardDefaults.cardColors(containerColor = ClaudeAccentBg)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Синтаксис (по строкам):", color = ClaudeText,
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("## Текст — заголовок (крупный, центр, +3pt)",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall)
                            Text("### Текст — заголовок раздела (+1pt, bold)",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall)
                            Text("> Текст — подпись",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall)
                            Text("* Текст — жирный body",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall)
                            Text("  Текст (2 пробела) — body с отступом 12pt",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall)
                            Text("Пустая строка — разделитель параграфов",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Плейсхолдеры (заменяются на реальные данные):",
                                color = ClaudeText, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold)
                            Text("{{contractNumber}}, {{contractDate}}, {{contractDay}}, {{contractFullDate}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{weekStart}}, {{weekEnd}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{tenantName}}, {{tenantPhone}}, {{tenantPassport}}, {{tenantAddress}}, {{tenantPinfl}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{tenantPassportFilled}}, {{tenantAddressFilled}}, {{tenantPinflFilled}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{scooterName}}, {{scooterVin}}, {{scooterEngine}}, {{scooterSerial}}, {{extraInfo}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{scooterVinFilled}}, {{scooterEngineFilled}}, {{scooterSerialFilled}}, {{extraInfoFilled}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{batteryIdsList}}, {{batteryIdsActa}}, {{batteryDamageText}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{weeklyAmount}}, {{dailyAmount}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{landlordName}}, {{landlordAddress}}, {{landlordBank}}, {{landlordAccount}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("{{landlordMfo}}, {{landlordInn}}, {{landlordPhone}}, {{landlordDirector}}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { bodyText = it },
                    label = { Text("Текст договора") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                label = if (isCreate) "Создать" else "Сохранить",
                icon = if (isCreate) Icons.Default.Add else Icons.Default.Save,
                enabled = name.isNotBlank(),
                onClick = {
                    val content = TemplateContent(
                        landlordName = landlordName,
                        landlordAddress = landlordAddress,
                        landlordBank = landlordBank,
                        landlordAccount = landlordAccount,
                        landlordMfo = landlordMfo,
                        landlordInn = landlordInn,
                        landlordPhone = landlordPhone,
                        landlordDirector = landlordDirector,
                        bodyText = bodyText
                    )
                    onSave(name, content, notes.takeIf { it.isNotBlank() })
                }
            )
        },
        dismissButton = {
            TextActionButton(
                label = "Отмена",
                icon = Icons.Default.Close,
                onClick = onDismiss
            )
        }
    )
}
