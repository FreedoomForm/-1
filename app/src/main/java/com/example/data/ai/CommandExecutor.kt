package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.SettingsRepository
import com.example.data.Transaction
import com.example.data.VirtualCard
import com.example.data.VirtualCardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Результат выполнения одной команды.
 *
 * [success] — выполнена ли команда успешно.
 * [message] — человекочитаемое сообщение (для показа пользователю).
 */
data class CommandResult(
    val success: Boolean,
    val message: String
)

/**
 * Парсер и исполнитель JSON-команд, сгенерированных Mistral Large.
 *
 * Принимает "сырой" ответ модели (строку), извлекает из него JSON-объект
 * (модель может оборачивать JSON в markdown ```json ... ``` блоки или
 * добавлять пояснения — мы ищем первый "{ ... }" блок), парсит массив
 * "commands" и выполняет каждую команду по очереди.
 *
 * Каждая команда либо создаёт сущность в БД (CREATE_RENTER, CREATE_SCOOTER,
 * CREATE_TRANSACTION, CREATE_CONTRACT, CREATE_VIRTUAL_CARD), либо
 * сигнализирует о завершении (FINISH).
 *
 * Все операции с БД выполняются на Dispatchers.IO.
 */
class CommandExecutor(private val context: Context) {

    private val db: AppDatabase = AppDatabase.getDatabase(context)
    private val settings: SettingsRepository = SettingsRepository(context)

    /**
     * Главный точка входа: принимает "сырой" ответ Mistral, выполняет
     * все команды, возвращает итоговый результат с агрегированным сообщением.
     *
     * @param mistralResponse "сырая" строка ответа модели (JSON-объект или
     *        markdown с встроенным JSON)
     * @return пара (общий успех, список результатов по каждой команде)
     */
    suspend fun execute(mistralResponse: String): Pair<Boolean, List<CommandResult>> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<CommandResult>()

            val json = parseResponseJson(mistralResponse)
            if (json == null) {
                results.add(CommandResult(false, "Mistral javobini parse qilib bo'lmadi"))
                return@withContext Pair(false, results)
            }

            val summary = json.optString("summary", "").trim()
            val commandsArray = json.optJSONArray("commands") ?: JSONArray()

            if (commandsArray.length() == 0) {
                results.add(CommandResult(false, "Hech qanday komanda topilmadi"))
                return@withContext Pair(false, results)
            }

            var allSuccess = true
            for (i in 0 until commandsArray.length()) {
                val cmd = commandsArray.optJSONObject(i) ?: continue
                val type = cmd.optString("type", "").uppercase()
                val result = when (type) {
                    "CREATE_RENTER" -> createRenter(cmd)
                    "CREATE_SCOOTER" -> createScooter(cmd)
                    "CREATE_TRANSACTION" -> createTransaction(cmd)
                    "CREATE_CONTRACT" -> createContract(cmd)
                    "CREATE_VIRTUAL_CARD" -> createVirtualCard(cmd)
                    "FINISH" -> CommandResult(true, "✓ Bajarildi")
                    else -> CommandResult(false, "Noma'lum komanda: $type")
                }
                results.add(result)
                if (!result.success) allSuccess = false
                if (type == "FINISH") break
            }

            if (summary.isNotEmpty()) {
                results.add(CommandResult(true, summary))
            }

