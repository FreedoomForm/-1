package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ContractTemplateViewModel
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeText

/**
 * Полноценный WYSIWYG редактор документа через WebView с contentEditable.
 *
 * Подход:
 * 1. WebView загружает editor.html (assets/docx_editor/editor.html)
 * 2. HTML содержит <div contentEditable="true"> — пользователь редактирует
 *    текст напрямую в WebView (как в Word)
 * 3. Toolbar: Bold (B), Italic (I), Underline (U), выравнивание, {{placeholder}}
 * 4. Поддержка {{placeholders}} — пользователь может вставить {{tenantName}}
 *    через кнопку { } в toolbar
 * 5. Auto-save: при изменении контента (debounce 1s) — вызывается
 *    Android.onContentChanged(html) → обновляет bodyText в БД
 * 6. При генерации контракта: bodyText парсится, {{placeholders}} заменяются,
 *    генерируется .docx через Apache POI (DocxContractGenerator)
 *
 * Это настоящий Word-like редактор — пользователь видит форматированный текст
 * и может редактировать его напрямую (bold, italic, выравнивание, и т.д.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxEditorScreen(
    templateId: Int,
    onBack: () -> Unit,
    viewModel: ContractTemplateViewModel = viewModel()
) {
    var templateName by remember { mutableStateOf("Загрузка…") }
    var htmlContent by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Загрузить template bodyText и конвертировать в HTML для редактора
    LaunchedEffect(templateId) {
        try {
            val template = viewModel.getById(templateId)
            if (template != null) {
                templateName = template.name
                val content = viewModel.parseContent(template.contentJson)
                // Конвертируем bodyText (line-based) в HTML для contentEditable
                htmlContent = bodyTextToHtml(content.bodyText, templateId)
            }
        } catch (e: Exception) {
            templateName = "Ошибка: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Редактор: $templateName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Сохранить перед выходом
                        webView?.let { wv ->
                            wv.evaluateJavascript("saveHtml()") {}
                        }
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = ClaudeAccent
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        webView?.let { wv ->
                            wv.evaluateJavascript("saveHtml()") {}
                        }
                    }) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Сохранить",
                            tint = ClaudeAccent
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    createEditorWebView(ctx, templateId, viewModel) { html ->
                        htmlContent = html
                    }.also { webView = it }
                },
                update = { wv ->
                    // Inject HTML content when loaded
                    if (htmlContent.isNotEmpty()) {
                        val escaped = htmlContent
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", "")
                        wv.evaluateJavascript("setContent('$escaped')") {}
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Создаёт WebView с contentEditable редактором.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createEditorWebView(
    context: Context,
    templateId: Int,
    viewModel: ContractTemplateViewModel,
    onContentChanged: (String) -> Unit
): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // JavascriptInterface for Android ↔ JavaScript communication
        addJavascriptInterface(
            EditorJsInterface(templateId, viewModel, onContentChanged),
            "Android"
        )

        webViewClient = WebViewClient()
        loadUrl("file:///android_asset/docx_editor/editor.html")
    }
}

/**
 * JavaScript interface — Android methods callable from JavaScript.
 */
private class EditorJsInterface(
    private val templateId: Int,
    private val viewModel: ContractTemplateViewModel,
    private val onContentChanged: (String) -> Unit
) {
    /**
     * Called from JavaScript when user clicks "Save" button.
     * Receives the full HTML of the contentEditable div.
     */
    @JavascriptInterface
    fun onSaveHtml(html: String) {
        // Convert HTML back to bodyText and save
        val bodyText = htmlToBodyText(html)
        // Save to DB via ViewModel
        viewModel.saveBodyText(templateId, bodyText)
    }

    /**
     * Called from JavaScript on content change (debounced 1s).
     */
    @JavascriptInterface
    fun onContentChanged(html: String) {
        onContentChanged(html)
        // Auto-save: convert HTML to bodyText and update
        val bodyText = htmlToBodyText(html)
        viewModel.saveBodyText(templateId, bodyText)
    }
}

