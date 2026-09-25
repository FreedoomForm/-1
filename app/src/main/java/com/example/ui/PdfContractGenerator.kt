package com.example.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.ContractTemplate
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.SettingsRepository
import com.example.data.TemplateAnnotation
import com.example.data.TemplateContent
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Генератор PDF-договора аренды электроскутера.
 *
 * Шаблон 1-в-1 повторяет документ rental_contract_SRC-000014_*.docx:
 *   • Заголовок + № договора + дата/город
 *   • Преамбула (Ижарага берувчи / Ижарага олувчи)
 *   • Раздел 1: Шартнома предмети (1.1, 1.2)
 *   • Раздел 2: Тўлов шартлари (2.1-2.5)
 *   • Раздел 3: Тарафларнинг ҳуқуқ ва мажбуриятлари (3.1-3.13)
 *   • Раздел 4: Жавобгарлик ва низолар (4.1-4.2)
 *   • Раздел 5: Бошқа шартлар (5.1-5.4)
 *   • Раздел 6: Реквизитлар ва имзолар
 *   • Топшириқ-қабул қилиш далолатномаси
 *
 * Формат: A4 (595 × 842 pt). Многостраничная вёрстка через StaticLayout.
 *
 * Начиная с v37 (DB migration 36→37), шаблон договора хранится в таблице
 * `contract_templates`. Методы [generate] / [generateUnlimited] читают
 * активную версию шаблона из БД и используют её содержимое
 * (8 реквизитов арендодателя + тело договора с {{placeholders}}).
 *
 * Новые методы [generateTo] / [generateUnlimitedTo] принимают
 * [TemplateContent] явно — используются для превью на странице
 * «Документооборот» и для скачивания PDF выбранной версии.
 */
object PdfContractGenerator {

    private const val TAG = "PdfContractGenerator"
    private const val PAGE_WIDTH = 595   // A4 @ 72 DPI
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val MARGIN_TOP = 40f
    private const val MARGIN_BOTTOM = 40f

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    // Только месяц и год — день уже выводится в кавычках «DD» в месте использования,
    // поэтому здесь он не нужен (раньше был "dd MMMM yyyy" → дублировался день).
    private val dateFmtUz = SimpleDateFormat("MMMM yyyy", Locale("ru"))

    // ──────────────────────────────────────────────────────────────────────
    // Публичные методы (внешний API)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Генерирует PDF-договор КОНЕЧНОЙ аренды (на неделю) с активной версией
     * шаблона из БД. Это обратная совместимая обёртка над [generateTo]:
     * читает активный шаблон типа [ContractTemplate.TYPE_LIMITED] из БД,
     * если его нет — использует дефолтный [TemplateContent].
     *
     * Сохраняет PDF в публичную папку Documents/ScooterContracts/.
     */
    fun generate(
        context: Context,
        entry: ContractHistoryEntry,
        renter: Renter?,
        scooter: Scooter? = null
    ): Uri? {
        val content = loadActiveTemplateContent(context, ContractTemplate.TYPE_LIMITED)
        val file = defaultOutputFile(context, "rental_contract_${computeContractNumber(entry)}")
        // Загружаем аннотации из активной версии шаблона
        val annotations = loadActiveAnnotations(context, ContractTemplate.TYPE_LIMITED)
        return generateTo(context, entry, renter, scooter, content, file, annotations)
    }

    /**
     * Генерирует PDF-договор БЕСКОНЕЧНОЙ аренды с активной версией шаблона
     * из БД. Сохраняет в Documents/ScooterContracts/.
     */
    fun generateUnlimited(
        context: Context,
        renter: Renter,
        scooter: Scooter? = null
    ): Uri? {
        val content = loadActiveTemplateContent(context, ContractTemplate.TYPE_UNLIMITED)
        val contractNumber = "SRC-UNLMT-${renter.id}-${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(System.currentTimeMillis()))}"
        val file = defaultOutputFile(context, "rental_contract_unlimited_${contractNumber}")
        // Загружаем аннотации из активной версии шаблона
        val annotations = loadActiveAnnotations(context, ContractTemplate.TYPE_UNLIMITED)
        return generateUnlimitedTo(context, renter, scooter, content, file, annotations)
    }

