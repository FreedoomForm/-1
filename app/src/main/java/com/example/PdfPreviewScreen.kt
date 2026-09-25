package com.example

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractTemplate
import com.example.ui.ContractTemplateViewModel
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeDivider
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary

/**
 * Экран «Превью PDF» — БЕЗ демо-данных.
 *
 * Пользователь видит структуру шаблона, но:
 *   • Реквизиты арендодателя: показаны как placeholder labels
 *     «(название ЯТТ/ИП)», «(адрес арендодателя)» и т.д.
 *   • Данные арендатора: «(имя арендатора)», «(телефон арендатора)» и т.д.
 *   • Данные скутера: «(модель скутера)», «(VIN скутера)» и т.д.
 *
 * Никаких реальных данных в превью — пользователь видит структуру шаблона
 * и какие поля будут заполнены при реальной генерации договора.
 *
 * В будущем (Phase 2) на этом экране будет кнопка «Редактировать PDF»,
 * которая откроет визуальный PDF-редактор (PdfBox-Android).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewScreen(
    templateId: Int,
    onBack: () -> Unit,
    onEditTemplate: () -> Unit = {},  // Phase 2: open visual PDF editor
    viewModel: ContractTemplateViewModel = viewModel()
) {
    var previewBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var template by remember { mutableStateOf<ContractTemplate?>(null) }

    // Загрузить template info и сгенерировать превью без демо-данных
    LaunchedEffect(templateId) {
        isLoading = true
        error = null
        try {
            template = viewModel.getById(templateId)
            if (template == null) {
                error = "Шаблон не найден (ID=$templateId)"
            } else {
                val bitmaps = viewModel.generatePreviewWithoutDemoData(templateId)
                if (bitmaps.isEmpty()) {
                    error = "Не удалось сгенерировать PDF. Проверьте тело шаблона."
                } else {
                    previewBitmaps = bitmaps
                }
            }
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
                        text = "Превью: ${template?.name ?: "Шаблон #$templateId"}",
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
                    // Кнопка «Редактировать» открывает визуальный PDF editor
                    // (PdfBox-Android) с поддержкой {{placeholders}} в аннотациях.
                    IconButton(onClick = onEditTemplate) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Редактировать PDF",
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
                                "Генерация превью PDF…",
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
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                previewBitmaps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Превью недоступно",
                            color = ClaudeTextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Surface(
                                color = ClaudeCard,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDivider),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Структура шаблона — без демо-данных. " +
                                        "При реальной генерации договора поля будут заполнены " +
                                        "актуальными данными арендатора, арендодателя и скутера.",
                                    modifier = Modifier.padding(12.dp),
                                    color = ClaudeTextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
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
            }
        }
    }
}
