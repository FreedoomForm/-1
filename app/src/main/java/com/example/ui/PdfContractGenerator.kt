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
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.SettingsRepository
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
 *   • Раздел 3: Тарафларнинг ҳуқуқ ва мажбуриятлари (3.1-3.12)
 *   • Раздел 4: Жавобгарлик ва низолар (4.1-4.2)
 *   • Раздел 5: Бошқа шартлар (5.1-5.4)
 *   • Раздел 6: Реквизитлар ва имзолар
 *   • Топшириқ-қабул қилиш далолатномаси
 *
 * Формат: A4 (595 × 842 pt). Многостраничная вёрстка через StaticLayout.
 */
object PdfContractGenerator {

    private const val TAG = "PdfContractGenerator"
    private const val PAGE_WIDTH = 595   // A4 @ 72 DPI
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val MARGIN_TOP = 40f
    private const val MARGIN_BOTTOM = 40f

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val dateFmtUz = SimpleDateFormat("dd MMMM yyyy", Locale("ru"))

    // ── Реквизиты арендодателя (статичны, как в docx) ──────────────────────
    private const val LANDLORD_NAME = "ЯТТ «АСИЛБЕКОВ ШЕРЗОД УЛУГБЕКОВИЧ»"
    private const val LANDLORD_ADDRESS = "Тошкент Шахри, Юнусобод тумани, Сайилгох кучаси, 17-уй"
    private const val LANDLORD_BANK = "Тошкент Ш., «КАПИТАЛБАНК» АТ БАНКИНИНГ БОШ ОФИСИ"
    private const val LANDLORD_ACCOUNT = "20218 000 9 04982540 001"
    private const val LANDLORD_MFO = "01088"
    private const val LANDLORD_INN = "32607780220041"
    private const val LANDLORD_PHONE = "+998 77 777 10 00"
    private const val LANDLORD_DIRECTOR = "Асилбеков Шерзод Улугбекович"

