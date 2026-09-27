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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Генератор DOCX-договора через нативный ZIP+XML (без Apache POI).
 *
 * DOCX — это ZIP-архив с XML-файлами внутри. Минимальный DOCX:
 *   - [Content_Types].xml — типы контента
 *   - _rels/.rels — отношения
 *   - word/document.xml — основной документ
 *   - word/_rels/document.xml.rels — отношения документа
 *
 * bodyText (line-based синтаксис) конвертируется в Word XML (w:p, w:r, w:t).
 * {{placeholders}} заменяются ДО генерации (через PdfContractGenerator.resolveBodyText).
 *
 * Преимущества перед Apache POI:
 *   - Нет внешних зависимостей (экономит ~9MB APK)
 *   - Работает на minSdk 24 (Apache POI 5.x требует minSdk 26)
 *   - Простой и понятный код
 */
object DocxContractGenerator {

    private const val TAG = "DocxContractGenerator"

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
            val now = System.currentTimeMillis()
            val entry = ContractHistoryEntry(
                id = 0, renterId = renter.id, timestamp = now, type = "CREATED",
                renterName = renter.name, renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: scooter?.name,
                weekStart = renter.rentStartDateTimestamp,
                weekEnd = renter.rentStartDateTimestamp + renter.rentDurationDays * 24L * 60 * 60 * 1000,
                weeklyPrice = SettingsRepository(context).weeklyPrice
                    .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE },
                passportData = renter.passportData, address = renter.address, pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "", engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "", batteryId2 = scooter?.batteryId2 ?: "",
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
    // Нативный DOCX генератор через ZIP+XML (без Apache POI)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Создаёт .docx файл из bodyText (line-based синтаксис).
     *
     * DOCX = ZIP-архив с XML-файлами. Структура:
     *   [Content_Types].xml — MIME-типы
     *   _rels/.rels — корневые отношения
     *   word/document.xml — основной документ (w:p = параграфы)
     *   word/_rels/document.xml.rels — отношения документа
     */
    private fun writeDocx(bodyText: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val documentXml = buildDocumentXml(bodyText)
        FileOutputStream(targetFile).use { fos ->
            ZipOutputStream(fos).use { zip ->
                // [Content_Types].xml
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write(CONTENT_TYPES_XML.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // _rels/.rels
                zip.putNextEntry(ZipEntry("_rels/.rels"))
                zip.write(ROOT_RELS_XML.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // word/document.xml
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // word/_rels/document.xml.rels
                zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                zip.write(DOCUMENT_RELS_XML.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /**
     * Конвертирует bodyText (line-based синтаксис) в Word XML (document.xml).
     *
     * Line-based синтаксис → Word XML:
     *   ## Текст  → <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="30"/></w:rPr><w:t>Текст</w:t></w:r></w:p>
     *   ### Текст → <w:p><w:r><w:rPr><w:b/><w:sz w:val="26"/></w:rPr><w:t>Текст</w:t></w:r></w:p>
     *   > Текст   → <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>Текст</w:t></w:r></w:p>
     *   * Текст   → <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Текст</w:t></w:r></w:p>
     *   пустая    → <w:p/>
     *   прочий    → <w:p><w:r><w:t>Текст</w:t></w:r></w:p>
     */
    private fun buildDocumentXml(bodyText: String): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("\n")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        sb.append("\n<w:body>\n")

        // Размер страницы A4 + поля (margins in twips: 1 inch = 1440 twips)
        sb.append("""<w:sectPr>""")
        sb.append("""<w:pgSz w:w="11906" w:h="16838"/>""")  // A4
        sb.append("""<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/>""")
        sb.append("""</w:sectPr>""")
        sb.append("\n")

        val lines = bodyText.split("\n")
        for (line in lines) {
            val escapedText = escapeXml(line)
            when {
                line.startsWith("## ") -> {
                    // Заголовок: bold, centered, +3pt (sz=24=12pt → sz=30=15pt)
                    sb.append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr>""")
                    sb.append("""<w:r><w:rPr><w:b/><w:sz w:val="30"/><w:szCs w:val="30"/></w:rPr><w:t xml:space="preserve">""")
                    sb.append(escapedText.removePrefix("## "))
                    sb.append("""</w:t></w:r></w:p>""")
                }
                line.startsWith("### ") -> {
                    // Заголовок раздела: bold, +1pt (sz=26=13pt)
                    sb.append("""<w:p>""")
                    sb.append("""<w:r><w:rPr><w:b/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr><w:t xml:space="preserve">""")
                    sb.append(escapedText.removePrefix("### "))
                    sb.append("""</w:t></w:r></w:p>""")
                }
                line.startsWith("> ") -> {
                    // Подпись: centered
                    sb.append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr>""")
                    sb.append("""<w:r><w:t xml:space="preserve">""")
                    sb.append(escapedText.removePrefix("> "))
                    sb.append("""</w:t></w:r></w:p>""")
                }
                line.startsWith("* ") -> {
                    // Bold body
                    sb.append("""<w:p>""")
                    sb.append("""<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">""")
                    sb.append(escapedText.removePrefix("* "))
                    sb.append("""</w:t></w:r></w:p>""")
                }
                line.isBlank() -> {
                    // Пустая строка
                    sb.append("""<w:p/>""")
                }
                else -> {
                    // Обычный текст
                    sb.append("""<w:p>""")
                    sb.append("""<w:r><w:t xml:space="preserve">""")
                    sb.append(escapedText)
                    sb.append("""</w:t></w:r></w:p>""")
                }
            }
            sb.append("\n")
        }
        sb.append("</w:body>\n</w:document>")
        return sb.toString()
    }

    /** Экранирует XML спецсимволы. */
    private fun escapeXml(s: String): String {
        return s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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

    // ── Константы — XML шаблоны для минимального DOCX ──────────────────────

    private const val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOCUMENT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
"""
}
