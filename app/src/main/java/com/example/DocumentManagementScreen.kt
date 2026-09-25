package com.example

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractTemplate
import com.example.data.TemplateContent
import com.example.ui.ContractTemplateViewModel
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeGold
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.DangerButton
import com.example.ui.components.TextActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Документооборот» — управление версиями шаблонов PDF-договора.
 *
 * Принимает [createTrigger], [editTrigger], [deleteTrigger], [searchTrigger]
 * от MainActivity TopAppBar (по образцу ContractListScreen). Реагирует на
 * увеличение значения триггера (LaunchedEffect).
 *
 * Содержит 4 секции в одном LazyColumn:
 *   1. TypeSelector — выбор типа документа (бесконечная / конечная аренда).
 *   2. Список версий выбранного типа — карты с золотой рамкой для активной.
 *   3. RenterSelector — выбор клиента для демо-данных.
 *   4. Превью PDF — список Bitmap'ов страниц через PdfRenderer.
 *
 * Диалоги (открываются по триггерам):
 *   • CreateTemplateDialog — имя + 8 реквизитов + bodyText.
 *   • EditTemplateDialog — то же для существующей версии.
 *   • DeleteConfirmDialog — подтверждение мягкого удаления.
 *   • SearchPanel — ввод строки поиска.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentManagementScreen(
    docTemplateViewModel: ContractTemplateViewModel,
    createTrigger: Int = 0,
    editTrigger: Int = 0,
    deleteTrigger: Int = 0,
    searchTrigger: Int = 0
) {
    // Локальный alias для удобства внутри функций
    val viewModel = docTemplateViewModel
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val selectedTemplateId by viewModel.selectedTemplateId.collectAsStateWithLifecycle()
    val renters by viewModel.renters.collectAsStateWithLifecycle()
    val selectedRenterId by viewModel.selectedRenterId.collectAsStateWithLifecycle()
    val activeTemplateId by viewModel.activeTemplateId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val previewBitmaps by viewModel.previewBitmaps.collectAsStateWithLifecycle()
    val isPreviewLoading by viewModel.isPreviewLoading.collectAsStateWithLifecycle()
    val previewError by viewModel.previewError.collectAsStateWithLifecycle()

    // ── Реакция на триггеры из TopAppBar (по образцу ContractListScreen.kt:131-181)
    var lastCreate by remember { mutableStateOf(createTrigger) }
    var lastEdit by remember { mutableStateOf(editTrigger) }
    var lastDelete by remember { mutableStateOf(deleteTrigger) }
    var lastSearch by remember { mutableStateOf(searchTrigger) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSearchPanel by remember { mutableStateOf(false) }

    LaunchedEffect(createTrigger) {
        if (createTrigger > lastCreate) {
            showCreateDialog = true
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

    // ── Регенерация превью при изменении выбора
    LaunchedEffect(selectedTemplateId, selectedRenterId, selectedType) {
        viewModel.regeneratePreview()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. TypeSelector — выбор типа
        item { TypeSelector(selectedType = selectedType, onSelect = viewModel::selectType) }

        // 2. Заголовок списка версий
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Версии шаблонов (${templates.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = ClaudeText
                )
                if (searchQuery.isNotBlank()) {
                    Text(
                        text = "Фильтр: \"$searchQuery\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
        }

        // 2.1 Список версий (карточки)
        if (templates.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ClaudeCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Нет созданных версий",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClaudeTextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Нажмите «+» в верхнем баре, чтобы создать первую версию",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClaudeTextSecondary
                        )
                    }
                }
            }
        } else {
            items(templates, key = { it.id }) { template ->
                VersionCard(
                    template = template,
                    isSelected = template.id == selectedTemplateId,
                    onClick = { viewModel.selectTemplate(template.id) }
                )
            }
        }

        // 3. RenterSelector — выбор клиента
        item {
            Text(
                "Демо-клиент для превью",
                style = MaterialTheme.typography.titleMedium,
                color = ClaudeText
            )
            RenterSelector(
                renters = renters,
                selectedId = selectedRenterId,
                onSelect = viewModel::selectRenter
            )
        }

        // 4. Превью PDF
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Превью PDF",
                    style = MaterialTheme.typography.titleMedium,
                    color = ClaudeText
                )
                Text(
                    if (previewBitmaps.isNotEmpty()) "${previewBitmaps.size} стр." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClaudeTextSecondary
                )
            }
        }

        if (isPreviewLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ClaudeAccent)
                }
            }
        } else if (previewError != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ClaudeCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = previewError!!,
                        modifier = Modifier.padding(16.dp),
                        color = ClaudeTextSecondary
                    )
                }
            }
        } else if (previewBitmaps.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ClaudeCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Выберите версию и клиента для превью",
                        modifier = Modifier.padding(16.dp),
                        color = ClaudeTextSecondary
                    )
                }
            }
        } else {
            items(previewBitmaps.size) { i ->
                val bitmap = previewBitmaps[i]
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(595f / 842f),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Страница ${i + 1}",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // ── Диалоги ────────────────────────────────────────────────────────────
    if (showCreateDialog) {
        TemplateEditorDialog(
            title = "Новая версия шаблона",
            initialName = "",
            initialContent = TemplateContent.DEFAULT_FOR_UNLIMITED.let {
                if (selectedType == ContractTemplate.TYPE_LIMITED)
                    TemplateContent.DEFAULT_FOR_LIMITED
                else it
            },
            isCreate = true,
            onDismiss = { showCreateDialog = false },
            onSave = { name, content ->
                viewModel.createTemplate(name, content)
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
                initialContent = initialContent,
                isCreate = false,
                onDismiss = { showEditDialog = false },
                onSave = { name, content ->
                    viewModel.updateTemplate(templateId, name, content)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(selectedType: String, onSelect: (String) -> Unit) {
    val tabs = listOf(
        ContractTemplate.TYPE_UNLIMITED to "Бесконечная аренда",
        ContractTemplate.TYPE_LIMITED to "Конечная аренда (неделя)"
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selectedType }.coerceAtLeast(0)
    PrimaryTabRow(selectedTabIndex = selectedIndex) {
        tabs.forEachIndexed { i, (type, label) ->
            Tab(
                selected = i == selectedIndex,
                onClick = { onSelect(type) },
                text = { Text(label) }
            )
        }
    }
}

@Composable
private fun VersionCard(
    template: ContractTemplate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (template.isActive) 2.dp else 1.dp,
                color = if (template.isActive) ClaudeGold else ClaudeDivider,
                shape = RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ClaudeAccentBg else ClaudeCard
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (template.isActive) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Активная",
                    tint = ClaudeGold,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
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
                template.updatedAt?.let { upd ->
                    Text(
                        text = "Изменён: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(upd))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun RenterSelector(
    renters: List<com.example.data.Renter>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit
) {
    val expanded = remember { mutableStateOf(false) }
    val selectedRenter = renters.firstOrNull { it.id == selectedId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ClaudeCard)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Заголовок-кнопка
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded.value = !expanded.value }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedRenter?.name ?: "Выберите клиента",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedRenter != null) ClaudeText else ClaudeTextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded.value) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Раскрыть",
                    tint = ClaudeTextSecondary
                )
            }
            // Список клиентов
            if (expanded.value) {
                if (renters.isEmpty()) {
                    Text(
                        "Нет активных арендаторов. Добавьте хотя бы одного на вкладке «Ijarachilar».",
                        modifier = Modifier.padding(8.dp),
                        color = ClaudeTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    renters.forEach { renter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(if (selectedId == renter.id) null else renter.id)
                                    expanded.value = false
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (renter.id == selectedId) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = ClaudeAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(renter.name, color = ClaudeText, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    renter.phoneNumber.ifBlank { "—" },
                                    color = ClaudeTextSecondary,
                                    style = MaterialTheme.typography.bodySmall
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
 * Универсальный диалог создания/редактирования версии шаблона.
 * Содержит: имя версии + 8 полей реквизитов арендодателя + большой
 * редактор текста договора (с моноширинным шрифтом).
 */
@Composable
private fun TemplateEditorDialog(
    title: String,
    initialName: String,
    initialContent: TemplateContent,
    isCreate: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, TemplateContent) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
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
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, color = ClaudeText)
        },
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
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Реквизиты арендодателя",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClaudeText
                )
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
                    Text("Текст договора",
                        style = MaterialTheme.typography.titleSmall, color = ClaudeText)
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
                            Text("\${contractNumber}, \${contractDate}, \${contractDay}, \${contractFullDate}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${weekStart}, \${weekEnd}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${tenantName}, \${tenantPhone}, \${tenantPassport}, \${tenantAddress}, \${tenantPinfl}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${tenantPassportFilled}, \${tenantAddressFilled}, \${tenantPinflFilled}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${scooterName}, \${scooterVin}, \${scooterEngine}, \${scooterSerial}, \${extraInfo}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${scooterVinFilled}, \${scooterEngineFilled}, \${scooterSerialFilled}, \${extraInfoFilled}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${batteryIdsList}, \${batteryIdsActa}, \${batteryDamageText}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${weeklyAmount}, \${dailyAmount}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${landlordName}, \${landlordAddress}, \${landlordBank}, \${landlordAccount}",
                                color = ClaudeTextSecondary, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("\${landlordMfo}, \${landlordInn}, \${landlordPhone}, \${landlordDirector}",
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
                    textStyle = TextStyle(
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
                icon = if (isCreate) Icons.Default.Add
                else Icons.Default.Save,
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
                    onSave(name, content)
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
