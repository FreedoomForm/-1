package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ContractTemplate
import com.example.ui.ContractTemplateViewModel
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
 * Экран «Таблица версий» — открывается при клике на шаблон.
 *
 * По образцу RenterContractHistoryScreen — TopAppBar с кнопкой «Назад»,
 * список версий выбранного типа (LIMITED или UNLIMITED).
 *
 * Каждая строка таблицы показывает:
 *   • Имя версии (name)
 *   • Примечание (notes) — отображается под именем, обрезается до 2 строк
 *   • ★ если версия активная
 *
 * Клик по строке → onOpenPdfPreview(templateId) — переход на превью PDF
 * БЕЗ демо-данных (см. PdfPreviewScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsTableScreen(
    type: String,
    onBack: () -> Unit,
    onOpenPdfPreview: (Int) -> Unit,
    viewModel: ContractTemplateViewModel = viewModel()
) {
    // Установить выбранный тип при входе на экран
    LaunchedEffect(type) {
        viewModel.selectType(type)
    }

    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val activeTemplateId by viewModel.activeTemplateId.collectAsStateWithLifecycle()

    val typeName = when (type) {
        ContractTemplate.TYPE_UNLIMITED -> "Бесконечная аренда"
        else -> "Конечная аренда (неделя)"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Версии: $typeName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = ClaudeAccent
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (templates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Нет созданных версий. Нажмите «+» в верхнем баре " +
                        "на странице «Документооборот».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClaudeTextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(templates, key = { it.id }) { template ->
                VersionTableItem(
                    template = template,
                    onClick = { onOpenPdfPreview(template.id) }
                )
            }
        }
    }
}

@Composable
private fun VersionTableItem(
    template: ContractTemplate,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ClaudeCard,
        border = androidx.compose.foundation.BorderStroke(
            width = if (template.isActive) 2.dp else 1.dp,
            color = if (template.isActive) ClaudeGold else ClaudeDivider
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (template.isActive) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Активная",
                    tint = ClaudeGold,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClaudeText,
                    fontWeight = if (template.isActive) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.size(4.dp))
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
                // ── Примечание (новое поле в v38) ──
                template.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "Примечание: $notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClaudeTextSecondary,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
