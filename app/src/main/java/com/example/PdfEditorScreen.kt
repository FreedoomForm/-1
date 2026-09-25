package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractTemplate
import com.example.data.TemplateAnnotation
import com.example.ui.ContractTemplateViewModel
import com.example.ui.PdfContractGenerator
import com.example.ui.PdfTextBlock
import com.example.ui.PdfTextExtractor
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeGold
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary
import java.io.File

/**
 * Визуальный PDF редактор (Вариант A — PdfBox-Android).
 *
 * Пользователь видит страницу PDF, может тапнуть в любом месте — откроется
 * диалог ввода текста. Введённый текст становится аннотацией, которая
 * рендерится как overlay на странице.
 *
 * Текст аннотации может содержать {{placeholders}} (например, {{tenantName}}).
 * При генерации финального PDF для контракта, {{placeholders}} заменяются
 * на реальные данные.
 *
 * Долгое нажатие на аннотацию → удалить.
 * Кнопка «Сохранить» в TopAppBar → сохраняет все аннотации в БД.
 *
 * Аннотации хранятся в [ContractTemplate.annotationsJson] (List<TemplateAnnotation>
 * сериализован в JSON). Координаты нормализованы (0..1) относительно ширины/высоты
 * страницы, чтобы корректно отображаться на разных экранах.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(
    templateId: Int,
    onBack: () -> Unit,
    viewModel: ContractTemplateViewModel = viewModel()
) {
    var template by remember { mutableStateOf<ContractTemplate?>(null) }
    var annotations by remember { mutableStateOf<List<TemplateAnnotation>>(emptyList()) }
    var textBlocks by remember { mutableStateOf<List<PdfTextBlock>>(emptyList()) }
    var previewBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableStateOf(0) }

    // Диалог ввода текста для новой аннотации
    var pendingPosition by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var pendingTextBlock by remember { mutableStateOf<PdfTextBlock?>(null) }
    var editingAnnotation by remember { mutableStateOf<TemplateAnnotation?>(null) }
    var annotationText by remember { mutableStateOf("") }

    var showSaveToast by remember { mutableStateOf<String?>(null) }

    // Загрузить template, annotations, и сгенерировать preview
    LaunchedEffect(templateId) {
        isLoading = true
        error = null
        try {
            val tpl = viewModel.getById(templateId)
            if (tpl == null) {
                error = "Шаблон не найден (ID=$templateId)"
                isLoading = false
                return@LaunchedEffect
            }
            template = tpl
            annotations = viewModel.getAnnotations(templateId)
            // Генерируем preview БЕЗ демо-данных (placeholder labels)
            val bitmaps = viewModel.generatePreviewWithoutDemoData(templateId)
            if (bitmaps.isEmpty()) {
                error = "Не удалось сгенерировать PDF"
                isLoading = false
                return@LaunchedEffect
            }
            previewBitmaps = bitmaps
            // Извлекаем текстовые блоки из сгенерированного PDF для Word-like
            // редактирования — пользователь тапает на существующий текст.
            val pdfFile = File(
                viewModel.getApplication<Application>().cacheDir,
                "preview_nodemo_latest.pdf"
            )
            // generatePreviewWithoutDemoData сохраняет PDF во cacheDir с именем
            // preview_nodemo_<timestamp>.pdf — нам нужен последний.
            val cacheDir = viewModel.getApplication<Application>().cacheDir
            val latestPdf = cacheDir.listFiles { f -> f.name.startsWith("preview_nodemo_") }
                ?.maxByOrNull { it.lastModified() }
            if (latestPdf != null) {
                textBlocks = PdfTextExtractor.extractTextBlocks(latestPdf)
            }
            currentPage = 0
        } catch (e: Exception) {
            error = "Ошибка: ${e.message ?: "неизвестная"}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Редактор: ${template?.name ?: "Шаблон #$templateId"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = ClaudeAccent
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveAnnotations(templateId, annotations)
                        showSaveToast = "Сохранено (${annotations.size} аннотаций)"
                    }) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Сохранить аннотации",
                            tint = ClaudeAccent
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = ClaudeAccent)
                            Text(
                                "Загрузка PDF…",
                                color = ClaudeTextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = error!!,
                            color = ClaudeTextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                previewBitmaps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Нет страниц для отображения")
                    }
                }
                else -> {
                    PdfPageEditor(
                        bitmap = previewBitmaps.getOrElse(currentPage) { previewBitmaps[0] },
                        pageNumber = currentPage,
                        pageCount = previewBitmaps.size,
                        annotations = annotations.filter { it.pageNumber == currentPage },
                        textBlocks = textBlocks.filter { it.pageNumber == currentPage },
                        onPageChange = { currentPage = it },
                        onAddAnnotation = { x, y ->
                            pendingPosition = x to y
                            editingAnnotation = null
                            annotationText = ""
                        },
                        onAnnotationLongClick = { ann ->
                            annotations = annotations.filter { it != ann }
                        },
                        onTextBlockClick = { block ->
                            // Пользователь тапнул на существующий текст →
                            // открывается диалог редактирования. При сохранении
                            // создаётся annotation с isReplacement=true —
                            // белая заливка + новый текст (Word-like подход).
                            editingAnnotation = null
                            pendingPosition = null
                            annotationText = block.text
                            // Сохраняем блок во временный state для создания
                            // replacement annotation в диалоге сохранения
                            pendingTextBlock = block
                        }
                    )
                }
            }
        }
    }

    // Диалог ввода текста для новой/существующей аннотации
    if (pendingPosition != null || editingAnnotation != null || pendingTextBlock != null) {
        val isEditing = editingAnnotation != null
        val isReplacement = pendingTextBlock != null
        AlertDialog(
            onDismissRequest = {
                pendingPosition = null
                editingAnnotation = null
                pendingTextBlock = null
                annotationText = ""
            },
            title = {
                Text(
                    when {
                        isEditing -> "Изменить аннотацию"
                        isReplacement -> "Редактирование текста"
                        else -> "Новая аннотация"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = ClaudeText
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = annotationText,
                        onValueChange = { annotationText = it },
                        label = { Text("Текст") },
                        placeholder = { Text("Например: Арендатор: {{tenantName}}") },
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isReplacement) {
                            "Редактирование существующего текста. При сохранении " +
                                "старый текст будет закрыт белой заливкой, новый текст " +
                                "нарисован сверху. Поддерживаются {{placeholders}}."
                        } else {
                            "Поддерживаются {{placeholders}}: {{tenantName}}, {{tenantPhone}}, " +
                                "{{landlordName}}, {{scooterName}}, {{weeklyAmount}} и др."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (annotationText.isNotBlank()) {
                        if (isEditing && editingAnnotation != null) {
                            // Обновляем существующую
                            annotations = annotations.map {
                                if (it == editingAnnotation) it.copy(text = annotationText)
                                else it
                            }
                        } else if (isReplacement && pendingTextBlock != null) {
                            // Word-like: создаём replacement annotation
                            // (белая заливка поверх старого текста + новый текст)
                            val block = pendingTextBlock!!
                            annotations = annotations + TemplateAnnotation(
                                pageNumber = currentPage,
                                x = block.normX,
                                y = block.normY,
                                width = block.normWidth,
                                height = block.normHeight,
                                text = annotationText,
                                fontSize = block.fontSize,
                                isReplacement = true
                            )
                        } else if (pendingPosition != null) {
                            // Добавляем новую (overlay)
                            val (x, y) = pendingPosition!!
                            annotations = annotations + TemplateAnnotation(
                                pageNumber = currentPage,
                                x = x,
                                y = y,
                                text = annotationText
                            )
                        }
                    }
                    pendingPosition = null
                    editingAnnotation = null
                    pendingTextBlock = null
                    annotationText = ""
                }) {
                    Text("OK", color = ClaudeAccent)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingPosition = null
                    editingAnnotation = null
                    pendingTextBlock = null
                    annotationText = ""
                }) {
                    Text("Отмена", color = ClaudeTextSecondary)
                }
            }
        )
    }

    // Toast уведомление о сохранении
    showSaveToast?.let { message ->
        LaunchedEffect(message) {
            // Просто показываем текст внизу на 2 секунды
            // (используем简易 state вместо Toast для тестирования в Compose)
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = ClaudeAccent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = message,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Одна страница PDF в редакторе — Bitmap + overlay аннотаций.
 * Tap по странице → открывает диалог для добавления аннотации.
 * Long-press на аннотацию → удаляет.
 */