    fun generate(
        context: Context,
        entry: ContractHistoryEntry,
        renter: Renter?,
        scooter: Scooter? = null
    ): Uri? {
        val doc = PdfDocument()
        try {
            // ── Динамические данные из записи истории ────────────────────────
            val contractNumber = "SRC-${entry.id.toString().padStart(6, '0')}"
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

            // ── Реквизиты арендатора для PDF (entry → renter fallback) ──────
            val tenantPassport = entry.passportData.ifBlank { renter?.passportData ?: "" }
            val tenantAddress = entry.address.ifBlank { renter?.address ?: "" }
            val tenantPinfl = entry.pinfl.ifBlank { renter?.pinfl ?: "" }

            // ── Реквизиты скутера для PDF (entry → scooter fallback) ────────
            // ВАЖНО: ранее данные скутера брались ТОЛЬКО из entry, и если они
            // там были пусты (контракт создан до того, как поля скутера стали
            // обязательными, или scooterId не разрешался) — PDF уходил с пустыми
            // линиями. Теперь добавлен fallback на саму сущность Scooter, что
            // гарантирует корректное отображение данных скутера в PDF всегда.
            val scooterVin = entry.vinNumber.ifBlank { scooter?.vinNumber ?: "" }
            val scooterEngine = entry.engineNumber.ifBlank { scooter?.engineNumber ?: "" }
            val scooterSerial = entry.scooterSerialNumber.ifBlank { scooter?.scooterSerialNumber ?: "" }
            val battId1 = entry.batteryId1.ifBlank { scooter?.batteryId1 ?: "" }
            val battId2 = entry.batteryId2.ifBlank { scooter?.batteryId2 ?: "" }
            val extraInfo = entry.additionalInfo.ifBlank { scooter?.additionalInfo ?: "" }

            // Заполнитель для пустых полей (чтобы линия для подписи оставалась)
            fun fill(value: String): String = value.ifBlank { "______________________________" }
            fun shortFill(value: String): String = value.ifBlank { "________" }

            // ── Paints ───────────────────────────────────────────────────────
            val titlePaint = TextPaint().apply {
                color = Color.BLACK
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val bodyPaint = TextPaint().apply {
                color = Color.BLACK
                textSize = 10f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val sectionPaint = TextPaint().apply {
                color = Color.BLACK
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val signaturePaint = TextPaint().apply {
                color = Color.BLACK
                textSize = 10f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }

            val contentWidth = (PAGE_WIDTH - 2 * MARGIN_X).toInt()

            // ── Сборка всех параграфов документа ────────────────────────────
            val paragraphs = buildList {
                // Заголовок
                add(Paragraph("ЭЛЕКТР СКУТЕР ИЖАРАСИ ШАРТНОМАСИ № $contractNumber", titlePaint, alignment = Layout.Alignment.ALIGN_CENTER, spaceAfter = 8f))
                add(Paragraph("«${dateFmt.format(Date(entry.timestamp)).take(2)}» $contractDate. Тошкент шаҳри", bodyPaint, spaceAfter = 12f))

                // Преамбула
                add(Paragraph(
                    "Кейинги ўринларда «Ижарага берувчи» деб аталадиган $LANDLORD_NAME номидан, бир томондан, кейинги ўринларда «Ижарага олувчи» деб аталадиган:",
                    bodyPaint, spaceAfter = 8f
                ))
                add(Paragraph(
                    "Манзил: ${fill(tenantAddress)} да яшовчи фуқаро ФИШ $tenantName",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "(Паспорт серия, рақам, олинган сана) ${fill(tenantPassport)}",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "Телефон: $tenantPhone",
                    bodyPaint, spaceAfter = 8f
                ))
                add(Paragraph(
                    "иккинчи томондан қуйидагилар тўғрисида ушбу шартномани туздилар:",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 1
                add(Paragraph("1. Шартнома предмети", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "1.1. Шартномага мувофиқ «Ижарага берувчи» ўзига мулк ҳуқуқи асосида тегишли бўлган:",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "1) $scooterName моделдаги электрли скутерни;",
                    bodyPaint, indent = 12f, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2) Аккумуляторлар (ID: ${shortFill(battId1)}, ID: ${shortFill(battId2)}) билан биргаликда «Ижарага олувчи»га топшириш, «Ижарага олувчи» эса вақтинча фойдаланиш учун уни ижарага олиш ва ижара ҳақини тўлаш мажбуриятини олади.",
                    bodyPaint, indent = 12f, spaceAfter = 4f
                ))
                add(Paragraph(
                    "Электр скутер ҳамда аккумулятор ҳақидаги батафсил маълумотлар ушбу шартноманинг ажралмас қисми бўлган топшириқ-қабул қилиш далолатномасида кўрсатилади.",
                    bodyPaint, spaceAfter = 6f
                ))
                add(Paragraph(
                    "1.2. «Ижарага олувчи» электрли скутердан Тошкент шаҳри ҳудудида етказиб бериш (курьер) хизмати кўрсатиш мақсадида фойдаланади.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 2
                add(Paragraph("2. Тўлов шартлари", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "2.1. «Ижарага олувчи» «Ижарага берувчи»га олдиндан 0 (ноль сўм 00 тийин) миқдорида кафолат пули тўлайди. Ушбу кафолат пули шартнома муддати якунланганидан сўнг «Ижарага олувчи»га қайтарилади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "Агар шартнома амалда бўлган вақтда мототранспорт воситасига зарар етказилган тақдирда, мототранспорт воситасини таъмирлаш ишлари ушбу маблағ ҳисобидан қопланади. Агар таъмирлаш учун ушбу кафолат пули етарли бўлмаса, қолган қисми «Ижарага олувчи» томонидан қопланади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.2. Мототранспорт воситасининг қиймати қайта нархлаш коэффициентлари ва амортизация меъёрларини ҳисобга олган ҳолда 11 500 000 (ўн бир миллион беш юз минг сўм 00 тийин)ни ташкил этади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.3. Ижара ҳақи «Ижарага олувчи» томонидан кунига ${formatAmount(dailyAmount)} (ўз сўмларида)дан ҳисобланади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.4. Ижара тўловлари «Ижарага олувчи» томонидан 7 кун учун олдиндан ${formatAmount(weeklyAmount)} миқдорида тўлаб борилади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.5. Ушбу шартнома бўйича ижара муддати ${dateFmt.format(Date(weekStart))} санасидан ${dateFmt.format(Date(weekEnd))} санасига қадар белгиланади. Томонлар келишувига асосан ижара муддати узайтирилиши мумкин.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 3
                add(Paragraph("3. Тарафларнинг ҳуқуқ ва мажбуриятлари", sectionPaint, spaceAfter = 6f))
                val section3 = listOf(
                    "3.1. «Ижарага берувчи» шартнома имзолангандан кейин электр скутерни ўша куннинг ўзида «Ижарага олувчи»га топширади.",
                    "3.2. Электр скутерни жорий таъмирлаш ва профилактика кўригидан ўтказиш «Ижарага олувчи» томон ҳисобидан амалга оширилади.",
                    "3.3. «Ижарага олувчи» электр скутерга қўшимча тақдим қилинган аккумуляторларни соз ҳолатда сақланиши учун жавобгар ҳисобланади.",
                    "3.4. Йўл транспорт ҳодисаси содир бўлганида ёки бошқа ҳар қандай ҳолатда электр скутерга ёки аккумуляторга зарар етган ҳолатда «Ижарага олувчи» 3 кун муддат ичида ўз ҳисобидан таъмирлайди.",
                    "3.5. Йўл транспорт ҳодисаси учинчи шахс айби билан содир этилиши натижасида электр скутерга ёки аккумуляторга етказилган зарарни қоплаш учун мулкдор сифатида «Ижарага берувчи» учинчи шахсдан етказилган зарарни ундириш юзасидан ваколатли идораларга даъво қилиш ҳуқуқига эга.",
                    "3.6. «Ижарага берувчи» шартномада келишилган ижара ҳақи ёки етказилган зарар бўйича қарздорлик ўз вақтида тўланмаган тақдирда, қарздорликни қонунчиликда белгиланган тартибда ундириш ҳуқуқига эга.",
                    "3.7. Форс-мажор ҳолатлари натижасида келиб чиқадиган зарарлар амалдаги қонунчиликка ва ушбу шартнома шартларига мувофиқ ҳал этилади.",
                    "3.8. Электр скутер ушбу шартнома имзоланишидан сўнг топшириқ-қабул қилиш далолатномасида кўрсатилган ҳолатда «Ижарага олувчи»га топширилади ва айнан шу ҳолатда қайтарилиши лозим.",
                    "3.9. «Ижарага олувчи» электр скутерни қайтариб топшириши ҳақида «Ижарага берувчи»ни камида 3 кун олдин хабардор қилиши лозим.",
                    "3.10. Ижара муддати давомида «Ижарага олувчи» томонидан Йўл ҳаракати қоидаларини бузиш оқибатида юзага келган ҳар қандай жарималар «Ижарага олувчи» томонидан тўланади.",
                    "3.11. Электр скутерни «Ижарага олувчи» томонидан бошқа учинчи шахсга фойдаланишга бериш қатъиян тақиқланади. Ушбу ҳолат аниқланган тақдирда «Ижарага берувчи» шартномани бир томонлама бекор қилиш ва электр скутерни қайтариб олиш ҳуқуқига эга.",
                    "3.12. Электр скутер авария ҳолатида, техник носоз ёки фойдаланишга яроқсиз ҳолатда қайтарилган, шунингдек электр скутер ёки аккумуляторлар йўқотилган, ўғирланган ёки топширилмаган ҳолларда, етказилган зарар учун тўлиқ моддий жавобгарлик «Ижарага олувчи» зиммасига юклатилади."
                )
                section3.forEach { add(Paragraph(it, bodyPaint, spaceAfter = 4f)) }
                add(Paragraph("", bodyPaint, spaceAfter = 8f))

                // Раздел 4
                add(Paragraph("4. Тарафларнинг жавобгарлиги ва низоларни ҳал қилиш тартиби", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "4.1. Тарафлар ўз мажбуриятларини бажармаган ёки лозим даражада бажармаганликлари учун Ўзбекистон Республикасининг Фуқаролик кодекси ва бошқа қонун ҳужжатлари ҳамда мазкур шартномага мувофиқ жавобгар бўладилар.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "4.2. Тарафлар ўртасида келиб чиқадиган низолар тарафларнинг ўзаро келишуви асосида ҳал этилади. Тарафлар келишувга эришмаган тақдирда, низо белгиланган тартибда судда ҳал этилади.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 5
                add(Paragraph("5. Шартноманинг бошқа шартлари", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "5.1. Шартномага киритилаётган барча ўзгартириш ва қўшимчалар ёзма равишда тузилган ва иккала тараф томонидан имзоланган ҳолдагина ҳақиқий ҳисобланади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "5.2. Шартноманинг бошланиш муддати шартнома тузилган санадан бошлаб кучга киради ва шартноманинг якунланиш муддати томонлар ўртасида қўшимча келишув имзоланиб, бекор қилинишига қадар амал қилади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "5.3. Шартнома 2 нусхада тузилган бўлиб, иккаласи ҳам бир хил юридик кучга эга.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "5.4. Мазкур шартномада назарда тутилмаган масалалар амалдаги қонун ҳужжатларига мувофиқ тартибга солинади.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 6 — Реквизиты
                add(Paragraph("6. Тарафларнинг реквизитлари ва имзолари:", sectionPaint, spaceAfter = 6f))
                add(Paragraph("«Ижарага берувчи»:", bodyPaint.applyBold(), spaceAfter = 4f))
                add(Paragraph("Номи: $LANDLORD_NAME", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Манзил: $LANDLORD_ADDRESS", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Банк: $LANDLORD_BANK", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Р/С: $LANDLORD_ACCOUNT", bodyPaint, spaceAfter = 2f))
                add(Paragraph("МФО: $LANDLORD_MFO", bodyPaint, spaceAfter = 2f))
                add(Paragraph("ИНН: $LANDLORD_INN", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Телефон: $LANDLORD_PHONE", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Рахбар: $LANDLORD_DIRECTOR", bodyPaint, spaceAfter = 8f))

                add(Paragraph("«Ижарага Олувчи»:", bodyPaint.applyBold(), spaceAfter = 4f))
                add(Paragraph("ФИШ: $tenantName", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Паспорт маълумотлари: ${fill(tenantPassport)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Манзил: ${fill(tenantAddress)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("ЖШШИР: ${fill(tenantPinfl)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Телефон: $tenantPhone", bodyPaint, spaceAfter = 8f))

                add(Paragraph(
                    "Ижарага берувчи имзоси: _______________________     Ижарага Олувчи имзоси: _______________________",
                    signaturePaint, spaceAfter = 16f
                ))

                // ── Топшириқ-қабул қилиш далолатномаси ─────────────────────────
                add(Paragraph(
                    "Топшириқ-қабул қилиш Далолатномаси",
                    sectionPaint, alignment = Layout.Alignment.ALIGN_CENTER, spaceAfter = 8f
                ))
                add(Paragraph(
                    "«${dateFmt.format(Date(entry.timestamp)).take(2)}» $contractDate. Тошкент шаҳри",
                    bodyPaint, alignment = Layout.Alignment.ALIGN_CENTER, spaceAfter = 8f
                ))
                add(Paragraph(
                    "Ушбу далолатнома шу ҳақдаки $LANDLORD_NAME (кейинги ўринларда “Ижарага берувчи”), корхона рахбари $LANDLORD_DIRECTOR ҳамда фуқаро $tenantName (кейинги ўринларда “Ижарага олувчи”) ўртасида «${dateFmt.format(Date(entry.timestamp))}» санасида имзоланган № $contractNumber сонли Электр скутер ижара шартномасига асосан қуйидаги Электр скутер аккумуляторлар билан биргаликда Ижарага олувчига топширилди:",
                    bodyPaint, spaceAfter = 8f
                ))
                add(Paragraph("Модель: $scooterName", bodyPaint, spaceAfter = 2f))
                add(Paragraph("VIN №: ${fill(scooterVin)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Двигатель рақами: ${fill(scooterEngine)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("ID рақами: ${fill(scooterSerial)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Аккумулятор ID рақамлари: ID: ${shortFill(battId1)}  ID: ${shortFill(battId2)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Қўшимча маълумот: ${fill(extraInfo)}", bodyPaint, spaceAfter = 8f))
                add(Paragraph(
                    "Ижарага берувчи юқорида кўрсатилган мототранспорт воситасини кўздан кечирганда қуйидаги ҳолатлар аниқланди:",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph("Рама ва корпус: соз ҳолатда", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Мотор: соз ҳолатда", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Бошқа қисмлар: соз ҳолатда", bodyPaint, spaceAfter = 8f))
                add(Paragraph(
                    "Топширди: _______________________     Қабул қилди: _______________________",
                    signaturePaint, spaceAfter = 12f
                ))
                add(Paragraph(
                    "$LANDLORD_NAME          $tenantName",
                    signaturePaint, alignment = Layout.Alignment.ALIGN_CENTER
                ))
            }

            // ── Рендер с пагинацией ──────────────────────────────────────────
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

                // If paragraph doesn't fit on current page — start new page
                if (y + paraHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                    doc.finishPage(page)
                    pageNumber++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                    canvas = page.canvas
                    y = MARGIN_TOP
                }

                // Draw paragraph (with optional indent)
                canvas.save()
                canvas.translate(MARGIN_X + para.indent, y)
                layout.draw(canvas)
                canvas.restore()
                y += paraHeight
            }
            doc.finishPage(page)

            // ── Сохранение в файл ──────────────────────────────────────────
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ScooterContracts"
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "rental_contract_${contractNumber}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf")
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

    private fun formatAmount(amount: Double): String {
        val longVal = amount.toLong()
        // Format with spaces: 420 000
        return "%,d".format(longVal).replace(",", " ")
    }

    /**
     * Генерирует PDF-договор аренды электроскутера с НЕОГРАНИЧЕННЫМ сроком
     * действия.
     *
     * Отличия от [generate]:
     *   • № договора = «SRC-UNLMT-<renterId>-<timestamp>»
     *   • В разделе 2.5 прямо указано, что договор действует на неограниченный
     *     срок до момента, когда арендатор примет решение его расторгнуть
     *     (формальный юридический язык).
     *   • Дата начала = renter.rentStartDateTimestamp, дата окончания = «—»
     *     (не указана, т.к. договор бессрочный).
     *   • В разделе 5.2 дополнительно подтверждается бессрочный характер
     *     договора и право арендатора расторгнуть его в любой момент с
     *     предварительным уведомлением за 3 дня.
     *
     * @param context  контекст приложения
     * @param renter   арендатор (источник данных)
     * @param scooter  скутер (опционально, для fallback'а полей)
     */
    fun generateUnlimited(
        context: Context,
        renter: Renter,
        scooter: Scooter? = null
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

            // Реквизиты скутера (renter не хранит → берём из scooter, если передан)
            val scooterVin = scooter?.vinNumber ?: ""
            val scooterEngine = scooter?.engineNumber ?: ""
            val scooterSerial = scooter?.scooterSerialNumber ?: ""
            val battId1 = scooter?.batteryId1 ?: ""
            val battId2 = scooter?.batteryId2 ?: ""
            val extraInfo = scooter?.additionalInfo ?: ""

            fun fill(value: String): String = value.ifBlank { "______________________________" }
            fun shortFill(value: String): String = value.ifBlank { "________" }

            // ── Paints (те же, что и в generate) ──────────────────────────
            val titlePaint = TextPaint().apply {
                color = Color.BLACK; textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true
            }
            val bodyPaint = TextPaint().apply {
                color = Color.BLACK; textSize = 10f
                typeface = Typeface.DEFAULT; isAntiAlias = true
            }
            val sectionPaint = TextPaint().apply {
                color = Color.BLACK; textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true
            }
            val signaturePaint = TextPaint().apply {
                color = Color.BLACK; textSize = 10f
                typeface = Typeface.DEFAULT; isAntiAlias = true
            }

            val contentWidth = (PAGE_WIDTH - 2 * MARGIN_X).toInt()

            // ── Сборка параграфов ──────────────────────────────────────────
            val paragraphs = buildList {
                add(Paragraph(
                    "ЭЛЕКТР СКУТЕР ИЖАРАСИ ШАРТНОМАСИ № $contractNumber",
                    titlePaint, alignment = Layout.Alignment.ALIGN_CENTER, spaceAfter = 8f
                ))
                add(Paragraph(
                    "«${dateFmt.format(Date(now)).take(2)}» $contractDate. Тошкент шаҳри",
                    bodyPaint, spaceAfter = 12f
                ))

                // Преамбула
                add(Paragraph(
                    "Кейинги ўринларда «Ижарага берувчи» деб аталадиган $LANDLORD_NAME номидан, бир томондан, кейинги ўринларда «Ижарага олувчи» деб аталадиган:",
                    bodyPaint, spaceAfter = 8f
                ))
                add(Paragraph(
                    "Манзил: ${fill(tenantAddress)} да яшовчи фуқаро ФИШ $tenantName",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "(Паспорт серия, рақам, олинган сана) ${fill(tenantPassport)}",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph("Телефон: $tenantPhone", bodyPaint, spaceAfter = 8f))
                add(Paragraph(
                    "иккинчи томондан қуйидагилар тўғрисида ушбу шартномани туздилар:",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 1
                add(Paragraph("1. Шартнома предмети", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "1.1. Шартномага мувофиқ «Ижарага берувчи» ўзига мулк ҳуқуқи асосида тегишли бўлган:",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "1) $scooterName моделдаги электрли скутерни;",
                    bodyPaint, indent = 12f, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2) Аккумуляторлар (ID: ${shortFill(battId1)}, ID: ${shortFill(battId2)}) билан биргаликда «Ижарага олувчи»га топшириш, «Ижарага олувчи» эса вақтинча фойдаланиш учун уни ижарага олиш ва ижара ҳақини тўлаш мажбуриятини олади.",
                    bodyPaint, indent = 12f, spaceAfter = 4f
                ))
                add(Paragraph(
                    "Электр скутер ҳамда аккумулятор ҳақидаги батафсил маълумотлар ушбу шартноманинг ажралмас қисми бўлган топшириқ-қабул қилиш далолатномасида кўрсатилади.",
                    bodyPaint, spaceAfter = 6f
                ))
                add(Paragraph(
                    "1.2. «Ижарага олувчи» электрли скутердан Тошкент шаҳри ҳудудида етказиб бериш (курьер) хизмати кўрсатиш мақсадида фойдаланади.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 2 — с НЕОГРАНИЧЕННЫМ сроком
                add(Paragraph("2. Тўлов шартлари", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "2.1. «Ижарага олувчи» «Ижарага берувчи»га олдиндан 0 (ноль сўм 00 тийин) миқдорида кафолат пули тўлайди. Ушбу кафолат пули шартнома муддати якунланганидан сўнг «Ижарага олувчи»га қайтарилади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "Агар шартнома амалда бўлган вақтда мототранспорт воситасига зарар етказилган тақдирда, мототранспорт воситасини таъмирлаш ишлари ушбу маблағ ҳисобидан қопланади. Агар таъмирлаш учун ушбу кафолат пули етарли бўлмаса, қолган қисми «Ижарага олувчи» томонидан қопланади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.2. Мототранспорт воситасининг қиймати қайта нархлаш коэффициентлари ва амортизация меъёрларини ҳисобга олган ҳолда 11 500 000 (ўн бир миллион беш юз минг сўм 00 тийин)ни ташкил этади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.3. Ижара ҳақи «Ижарага олувчи» томонидан кунига ${formatAmount(dailyAmount)} (ўз сўмларида)дан ҳисобланади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "2.4. Ижара тўловлари «Ижарага олувчи» томонидан 7 кун учун олдиндан ${formatAmount(weeklyAmount)} миқдорида тўлаб борилади.",
                    bodyPaint, spaceAfter = 4f
                ))
                // ── КЛЮЧЕВОЕ ОТЛИЧИЕ: бессрочный договор ─────────────────────
                add(Paragraph(
                    "2.5. Ушбу шартнома бўйича ижара муддати ${dateFmt.format(Date(weekStart))} санасидан бошлаб чексиз муддатга, яъни «Ижарага олувчи» шартномани расман тугатиш тўғрисида ёзма равишда билдиргунга қадар, шу вақтгача амал қилади. Шартнома «Ижарага олувчи» томонидан исталган вақтда, олдиндан камида 3 (уч) кун муддатда хабардор қилиниши шартли равишда, бекор қилиниши мумкин. Ҳар ҳафталик ижара тўловлари шартнома амал қилиш давомида тўлаб борилаверади, то «Ижарага олувчи» шартномани тугатиш тўғрисидаги ёзма аризани тақдим этгунча.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 3
                add(Paragraph("3. Тарафларнинг ҳуқуқ ва мажбуриятлари", sectionPaint, spaceAfter = 6f))
                val section3 = listOf(
                    "3.1. «Ижарага берувчи» шартнома имзолангандан кейин электр скутерни ўша куннинг ўзида «Ижарага олувчи»га топширади.",
                    "3.2. Электр скутерни жорий таъмирлаш ва профилактика кўригидан ўтказиш «Ижарага олувчи» томон ҳисобидан амалга оширилади.",
                    "3.3. «Ижарага олувчи» электр скутерга қўшимча тақдим қилинган аккумуляторларни соз ҳолатда сақланиши учун жавобгар ҳисобланади.",
                    "3.4. Йўл транспорт ҳодисаси содир бўлганида ёки бошқа ҳар қандай ҳолатда электр скутерга ёки аккумуляторга зарар етган ҳолатда «Ижарага олувчи» 3 кун муддат ичида ўз ҳисобидан таъмирлайди.",
                    "3.5. Йўл транспорт ҳодисаси учинчи шахс айби билан содир этилиши натижасида электр скутерга ёки аккумуляторга етказилган зарарни қоплаш учун мулкдор сифатида «Ижарага берувчи» учинчи шахсдан етказилган зарарни ундириш юзасидан ваколатли идораларга даъво қилиш ҳуқуқига эга.",
                    "3.6. «Ижарага берувчи» шартномада келишилган ижара ҳақи ёки етказилган зарар бўйича қарздорлик ўз вақтида тўланмаган тақдирда, қарздорликни қонунчиликда белгиланган тартибда ундириш ҳуқуқига эга.",
                    "3.7. Форс-мажор ҳолатлари натижасида келиб чиқадиган зарарлар амалдаги қонунчиликка ва ушбу шартнома шартларига мувофиқ ҳал этилади.",
                    "3.8. Электр скутер ушбу шартнома имзоланишидан сўнг топшириқ-қабул қилиш далолатномасида кўрсатилган ҳолатда «Ижарага олувчи»га топширилади ва айнан шу ҳолатда қайтарилиши лозим.",
                    "3.9. «Ижарага олувчи» электр скутерни қайтариб топшириши ҳақида «Ижарага берувчи»ни камида 3 кун олдин хабардор қилиши лозим.",
                    "3.10. Ижара муддати давомида «Ижарага олувчи» томонидан Йўл ҳаракати қоидаларини бузиш оқибатида юзага келган ҳар қандай жарималар «Ижарага олувчи» томонидан тўланади.",
                    "3.11. Электр скутерни «Ижарага олувчи» томонидан бошқа учинчи шахсга фойдаланишга бериш қатъиян тақиқланади. Ушбу ҳолат аниқланган тақдирда «Ижарага берувчи» шартномани бир томонлама бекор қилиш ва электр скутерни қайтариб олиш ҳуқуқига эга.",
                    "3.12. Электр скутер авария ҳолатида, техник носоз ёки фойдаланишга яроқсиз ҳолатда қайтарилган, шунингдек электр скутер ёки аккумуляторлар йўқотилган, ўғирланган ёки топширилмаган ҳолларда, етказилган зарар учун тўлиқ моддий жавобгарлик «Ижарага олувчи» зиммасига юклатилади."
                )
                section3.forEach { add(Paragraph(it, bodyPaint, spaceAfter = 4f)) }
                add(Paragraph("", bodyPaint, spaceAfter = 8f))

                // Раздел 4
                add(Paragraph("4. Тарафларнинг жавобгарлиги ва низоларни ҳал қилиш тартиби", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "4.1. Тарафлар ўз мажбуриятларини бажармаган ёки лозим даражада бажармаганликлари учун Ўзбекистон Республикасининг Фуқаролик кодекси ва бошқа қонун ҳужжатлари ҳамда мазкур шартномага мувофиқ жавобгар бўладилар.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "4.2. Тарафлар ўртасида келиб чиқадиган низолар тарафларнинг ўзаро келишуви асосида ҳал этилади. Тарафлар келишувга эришмаган тақдирда, низо белгиланган тартибда судда ҳал этилади.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 5 — с бессрочным характером
                add(Paragraph("5. Шартноманинг бошқа шартлари", sectionPaint, spaceAfter = 6f))
                add(Paragraph(
                    "5.1. Шартномага киритилаётган барча ўзгартириш ва қўшимчалар ёзма равишда тузилган ва иккала тараф томонидан имзоланган ҳолдагина ҳақиқий ҳисобланади.",
                    bodyPaint, spaceAfter = 4f
                ))
                // ── КЛЮЧЕВОЕ ОТЛИЧИЕ: подтверждение бессрочности ─────────────
                add(Paragraph(
                    "5.2. Ушбу шартнома ${dateFmt.format(Date(weekStart))} санасида тузилган санадан бошлаб кучга киради ва чексиз муддатга, яъни «Ижарага олувчи» шартномани тугатиш тўғрисида ёзма равишда ариза бергунга қадар амал қилади. «Ижарага олувчи» шартномани исталган вақтда бир томонлама тугатиш ҳуқуқига эга, бу ҳақда камида 3 (уч) кун олдин «Ижарага берувчи»ни хабардор қилиши шарт. Шартнома тугатилганда электр скутер ва аккумуляторлар «Ижарага берувчи»га қайтарилади, қолган ҳафталик тўловлар ўзаро ҳисоб-китоб қилинади.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "5.3. Шартнома 2 нусхада тузилган бўлиб, иккаласи ҳам бир хил юридик кучга эга.",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph(
                    "5.4. Мазкур шартномада назарда тутилмаган масалалар амалдаги қонун ҳужжатларига мувофиқ тартибга солинади.",
                    bodyPaint, spaceAfter = 12f
                ))

                // Раздел 6 — Реквизиты
                add(Paragraph("6. Тарафларнинг реквизитлари ва имзолари:", sectionPaint, spaceAfter = 6f))
                add(Paragraph("«Ижарага берувчи»:", bodyPaint.applyBold(), spaceAfter = 4f))
                add(Paragraph("Номи: $LANDLORD_NAME", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Манзил: $LANDLORD_ADDRESS", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Банк: $LANDLORD_BANK", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Р/С: $LANDLORD_ACCOUNT", bodyPaint, spaceAfter = 2f))
                add(Paragraph("МФО: $LANDLORD_MFO", bodyPaint, spaceAfter = 2f))
                add(Paragraph("ИНН: $LANDLORD_INN", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Телефон: $LANDLORD_PHONE", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Рахбар: $LANDLORD_DIRECTOR", bodyPaint, spaceAfter = 8f))

                add(Paragraph("«Ижарага Олувчи»:", bodyPaint.applyBold(), spaceAfter = 4f))
                add(Paragraph("ФИШ: $tenantName", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Паспорт маълумотлари: ${fill(tenantPassport)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Манзил: ${fill(tenantAddress)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("ЖШШИР: ${fill(tenantPinfl)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Телефон: $tenantPhone", bodyPaint, spaceAfter = 8f))

                add(Paragraph(
                    "Ижарага берувчи имзоси: _______________________     Ижарага Олувчи имзоси: _______________________",
                    signaturePaint, spaceAfter = 16f
                ))

                // Топшириқ-қабул қилиш далолатномаси
                add(Paragraph(
                    "Топшириқ-қабул қилиш Далолатномаси",
                    sectionPaint, alignment = Layout.Alignment.ALIGN_CENTER, spaceAfter = 8f
                ))
                add(Paragraph(
                    "«${dateFmt.format(Date(now)).take(2)}» $contractDate. Тошкент шаҳри",
                    bodyPaint, alignment = Layout.Alignment.ALIGN_CENTER, spaceAfter = 8f
                ))
                add(Paragraph(
                    "Ушбу далолатнома шу ҳақдаки $LANDLORD_NAME (кейинги ўринларда “Ижарага берувчи”), корхона рахбари $LANDLORD_DIRECTOR ҳамда фуқаро $tenantName (кейинги ўринларда “Ижарага олувчи”) ўртасида «${dateFmt.format(Date(now))}» санасида имзоланган № $contractNumber сонли Электр скутер ижара шартномасига асосан қуйидаги Электр скутер аккумуляторлар билан биргаликда Ижарага олувчига топширилди:",
                    bodyPaint, spaceAfter = 8f
                ))
                add(Paragraph("Модель: $scooterName", bodyPaint, spaceAfter = 2f))
                add(Paragraph("VIN №: ${fill(scooterVin)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Двигатель рақами: ${fill(scooterEngine)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("ID рақами: ${fill(scooterSerial)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Аккумулятор ID рақамлари: ID: ${shortFill(battId1)}  ID: ${shortFill(battId2)}", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Қўшимча маълумот: ${fill(extraInfo)}", bodyPaint, spaceAfter = 8f))
                add(Paragraph(
                    "Ижарага берувчи юқорида кўрсатилган мототранспорт воситасини кўздан кечирганда қуйидаги ҳолатлар аниқланди:",
                    bodyPaint, spaceAfter = 4f
                ))
                add(Paragraph("Рама ва корпус: соз ҳолатда", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Мотор: соз ҳолатда", bodyPaint, spaceAfter = 2f))
                add(Paragraph("Бошқа қисмлар: соз ҳолатда", bodyPaint, spaceAfter = 8f))
                add(Paragraph(
                    "Топширди: _______________________     Қабул қилди: _______________________",
                    signaturePaint, spaceAfter = 12f
                ))
                add(Paragraph(
                    "$LANDLORD_NAME          $tenantName",
                    signaturePaint, alignment = Layout.Alignment.ALIGN_CENTER
                ))
            }

            // ── Рендер с пагинацией ──────────────────────────────────────────
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
            doc.finishPage(page)

            // ── Сохранение ────────────────────────────────────────────────
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ScooterContracts"
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "rental_contract_unlimited_${contractNumber}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf")
            FileOutputStream(file).use { fos -> doc.writeTo(fos) }

            Log.i(TAG, "Unlimited PDF saved: ${file.absolutePath}")
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate unlimited PDF", e)
            return null
        } finally {
            doc.close()
        }
    }

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
}