/**
 * Конвертирует bodyText (line-based синтаксис) в HTML для contentEditable.
 *
 * Line-based синтаксис:
 *   ## Заголовок → <h1>Заголовок</h1>
 *   ### Раздел → <h2>Раздел</h2>
 *   > Подпись → <p style="text-align:center">Подпись</p>
 *   * Жирный → <p><b>Жирный</b></p>
 *   (пустая строка) → </p><p>
 *   обычный текст → <p>обычный текст</p>
 */
private fun bodyTextToHtml(bodyText: String, templateId: Int): String {
    val lines = bodyText.split("\n")
    val sb = StringBuilder()
    sb.append("<div>")
    for (line in lines) {
        when {
            line.startsWith("## ") -> {
                sb.append("<h1>").append(escapeHtml(line.removePrefix("## "))).append("</h1>")
            }
            line.startsWith("### ") -> {
                sb.append("<h2>").append(escapeHtml(line.removePrefix("### "))).append("</h2>")
            }
            line.startsWith("> ") -> {
                sb.append("<p style=\"text-align:center\">")
                sb.append(escapeHtml(line.removePrefix("> ")))
                sb.append("</p>")
            }
            line.startsWith("* ") -> {
                sb.append("<p><b>")
                sb.append(escapeHtml(line.removePrefix("* ")))
                sb.append("</b></p>")
            }
            line.isBlank() -> {
                sb.append("<p>&nbsp;</p>")
            }
            else -> {
                sb.append("<p>")
                sb.append(escapeHtml(line))
                sb.append("</p>")
            }
        }
    }
    sb.append("</div>")
    return sb.toString()
}

/**
 * Конвертирует HTML (из contentEditable) обратно в bodyText (line-based).
 *
 * Парсит HTML теги и конвертирует в line-based синтаксис:
 *   <h1> → ## title
 *   <h2> → ### section
 *   <p style="text-align:center"> → > signature
 *   <p><b> → * bold
 *   <p> → обычная строка
 */
private fun htmlToBodyText(html: String): String {
    // Удаляем outer div
    val cleaned = html
        .replace("<div[^>]*>".toRegex(), "")
        .replace("</div>", "")
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    val lines = mutableListOf<String>()
    val pPattern = "<p[^>]*>(.*?)</p>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val h1Pattern = "<h1[^>]*>(.*?)</h1>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val h2Pattern = "<h2[^>]*>(.*?)</h2>".toRegex(RegexOption.DOT_MATCHES_ALL)

    // Разбиваем по параграфам
    val allPatterns = listOf(h1Pattern to "## ", h2Pattern to "### ")
    // Простая стратегия: заменяем теги на маркеры
    var result = cleaned
    result = result.replace(h1Pattern) { "## " + stripTags(it.groupValues[1]) + "\n" }
    result = result.replace(h2Pattern) { "### " + stripTags(it.groupValues[1]) + "\n" }
    // Проверяем <p style="text-align:center"> → > prefix
    result = result.replace(
        """<p\s+style="text-align:\s*center"[^>]*>(.*?)</p>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    ) { "> " + stripTags(it.groupValues[1]) + "\n" }
    // Проверяем <p><b>...</b></p> → * prefix
    result = result.replace(
        """<p>\s*<b>(.*?)</b>\s*</p>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    ) { "* " + stripTags(it.groupValues[1]) + "\n" }
    // Обычные <p> → строка
    result = result.replace(
        """<p[^>]*>(.*?)</p>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    ) { stripTags(it.groupValues[1]) + "\n" }
    // Удаляем оставшиеся HTML теги
    result = result.replace("<[^>]+>".toRegex(), "")
    // Нормализуем переводы строк
    result = result.replace("\r", "")
    // Удаляем пустые строки в начале/конце
    result = result.trim() + "\n"
    return result
}

/** Удаляет HTML теги из строки. */
private fun stripTags(s: String): String {
    return s.replace("<[^>]+>".toRegex(), "").trim()
}

/** Экранирует HTML спецсимволы. */
private fun escapeHtml(s: String): String {
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