@Composable
private fun PdfPageEditor(
    bitmap: Bitmap,
    pageNumber: Int,
    pageCount: Int,
    annotations: List<TemplateAnnotation>,
    textBlocks: List<PdfTextBlock>,
    onPageChange: (Int) -> Unit,
    onAddAnnotation: (Float, Float) -> Unit,
    onAnnotationLongClick: (TemplateAnnotation) -> Unit,
    onTextBlockClick: (PdfTextBlock) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Панель навигации по страницам ──
        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (pageNumber > 0) onPageChange(pageNumber - 1) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Пред. страница",
                        tint = if (pageNumber > 0) ClaudeAccent else ClaudeTextSecondary
                    )
                }
                Text(
                    "Страница ${pageNumber + 1} из $pageCount",
                    color = ClaudeText,
                    style = MaterialTheme.typography.bodyMedium
                )
                IconButton(onClick = { if (pageNumber < pageCount - 1) onPageChange(pageNumber + 1) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "След. страница",
                        tint = if (pageNumber < pageCount - 1) ClaudeAccent else ClaudeTextSecondary,
                        modifier = Modifier.background(
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                }
            }
        }

        // ── Подсказка ──
        Surface(
            color = ClaudeCard,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Тап по существующему тексту (outlined) → редактирование (Word-like). " +
                    "Тап по пустому месту → добавить новую аннотацию. " +
                    "Долгое нажатие на аннотацию → удалить. " +
                    "Поддержка {{placeholders}}.",
                modifier = Modifier.padding(8.dp),
                color = ClaudeTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ── Страница PDF с overlay текстовых блоков и аннотаций ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(595f / 842f)
                .pointerInput(pageNumber) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Конвертируем tap position → нормализованные координаты (0..1)
                            val normX = offset.x / size.width
                            val normY = offset.y / size.height
                            // Проверяем, попал ли тап в один из textBlocks
                            val hit = textBlocks.firstOrNull { block ->
                                normX >= block.normX &&
                                    normX <= block.normX + block.normWidth &&
                                    normY >= block.normY &&
                                    normY <= block.normY + block.normHeight
                            }
                            if (hit != null) {
                                onTextBlockClick(hit)
                            } else {
                                onAddAnnotation(normX, normY)
                            }
                        }
                    )
                }
        ) {
            // Рендерим Bitmap страницы
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Страница ${pageNumber + 1}",
                modifier = Modifier.fillMaxSize()
            )

            // ── Overlay текстовых блоков (outlined — для tap-to-edit) ──
            // Показываем outline вокруг каждого блока извлечённого текста,
            // чтобы пользователь видел, куда тапать для редактирования.
            textBlocks.forEach { block ->
                val xDp = (block.normX * 595f).dp
                val yDp = (block.normY * 842f).dp
                val wDp = (block.normWidth * 595f).dp
                val hDp = (block.normHeight * 842f).dp
                Box(
                    modifier = Modifier
                        .padding(start = xDp, top = yDp)
                        .size(width = wDp, height = hDp)
                        .border(
                            1.dp,
                            ClaudeAccent.copy(alpha = 0.4f),
                            RoundedCornerShape(1.dp)
                        )
                )
            }

            // ── Overlay аннотаций пользователя (overlay + replacement) ──
            annotations.forEach { ann ->
                val xDp = (ann.x.coerceIn(0f, 1f) * 595f).dp
                val yDp = (ann.y.coerceIn(0f, 1f) * 842f).dp
                val wDp = if (ann.isReplacement && ann.width > 0f) (ann.width * 595f).dp else 0.dp
                val hDp = if (ann.isReplacement && ann.height > 0f) (ann.height * 842f).dp else 0.dp
                Box(
                    modifier = Modifier
                        .padding(start = xDp, top = yDp)
                        .pointerInput(ann) {
                            detectTapGestures(
                                onLongPress = { onAnnotationLongClick(ann) },
                                onTap = { /* одиночный тап игнорируем */ }
                            )
                        }
                ) {
                    if (ann.isReplacement) {
                        // Replacement: показываем белую заливку + новый текст
                        Surface(
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(width = wDp, height = hDp)
                        ) {
                            Text(
                                text = ann.text,
                                color = androidx.compose.ui.graphics.Color.Black,
                                fontSize = (ann.fontSize).sp,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                            )
                        }
                    } else {
                        // Overlay: просто текст с фоном
                        Surface(
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(2.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeGold)
                        ) {
                            Text(
                                text = ann.text,
                                color = androidx.compose.ui.graphics.Color.Black,
                                fontSize = (ann.fontSize).sp,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Список аннотаций на этой странице ──
        if (annotations.isNotEmpty()) {
            Text(
                "Аннотации (${annotations.size}):",
                color = ClaudeText,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(annotations) { ann ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (ann.isReplacement) "[замена] ${ann.text}" else ann.text,
                            modifier = Modifier.weight(1f),
                            color = ClaudeText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        IconButton(onClick = { onAnnotationLongClick(ann) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить",
                                tint = ClaudeTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
