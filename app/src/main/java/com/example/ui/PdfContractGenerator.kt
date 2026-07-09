package com.example.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Генератор PDF-документа для одного контракта.
 *
 * Использует встроенный android.graphics.pdf.PdfDocument (доступен с API 19).
 * Без сторонних библиотек.
 *
 * Формат: A4 (595 × 842 pt). Шаблон документа:
 *   ┌─────────────────────────────────────────────┐
 *   │            SKUTER IJARASI SHARTNOMASI        │
 *   │              (договор аренды скутера)        │
 *   ├─────────────────────────────────────────────┤
 *   │  Shartnoma №  <id>                          │
 *   │  Sana:        <timestamp>                   │
 *   ├─────────────────────────────────────────────┤
 *   │  IJARACHI (Арендатор)                       │
 *   │    F.I.O.      : <name>                     │
 *   │    Tel.        : <phone>                    │
 *   ├─────────────────────────────────────────────┤
 *   │  SKUTER (Скутер)                            │
 *   │    Nomi        : <scooterName>              │
 *   ├─────────────────────────────────────────────┤
 *   │  IJARA MAVSUMI (Срок аренды)                │
 *   │    Boshlanish  : <weekStart>                │
 *   │    Tugash      : <weekEnd>                  │
 *   ├─────────────────────────────────────────────┤
 *   │  TO'LOV (Оплата)                            │
 *   │    Summa       : <amount> UZS               │
 *   │    Haftalik    : <weeklyPrice> UZS          │
 *   │    Holat       : <type>                     │
 *   ├─────────────────────────────────────────────┤
 *   │  Imzo: ____________      Imzo: ____________ │
 *   │  (Ijarachi)              (Ijaraga beruvchi) │
 *   └─────────────────────────────────────────────┘
 */
object PdfContractGenerator {

