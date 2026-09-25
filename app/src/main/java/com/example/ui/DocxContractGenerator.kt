package com.example.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.ContractHistoryEntry
import com.example.data.ContractTemplate
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.SettingsRepository
import com.example.data.TemplateContent
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Генератор DOCX-договора аренды электроскутера.
 *
 * Аналог PdfContractGenerator, но генерирует .docx файл (вместо .pdf)
 * через Apache POI (XWPFDocument). DOCX — редактируемый формат, пользователь
 * может открыть файл в Word/WPS Office/Google Docs и редактировать.
 *
 * Шаблон тот же — bodyText с line-based синтаксисом и {{placeholders}}.
 * Замена {{placeholders}} идёт ДО генерации DOCX (как в PdfContractGenerator).
 *
 * Формат: A4, поля 2cm, шрифт Times New Roman 12pt (как в Word).
 */
object DocxContractGenerator {

    private const val TAG = "DocxContractGenerator"

    /**
     * Генерирует DOCX-договор КОНЕЧНОЙ аренды (на неделю) с активной версией
     * шаблона из БД. Сохраняет в Documents/ScooterContracts/.
     */
    fun generate(
        context: Context,
        entry: ContractHistoryEntry,
        renter: Renter?,
        scooter: Scooter? = null
    ): Uri? {
        val content = PdfContractGenerator.loadActiveTemplateContent(context, ContractTemplate.TYPE_LIMITED)
        val contractNumber = "SRC-${entry.id.toString().padStart(6, '0')}"
        val file = defaultOutputFile(context, "rental_contract_${contractNumber}")
        return generateTo(context, entry, renter, scooter, content, file)
    }

    /**
     * Генерирует DOCX-договор БЕСКОНЕЧНОЙ аренды с активной версией шаблона.
     */
    fun generateUnlimited(
        context: Context,
        renter: Renter,
        scooter: Scooter? = null
    ): Uri? {
        val content = PdfContractGenerator.loadActiveTemplateContent(context, ContractTemplate.TYPE_UNLIMITED)
        val contractNumber = "SRC-UNLMT-${renter.id}-${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(System.currentTimeMillis()))}"
        val file = defaultOutputFile(context, "rental_contract_unlimited_${contractNumber}")
        return generateUnlimitedTo(context, renter, scooter, content, file)
    }

    /**
     * Генерирует DOCX с заданным content шаблона и сохраняет в targetFile.
     */
    fun generateTo(
        context: Context,
        entry: ContractHistoryEntry,
        renter: Renter?,
        scooter: Scooter?,
        content: TemplateContent,
        targetFile: File
    ): Uri? {
        return try {
            val resolvedBody = PdfContractGenerator.resolveBodyText(
                context, entry, renter, scooter, content
            )
            writeDocx(resolvedBody, targetFile)
            Log.i(TAG, "DOCX saved: ${targetFile.absolutePath}")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate DOCX", e)
            null
        }
    }

    fun generateUnlimitedTo(
        context: Context,
        renter: Renter,
        scooter: Scooter?,
        content: TemplateContent,
        targetFile: File
    ): Uri? {
        return try {
            // Для unlimited: создаём фейковый entry из renter
            val now = System.currentTimeMillis()
            val entry = ContractHistoryEntry(
                id = 0,
                renterId = renter.id,
                timestamp = now,
                type = "CREATED",
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: scooter?.name,
                weekStart = renter.rentStartDateTimestamp,
                weekEnd = renter.rentStartDateTimestamp + renter.rentDurationDays * 24L * 60 * 60 * 1000,
                weeklyPrice = SettingsRepository(context).weeklyPrice
                    .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE },
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: ""
            )
            val resolvedBody = PdfContractGenerator.resolveBodyText(
                context, entry, renter, scooter, content
            )
            writeDocx(resolvedBody, targetFile)
            Log.i(TAG, "Unlimited DOCX saved: ${targetFile.absolutePath}")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate unlimited DOCX", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // DOCX generation via Apache POI
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Создаёт .docx файл из bodyText (line-based синтаксис).
     *
     * Line-based синтаксис:
     *   ## Текст  → заголовок (bold, centered, +3pt)
     *   ### Текст → заголовок раздела (bold, +1pt)
     *   > Текст   → подпись (centered)
     *   * Текст   → bold body
     *   пустая    → пустая строка
     *   прочий    → обычный текст
     */
    private fun writeDocx(bodyText: String, targetFile: File) {
        val doc = XWPFDocument()
        try {
            val lines = bodyText.split("\n")
            for (line in lines) {
                val para = doc.createParagraph()
                when {
                    line.startsWith("## ") -> {
                        para.alignment = ParagraphAlignment.CENTER
                        val run = para.createRun()
                        run.isBold = true
                        run.fontSize = 15  // 12pt + 3
                        run.setText(line.removePrefix("## "))
                    }
                    line.startsWith("### ") -> {
                        val run = para.createRun()
                        run.isBold = true
                        run.fontSize = 13  // 12pt + 1
                        run.setText(line.removePrefix("### "))
                    }
                    line.startsWith("> ") -> {
                        para.alignment = ParagraphAlignment.CENTER
                        val run = para.createRun()
                        run.setText(line.removePrefix("> "))
                    }
                    line.startsWith("* ") -> {
                        val run = para.createRun()
                        run.isBold = true
                        run.setText(line.removePrefix("* "))
                    }
                    line.isBlank() -> {
                        // Пустая строка — просто пустой параграф
                        val run = para.createRun()
                        run.setText("")
                    }
                    else -> {
                        val run = para.createRun()
                        run.setText(line)
                    }
                }
                // Отступ после параграфа
                para.spacingAfter = 120  // ~6pt
            }

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { fos -> doc.write(fos) }
        } finally {
            doc.close()
        }
    }

    private fun defaultOutputFile(context: Context, baseName: String): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ScooterContracts"
        )
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "${baseName}_$ts.docx")
    }
}