    /**
     * Генерирует PDF-договор КОНЕЧНОЙ аренды с заданным [content] шаблона
     * и сохраняет в [targetFile]. Используется для:
     *   • превью на странице «Документооборот» (targetFile = cacheDir)
     *   • скачивания PDF выбранной версии с демо-данными (targetFile = Documents/...)
     */
    fun generateTo(
        context: Context,
        entry: ContractHistoryEntry,
        renter: Renter?,
        scooter: Scooter?,
        content: TemplateContent,
        targetFile: File,
        annotations: List<TemplateAnnotation> = emptyList()
    ): Uri? {
        val doc = PdfDocument()
        try {
            // ── Динамические данные из записи истории ────────────────────
            val contractNumber = computeContractNumber(entry)
            val contractDate = dateFmtUz.format(Date(entry.timestamp))
            val weekStart = entry.weekStart ?: renter?.rentStartDateTimestamp ?: System.currentTimeMillis()
            val weekEnd = entry.weekEnd
                ?: renter?.let { it.rentStartDateTimestamp + it.rentDurationDays * 24L * 60 * 60 * 1000 }
                ?: (weekStart + 7L * 24 * 60 * 60 * 1000)
            val tenantName = entry.renterName.ifBlank { renter?.name ?: "" }
            val tenantPhone = entry.renterPhone.ifBlank { renter?.phoneNumber ?: "" }
            val scooterName = entry.scooterName ?: renter?.scooterName ?: scooter?.name ?: ""
            val weeklyAmount = entry.weeklyPrice.takeIf { it > 0 }
                ?: renter?.let { 0.0 } ?: 0.0
            val dailyAmount = if (weeklyAmount > 0) weeklyAmount / 7.0 else 0.0

            // ── Реквизиты арендатора для PDF (entry → renter fallback) ──
            val tenantPassport = entry.passportData.ifBlank { renter?.passportData ?: "" }
            val tenantAddress = entry.address.ifBlank { renter?.address ?: "" }
            val tenantPinfl = entry.pinfl.ifBlank { renter?.pinfl ?: "" }

            // ── Реквизиты скутера для PDF ──────────────────────────────────
            val scooterVin = entry.vinNumber.ifBlank { scooter?.vinNumber ?: "" }
            val scooterEngine = entry.engineNumber.ifBlank { scooter?.engineNumber ?: "" }
            val scooterSerial = entry.scooterSerialNumber.ifBlank { scooter?.scooterSerialNumber ?: "" }
            val battId1 = entry.batteryId1.ifBlank { scooter?.batteryId1 ?: "" }
            val battId2 = entry.batteryId2.ifBlank { scooter?.batteryId2 ?: "" }
            val extraInfo = entry.additionalInfo.ifBlank { scooter?.additionalInfo ?: "" }

            // ── Настройки: размер шрифта + цена аккума ─────────────────────
            val settings = SettingsRepository(context)
            val contentWidth = (PAGE_WIDTH - 2 * MARGIN_X).toInt()
            val batteryDamageAmount = settings.batteryDamagePrice
            val batteryDamageText = formatAmountWithText(batteryDamageAmount)

            // ── Плейсхолдеры ───────────────────────────────────────────────
            val placeholders = buildPlaceholders(
                content = content,
                contractNumber = contractNumber,
                contractDate = contractDate,
                contractDay = dateFmt.format(Date(entry.timestamp)).take(2),
                contractFullDate = dateFmt.format(Date(entry.timestamp)),
                weekStart = dateFmt.format(Date(weekStart)),
                weekEnd = dateFmt.format(Date(weekEnd)),
                tenantName = tenantName,
                tenantPhone = tenantPhone,
                tenantPassport = tenantPassport,
                tenantAddress = tenantAddress,
                tenantPinfl = tenantPinfl,
                scooterName = scooterName,
                scooterVin = scooterVin,
                scooterEngine = scooterEngine,
                scooterSerial = scooterSerial,
                battId1 = battId1,
                battId2 = battId2,
                extraInfo = extraInfo,
                weeklyAmount = formatAmount(weeklyAmount),
                dailyAmount = formatAmount(dailyAmount),
                batteryDamageText = batteryDamageText
            )

            val effectiveBody = content.bodyText.ifBlank { TemplateContent.DEFAULT_CONTRACT_BODY_LIMITED }
            val resolvedBody = applyPlaceholders(effectiveBody, placeholders)

            // ── Подбор размера шрифта (auto: уменьшаем пока не влезет) ──
            val baseFontSize = pickBaseFontSize(
                autoFit = settings.pdfFontSizeAuto,
                initialSize = SettingsRepository.DEFAULT_PDF_FONT_SIZE,
                manualSize = settings.pdfFontSize,
                buildParagraphs = { size ->
                    val paints = createPaints(size)
                    parseTemplateToParagraphs(resolvedBody, paints)
                },
                contentWidth = contentWidth
            )
            val paints = createPaints(baseFontSize)
            val paragraphs = parseTemplateToParagraphs(resolvedBody, paints)

            renderParagraphs(doc, paragraphs, contentWidth, annotations, placeholders)
            writePdfToFile(doc, targetFile)

            Log.i(TAG, "PDF saved: ${targetFile.absolutePath}")
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate PDF", e)
            return null
        } finally {
            doc.close()
        }
    }