            Pair(allSuccess, results)
        }

    /**
     * Извлекает JSON-объект из ответа модели.
     *
     * Модель может вернуть:
     *   • чистый JSON: { "commands": [...] }
     *   • JSON в markdown-блоке: ```json\n{ ... }\n```
     *   • JSON с пояснениями вокруг: "Here is the JSON:\n{ ... }\nThat's it."
     *
     * Мы ищем первый "{" и последний "}" — между ними должен быть валидный JSON.
     */
    private fun parseResponseJson(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Прямой парсинг (если ответ — чистый JSON)
        try {
            return JSONObject(trimmed)
        } catch (_: Exception) { /* fall through */ }

        // Поиск markdown-блока ```json ... ```
        val mdPattern = Regex("""```(?:json)?\s*(\{[\s\S]*\})\s*```""", RegexOption.IGNORE_CASE)
        mdPattern.find(trimmed)?.let { match ->
            try {
                return JSONObject(match.groupValues[1])
            } catch (_: Exception) { /* fall through */ }
        }

        // Поиск первого "{" и последнего "}"
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = trimmed.substring(firstBrace, lastBrace + 1)
            try {
                return JSONObject(candidate)
            } catch (_: Exception) { /* fall through */ }
        }

        return null
    }

    // ── Команда: CREATE_RENTER ──────────────────────────────────────────────
    private suspend fun createRenter(cmd: JSONObject): CommandResult {
        try {
            val name = cmd.optString("name", "").trim()
            if (name.isEmpty()) {
                return CommandResult(false, "CREATE_RENTER: 'name' majburiy")
            }
            val phone = normalizePhone(cmd.optString("phoneNumber", ""))
            if (phone.isEmpty()) {
                return CommandResult(false, "CREATE_RENTER: 'phoneNumber' majburiy")
            }
            val debt = cmd.optDouble("debt", 0.0)
            val duration = cmd.optInt("rentDurationDays", 7)
            val scooterName = cmd.optString("scooterName", "").trim().ifEmpty { null }
            val weeklyPrice = cmd.optDouble("weeklyPrice", settings.weeklyPrice.takeIf { it > 0 }
                ?: SettingsRepository.DEFAULT_WEEKLY_PRICE)
            val passportData = cmd.optString("passportData", "").trim()
            val address = cmd.optString("address", "").trim()
            val pinfl = cmd.optString("pinfl", "").trim()

            // Находим скутер по имени (если указан)
            val scooter = scooterName?.let { findScooterByName(it) }
            val scooterId = scooter?.id
            val scooterNameResolved = scooter?.name ?: scooterName

            val now = System.currentTimeMillis()
            val renter = Renter(
                name = name,
                phoneNumber = phone,
                debtAmount = if (debt > 0) debt else 0.0,
                rentDurationDays = duration,
                rentStartDateTimestamp = now,
                isReturned = false,
                isOverdueSmsSent = false,
                scooterId = scooterId,
                scooterName = scooterNameResolved,
                balance = if (debt > 0) -debt else 0.0,
                passportData = passportData,
                address = address,
                pinfl = pinfl
            )
            val newId = db.renterDao().insertRenter(renter).toInt()

            // Создаём начальный контракт (CREATED) — как при ручном создании.
            // Логика упрощена: всегда создаётся один CREATED контракт с
            // weekStart=now, weekEnd=now+7days, isPaid = (debt == 0).
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs
            val contract = ContractHistoryEntry(
                renterId = newId,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_CREATED,
                amount = weeklyPrice,
                notes = if (debt > 0) "Skaner orqali yaratildi (qarz bilan)" else "Skaner orqali yaratildi (oldindan to'langan)",
                renterName = name,
                renterPhone = phone,
                scooterName = scooterNameResolved,
                weekStart = now,
                weekEnd = now + weekMs,
                weeklyPrice = weeklyPrice,
                passportData = passportData,
                address = address,
                pinfl = pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = debt <= 0
            )
            db.contractHistoryDao().insert(contract)

            // Для предоплаченного контракта — создаём Transaction и
            // зачисляем на главную карту (как при ручном создании).
            if (debt <= 0) {
                val tx = Transaction(
                    contractId = null,  // не знаем ID только что созданного контракта
                    renterId = newId,
                    scooterId = scooterId,
                    timestamp = now,
                    type = Transaction.TYPE_PAYMENT,
                    amount = weeklyPrice,
                    notes = "Skaner orqali oldindan to'lov",
                    renterName = name,
                    renterPhone = phone,
                    scooterName = scooterNameResolved ?: "",
                    contractLabel = ""
                )
                db.transactionDao().insert(tx)

                try {
                    val cardRepo = VirtualCardRepository(
                        db.virtualCardDao(), db.cardTransactionDao()
                    )
                    cardRepo.depositContractIncome(
                        amount = weeklyPrice,
                        note = "Skaner: ${name} (oldindan to'langan)",
                        contractId = null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "depositContractIncome failed for scanned renter: ${e.message}")
                }
            }

            return CommandResult(true,
                "✓ Ijarachi yaratildi: $name ($phone)" +
                (if (debt > 0) ", qarz: ${formatMoney(debt)}" else ""))
        } catch (e: Exception) {
            Log.e(TAG, "createRenter failed", e)
            return CommandResult(false, "CREATE_RENTER xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_SCOOTER ─────────────────────────────────────────────
    private suspend fun createScooter(cmd: JSONObject): CommandResult {
        try {
            val name = cmd.optString("name", "").trim()
            if (name.isEmpty()) {
                return CommandResult(false, "CREATE_SCOOTER: 'name' majburiy")
            }
            val documentedNumber = cmd.optString("documentedNumber", "").trim().ifEmpty { null }
            val scooter = Scooter(
                name = name,
                documentedNumber = documentedNumber,
                vinNumber = cmd.optString("vinNumber", "").trim(),
                engineNumber = cmd.optString("engineNumber", "").trim(),
                scooterSerialNumber = cmd.optString("scooterSerialNumber", "").trim(),
                batteryId1 = cmd.optString("batteryId1", "").trim(),
                batteryId2 = cmd.optString("batteryId2", "").trim(),
                additionalInfo = cmd.optString("additionalInfo", "").trim()
            )
            db.scooterDao().insertScooter(scooter)
            return CommandResult(true, "✓ Skuter yaratildi: $name")
        } catch (e: Exception) {
            Log.e(TAG, "createScooter failed", e)
            return CommandResult(false, "CREATE_SCOOTER xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_TRANSACTION ─────────────────────────────────────────
    private suspend fun createTransaction(cmd: JSONObject): CommandResult {
        try {
            val renterName = cmd.optString("renterName", "").trim()
            if (renterName.isEmpty()) {
                return CommandResult(false, "CREATE_TRANSACTION: 'renterName' majburiy")
            }
            val renter = findRenterByName(renterName)
            if (renter == null) {
                return CommandResult(false,
                    "CREATE_TRANSACTION: '$renterName' ijarachi topilmadi. " +
                    "Avval ijarachini skaner orqali yarating.")
            }
            val amount = cmd.optDouble("amount", 0.0)
            if (amount <= 0) {
                return CommandResult(false, "CREATE_TRANSACTION: 'amount' > 0 bo'lishi kerak")
            }
            val txType = cmd.optString("txType", "PAYMENT").uppercase()
                .let { if (it.isBlank()) "PAYMENT" else it }
            val notes = cmd.optString("notes", "").trim()
            val scooterName = cmd.optString("scooterName", "").trim().ifEmpty { null }

            val now = System.currentTimeMillis()
            val tx = Transaction(
                contractId = null,
                renterId = renter.id,
                scooterId = renter.scooterId,
                timestamp = now,
                type = txType,
                amount = amount,
                notes = notes.ifEmpty { "Skaner orqali: $txType" },
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = scooterName ?: renter.scooterName ?: "",
                contractLabel = ""
            )
            db.transactionDao().insert(tx)

            // Если это PAYMENT — увеличиваем баланс арендатора и зачисляем
            // на главную карту (как при applyWeeklyPayment).
            if (txType == Transaction.TYPE_PAYMENT) {
                val newBalance = renter.balance + amount
                db.renterDao().updateRenter(renter.copy(
                    balance = newBalance,
                    debtAmount = maxOf(0.0, -newBalance),
                    lastPaymentTimestamp = now,
                    isOverdueSmsSent = false
                ))

                try {
                    val cardRepo = VirtualCardRepository(
                        db.virtualCardDao(), db.cardTransactionDao()
                    )
                    cardRepo.depositContractIncome(
                        amount = amount,
                        note = "Skaner: ${renter.name} to'lovi",
                        contractId = null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "depositContractIncome for tx failed: ${e.message}")
                }
            }

            return CommandResult(true,
                "✓ Tranzaksiya: ${renter.name} — ${formatMoney(amount)} ($txType)")
        } catch (e: Exception) {
            Log.e(TAG, "createTransaction failed", e)
            return CommandResult(false, "CREATE_TRANSACTION xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_CONTRACT ────────────────────────────────────────────
    private suspend fun createContract(cmd: JSONObject): CommandResult {
        try {
            val renterName = cmd.optString("renterName", "").trim()
            if (renterName.isEmpty()) {
                return CommandResult(false, "CREATE_CONTRACT: 'renterName' majburiy")
            }
            val renter = findRenterByName(renterName)
            if (renter == null) {
                return CommandResult(false,
                    "CREATE_CONTRACT: '$renterName' ijarachi topilmadi")
            }
            val amount = cmd.optDouble("amount",
                settings.weeklyPrice.takeIf { it > 0 }
                    ?: SettingsRepository.DEFAULT_WEEKLY_PRICE)
            val weekStart = parseDate(cmd.optString("weekStart", "")) ?: System.currentTimeMillis()
            val weekEnd = parseDate(cmd.optString("weekEnd", ""))
                ?: (weekStart + 7L * 24 * 60 * 60 * 1000)
            val isPaid = cmd.optBoolean("isPaid", false)
            val notes = cmd.optString("notes", "").trim().ifEmpty { "Skaner orqali yaratildi" }
            val scooterName = cmd.optString("scooterName", "").trim().ifEmpty { null }
            val scooter = scooterName?.let { findScooterByName(it) } ?: renter.scooterId?.let { findScooterById(it) }

            val now = System.currentTimeMillis()
            val contract = ContractHistoryEntry(
                renterId = renter.id,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW,  // дополнительный контракт
                amount = amount,
                notes = notes,
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = scooter?.name ?: renter.scooterName,
                weekStart = weekStart,
                weekEnd = weekEnd,
                weeklyPrice = amount,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = isPaid
            )
            db.contractHistoryDao().insert(contract)

            // Корректируем баланс арендатора
            val delta = if (isPaid) 0.0 else -amount
            if (delta != 0.0) {
                val newBalance = renter.balance + delta
                db.renterDao().updateRenter(renter.copy(
                    balance = newBalance,
                    debtAmount = maxOf(0.0, -newBalance)
                ))
            }

            return CommandResult(true,
                "✓ Kontrakt: ${renter.name} — ${formatMoney(amount)}" +
                if (isPaid) " (to'langan)" else " (qarz)")
        } catch (e: Exception) {
            Log.e(TAG, "createContract failed", e)
            return CommandResult(false, "CREATE_CONTRACT xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_VIRTUAL_CARD ────────────────────────────────────────
    private suspend fun createVirtualCard(cmd: JSONObject): CommandResult {
        try {
            val name = cmd.optString("name", "").trim()
            if (name.isEmpty()) {
                return CommandResult(false, "CREATE_VIRTUAL_CARD: 'name' majburiy")
            }
            val balance = cmd.optDouble("balance", 0.0)
            val colorHex = cmd.optString("colorHex", "#FF1565C0").trim().ifEmpty { "#FF1565C0" }
            val info = cmd.optString("info", "").trim().ifEmpty { null }

            val card = VirtualCard(
                name = name,
                balance = balance,
                colorHex = colorHex,
                info = info,
                isDefault = false,
                kind = VirtualCard.KIND_REGULAR
            )
            db.virtualCardDao().insertCard(card)
            return CommandResult(true, "✓ Karta yaratildi: $name (${formatMoney(balance)})")
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualCard failed", e)
            return CommandResult(false, "CREATE_VIRTUAL_CARD xato: ${e.message}")
        }
    }

    // ── Вспомогательные методы ──────────────────────────────────────────────

    private suspend fun findRenterByName(name: String): Renter? {
        val all = db.renterDao().getAllRentersOnce()
        return all.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            ?: all.firstOrNull { it.name.trim().startsWith(name, ignoreCase = true) }
            ?: all.firstOrNull { name.trim().startsWith(it.name, ignoreCase = true) }
    }

    private suspend fun findScooterByName(name: String): Scooter? {
        val all = db.scooterDao().getAllScootersOnce()
        return all.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            ?: all.firstOrNull { it.name.trim().startsWith(name, ignoreCase = true) }
    }

    private suspend fun findScooterById(id: Int): Scooter? = db.scooterDao().getScooterById(id)

    /**
     * Нормализует номер телефона в формат +998XXXXXXXXX.
     * Принимает: "+998 90 123 45 67", "998901234567", "901234567", "+998-90-123-45-67" и т.д.
     */
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.isEmpty() -> ""
            digits.startsWith("998") && digits.length == 12 -> "+$digits"
            digits.length == 9 -> "+998$digits"
            digits.length == 11 && digits.startsWith("8") -> "+998${digits.drop(1)}"
            else -> "+$digits"
        }
    }

    /**
     * Парсит дату в формате ISO "YYYY-MM-DD" или "DD.MM.YYYY".
     * Возвращает timestamp в миллисекундах, или null если не удалось.
     */
    private fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        val patterns = listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy")
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                return fmt.parse(s)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /**
     * Форматирует сумму в UZS: "420 000 UZS".
     */
    private fun formatMoney(amount: Double): String {
        val formatted = String.format(Locale.US, "%,.0f", amount)
            .replace(",", " ")
        return "$formatted UZS"
    }

    companion object {
        private const val TAG = "CommandExecutor"
    }
}