    private const val TAG = "PdfContractGenerator"
    private const val PAGE_WIDTH  = 595  // A4 @ 72 DPI
    private const val PAGE_HEIGHT = 842

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun generate(
        context: Context,
        entry: ContractHistoryEntry,
        renter: Renter?
    ): Uri? {
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            val density = context.resources.displayMetrics.density

            // Paints
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val subtitlePaint = Paint().apply {
                color = 0xFF666666.toInt()
                textSize = 11f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val sectionPaint = Paint().apply {
                color = Color.WHITE
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val labelPaint = Paint().apply {
                color = 0xFF444444.toInt()
                textSize = 12f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val valuePaint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val accentLinePaint = Paint().apply {
                color = 0xFFD97706.toInt()  // янтарный — акцент
                strokeWidth = 3f
                isAntiAlias = true
            }
            val dividerPaint = Paint().apply {
                color = 0xFFE5E7EB.toInt()
                strokeWidth = 1f
                isAntiAlias = true
            }
            val sectionBgPaint = Paint().apply {
                color = 0xFFF9FAFB.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val sectionHeaderPaint = Paint().apply {
                color = 0xFF111827.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val marginX = 40f
            var y = 60f

            // ── Header: лого-полоса ────────────────────────────────────
            canvas.drawRect(marginX, y, PAGE_WIDTH - marginX, y + 4f, accentLinePaint)
            y += 16f

            // ── Title ──────────────────────────────────────────────────
            val title = "SKUTER IJARASI SHARTNOMASI"
            canvas.drawText(title, marginX, y + 8f, titlePaint)
            y += 24f
            canvas.drawText("Договор аренды скутера", marginX, y, subtitlePaint)
            y += 22f

            // ── Meta: Shartnoma № и дата ───────────────────────────────
            drawMetaRow(canvas, marginX, y, "Shartnoma №:", "#${entry.id}", labelPaint, valuePaint)
            y += 16f
            drawMetaRow(canvas, marginX, y, "Tuzilgan sana:", dateTimeFmt.format(Date(entry.timestamp)),
                labelPaint, valuePaint)
            y += 24f

            // ── Section: IJARACHI ──────────────────────────────────────
            y = drawSection(canvas, marginX, y, "IJARACHI (Арендатор)")
            y += 18f
            val name = entry.renterName.ifBlank { renter?.name ?: "—" }
            val phone = entry.renterPhone.ifBlank { renter?.phoneNumber ?: "—" }
            y = drawField(canvas, marginX, y, "F.I.O. (Ф.И.О.)", name, labelPaint, valuePaint)
            y = drawField(canvas, marginX, y, "Telefon", phone, labelPaint, valuePaint)
            y += 12f

            // ── Section: SKUTER ────────────────────────────────────────
            y = drawSection(canvas, marginX, y, "SKUTER (Скутер)")
            y += 18f
            y = drawField(canvas, marginX, y, "Nomi (Наименование)",
                entry.scooterName ?: renter?.scooterName ?: "—", labelPaint, valuePaint)
            y += 12f

            // ── Section: IJARA MAVSUMI ────────────────────────────────
            y = drawSection(canvas, marginX, y, "IJARA MAVSUMI (Срок аренды)")
            y += 18f
            val startStr = entry.weekStart?.let { dateFmt.format(Date(it)) }
                ?: renter?.rentStartDateTimestamp?.let { dateFmt.format(Date(it)) }
                ?: "—"
            val endStr = entry.weekEnd?.let { dateFmt.format(Date(it)) }
                ?: renter?.let { dateFmt.format(Date(it.rentStartDateTimestamp + it.rentDurationDays * 24L * 60 * 60 * 1000)) }
                ?: "—"
            y = drawField(canvas, marginX, y, "Boshlanish (Начало)", startStr, labelPaint, valuePaint)
            y = drawField(canvas, marginX, y, "Tugash (Окончание)", endStr, labelPaint, valuePaint)
            y += 12f

            // ── Section: TO'LOV ────────────────────────────────────────
            y = drawSection(canvas, marginX, y, "TO'LOV (Оплата)")
            y += 18f
            y = drawField(canvas, marginX, y, "Summa (Сумма)",
                "${entry.amount.toBigDecimal().stripTrailingZeros().toPlainString()} UZS",
                labelPaint, valuePaint)
            y = drawField(canvas, marginX, y, "Haftalik narxi (Недельная цена)",
                "${entry.weeklyPrice.toBigDecimal().stripTrailingZeros().toPlainString()} UZS",
                labelPaint, valuePaint)
            y = drawField(canvas, marginX, y, "Holat (Статус)", typeLabel(entry.type), labelPaint, valuePaint)
            if (!entry.notes.isNullOrBlank()) {
                y = drawField(canvas, marginX, y, "Izoh (Примечание)", entry.notes, labelPaint, valuePaint)
            }
            y += 18f

            // ── Divider ────────────────────────────────────────────────
            canvas.drawLine(marginX, y, PAGE_WIDTH - marginX, y, dividerPaint)
            y += 30f

            // ── Signatures ────────────────────────────────────────────
            canvas.drawText("Ijarachi: ____________________", marginX, y, labelPaint)
            canvas.drawText("Ijaraga beruvchi: ____________________",
                PAGE_WIDTH - marginX - 240f, y, labelPaint)
            y += 22f
            canvas.drawText("(подпись арендатора)", marginX, y, subtitlePaint)
            canvas.drawText("(подпись арендодателя)", PAGE_WIDTH - marginX - 240f, y, subtitlePaint)
            y += 40f

            // ── Footer ────────────────────────────────────────────────
            canvas.drawText("Call center: 71 200 55 56", marginX, y, subtitlePaint)
            canvas.drawText("Ushbu shartnoma elektron hujjat sifatida amal qiladi.",
                marginX, y + 14f, subtitlePaint)

            doc.finishPage(page)

            // ── Сохранение в файл ──────────────────────────────────────
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ScooterContracts"
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "contract_${entry.id}_${entry.timestamp}.pdf")
            FileOutputStream(file).use { fos -> doc.writeTo(fos) }

            Log.i(TAG, "PDF saved: ${file.absolutePath}")
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate PDF", e)
            return null
        } finally {
            doc.close()
        }
    }

    private fun drawMetaRow(
        canvas: android.graphics.Canvas, x: Float, y: Float,
        label: String, value: String,
        labelPaint: Paint, valuePaint: Paint
    ): Float {
        canvas.drawText(label, x, y + 12f, labelPaint)
        canvas.drawText(value, x + 130f, y + 12f, valuePaint)
        return y
    }

    private fun drawSection(
        canvas: android.graphics.Canvas, x: Float, y: Float,
        title: String
    ): Float {
        // фон
        val bgPaint = Paint().apply {
            color = 0xFF111827.toInt()
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val right = 595 - 40f
        canvas.drawRect(x, y, right, y + 22f, bgPaint)
        canvas.drawText(title, x + 8f, y + 15f, textPaint)
        return y + 22f
    }

    private fun drawField(
        canvas: android.graphics.Canvas, x: Float, y: Float,
        label: String, value: String,
        labelPaint: Paint, valuePaint: Paint
    ): Float {
        canvas.drawText(label, x, y + 12f, labelPaint)
        canvas.drawText(value, x + 200f, y + 12f, valuePaint)
        return y + 18f
    }

    private fun typeLabel(t: String): String = when (t) {
        ContractHistoryEntry.TYPE_CREATED    -> "Yangi shartnoma (Новый)"
        ContractHistoryEntry.TYPE_PAYMENT    -> "To'langan (Оплачено)"
        ContractHistoryEntry.TYPE_AUTO_RENEW -> "Avtomatik yangilangan (Автопродление)"
        ContractHistoryEntry.TYPE_TERMINATED -> "Tugatilgan (Расторгнут)"
        ContractHistoryEntry.TYPE_RETURNED   -> "Qaytarilgan (Возврат)"
        else -> t
    }
}
