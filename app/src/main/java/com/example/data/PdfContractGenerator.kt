package com.example.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a formal PDF contract for a scooter rental.
 *
 * Per user spec: the contract is for an UNLIMITED time — it remains in force
 * until the renter formally decides to end it. Wording is formal/legal.
 */
object PdfContractGenerator {

    private const val PAGE_WIDTH  = 595   // A4 width in points (1pt = 1/72 inch)
    private const val PAGE_HEIGHT = 842   // A4 height in points
    private const val MARGIN      = 50f

    /**
     * Generates the PDF file and returns a content:// URI that can be shared/opened.
     */
    fun generateContractPdf(
        context: Context,
        renter: Renter,
        scooter: Scooter?,
        weeklyPrice: Double
    ): Uri? {
        val doc = PdfDocument()

        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val headingPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 11f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val accentPaint = Paint().apply {
            color = android.graphics.Color.rgb(0x8B, 0x5C, 0xF6) // ClaudeAccent-ish
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = android.graphics.Color.LTGRAY
            strokeWidth = 1f
        }

        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val now = Date()
        var y = MARGIN + 20f

        // ── Title ───────────────────────────────────────────────
        canvas.drawText("SKUTER IJARA SHARTNOMASI", MARGIN, y, titlePaint)
        y += 8f
        canvas.drawText("Scooter Rental Agreement", MARGIN, y + 14f, accentPaint)
        y += 36f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 24f

        // ── Contract number & date ──────────────────────────────
        canvas.drawText("Shartnoma raqami: №${renter.id}-${System.currentTimeMillis() % 10000}",
            MARGIN, y, bodyPaint)
        y += 16f
        canvas.drawText("Tuzilgan sana: ${dateFmt.format(now)}", MARGIN, y, bodyPaint)
        y += 28f

        // ── Parties ─────────────────────────────────────────────
        canvas.drawText("TOMONLAR", MARGIN, y, headingPaint)
        y += 18f

        val parties = buildString {
            append("Ijarachi (Rent giver): «Skuter Ijarasi» kompaniyasi, bundan keyin «Ijara beruvchi» deb ataladi.\n\n")
            append("Ijara oluvchi: ${renter.name}, telefon: ${renter.phoneNumber}, ")
            append("bundan keyin «Ijarachi» deb ataladi.")
        }
        y = drawWrappedText(canvas, parties, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 12f

        // ── Subject of the contract ────────────────────────────
        canvas.drawText("SHARTNOMA PREDMETI", MARGIN, y, headingPaint)
        y += 18f

        val subject = buildString {
            append("1. Ijara beruvchi ijarachiga skuterdan foydalanish huquqini beradi.\n")
            if (scooter != null) {
                append("   Skuter nomi: ${scooter.name}")
                if (!scooter.documentedNumber.isNullOrBlank()) {
                    append(" (hujjat raqami: ${scooter.documentedNumber})")
                }
                append(".\n")
            } else if (!renter.scooterName.isNullOrBlank()) {
                append("   Skuter nomi: ${renter.scooterName}.\n")
            }
            append("2. Ijara boshlanish sanasi: ${dateFmt.format(Date(renter.rentStartDateTimestamp))}.\n")
            append("3. Haftalik ijara to'lovi miqdori: ${formatMoney(weeklyPrice)} so'm.")
        }
        y = drawWrappedText(canvas, subject, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 12f

        // ── Contract duration (UNLIMITED) ──────────────────────
        canvas.drawText("SHARTNOMA MUDDATI", MARGIN, y, headingPaint)
        y += 18f

        val duration = buildString {
            append("4. Mazkur shartnoma cheksiz muddatga tuziladi.\n")
            append("5. Shartnoma ijarachi o'z xohishiga ko'ra uni rasman tugatish to'g'risida ")
            append("yozma bildirishnoma bergunga qadar amal qiladi.\n")
            append("6. Ijara beruvchi tomonidan shartnoma faqat qonunda nazarda tutilgan ")
            append("holatlardagina tugatilishi mumkin.")
        }
        y = drawWrappedText(canvas, duration, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 12f

        // ── Payment terms ──────────────────────────────────────
        canvas.drawText("TO'LOV SHARTLARI", MARGIN, y, headingPaint)
        y += 18f

        val payment = buildString {
            append("7. Ijarachi har haftalik to'lovni o'z vaqtida amalga oshirishga majbur.\n")
            append("8. To'lov muddati o'tib ketgan taqdirda, ijara beruvchi ogohlantirish ")
            append("yuborish huquqiga ega.\n")
            append("9. To'lovlar ijara beruvchi ko'rsatgan usulda (naqd yoki plastik orqali) ")
            append("amalga oshiriladi.")
        }
        y = drawWrappedText(canvas, payment, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 12f

        // ── Rights & obligations ───────────────────────────────
        canvas.drawText("TOMONLARNING HUQUQ VA BURCHLARI", MARGIN, y, headingPaint)
        y += 18f

        val rights = buildString {
            append("10. Ijarachi skuterdan ehtiyotkorlik bilan foydalanishi va uni yaxshi holatda saqlashi shart.\n")
            append("11. Skuterdagi har qanday shikast uchun ijarachi mas'uliyatli bo'ladi.\n")
            append("12. Ijara beruvchi skuterni ish holatida topshirish va texnik xizmat ko'rsatishni ta'minlaydi.\n")
            append("13. Ijarachi skuterni uchinchi shaxslarga ijaraga berishga yoki sotishga haqli emas.")
        }
        y = drawWrappedText(canvas, rights, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 12f

        // ── Contract termination ───────────────────────────────
        canvas.drawText("SHARTNOMANI TUGATISH", MARGIN, y, headingPaint)
        y += 18f

        val termination = buildString {
            append("14. Ijarachi shartnomani tugatish to'g'risida oldindan (kamida 3 kun) ")
            append("yozma ravishda xabar berishi kerak.\n")
            append("15. Shartnoma tugatilganda skuter ijara beruvchiga to'liq holatda qaytariladi.\n")
            append("16. Agar ijarachida to'lanmagan qarz bo'lsa, u shartnoma tugatilgunga qadar to'lanishi shart.")
        }
        y = drawWrappedText(canvas, termination, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 16f

        // ── Final provisions ───────────────────────────────────
        canvas.drawText("YAKUNIY QOIDALAR", MARGIN, y, headingPaint)
        y += 18f

        val finalText = buildString {
            append("17. Mazkur shartnoma ikkala tomon imzolagan paytdan boshlab kuchga kiradi.\n")
            append("18. Shartnoma ikki nusxada tuziladi, har bir tomon bittadan oladi.\n")
            append("19. Mazkur shartnomada nazarda tutilmagan masalalar qonunchilikka muvofiq hal qilinadi.")
        }
        y = drawWrappedText(canvas, finalText, bodyPaint, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, 16f)
        y += 30f

        // ── Signatures ─────────────────────────────────────────
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 24f

        canvas.drawText("Ijara beruvchi: _________________", MARGIN, y, bodyPaint)
        canvas.drawText("Ijarachi: _________________", PAGE_WIDTH - MARGIN - 200f, y, bodyPaint)
        y += 24f

        canvas.drawText("«Skuter Ijarasi»", MARGIN, y, bodyPaint)
        canvas.drawText(renter.name, PAGE_WIDTH - MARGIN - 200f, y, bodyPaint)
        y += 24f

        canvas.drawText("Imzo / Sana: ${dateFmt.format(now)}",
            MARGIN, y, bodyPaint)
        canvas.drawText("Imzo / Sana: ${dateFmt.format(now)}",
            PAGE_WIDTH - MARGIN - 200f, y, bodyPaint)

        doc.finishPage(page)

        // ── Save & share ───────────────────────────────────────
        val outputDir = File(context.cacheDir, "contracts").apply { mkdirs() }
        val safeName = renter.name.replace(Regex("[^a-zA-Z0-9А-Яа-я]"), "_")
        val file = File(outputDir, "contract_${safeName}_${renter.id}.pdf")

        return try {
            FileOutputStream(file).use { fos -> doc.writeTo(fos) }
            doc.close()
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            doc.close()
            null
        }
    }

    private fun drawWrappedText(
        canvas: android.graphics.Canvas,
        text: String,
        paint: Paint,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float
    ): Float {
        var y = startY
        for (rawLine in text.split("\n")) {
            if (rawLine.isBlank()) {
                y += lineHeight * 0.6f
                continue
            }
            val words = rawLine.split(" ")
            val current = StringBuilder()
            for (word in words) {
                val test = if (current.isEmpty()) word else "$current $word"
                val width = paint.measureText(test)
                if (width > maxWidth && current.isNotEmpty()) {
                    canvas.drawText(current.toString(), x, y, paint)
                    y += lineHeight
                    current.clear()
                    current.append(word)
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) {
                canvas.drawText(current.toString(), x, y, paint)
                y += lineHeight
            }
        }
        return y
    }

    private fun formatMoney(amount: Double): String {
        return amount.toBigDecimal().stripTrailingZeros().toPlainString()
    }
}
