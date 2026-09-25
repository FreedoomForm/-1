package com.example.ui

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Извлекает текст из PDF файла с позициями (для Word-like редактирования).
 *
 * Использует PdfBox-Android 2.0 API: [PDFTextStripper] с переопределённым
 * [processTextPosition] для захвата координат каждого куска текста.
 *
 * Возвращает список [PdfTextBlock] — каждый блок = один символ или группа
 * соседних символов с одинаковым шрифтом/размером.
 *
 * Используется в [com.example.ui.PdfEditorScreen] — пользователь тапает на
 * текстовый блок, открывается диалог редактирования, и при сохранении
 * создаётся [com.example.data.TemplateAnnotation] с [isReplacement] = true
 * (белая заливка + новый текст — подход как у PDFTron/Foxit).
 */
data class PdfTextBlock(
    val pageNumber: Int,       // 0-based
    val x: Float,              // pt, top-left origin (как в редакторе)
    val y: Float,              // pt, top-left origin
    val width: Float,          // pt
    val height: Float,         // pt
    val text: String,
    val fontSize: Float,
    val pageWidth: Float,
    val pageHeight: Float
) {
    /** Нормализованная X (0..1). */
    val normX: Float get() = if (pageWidth > 0) x / pageWidth else 0f
    /** Нормализованная Y (0..1, top-down). */
    val normY: Float get() = if (pageHeight > 0) y / pageHeight else 0f
    /** Нормализованная ширина (0..1). */
    val normWidth: Float get() = if (pageWidth > 0) width / pageWidth else 0f
    /** Нормализованная высота (0..1). */
    val normHeight: Float get() = if (pageHeight > 0) height / pageHeight else 0f
}

object PdfTextExtractor {

    private const val TAG = "PdfTextExtractor"

    /**
     * Извлекает все текстовые блоки из PDF файла.
     *
     * Группирует соседние символы на одной линии с одинаковым размером шрифта
     * в один блок (чтобы пользователь тапал на слово/фразу, а не на отдельные буквы).
     *
     * @param pdfFile PDF файл для извлечения
     * @return список [PdfTextBlock], сгруппированных по строкам
     */
    fun extractTextBlocks(pdfFile: File): List<PdfTextBlock> {
        if (!pdfFile.exists()) return emptyList()
        val result = mutableListOf<PdfTextBlock>()
        try {
            val document = PDDocument.load(pdfFile)
            try {
                val pageCount = document.pages.count
                for (pageIndex in 0 until pageCount) {
                    val page = document.getPage(pageIndex)
                    val pageWidth = page.mediaBox?.width?.toFloat() ?: 595f
                    val pageHeight = page.mediaBox?.height?.toFloat() ?: 842f
                    // Кастомный stripper для захвата позиций
                    val stripper = PositionCapturingStripper(pageIndex, pageWidth, pageHeight)
                    stripper.startPage = pageIndex + 1  // 1-based
                    stripper.endPage = pageIndex + 1
                    stripper.writeText(document, java.io.StringWriter())
                    result.addAll(stripper.blocks)
                }
            } finally {
                document.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text from PDF: ${pdfFile.absolutePath}", e)
        }
        // Группируем блоки с одинаковым Y и близким X (в одну строку)
        return groupAdjacentBlocks(result)
    }

    /**
     * Группирует соседние блоки с одинаковым Y и близким X в одну строку.
     * Это позволяет пользователю тапать на слово/фразу, а не на отдельные буквы.
     */
    private fun groupAdjacentBlocks(blocks: List<PdfTextBlock>): List<PdfTextBlock> {
        if (blocks.isEmpty()) return blocks
        val grouped = mutableListOf<PdfTextBlock>()
        // Сортируем по странице, затем по Y, затем по X
        val sorted = blocks.sortedWith(
            compareBy<PdfTextBlock>(
                { it.pageNumber },
                { it.y },       // тот же Y = та же строка
                { it.x }
            )
        )
        var current: PdfTextBlock? = null
        for (block in sorted) {
            val cur = current
            if (cur == null) {
                current = block
                continue
            }
            // Тот же Y (в пределах 3pt) и тот же page → группируем
            if (cur.pageNumber == block.pageNumber && Math.abs(cur.y - block.y) < 3f) {
                val newX = Math.min(cur.x, block.x)
                val newY = Math.min(cur.y, block.y)
                val newRight = Math.max(cur.x + cur.width, block.x + block.width)
                val newBottom = Math.max(cur.y + cur.height, block.y + block.height)
                current = cur.copy(
                    x = newX,
                    y = newY,
                    width = newRight - newX,
                    height = newBottom - newY,
                    text = cur.text + block.text
                )
            } else {
                grouped.add(cur)
                current = block
            }
        }
        current?.let { grouped.add(it) }
        return grouped
    }

    /**
     * Кастомный PDFTextStripper — переопределяет processTextPosition для
     * захвата координат каждого текстового фрагмента.
     */
    private class PositionCapturingStripper(
        val pageIndex: Int,
        val pageWidth: Float,
        val pageHeight: Float
    ) : PDFTextStripper() {
        val blocks = mutableListOf<PdfTextBlock>()

        override fun processTextPosition(text: TextPosition) {
            // text.x, text.y — координаты в PDF (bottom-left origin)
            // Конвертируем в top-left origin (как в редакторе)
            val x = text.x
            val y = pageHeight - text.y  // инвертируем Y
            val width = text.width
            val height = text.height.takeIf { it > 0 } ?: text.fontSize * 0.8f
            val fontSize = text.fontSize
            val str = text.unicode ?: ""
            if (str.isNotBlank()) {
                blocks.add(
                    PdfTextBlock(
                        pageNumber = pageIndex,
                        x = x,
                        y = y - height,  // корректируем чтобы y было верхнее левое
                        width = width,
                        height = height,
                        text = str,
                        fontSize = fontSize,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight
                    )
                )
            }
        }
    }
}
