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
         * соответствующие команды. Недостающие поля она заполняет разумными
         * значениями по умолчанию.
         */
        const val SYSTEM_PROMPT = """You are an assistant for a scooter rental app (Uzbekistan).
Your job: take OCR text from a photo and produce JSON commands that create entities in the app.

You receive a photo of a handwritten or printed list. It can be:
- list of renters (ijarachilar)
- list of scooters (skuterlar)
- list of transactions (tranzaksiyalar)
- list of contracts (kontraktlar)
- list of virtual cards (virtual kartalar)
- mixed content

Decide YOURSELF what the photo contains based on column headers, content, and context. Then produce one or more JSON commands.

Respond with a single JSON object:
{
  "commands": [ <command1>, <command2>, ... ],
  "summary": "short human-readable summary in Uzbek of what you did"
}

Each command is an object with a "type" field. Supported types:

1. CREATE_RENTER — create a new renter
   {
     "type": "CREATE_RENTER",
     "name": "string (required)",
     "phoneNumber": "string (required, digits, can start with +998)",
     "debt": number (initial debt in UZS, default 0),
     "rentDurationDays": integer (default 7),
     "rentStartDate": "ISO date string YYYY-MM-DD (REQUIRED if photo shows a date — e.g. '2025-03-15'; default: today)",
     "scooterName": "string or null (if photo mentions scooter)",
     "weeklyPrice": number (default 420000),
     "passportData": "string (default empty)",
     "address": "string (default empty)",
     "pinfl": "string (default empty)"
   }

2. CREATE_SCOOTER — create a new scooter
   {
     "type": "CREATE_SCOOTER",
     "name": "string (required)",
     "documentedNumber": "string or null",
     "vinNumber": "string (default empty)",
     "engineNumber": "string (default empty)",
     "scooterSerialNumber": "string (default empty)",
     "batteryId1": "string (default empty)",
     "batteryId2": "string (default empty)",
     "additionalInfo": "string (default empty)"
   }

3. CREATE_TRANSACTION — record a manual transaction
   {
     "type": "CREATE_TRANSACTION",
     "renterName": "string (required, must match existing renter name)",
     "amount": number (required, positive),
     "txType": "PAYMENT | PENALTY | REPAIR | RETURNED | TERMINATED | CUSTOM (default PAYMENT)",
     "notes": "string (default empty)",
     "scooterName": "string or null",
     "date": "ISO date string YYYY-MM-DD (REQUIRED if photo shows a date for this transaction; default: today)"
   }

4. CREATE_CONTRACT — create a contract for an existing renter
   {
     "type": "CREATE_CONTRACT",
     "renterName": "string (required, must match existing renter)",
     "scooterName": "string or null",
     "amount": number (default weeklyPrice from settings)",
     "weekStart": "ISO date string YYYY-MM-DD (REQUIRED if photo shows a date — use the date from photo; default: today)",
     "weekEnd": "ISO date string or null (default weekStart + 7 days)",
     "isPaid": boolean (default false),
     "notes": "string (default empty)"
   }

5. CREATE_VIRTUAL_CARD — create a virtual financial card
   {
     "type": "CREATE_VIRTUAL_CARD",
     "name": "string (required)",
     "balance": number (default 0),
     "colorHex": "string (default '#FF1565C0')",
     "info": "string or null"
   }

6. FINISH — signal that all commands are emitted
   { "type": "FINISH" }

Rules:
- Always include at least one command in "commands" array.
- Always emit FINISH as the LAST command after all real commands.
- If photo is unclear or empty, emit FINISH only with summary explaining the issue.
- Phone numbers: normalize to +998XXXXXXXXX format. If only 9 digits given, prepend +998.
- Money amounts: parse as numbers in UZS (Uzbek so'm). "420 ming" = 420000.

DATES — CRITICAL RULE:
- The photo often contains a date column ("Sana", "Дата", "Date") or a single date heading at the top of the list.
- ALWAYS extract that date and put it into "rentStartDate" (for CREATE_RENTER) or "date" (for CREATE_TRANSACTION).
- If the photo has a per-row date column, use each row's own date.
- If the photo has one shared date for the whole list, use that date for every renter/transaction.
- Date format in output MUST be ISO "YYYY-MM-DD".
- Acceptable input formats from OCR: "15.03.2025", "15/03/2025", "2025-03-15", "15-mar", "15 mart", "15.03".
- If year is missing, assume current year.
- Only use today's date as fallback when the photo genuinely has NO date anywhere.

- For CREATE_RENTER: if photo shows debt, set "debt" field. If shows prepaid, set debt=0.
- For CREATE_TRANSACTION: renterName MUST match an existing renter (case-insensitive). If unsure, use CREATE_RENTER instead.
- Fill missing required fields with reasonable defaults. Never ask user for clarification.
- Respond ONLY with the JSON object. No markdown, no explanations outside JSON.

Today's date: use current date.
"""
    }
}
