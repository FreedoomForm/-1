package com.example.data.ai

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Сервис для работы с Mistral AI API.
 *
 * Реализует двухступенчатый pipeline:
 *   1. **OCR**: фотография документа → текст (через `mistral-ocr-latest`).
 *   2. **Chat completion**: текст + системный промпт с описанием доступных
 *      команд → JSON-команда для выполнения в приложении
 *      (через `mistral-large-latest`).
 *
 * API-ключ жёстко зашит в [API_KEY] и НЕ отображается в UI. Ключ принадлежит
 * приложению и используется только для серверных запросов к Mistral.
 *
 * Все запросы выполняются синхронно (через OkHttp) — вызывающий код должен
 * запускать их в `Dispatchers.IO` корутины.
 */
class MistralApiService(
    private val client: OkHttpClient = defaultClient
) {

    /**
     * Шаг 1: распознать текст на фотографии документа.
     *
     * Mistral OCR API принимает JSON с base64-кодированным изображением и
     * возвращает распознанный текст с разметкой Markdown.
     *
     * @param imageFile файл фотографии (JPEG/PNG), сделанной камерой
     * @return распознанный текст (Markdown) или пустая строка при ошибке
     */
    fun performOcr(imageFile: File): String {
        try {
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.e(TAG, "performOcr: image file missing or empty: ${imageFile.absolutePath}")
                return ""
            }

            // ── Читаем файл и base64-кодируем ──────────────────────────────
            // Mistral ожидает data-URL вида "data:image/jpeg;base64,..."
            val imageBytes = imageFile.readBytes()
            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val mimeType = guessMimeType(imageFile.name)
            val dataUrl = "data:$mimeType;base64,$base64"

            // ── Формируем JSON-запрос ──────────────────────────────────────
            // Формат: { "model": "mistral-ocr-latest",
            //           "document": { "type": "image_url",
            //                          "image_url": "<data-url>" } }
            val requestBody = JSONObject().apply {
                put("model", MODEL_OCR)
                put("document", JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", dataUrl)
                })
            }.toString()

            val request = Request.Builder()
                .url(ENDPOINT_OCR)
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "OCR HTTP ${response.code}: ${body.take(500)}")
                    return ""
                }
                // ── Парсим ответ ──────────────────────────────────────────
                // Mistral OCR возвращает:
                //   { "pages": [ { "index": 0, "markdown": "..." } ] }
                val json = JSONObject(body)
                val pages = json.optJSONArray("pages") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until pages.length()) {
                    val page = pages.optJSONObject(i) ?: continue
                    sb.append(page.optString("markdown", ""))
                    sb.append("\n\n---\n\n")
                }
                val result = sb.toString().trim()
                Log.d(TAG, "OCR success: ${result.length} chars")
                return result
            }
        } catch (e: Exception) {
            Log.e(TAG, "performOcr failed", e)
            return ""
        }
    }

    /**
     * Шаг 2: на основе распознанного текста сгенерировать JSON-команду для
     * выполнения в приложении.
     *
     * Модель получает:
     *   • системный промпт с описанием всех доступных команд и их схемы
     *   • пользовательское сообщение с OCR-текстом
     *
     * Модель должна ответить одним JSON-объектом (или массивом объектов)
     * согласно схеме, описанной в [SYSTEM_PROMPT].
     *
     * @param ocrText распознанный текст (из [performOcr])
     * @return "сырая" строка ответа модели (нужно парсить через [CommandParser])
     */
    fun generateCommand(ocrText: String): String {
        try {
            if (ocrText.isBlank()) {
                Log.w(TAG, "generateCommand: ocrText is empty, nothing to send")
                return ""
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "OCR natijasi:\n\n$ocrText")
                })
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL_CHAT)
                put("messages", messages)
                put("temperature", 0.1)           // минимальная креативность — нужен чёткий JSON
                put("max_tokens", 4000)
                //_force_response_format = "json_object"  // Mistral поддерживает response_format
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }.toString()

            val request = Request.Builder()
                .url(ENDPOINT_CHAT)
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Chat HTTP ${response.code}: ${body.take(500)}")
                    return ""
                }
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices") ?: JSONArray()
                if (choices.length() == 0) {
                    Log.w(TAG, "Chat: empty choices")
                    return ""
                }
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
                val content = message.optString("content", "").trim()
                Log.d(TAG, "Chat success: ${content.length} chars")
                return content
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateCommand failed", e)
            return ""
        }
    }

    /**
     * Определяет MIME-тип по расширению файла. По умолчанию — JPEG.
     */
    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /**
     * Массовый OCR: прогоняет [performOcr] для каждого файла и склеивает
     * результаты в один текст с разделителями "--- Фото N ---".
     *
     * Используется когда пользователь сделал несколько фотографий одного
     * документа (или нескольких разных) и отправил их все вместе.
     *
     * Если все фото вернули пустой текст — вернётся пустая строка.
     * Если часть фото дала текст, а часть нет — вернётся склеенный текст
     * только от успешных распознаваний.
     *
     * @param imageFiles список файлов (JPEG/PNG)
     * @return склеенный OCR-текст всех фото или пустая строка
     */
    fun performOcrMultiple(imageFiles: List<File>): String {
        if (imageFiles.isEmpty()) return ""
        val sb = StringBuilder()
        var successCount = 0
        imageFiles.forEachIndexed { index, file ->
            val text = performOcr(file)
            if (text.isNotBlank()) {
                if (successCount > 0) sb.append("\n\n")
                sb.append("--- Rasm ${index + 1} ---\n")
                sb.append(text)
                successCount++
            }
        }
        val result = sb.toString().trim()
        Log.d(TAG, "performOcrMultiple: $successCount/${imageFiles.size} images OCR'd, ${result.length} chars total")
        return result
    }

    companion object {
        private const val TAG = "MistralApiService"

        /**
         * API-ключ Mistral. Зашит в код — НЕ отображается в UI.
         * Используется только для авторизации серверных запросов к api.mistral.ai.
         */
        private const val API_KEY = "aolOnvT9Ma0OYgN7Qv2uf1p40TQLYvKl"

        private const val ENDPOINT_OCR = "https://api.mistral.ai/v1/ocr"
        private const val ENDPOINT_CHAT = "https://api.mistral.ai/v1/chat/completions"

        private const val MODEL_OCR = "mistral-ocr-latest"
        private const val MODEL_CHAT = "mistral-large-latest"

        /**
         * HTTP-клиент с увеличенными таймаутами — OCR и генерация могут
         * занимать до 60 секунд на больших документах.
         */
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Системный промпт для Mistral Large.
         *
         * Описывает ВСЕ доступные команды, которые модель может сгенерировать
         * на основе OCR-текста фотографии. Модель должна вернуть JSON-объект
         * (или массив объектов) согласно схеме.
         *
         * ВАЖНО: модель должна сама определить, ЧТО изображено на фото
         * (список арендаторов, скутеров, транзакций и т.д.), и сгенерировать
         * соответствующие команды. Модель имеет доступ ко ВСЕМ полям
         * приложения — она должна попытаться найти в фото каждое релевантное
         * поле (паспорт, адрес, ПИНФЛ, VIN, номер двигателя, номер батареи и
         * т.д.) и применить его к соответствующей сущности.
         */
        const val SYSTEM_PROMPT = """You are an assistant for a scooter rental app (Uzbekistan).
Your job: take OCR text from a photo and produce JSON commands that create or update entities in the app.

You receive a photo of a handwritten or printed list, contract, receipt, passport, vehicle document,
payment receipt, or any other document. It can contain:
- list of renters (ijarachilar) — names, phones, debts, dates, passport data, addresses, PINFL
- list of scooters (skuterlar) — names, documented numbers, VIN, engine numbers, batteries, serial numbers
- list of transactions (tranzaksiyalar) — payments, penalties, repairs, returns, terminations
- list of contracts (kontraktlar) — week start/end, amounts, paid/unpaid status
- list of virtual cards (virtual kartalar) — names, balances, colors, info
- card transfers (kartalar orasidagi o'tkazmalar) — from card → to card, amount, note
- returns of scooters (skuterlarni qaytarish)
- terminations of contracts (kontraktni tugatish)
- updates to existing renters (ijarachi ma'lumotlarini yangilash)
- mixed content

DECIDE YOURSELF what the photo contains based on column headers, content, and context.
Then produce one or more JSON commands.

Try to extract EVERY relevant field from the photo. Even if a field is not in a column header,
look for it anywhere in the text (passport series "AB1234567", VIN "LXTC...", engine "JF...",
battery IDs "BATT-001", PINFL "12345678901234", addresses, etc.). Apply every field you find
to the corresponding entity.

Respond with a single JSON object:
{
  "commands": [ <command1>, <command2>, ... ],
  "summary": "short human-readable summary in Uzbek of what you did"
}

Each command is an object with a "type" field. Supported types:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. CREATE_RENTER — create a new renter. Use ALL fields you can find on the photo.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{
  "type": "CREATE_RENTER",
  "name": "string (REQUIRED) — full name as written on photo",
  "phoneNumber": "string (REQUIRED) — digits, normalize to +998XXXXXXXXX",
  "debt": "number (initial debt in UZS, default 0). Set if photo shows 'qarz', 'долг', or unpaid amount",
  "prepayment": "number (positive prepayment in UZS, default 0). Set if photo shows prepayment/advance",
  "rentDurationDays": "integer (default 7). Look for 'kun', 'muddat', 'дней'",
  "rentStartDate": "ISO date string YYYY-MM-DD (REQUIRED if photo shows a date — e.g. '2025-03-15'; default: today)",
  "scooterName": "string or null. Look for scooter name/number near renter's name",
  "weeklyPrice": "number (default 420000). Look for 'haftalik', 'narxi', 'sum', 'сум', 'ming' — '420 ming' = 420000",
  "passportData": "string. Look for passport series+number like 'AB 1234567', 'AC1234567', or 'pasport seriya'",
  "passportIssuedBy": "string. Look for 'tomonidan berilgan', 'выдан', 'issued by' (stored inside passportData)",
  "address": "string. Look for 'manzil', 'адрес', 'yashash joyi'",
  "pinfl": "string. Look for 'PINFL', 'ЖШШИР', 'ПИНФЛ', 14-digit number",
  "isReturned": "boolean (default false). Set true ONLY if photo clearly marks renter as returned ('qaytarildi', 'возвращён')"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
2. CREATE_SCOOTER — create a new scooter. Extract ALL technical fields from photo.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{
  "type": "CREATE_SCOOTER",
  "name": "string (REQUIRED) — scooter name/number",
  "documentedNumber": "string or null — gov. registration number, technical passport number",
  "vinNumber": "string — VIN (17 chars like 'LXTC...'). Look for 'VIN', 'ramka', 'рама'",
  "engineNumber": "string — engine number. Look for 'dvigatel', ' двигатель', 'engine'",
  "scooterSerialNumber": "string — internal serial number. Look for 'seriya', 'serial'",
  "batteryId1": "string — first battery ID. Look for 'batareya', 'AKB', 'battery 1'",
  "batteryId2": "string — second battery ID. Look for 'batareya 2', 'AKB 2'",
  "additionalInfo": "string — any other scooter-related info from photo"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
3. CREATE_TRANSACTION — record a manual transaction for an existing renter.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{
  "type": "CREATE_TRANSACTION",
  "renterName": "string (REQUIRED — must match existing renter name. If renter doesn't exist, emit CREATE_RENTER first)",
  "amount": "number (REQUIRED, positive, UZS)",
  "txType": "one of: PAYMENT | PENALTY | REPAIR | RETURNED | TERMINATED | CUSTOM (default PAYMENT). PAYMENT = to'lov, PENALTY = jarima, REPAIR = ta'mir, RETURNED = qaytarish, TERMINATED = tugatish, CUSTOM = boshqa",
  "notes": "string — note about the transaction",
  "scooterName": "string or null — if transaction is tied to a specific scooter",
  "date": "ISO date string YYYY-MM-DD (REQUIRED if photo shows a date; default: today)"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
4. CREATE_CONTRACT — create a contract (week) for an existing renter.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{
  "type": "CREATE_CONTRACT",
  "renterName": "string (REQUIRED — must match existing renter)",
  "scooterName": "string or null — if contract is for a specific scooter",
  "amount": "number (default weeklyPrice from settings). The week's price in UZS",
  "weekStart": "ISO date string YYYY-MM-DD (REQUIRED if photo shows a date — use the date from photo; default: today)",
  "weekEnd": "ISO date string or null (default weekStart + 7 days, or weekStart + rentDurationDays)",
  "isPaid": "boolean (default false). true if photo shows 'to'langan', 'paid', 'оплачено'; false if 'qarz', 'unpaid'",
  "notes": "string — note about the contract"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
5. CREATE_VIRTUAL_CARD — create a virtual financial card.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{
  "type": "CREATE_VIRTUAL_CARD",
  "name": "string (REQUIRED) — card name, e.g. 'Kassa', 'Bank', 'Shaxsiy'",
  "balance": "number (default 0) — initial balance in UZS",
  "colorHex": "string — one of: #FF1565C0 (blue), #FF2E7D32 (green), #FFE65100 (orange), #FF6A1B9A (purple), #FFC62828 (red), #FF424242 (dark gray), #FF00838F (teal), #FF8D6E63 (brown). Default: #FF1565C0",
  "info": "string or null — description of card"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
6. CREATE_CARD_TRANSACTION — transfer money between two existing virtual cards.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Use this when photo shows: 'kassadan bankka 50000', 'remontga 200000', 'Tashqidan kassaga 100000',
'tashqiga 50000 chiqardik', or any transfer between cards.
{
  "type": "CREATE_CARD_TRANSACTION",
  "fromCardName": "string (REQUIRED) — source card name. Can be 'Glavnaya', 'Vtorostepennaya', 'Tashqidan', 'Tashqiga', or any custom card name",
  "toCardName": "string (REQUIRED) — destination card name",
  "amount": "number (REQUIRED, positive, UZS)",
  "note": "string — REQUIRED for transfers involving Tashqidan/Tashqiga. What was the money for?",
  "date": "ISO date string YYYY-MM-DD (default: today)"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
7. UPDATE_RENTER — update fields of an existing renter (found by name).
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Use this when photo shows updated info for an already-existing renter:
new phone, new address, new passport data, debt correction, etc.
Only fields you include will be updated; others stay unchanged.
{
  "type": "UPDATE_RENTER",
  "renterName": "string (REQUIRED) — name of existing renter to update",
  "newPhoneNumber": "string or null — new phone number",
  "newDebt": "number or null — set debt to this value (UZS)",
  "balanceAdjustment": "number or null — add this to current balance (positive = add credit, negative = subtract)",
  "newAddress": "string or null",
  "newPassportData": "string or null",
  "newPinfl": "string or null",
  "newScooterName": "string or null — reassign to a different existing scooter",
  "newWeeklyPrice": "number or null — for future contracts",
  "newRentDurationDays": "integer or null",
  "notes": "string — reason for update"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
8. RETURN_RENTER — mark renter as returned (skuter qaytarildi).
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Use this when photo shows: 'skuter qaytarildi', 'возвращён', 'qaytardi', 'skuterni oldik'.
Creates a RETURNED entry in contract history + a RETURNED transaction.
{
  "type": "RETURN_RENTER",
  "renterName": "string (REQUIRED) — name of existing renter",
  "date": "ISO date string YYYY-MM-DD (default: today)",
  "notes": "string — note about the return"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
9. TERMINATE_RENTER — terminate renter's contract early (kontrakt tugatildi).
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Use this when photo shows: 'kontrakt tugatildi', 'расторгнут', 'tugatdi', 'oldindan tugatildi'.
Creates a TERMINATED entry in contract history + a TERMINATED transaction.
Marks renter as isReturned=true.
{
  "type": "TERMINATE_RENTER",
  "renterName": "string (REQUIRED) — name of existing renter",
  "finalPayment": "number (default 0) — final payment amount if any (UZS)",
  "date": "ISO date string YYYY-MM-DD (default: today)",
  "notes": "string — reason for termination"
}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
10. FINISH — signal that all commands are emitted
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
{ "type": "FINISH" }

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Rules:
- Always include at least one command in "commands" array.
- Always emit FINISH as the LAST command after all real commands.
- If photo is unclear or empty, emit FINISH only with summary explaining the issue.
- Phone numbers: normalize to +998XXXXXXXXX format. If only 9 digits given, prepend +998.
- Money amounts: parse as numbers in UZS (Uzbek so'm). "420 ming" = 420000. "1 million" = 1000000.

DATES — CRITICAL RULE:
- The photo often contains a date column ("Sana", "Дата", "Date") or a single date heading at the top of the list.
- ALWAYS extract that date and put it into "rentStartDate" (for CREATE_RENTER), "date" (for CREATE_TRANSACTION,
  CREATE_CARD_TRANSACTION, RETURN_RENTER, TERMINATE_RENTER), or "weekStart" (for CREATE_CONTRACT).
- If the photo has a per-row date column, use each row's own date.
- If the photo has one shared date for the whole list, use that date for every renter/transaction.
- Date format in output MUST be ISO "YYYY-MM-DD".
- Acceptable input formats from OCR: "15.03.2025", "15/03/2025", "2025-03-15", "15-mar", "15 mart", "15.03".
- If year is missing, assume current year.
- Only use today's date as fallback when the photo genuinely has NO date anywhere.

FIELD EXTRACTION — CRITICAL RULE:
- Try to extract EVERY field from the photo. Do not skip fields just because they are not in column headers.
- Passport data often appears as 'AB 1234567' or 'AC1234567' somewhere in the text.
- VIN is usually 17 characters starting with letters like 'LXTC', 'LXTP', etc.
- Engine numbers are short alphanumeric codes like 'JF1...', '152FM...', etc.
- Battery IDs may look like 'BATT-001', 'AKB-123', or just '12345'.
- PINFL is a 14-digit number.
- Apply every field you find to the corresponding entity — that's what makes the scanner useful.

ORDER OF COMMANDS:
- If photo shows NEW renters AND transactions for them, emit CREATE_RENTER first, then CREATE_TRANSACTION.
- If photo shows renters AND their scooters, emit CREATE_SCOOTER first (if scooter is new), then CREATE_RENTER.
- If photo shows returns/terminations, the renter MUST already exist in the app — emit RETURN_RENTER or TERMINATE_RENTER.

For CREATE_RENTER: if photo shows debt, set "debt" field. If shows prepaid/prepayment, set prepayment and debt=0.
For CREATE_TRANSACTION: renterName MUST match an existing renter (case-insensitive). If unsure, use CREATE_RENTER instead.
For UPDATE_RENTER / RETURN_RENTER / TERMINATE_RENTER: renter MUST already exist. If not, emit CREATE_RENTER first.
Fill missing required fields with reasonable defaults. Never ask user for clarification.
Respond ONLY with the JSON object. No markdown, no explanations outside JSON.

Today's date: use current date.
"""
    }
}