    /**
     * Генерирует PDF-договор БЕСКОНЕЧНОЙ аренды с заданным [content] шаблона
     * и сохраняет в [targetFile]. Используется для превью и скачивания
     * на странице «Документооборот».
     */
    fun generateUnlimitedTo(
        context: Context,
        renter: Renter,
        scooter: Scooter?,
        content: TemplateContent,
        targetFile: File,
        annotations: List<TemplateAnnotation> = emptyList()
    ): Uri? {
        val doc = PdfDocument()
        try {
            // ── Динамические данные ────────────────────────────────────────
            val now = System.currentTimeMillis()
            val contractNumber = "SRC-UNLMT-${renter.id}-${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))}"
            val contractDate = dateFmtUz.format(Date(now))
            val weekStart = renter.rentStartDateTimestamp
            val tenantName = renter.name
            val tenantPhone = renter.phoneNumber
            val scooterName = renter.scooterName ?: scooter?.name ?: ""
            val weeklyAmount = SettingsRepository(context).weeklyPrice
                .let { if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE }
            val dailyAmount = weeklyAmount / 7.0

            // Реквизиты арендатора
            val tenantPassport = renter.passportData
            val tenantAddress = renter.address
            val tenantPinfl = renter.pinfl

            // Реквизиты скутера
            val scooterVin = scooter?.vinNumber ?: ""
            val scooterEngine = scooter?.engineNumber ?: ""
            val scooterSerial = scooter?.scooterSerialNumber ?: ""
            val battId1 = scooter?.batteryId1 ?: ""
            val battId2 = scooter?.batteryId2 ?: ""
            val extraInfo = scooter?.additionalInfo ?: ""

            // Настройки
            val settings = SettingsRepository(context)
            val contentWidth = (PAGE_WIDTH - 2 * MARGIN_X).toInt()
            val batteryDamageAmount = settings.batteryDamagePrice
            val batteryDamageText = formatAmountWithText(batteryDamageAmount)

            // Плейсхолдеры
            val placeholders = buildPlaceholders(
                content = content,
                contractNumber = contractNumber,
                contractDate = contractDate,
                contractDay = dateFmt.format(Date(now)).take(2),
                contractFullDate = dateFmt.format(Date(now)),
                weekStart = dateFmt.format(Date(weekStart)),
                weekEnd = "—",
                tenantName = tenantName,
                tenantPhone = tenantPhone,
                tenantPassport = tenantPassport,
                tenantAddress = tenantAddress,
                tenantPinfl = tenantPinfl,
                scooterName = scooterName,
                scooterVin = scooterVin,
                scooterEngine = scooterEngine,
                scooterSerial = scooterSerial,
                battId1 = battId1,
                battId2 = battId2,
                extraInfo = extraInfo,
                weeklyAmount = formatAmount(weeklyAmount),
                dailyAmount = formatAmount(dailyAmount),
                batteryDamageText = batteryDamageText
            )

            val effectiveBody = content.bodyText.ifBlank { TemplateContent.DEFAULT_CONTRACT_BODY_UNLIMITED }
            val resolvedBody = applyPlaceholders(effectiveBody, placeholders)

            val baseFontSize = pickBaseFontSize(
                autoFit = settings.pdfFontSizeAuto,
                initialSize = SettingsRepository.DEFAULT_PDF_FONT_SIZE,
                manualSize = settings.pdfFontSize,
                buildParagraphs = { size ->
                    val paints = createPaints(size)
                    parseTemplateToParagraphs(resolvedBody, paints)
                },
                contentWidth = contentWidth
            )
            val paints = createPaints(baseFontSize)
            val paragraphs = parseTemplateToParagraphs(resolvedBody, paints)

            renderParagraphs(doc, paragraphs, contentWidth, annotations, placeholders)
            writePdfToFile(doc, targetFile)

            Log.i(TAG, "Unlimited PDF saved: ${targetFile.absolutePath}")
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate unlimited PDF", e)
            return null
        } finally {
            doc.close()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Помощники для шаблонов
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Рендерит аннотации пользователя на странице PDF через [Canvas.drawText].
     *
     * Используется в [renderParagraphs] (inline) для каждой страницы:
     * после того как все параграфы отрисованы на странице, мы рисуем поверх
     * них текстовые аннотации пользователя.
     *
     * Координаты нормализованы (0..1) относительно ширины/высоты страницы.
     * Y инвертируется (Canvas top-down vs PDF bottom-up convention у пользователя
     * в редакторе — но Canvas тоже top-down, так что y * pageHeight).
     *
     * Текст аннотаций может содержать {{placeholders}} — заменяются через
     * [applyPlaceholders] перед рендером.
     *
     * @param canvas Canvas текущей страницы (получается из PdfDocument.Page.canvas)
     * @param pageNumber Номер текущей страницы (0-based)
     * @param annotations Все аннотации (фильтруем по pageNumber)
     * @param placeholders Карта плейсхолдеров для замены в тексте аннотаций
     */
    private fun renderAnnotationsOnPage(
        canvas: android.graphics.Canvas,
        pageNumber: Int,
        annotations: List<TemplateAnnotation>,
        placeholders: Map<String, String>
    ) {
        val pageAnnotations = annotations.filter { it.pageNumber == pageNumber }
        if (pageAnnotations.isEmpty()) return
        val paint = Paint().apply {
            isAntiAlias = true
        }
        for (ann in pageAnnotations) {
            val resolvedText = applyPlaceholders(ann.text, placeholders)
            val x = ann.x * PAGE_WIDTH
            val y = ann.y * PAGE_HEIGHT  // Canvas top-down, как в редакторе
            paint.color = parseColor(ann.colorHex)
            paint.textSize = ann.fontSize
            canvas.drawText(resolvedText, x, y, paint)
        }
    }

    /** Парсит #RRGGBB hex строку в Android Color Int. */
    private fun parseColor(hex: String): Int {
        return try {
            val cleaned = hex.removePrefix("#")
            Color.parseColor("#$cleaned")
        } catch (e: Exception) {
            Color.BLACK
        }
    }

    /**
     * Загружает активную версию шаблона из БД. Если БД недоступна или
     * нет активной версии — возвращает дефолтный [TemplateContent].
     *
     * Использует [runBlocking] т.к. публичные методы [generate] / [generateUnlimited]
     * синхронные (обратная совместимость с ContractHistoryViewModel).
     * DAO-запрос кэширован Room'ом, отрабатывает за <5 ms.
     */
    private fun loadActiveTemplateContent(context: Context, type: String): TemplateContent {
        return try {
            kotlinx.coroutines.runBlocking {
                val db = AppDatabase.getDatabase(context)
                val active = db.contractTemplateDao().getActiveForType(type)
                if (active != null) {
                    try {
                        kotlinx.serialization.json.Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                        }.decodeFromString<TemplateContent>(active.contentJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse template JSON, fallback to default", e)
                        TemplateContent()
                    }
                } else {
                    TemplateContent()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load active template, fallback to default", e)
            TemplateContent()
        }
    }

    /** Вычисляет номер договора в формате SRC-000014. */
    private fun computeContractNumber(entry: ContractHistoryEntry): String =
        "SRC-${entry.id.toString().padStart(6, '0')}"

    /**
     * Загружает аннотации пользователя из активной версии шаблона в БД.
     * Используется при генерации финального PDF для контракта — аннотации
     * применяются как overlay-текст поверх базового PDF.
     *
     * Если БД недоступна или нет активной версии — возвращает пустой список.
     */
    private fun loadActiveAnnotations(context: Context, type: String): List<TemplateAnnotation> {
        return try {
            kotlinx.coroutines.runBlocking {
                val db = AppDatabase.getDatabase(context)
                val active = db.contractTemplateDao().getActiveForType(type) ?: return@runBlocking emptyList()
                TemplateAnnotation.parseList(active.annotationsJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load active annotations, fallback to empty list", e)
            emptyList()
        }
    }

    /**
     * Строит карту плейсхолдеров для замены в теле шаблона.
     *
     * Ключи — имена вида "tenantName", "landlordName" и т.д.
     * В шаблоне используются как {{tenantName}}.
     */
    @Suppress("LongParameterList")
    private fun buildPlaceholders(
        content: TemplateContent,
        contractNumber: String,
        contractDate: String,
        contractDay: String,
        contractFullDate: String,
        weekStart: String,
        weekEnd: String,
        tenantName: String,
        tenantPhone: String,
        tenantPassport: String,
        tenantAddress: String,
        tenantPinfl: String,
        scooterName: String,
        scooterVin: String,
        scooterEngine: String,
        scooterSerial: String,
        battId1: String,
        battId2: String,
        extraInfo: String,
        weeklyAmount: String,
        dailyAmount: String,
        batteryDamageText: String
    ): Map<String, String> {
        // Заполнитель для пустых полей (чтобы линия для подписи оставалась)
        val fill: (String) -> String = { it.ifBlank { "______________________________" } }

        return mapOf(
            // Реквизиты арендодателя (из content)
            "landlordName" to content.landlordName,
            "landlordAddress" to content.landlordAddress,
            "landlordBank" to content.landlordBank,
            "landlordAccount" to content.landlordAccount,
            "landlordMfo" to content.landlordMfo,
            "landlordInn" to content.landlordInn,
            "landlordPhone" to content.landlordPhone,
            "landlordDirector" to content.landlordDirector,

            // Договор
            "contractNumber" to contractNumber,
            "contractDate" to contractDate,
            "contractDay" to contractDay,
            "contractFullDate" to contractFullDate,
            "weekStart" to weekStart,
            "weekEnd" to weekEnd,

            // Реквизиты арендатора
            "tenantName" to tenantName,
            "tenantPhone" to tenantPhone,
            "tenantPassport" to tenantPassport,
            "tenantAddress" to tenantAddress,
            "tenantPinfl" to tenantPinfl,

            // Реквизиты скутера
            "scooterName" to scooterName,
            "scooterVin" to scooterVin,
            "scooterEngine" to scooterEngine,
            "scooterSerial" to scooterSerial,
            "extraInfo" to extraInfo,

            // Аккумуляторы (отформатированные)
            "batteryIdsList" to formatBatteryIds(battId1, battId2, ", "),
            "batteryIdsActa" to formatBatteryIds(battId1, battId2, "  "),

            // Суммы
            "weeklyAmount" to weeklyAmount,
            "dailyAmount" to dailyAmount,
            "batteryDamageText" to batteryDamageText,

            // Заполненные варианты (для линий подписи)
            "tenantPassportFilled" to fill(tenantPassport),
            "tenantAddressFilled" to fill(tenantAddress),
            "tenantPinflFilled" to fill(tenantPinfl),
            "scooterVinFilled" to fill(scooterVin),
            "scooterEngineFilled" to fill(scooterEngine),
            "scooterSerialFilled" to fill(scooterSerial),
            "extraInfoFilled" to fill(extraInfo)
        )
    }

    /**
     * Заменяет все {{placeholders}} в [body] на значения из [placeholders].
     *
     * Использует регулярное выражение для поиска `\$\{name\}` и заменяет на
     * значение из карты. Если плейсхолдер не найден в карте — оставляет
     * как есть (не падает).
     */
    private fun applyPlaceholders(body: String, placeholders: Map<String, String>): String {
        val regex = Regex("""\{\{(\w+)\}\}""")
        return regex.replace(body) { match ->
            placeholders[match.groupValues[1]] ?: match.value
        }
    }

    /**
     * Парсит [bodyText] в список [Paragraph] для рендера в PDF.
     *
     * Line-based синтаксис (см. документацию [TemplateContent]):
     *   • `## ` prefix    — заголовок (titlePaint, +3pt, BOLD, center, 8f spaceAfter)
     *   • `### ` prefix   — заголовок раздела (sectionPaint, +1pt, BOLD, 6f spaceAfter)
     *   • `> ` prefix     — подпись (signaturePaint, 12f spaceAfter)
     *   • `* ` prefix     — bold body (bodyPaint.applyBold(), 4f spaceAfter)
     *   • `  ` (2 пробела) — body с отступом 12pt (для пунктов 1) 2))
     *   • пустая строка   — игнорируется, но даёт extra spaceAfter предыдущему
     *   • прочие строки   — body paint, indent=0, spaceAfter=4f
     */
    private fun parseTemplateToParagraphs(bodyText: String, paints: PdfPaints): List<Paragraph> {
        val result = mutableListOf<Paragraph>()
        val lines = bodyText.split("\n")
        var lastSpaceAfter = 0f

        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine

            when {
                line.isBlank() -> {
                    // Пустая строка — даём extra spaceAfter предыдущему параграфу.
                    if (result.isNotEmpty()) {
                        val prev = result.removeAt(result.lastIndex)
                        result.add(prev.copy(spaceAfter = (prev.spaceAfter + 4f).coerceAtMost(12f)))
                    }
                    lastSpaceAfter = 0f
                }
                line.startsWith("## ") -> {
                    result.add(Paragraph(
                        text = line.removePrefix("## "),
                        paint = paints.titlePaint,
                        alignment = Layout.Alignment.ALIGN_CENTER,
                        spaceAfter = 8f
                    ))
                    lastSpaceAfter = 8f
                }
                line.startsWith("### ") -> {
                    result.add(Paragraph(
                        text = line.removePrefix("### "),
                        paint = paints.sectionPaint,
                        spaceAfter = 6f
                    ))
                    lastSpaceAfter = 6f
                }
                line.startsWith("> ") -> {
                    result.add(Paragraph(
                        text = line.removePrefix("> "),
                        paint = paints.signaturePaint,
                        spaceAfter = 12f
                    ))
                    lastSpaceAfter = 12f
                }
                line.startsWith("* ") -> {
                    result.add(Paragraph(
                        text = line.removePrefix("* "),
                        paint = paints.bodyPaint.applyBold(),
                        spaceAfter = 4f
                    ))
                    lastSpaceAfter = 4f
                }
                line.startsWith("  ") -> {
                    // Отступ 12pt (для пунктов "1) ..." "2) ...")
                    result.add(Paragraph(
                        text = line.trimStart(),
                        paint = paints.bodyPaint,
                        indent = 12f,
                        spaceAfter = 4f
                    ))
                    lastSpaceAfter = 4f
                }
                else -> {
                    result.add(Paragraph(
                        text = line,
                        paint = paints.bodyPaint,
                        spaceAfter = 4f
                    ))
                    lastSpaceAfter = 4f
                }
            }
        }
        return result
    }

    // ──────────────────────────────────────────────────────────────────────
    // Форматирование
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Форматирует список ID аккумуляторов для PDF-договора.
     *
     * Правила (чтобы не показывать дубликаты, когда оба аккумулятора
     * имеют одинаковый ID или когда второй аккумулятор отсутствует):
     *   • Если battId2 пустой → показываем только "ID: battId1"
     *   • Если battId1 пустой → показываем только "ID: battId2"
     *   • Если battId1 == battId2 → показываем только один "ID: battId1"
     *   • Иначе → "ID: battId1, ID: battId2" (или с другим сепаратором)
     *   • Если оба пустые → "ID: ______" (заглушка для подписи)
     *
     * @param separator разделитель между двумя ID (", " для раздела 1.1,
     *                  "  " для далолатномаси)
     */
    private fun formatBatteryIds(
        battId1: String,
        battId2: String,
        separator: String = ", "
    ): String {
        val shortFill: (String) -> String = { it.ifBlank { "________" } }
        return when {
            battId1.isBlank() && battId2.isBlank() -> "ID: ${shortFill("")}"
            battId1.isBlank() -> "ID: ${shortFill(battId2)}"
            battId2.isBlank() -> "ID: ${shortFill(battId1)}"
            battId1 == battId2 -> "ID: ${shortFill(battId1)}"
            else -> "ID: ${shortFill(battId1)}${separator}ID: ${shortFill(battId2)}"
        }
    }

    private fun formatAmount(amount: Double): String {
        val longVal = amount.toLong()
        // Format with spaces: 420 000
        return "%,d".format(longVal).replace(",", " ")
    }

    /**
     * Форматирует сумму прописью на узбекском для PDF-договора.
     *
     * Упрощённая версия: возвращает сумму цифрами с разделением разрядов
     * пробелом + "сўм" + копейки "00 тийин". Используется в секции
     * о поломке аккумулятора (3.13).
     */
    private fun formatAmountWithText(amount: Double): String {
        val longVal = amount.toLong()
        return "${"%,d".format(longVal).replace(",", " ")} сўм 00 тийин"
    }

    // ──────────────────────────────────────────────────────────────────────
    // I/O helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Создаёт File в публичной папке Documents/ScooterContracts/. */
    private fun defaultOutputFile(context: Context, baseName: String): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ScooterContracts"
        )
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "${baseName}_$ts.pdf")
    }

    /** Записывает PdfDocument в файл. */
    private fun writePdfToFile(doc: PdfDocument, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos -> doc.writeTo(fos) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rendering infrastructure (без изменений по сравнению с v36)
    // ──────────────────────────────────────────────────────────────────────

    private data class Paragraph(
        val text: String,
        val paint: TextPaint,
        val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        val indent: Float = 0f,
        val spaceAfter: Float = 4f
    )

    private fun TextPaint.applyBold(): TextPaint = TextPaint(this).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /**
     * Контейнер для четырёх paints, параметризованных базовым размером.
     *  • titlePaint    — baseSize + 3 (заголовок договора)
     *  • sectionPaint  — baseSize + 1 (заголовки разделов)
     *  • bodyPaint     — baseSize (основной текст)
     *  • signaturePaint — baseSize (подписи)
     */
    private data class PdfPaints(
        val titlePaint: TextPaint,
        val bodyPaint: TextPaint,
        val sectionPaint: TextPaint,
        val signaturePaint: TextPaint
    )

    private fun createPaints(baseSize: Float): PdfPaints {
        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = baseSize + 3f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = baseSize
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val sectionPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = baseSize + 1f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val signaturePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = baseSize
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        return PdfPaints(titlePaint, bodyPaint, sectionPaint, signaturePaint)
    }

    /**
     * Измеряет суммарную высоту всех параграфов (с учётом spaceAfter и
     * переносов строк). Не рисует — только считает.
     *
     * Возвращает пару (totalHeight, pageCount). pageCount > 1 значит
     * документ не помещается на одну страницу A4.
     */
    private fun measureParagraphs(
        paragraphs: List<Paragraph>,
        contentWidth: Int
    ): Pair<Float, Int> {
        val usableHeight = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM
        var totalHeight = 0f
        var pageCount = 1
        var pageUsed = 0f
        for (para in paragraphs) {
            val layout = StaticLayout.Builder
                .obtain(para.text, 0, para.text.length, para.paint, contentWidth)
                .setAlignment(para.alignment)
                .setLineSpacing(0f, 1.3f)
                .setIncludePad(false)
                .build()
            val paraHeight = layout.height + para.spaceAfter
            totalHeight += paraHeight
            if (pageUsed + paraHeight > usableHeight) {
                pageCount++
                pageUsed = paraHeight
            } else {
                pageUsed += paraHeight
            }
        }
        return totalHeight to pageCount
    }

    /**
     * Подбирает размер шрифта так, чтобы документ поместился на 1 страницу.
     *
     * Если autoFit=true — начинает с [initialSize] и уменьшает на 0.5f
     * за шаг, пока не поместится или не достигнет [minSize].
     * Если autoFit=false — возвращает [manualSize] (без подгонки).
     */
    private fun pickBaseFontSize(
        autoFit: Boolean,
        initialSize: Float,
        manualSize: Float,
        minSize: Float = 7f,
        buildParagraphs: (Float) -> List<Paragraph>,
        contentWidth: Int
    ): Float {
        if (!autoFit) return manualSize
        var size = initialSize
        while (size >= minSize) {
            val paragraphs = buildParagraphs(size)
            val (_, pages) = measureParagraphs(paragraphs, contentWidth)
            if (pages <= 1) return size
            size -= 0.5f
        }
        return minSize
    }

    /**
     * Рендерит список параграфов в PdfDocument с пагинацией (на случай если
     * документ всё же не уместился на одну страницу — например при manual
     * режиме с большим размером шрифта).
     *
     * После рендера параграфов на каждой странице, также рендерит текстовые
     * аннотации пользователя (через [renderAnnotationsOnPage]) — поверх
     * параграфов. Аннотации могут содержать {{placeholders}} — заменяются
     * на реальные значения через [applyPlaceholders].
     */
    private fun renderParagraphs(
        doc: PdfDocument,
        paragraphs: List<Paragraph>,
        contentWidth: Int,
        annotations: List<TemplateAnnotation> = emptyList(),
        placeholders: Map<String, String> = emptyMap()
    ) {
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN_TOP

        for (para in paragraphs) {
            val layout = StaticLayout.Builder
                .obtain(para.text, 0, para.text.length, para.paint, contentWidth)
                .setAlignment(para.alignment)
                .setLineSpacing(0f, 1.3f)
                .setIncludePad(false)
                .build()

            val paraHeight = layout.height + para.spaceAfter

            if (y + paraHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                // Перед завершением страницы — рендерим аннотации для неё
                renderAnnotationsOnPage(canvas, pageNumber - 1, annotations, placeholders)
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = MARGIN_TOP
            }

            canvas.save()
            canvas.translate(MARGIN_X + para.indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += paraHeight
        }
        // Рендерим аннотации для последней страницы
        renderAnnotationsOnPage(canvas, pageNumber - 1, annotations, placeholders)
        doc.finishPage(page)
    }
}
