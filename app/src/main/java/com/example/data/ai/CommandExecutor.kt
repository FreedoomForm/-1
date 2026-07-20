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
import com.example.data.isExternal
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
 * Контекст текущего батча команд: отслеживает сущности, созданные в этом
 * запуске [CommandExecutor.execute]. Используется для дедупликации на стороне
 * приложения — на случай, если Mistral прислал CREATE_CONTRACT или
 * CREATE_TRANSACTION, которые дублируют то, что уже создано командой
 * CREATE_RENTER в этом же батче.
 *
 * Mistral не видит сущности, созданные внутри CREATE_RENTER (они не были в
 * snapshot), поэтому промпт иногда всё равно присылает дубль. Этот контекст
 * ловит такие дубли и пропускает их.
 */
private class BatchContext {
    /**
     * Арендаторы, созданные в этом батче: ключ — нормализованное имя,
     * значение — данные об их начальном контракте (если был).
     */
    val renters: MutableMap<String, RenterCreated> = mutableMapOf()

    /**
     * Контракты, созданные в этом батче: ключ — нормализованное имя
     * арендатора, значение — список weekStart-ов (ms).
     */
    val contractsByRenter: MutableMap<String, MutableList<Long>> = mutableMapOf()

    /**
     * PAYMENT-транзакции, созданные в этом батче: ключ — нормализованное имя
     * арендатора, значение — список (amount, timestamp).
     */
    val paymentsByRenter: MutableMap<String, MutableList<Pair<Double, Long>>> = mutableMapOf()

    fun key(name: String): String = name.trim().lowercase()

    /** Запомнить, что арендатор с указанным именем был создан в этом батче
     *  вместе с начальным контрактом на неделю, начинающуюся [weekStartMs]. */
    fun recordRenter(name: String, weekStartMs: Long, paymentAmount: Double, paymentTs: Long) {
        val k = key(name)
        renters[k] = RenterCreated(weekStartMs = weekStartMs)
        contractsByRenter.getOrPut(k) { mutableListOf() }.add(weekStartMs)
        if (paymentAmount > 0.0) {
            paymentsByRenter.getOrPut(k) { mutableListOf() }.add(paymentAmount to paymentTs)
        }
    }

    /** Запомнить дополнительный контракт для [name] с началом недели [weekStartMs]. */
    fun recordContract(name: String, weekStartMs: Long) {
        val k = key(name)
        contractsByRenter.getOrPut(k) { mutableListOf() }.add(weekStartMs)
    }

    /** Запомнить дополнительнюю PAYMENT-транзакцию для [name]. */
    fun recordPayment(name: String, amount: Double, ts: Long) {
        val k = key(name)
        paymentsByRenter.getOrPut(k) { mutableListOf() }.add(amount to ts)
    }

    /** Есть ли уже контракт для [name] с началом недели, попадающим в диапазон
     *  [weekStartMs ± 1 день] (защита от мелких расхождений в датах OCR)? */
    fun hasContract(name: String, weekStartMs: Long): Boolean {
        val k = key(name)
        val list = contractsByRenter[k] ?: return false
        val dayMs = 24L * 60 * 60 * 1000
        return list.any { Math.abs(it - weekStartMs) <= dayMs }
    }

    /** Есть ли уже PAYMENT-транзакция для [name] с тем же amount (±1 UZS) и
     *  датой ±1 день? */
    fun hasPayment(name: String, amount: Double, ts: Long): Boolean {
        val k = key(name)
        val list = paymentsByRenter[k] ?: return false
        val dayMs = 24L * 60 * 60 * 1000
        return list.any { p ->
            Math.abs(p.first - amount) < 1.0 && Math.abs(p.second - ts) <= dayMs
        }
    }
}

private data class RenterCreated(val weekStartMs: Long)

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

            // ── Batch-level deduplication context ──────────────────────────
            // Mistral иногда ошибается и после CREATE_RENTER (который уже
            // создаёт контракт + транзакцию внутри себя) присылает отдельный
            // CREATE_CONTRACT или CREATE_TRANSACTION для той же недели/того
            // же платежа — это приводило к дублям. Этот контекст отслеживает
            // что уже создано в текущем батче, и createContract/createTransaction
            // проверяют его перед вставкой.
            val batchCtx = BatchContext()

            var allSuccess = true
            for (i in 0 until commandsArray.length()) {
                val cmd = commandsArray.optJSONObject(i) ?: continue
                val type = cmd.optString("type", "").uppercase()
                val result = when (type) {
                    "CREATE_RENTER" -> createRenter(cmd, batchCtx)
                    "CREATE_SCOOTER" -> createScooter(cmd)
                    "CREATE_TRANSACTION" -> createTransaction(cmd, batchCtx)
                    "CREATE_CONTRACT" -> createContract(cmd, batchCtx)
                    "CREATE_VIRTUAL_CARD" -> createVirtualCard(cmd)
                    "CREATE_CARD_TRANSACTION" -> createCardTransaction(cmd)
                    "UPDATE_RENTER" -> updateRenter(cmd)
                    "UPDATE_SCOOTER" -> updateScooter(cmd)
                    "UPDATE_VIRTUAL_CARD" -> updateVirtualCard(cmd)
                    "UPDATE_CONTRACT" -> updateContract(cmd)
                    "UPDATE_TRANSACTION" -> updateTransaction(cmd)
                    "RETURN_RENTER" -> returnRenter(cmd)
                    "TERMINATE_RENTER" -> terminateRenter(cmd)
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
        if (trimmed.isEmpty()) {
            Log.w(TAG, "parseResponseJson: empty input")
            return null
        }

        // Прямой парсинг (если ответ — чистый JSON)
        try {
            return JSONObject(trimmed)
        } catch (e: Exception) {
            Log.d(TAG, "parseResponseJson: direct parse failed (${e.message}), trying markdown/brace extraction")
        }

        // Поиск markdown-блока ```json ... ```
        val mdPattern = Regex("""```(?:json)?\s*(\{[\s\S]*\})\s*```""", RegexOption.IGNORE_CASE)
        mdPattern.find(trimmed)?.let { match ->
            try {
                return JSONObject(match.groupValues[1])
            } catch (e: Exception) {
                Log.d(TAG, "parseResponseJson: markdown block parse failed: ${e.message}")
            }
        }

        // Поиск первого "{" и последнего "}"
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = trimmed.substring(firstBrace, lastBrace + 1)
            try {
                return JSONObject(candidate)
            } catch (e: Exception) {
                Log.w(TAG, "parseResponseJson: brace extraction parse failed: ${e.message}, " +
                    "candidate (first 300 chars)=${candidate.take(300)}")
            }
        } else {
            Log.w(TAG, "parseResponseJson: no braces found in response (first 500 chars)=" +
                "${trimmed.take(500)}")
        }

        return null
    }

    // ── Команда: CREATE_RENTER ──────────────────────────────────────────────
    //
    // ИИ вызывает эту команду ровно с теми же параметрами, что и пользователь
    // через форму RenterFormDialog. ВСЯ логика создания контрактов, транзакций
    // и зачислений на карту делается ТОЙ ЖЕ последовательностью операций, что
    // и в RenterViewModel.addRenter — ИИ не имеет собственной версии этой
    // логики. Это гарантирует, что поведение приложения одинаково независимо
    // от того, кто создаёт арендатора — пользователь или ИИ.
    //
    // Параметры, которые передаёт ИИ, — это РОВНО те параметры, что и форма:
    //   name, phone, debt, duration, startTimestamp, scooterId, scooterName,
    //   weeklyPrice, passportData, address, pinfl, contractGroups (опц.)
    //
    // Логика по дате (идентична RenterViewModel.addRenter):
    //   • Если (now - startTimestamp) > 7 дней → один контракт с минусом (долг)
    //   • Если 0 < (now - startTimestamp) ≤ 7 дней → один контракт с плюсом
    //   • Если startTimestamp ≥ now → несколько контрактов с плюсом
    //     (по одному на каждую неделю от min(today, start) до start)
    //   • contractGroups (если переданы) имеют приоритет над автогенерацией
    //
    // Для каждого оплаченного контракта (isPaid=true):
    //   • создаётся Transaction(TYPE_PAYMENT, contractId)
    //   • depositContractIncome на главную карту с contractId
    // Это обеспечивает корректный каскадный реверс при удалении контракта.
    private suspend fun createRenter(cmd: JSONObject, batch: BatchContext): CommandResult {
        try {
            // ── Поля формы (те же, что и RenterFormDialog передаёт в addRenter) ──
            val rawName = cmd.optString("name", "").trim()
            val name = if (rawName.isEmpty()) {
                val all = db.renterDao().getAllRentersOnce()
                val nextN = (all.mapNotNull {
                    it.name.removePrefix("Noma'lum ijarachi #").toIntOrNull()
                }.maxOrNull() ?: 0) + 1
                "Noma'lum ijarachi #$nextN"
            } else rawName
            val phone = normalizePhone(cmd.optString("phoneNumber", ""))
            val debt = cmd.optDouble("debt", 0.0)
            val prepayment = cmd.optDouble("prepayment", 0.0)
            val duration = cmd.optInt("rentDurationDays", 7).coerceAtLeast(1)
            val scooterName = cmd.optString("scooterName", "").trim().ifEmpty { null }
            // weeklyPrice — из фото или 0 (как в форме, никаких выдуманных значений)
            val weeklyPrice = cmd.optDouble("weeklyPrice", 0.0)

            val rawPassport = cmd.optString("passportData", "").trim()
            val issuedBy = cmd.optString("passportIssuedBy", "").trim()
            val passportData = when {
                rawPassport.isEmpty() -> issuedBy
                issuedBy.isEmpty() -> rawPassport
                else -> "$rawPassport, berilgan: $issuedBy"
            }
            val address = cmd.optString("address", "").trim()
            val pinfl = cmd.optString("pinfl", "").trim()
            val isReturned = cmd.optBoolean("isReturned", false)

            val rentStartTs = parseDate(cmd.optString("rentStartDate", "").trim())
                ?: System.currentTimeMillis()

            // Находим скутер по имени (как делает форма через выпадающий список)
            val scooter = scooterName?.let { findScooterByName(it) }
            val scooterId = scooter?.id
            val scooterNameResolved = scooter?.name ?: scooterName

            // ── contractGroups (опционально, как в форме) ─────────────────
            // Если ИИ передал "contractGroups" — массив объектов {start, end} в ISO,
            // они имеют приоритет над автогенерацией по дате. Это соответствует
            // поведению формы: если пользователь выбрал группы в календаре, они
            // используются; иначе работает автоматическая логика по дате.
            val contractGroups: List<Pair<Long, Long>> = run {
                val arr = cmd.optJSONArray("contractGroups") ?: return@run emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val g = arr.optJSONObject(i) ?: continue
                        val s = parseDate(g.optString("start", "")) ?: continue
                        val e = parseDate(g.optString("end", "")) ?: continue
                        add(s to e)
                    }
                }
            }

            // ── ТЕ ЖЕ КОНСТАНТЫ, что и в RenterViewModel.addRenter ──────────
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs
            val effectiveWeeklyPrice = if (weeklyPrice > 0) weeklyPrice
                                       else SettingsRepository.DEFAULT_WEEKLY_PRICE

            // ── ТЕ ЖЕ СЦЕНАРИИ, что и в RenterViewModel.addRenter ───────────
            val diffMs = now - rentStartTs
            val scenario = when {
                contractGroups.isNotEmpty() -> 4
                diffMs > weekMs -> 1
                diffMs > 0L -> 2
                else -> 3
            }

            // ── ТЕ ЖЕ SPECS, что и в RenterViewModel.addRenter ──────────────
            data class ContractSpec(val weekStart: Long, val weekEnd: Long, val isPaid: Boolean)
            val specs: List<ContractSpec> = when (scenario) {
                4 -> contractGroups.map { (gs, ge) ->
                    val isPaid = (now - ge) <= weekMs
                    ContractSpec(gs, ge, isPaid)
                }
                1 -> listOf(ContractSpec(rentStartTs, rentStartTs + weekMs, isPaid = false))
                2 -> listOf(ContractSpec(rentStartTs, rentStartTs + weekMs, isPaid = true))
                3 -> {
                    val specsList = mutableListOf<ContractSpec>()
                    var cursor = rentStartTs
                    val today = now
                    if (cursor > today) cursor = today
                    while (cursor < rentStartTs + dayMs) {
                        specsList.add(ContractSpec(cursor, cursor + weekMs, isPaid = true))
                        cursor += weekMs
                        if (specsList.size > 52) break
                    }
                    specsList
                }
                else -> listOf(ContractSpec(rentStartTs, rentStartTs + weekMs, isPaid = true))
            }

            // ── Если фото показало предоплату (prepayment > 0),korrektiruem ──
            // спецификацию: делаем первый контракт isPaid=true (предоплата закрывает
            // первую неделю), а если prepayment < weeklyPrice — остальные как долг.
            // Это соответствует поведению applyWeeklyPayment при ручной оплате.
            //
            // Однако наша новая логика уже автоматически ставит isPaid по дате.
            // Если фото показало prepayment > 0 — это значит, что пользователь
            // заплатил, поэтому даже для просроченной даты предоплата должна
            // закрыть контракт. Поэтому корректируем:
            //   • prepayment > 0  →  первый контракт становится isPaid=true
            //   • debt > 0        →  первый контракт остаётся isPaid=false
            val effectiveSpecs = if (specs.isNotEmpty()) {
                val first = specs.first()
                val correctedFirst = when {
                    debt > 0 -> first.copy(isPaid = false)
                    prepayment > 0 -> first.copy(isPaid = true)
                    else -> first
                }
                listOf(correctedFirst) + specs.drop(1)
            } else specs

            // ── Тот же расчёт начального баланса ────────────────────────────
            val contractsBalance = effectiveSpecs.fold(0.0) { acc, s ->
                acc + if (s.isPaid) effectiveWeeklyPrice else -effectiveWeeklyPrice
            }
            val initialBalance = when {
                debt > 0 -> -debt + contractsBalance
                prepayment > 0 -> prepayment - effectiveWeeklyPrice + specs.drop(1).fold(0.0) { acc, s ->
                    acc + if (s.isPaid) effectiveWeeklyPrice else -effectiveWeeklyPrice
                }
                else -> contractsBalance
            }
            val finalDebt = if (initialBalance < 0) -initialBalance else 0.0

            // ── Создаём арендатора (как RenterViewModel: provisional + insert) ──
            val renter = Renter(
                name = name,
                phoneNumber = phone,
                debtAmount = finalDebt,
                rentDurationDays = duration,
                rentStartDateTimestamp = rentStartTs,
                isReturned = isReturned,
                isOverdueSmsSent = false,
                scooterId = scooterId,
                scooterName = scooterNameResolved,
                balance = initialBalance,
                passportData = passportData,
                address = address,
                pinfl = pinfl
            )
            val newId = db.renterDao().insertRenter(renter).toInt()
            val savedRenter = renter.copy(id = newId)

            // ── Создаём контракты по specs (как RenterViewModel) ────────────
            val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.US)
            effectiveSpecs.forEachIndexed { idx, spec ->
                val contractType = if (idx == 0) ContractHistoryEntry.TYPE_CREATED
                                   else ContractHistoryEntry.TYPE_AUTO_RENEW
                val notes = when {
                    spec.isPaid && effectiveSpecs.size > 1 -> "${idx + 1}-hafta oldindan to'lov (skaner)"
                    spec.isPaid -> "Skaner orqali yaratildi (oldindan to'langan)"
                    else -> "Skaner orqali yaratildi (qarz bilan)"
                }

                val contractId = db.contractHistoryDao().insert(ContractHistoryEntry(
                    renterId = newId,
                    timestamp = now,
                    type = contractType,
                    amount = effectiveWeeklyPrice,
                    notes = notes,
                    renterName = name,
                    renterPhone = phone,
                    scooterName = scooterNameResolved,
                    weekStart = spec.weekStart,
                    weekEnd = spec.weekEnd,
                    weeklyPrice = effectiveWeeklyPrice,
                    passportData = passportData,
                    address = address,
                    pinfl = pinfl,
                    vinNumber = scooter?.vinNumber ?: "",
                    engineNumber = scooter?.engineNumber ?: "",
                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                    batteryId1 = scooter?.batteryId1 ?: "",
                    batteryId2 = scooter?.batteryId2 ?: "",
                    additionalInfo = scooter?.additionalInfo ?: "",
                    isPaid = spec.isPaid
                )).toInt()

                // ── Для оплаченного контракта: Transaction + depositContractIncome ──
                // Это ТОТ ЖЕ код, что и в RenterViewModel.addRenter.
                if (spec.isPaid && contractId > 0) {
                    try {
                        val wsStr = dateFmt.format(Date(spec.weekStart))
                        val weStr = dateFmt.format(Date(spec.weekEnd))
                        val contractLabel = "#$contractId  $wsStr → $weStr"
                        db.transactionDao().insert(Transaction(
                            contractId = contractId,
                            renterId = newId,
                            scooterId = scooterId,
                            timestamp = now,
                            type = Transaction.TYPE_PAYMENT,
                            amount = effectiveWeeklyPrice,
                            notes = notes,
                            renterName = name,
                            renterPhone = phone,
                            scooterName = scooterNameResolved ?: "",
                            contractLabel = contractLabel
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to insert prepaid Transaction for contract #$contractId: ${e.message}")
                    }

                    try {
                        val cardRepo = VirtualCardRepository(
                            db.virtualCardDao(), db.cardTransactionDao()
                        )
                        cardRepo.depositContractIncome(
                            amount = effectiveWeeklyPrice,
                            note = "Skaner: $name — #$contractId",
                            contractId = contractId
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "depositContractIncome failed for new contract #$contractId: ${e.message}")
                    }
                }

                // Регистрируем в batch-контексте (для дедупликации)
                batch.recordContract(name, spec.weekStart)
                if (spec.isPaid) {
                    batch.recordPayment(name, effectiveWeeklyPrice, now)
                }
            }

            val dateStr = dateFmt.format(Date(rentStartTs))
            val balanceNote = when {
                debt > 0 -> ", qarz: ${formatMoney(debt)}"
                prepayment > 0 -> ", oldindan: ${formatMoney(prepayment)}"
                else -> ""
            }
            return CommandResult(true,
                "✓ Ijarachi yaratildi: $name ($phone)$balanceNote, sana: $dateStr")
        } catch (e: Exception) {
            Log.e(TAG, "createRenter failed", e)
            return CommandResult(false, "CREATE_RENTER xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_SCOOTER ─────────────────────────────────────────────
    private suspend fun createScooter(cmd: JSONObject): CommandResult {
        try {
            // name — OPTIONAL. Если фото не показало имя, auto-генерируем
            // "Skillmax-NNN" (следующий свободный номер после существующих).
            val rawName = cmd.optString("name", "").trim()
            val name = if (rawName.isEmpty()) {
                val all = db.scooterDao().getAllScootersOnce()
                val nextN = (all.mapNotNull {
                    it.name.removePrefix("Skillmax-").trimStart('0').toIntOrNull()
                }.maxOrNull() ?: 0) + 1
                "Skillmax-" + nextN.toString().padStart(3, '0')
            } else rawName
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
    private suspend fun createTransaction(cmd: JSONObject, batch: BatchContext): CommandResult {
        try {
            // renterName — OPTIONAL. Если фото не показало имя арендатора,
            // привязываем транзакцию к ПОСЛЕДНЕМУ созданному арендатору в БД
            // (это соответствует правилу «значение от предыдущего»). Если в БД
            // вообще нет арендаторов — пропускаем команду.
            val renterName = cmd.optString("renterName", "").trim()
            val renter = if (renterName.isEmpty()) {
                db.renterDao().getAllRentersOnce().maxByOrNull { it.id }
            } else {
                findRenterByName(renterName)
            }
            if (renter == null) {
                return CommandResult(false,
                    "CREATE_TRANSACTION: ijarachi topilmadi " +
                    "(renterName='$renterName' va bazada ijarachilar yo'q). " +
                    "Avval ijarachini skaner orqali yarating.")
            }
            val amount = cmd.optDouble("amount", 0.0)
            // Раньше тут была проверка amount > 0 с отказом — убрана.
            // Если фото не показывает сумму → агент присылает 0, и мы создаём
            // транзакцию с нулевой суммой (это валидный сценарий по требованию
            // пользователя: никаких выдуманных значений).
            val txType = cmd.optString("txType", "PAYMENT").uppercase()
                .let { if (it.isBlank()) "PAYMENT" else it }
            val notes = cmd.optString("notes", "").trim()
            val scooterName = cmd.optString("scooterName", "").trim().ifEmpty { null }

            // ── Дата транзакции ────────────────────────────────────────────
            // Mistral извлекает дату из фото и кладёт её в "date" в формате
            // ISO "YYYY-MM-DD". Если поле отсутствует — fallback на сегодня.
            //
            // ВАЖНО: раньше здесь всегда стояло System.currentTimeMillis() —
            // из-за этого все транзакции создавались "сегодня", даже если на
            // фото была дата недели/месяца назад.
            val txTimestamp = parseDate(cmd.optString("date", "").trim())
                ?: System.currentTimeMillis()

            // ── Batch-level дедупликация ──────────────────────────────────
            // Если в этом же батче CREATE_RENTER уже создал PAYMENT транзакцию
            // для того же арендатора на ту же сумму и дату (±1 день) — это дубль,
            // пропускаем. Иначе баланс арендатора удваивался бы и зачисление
            // на карту происходило бы дважды.
            if (txType == Transaction.TYPE_PAYMENT &&
                batch.hasPayment(renterName, amount, txTimestamp)) {
                Log.i(TAG, "createTransaction: SKIP duplicate PAYMENT for '$renterName' " +
                    "(amount=${amount}, ts=$txTimestamp) — already created in this batch by CREATE_RENTER")
                return CommandResult(true,
                    "✓ To'lov avval qayd qilingan (CREATE_RENTER ichida): ${renter.name}, " +
                    "${formatMoney(amount)} — o'tkazib yuborildi")
            }

            val tx = Transaction(
                contractId = null,
                renterId = renter.id,
                scooterId = renter.scooterId,
                timestamp = txTimestamp,
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
                    lastPaymentTimestamp = txTimestamp,
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

                // Регистрируем в batch-контексте, чтобы следующие команды
                // CREATE_TRANSACTION для того же арендатора могли проверить дубли.
                batch.recordPayment(renter.name, amount, txTimestamp)
            }

            val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(txTimestamp))
            return CommandResult(true,
                "✓ Tranzaksiya: ${renter.name} — ${formatMoney(amount)} ($txType), sana: $dateStr")
        } catch (e: Exception) {
            Log.e(TAG, "createTransaction failed", e)
            return CommandResult(false, "CREATE_TRANSACTION xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_CONTRACT ────────────────────────────────────────────
    //
    // ИИ вызывает эту команду ровно с теми же параметрами, что и пользователь
    // через диалог "Yangi kontrakt" на странице контрактов арендатора
    // (ContractHistoryViewModel.createManualContract). Логика создания
    // контракта и зачисления на карту идентична пользовательскому пути:
    //   • создаём ContractHistoryEntry (TYPE_AUTO_RENEW)
    //   • если isPaid=true → создаём Transaction(TYPE_PAYMENT, contractId)
    //     и depositContractIncome на главную карту с contractId
    //   • если isPaid=false → только создаём контракт (долг)
    //
    // Баланс арендатора НЕ корректируем здесь вручную — это делается через
    // applyWeeklyPayment, который пользователь вызывает кнопкой "To'lash"
    // (payWeeklyForRenters). ИИ не должен самостоятельно менять баланс.
    private suspend fun createContract(cmd: JSONObject, batch: BatchContext): CommandResult {
        try {
            val renterName = cmd.optString("renterName", "").trim()
            val renter = if (renterName.isEmpty()) {
                db.renterDao().getAllRentersOnce().maxByOrNull { it.id }
            } else {
                findRenterByName(renterName)
            }
            if (renter == null) {
                return CommandResult(false,
                    "CREATE_CONTRACT: ijarachi topilmadi " +
                    "(renterName='$renterName' va bazada ijarachilar yo'q).")
            }
            val amount = cmd.optDouble("amount", 0.0)
            val weekStart = parseDate(cmd.optString("weekStart", "")) ?: System.currentTimeMillis()
            val weekEnd = parseDate(cmd.optString("weekEnd", ""))
                ?: (weekStart + 7L * 24 * 60 * 60 * 1000)
            val isPaid = cmd.optBoolean("isPaid", false)
            val notes = cmd.optString("notes", "").trim().ifEmpty { "Skaner orqali yaratildi" }
            val scooterName = cmd.optString("scooterName", "").trim().ifEmpty { null }
            val scooter = scooterName?.let { findScooterByName(it) } ?: renter.scooterId?.let { findScooterById(it) }

            // ── Batch-level дедупликация ──────────────────────────────────
            if (batch.hasContract(renterName, weekStart)) {
                Log.i(TAG, "createContract: SKIP duplicate contract for '$renterName' " +
                    "(weekStart=$weekStart) — already created in this batch by CREATE_RENTER")
                return CommandResult(true,
                    "✓ Kontrakt avval yaratilgan (CREATE_RENTER ichida): ${renter.name}, " +
                    "${formatMoney(amount)} — o'tkazib yuborildi")
            }

            val now = System.currentTimeMillis()
            // ── Создаём контракт (как createManualContract) ──────────────────
            val contractId = db.contractHistoryDao().insert(ContractHistoryEntry(
                renterId = renter.id,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
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
            )).toInt()

            // ── Для оплаченного контракта: Transaction + depositContractIncome ──
            // Это ТОТ ЖЕ код, что и в applyWeeklyPayment (RenterViewModel) при
            // предоплате. ИИ не делает ничего, чего не делал бы пользователь
            // кнопкой "To'lash".
            if (isPaid && contractId > 0) {
                try {
                    val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.US)
                    val wsStr = dateFmt.format(Date(weekStart))
                    val weStr = dateFmt.format(Date(weekEnd))
                    val contractLabel = "#$contractId  $wsStr → $weStr"
                    db.transactionDao().insert(Transaction(
                        contractId = contractId,
                        renterId = renter.id,
                        scooterId = renter.scooterId,
                        timestamp = now,
                        type = Transaction.TYPE_PAYMENT,
                        amount = amount,
                        notes = notes,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        contractLabel = contractLabel
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to insert Transaction for contract #$contractId: ${e.message}")
                }

                try {
                    val cardRepo = VirtualCardRepository(
                        db.virtualCardDao(), db.cardTransactionDao()
                    )
                    cardRepo.depositContractIncome(
                        amount = amount,
                        note = "Skaner: ${renter.name} to'lovi — #$contractId",
                        contractId = contractId
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "depositContractIncome failed for contract #$contractId: ${e.message}")
                }

                // Обновляем баланс арендатора: +amount (как applyWeeklyPayment)
                val newBalance = renter.balance + amount
                db.renterDao().updateRenter(renter.copy(
                    balance = newBalance,
                    debtAmount = maxOf(0.0, -newBalance),
                    lastPaymentTimestamp = now,
                    isOverdueSmsSent = false
                ))

                batch.recordPayment(renter.name, amount, now)
            }

            batch.recordContract(renter.name, weekStart)

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
            // name — OPTIONAL. Если фото не показало имя, auto-генерируем
            // "Yangi karta #N" (следующий свободный номер).
            val rawName = cmd.optString("name", "").trim()
            val name = if (rawName.isEmpty()) {
                val all = db.virtualCardDao().getAllCardsOnce()
                val nextN = (all.mapNotNull {
                    it.name.removePrefix("Yangi karta #").toIntOrNull()
                }.maxOrNull() ?: 0) + 1
                "Yangi karta #$nextN"
            } else rawName
            val balance = cmd.optDouble("balance", 0.0)
            val colorHex = cmd.optString("colorHex", "#FF1565C0").trim().ifEmpty { "#FF1565C0" }
            val info = cmd.optString("info", "").trim().ifEmpty { null }

            val card = VirtualCard(
                name = name,
                balance = balance,
                colorHex = colorHex,
                info = info,
                isDefault = false,
                kind = VirtualCard.KIND_REGULAR  // сканер не может создавать внешние карты
            )
            db.virtualCardDao().insertCard(card)
            return CommandResult(true, "✓ Karta yaratildi: $name (${formatMoney(balance)})")
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualCard failed", e)
            return CommandResult(false, "CREATE_VIRTUAL_CARD xato: ${e.message}")
        }
    }

    // ── Команда: CREATE_CARD_TRANSACTION ────────────────────────────────────
    // Перевод между двумя виртуальными картами. Используется когда фото показывает
    // "kassadan bankka 50000", "Tashqidan kassaga 100000", "remontga 200000" и т.д.
    private suspend fun createCardTransaction(cmd: JSONObject): CommandResult {
        try {
            // fromCardName / toCardName — OPTIONAL. Если фото не показало имя
            // карты, используем основную (isDefault=true) карту. Если основных
            // несколько — берём первую. Если основных нет — берём первую попавшуюся
            // обычную карту (KIND_REGULAR). Если и таких нет — пропускаем.
            val fromName = cmd.optString("fromCardName", "").trim()
            val toName = cmd.optString("toCardName", "").trim()
            val allCards = db.virtualCardDao().getAllCardsOnce()
            val defaultCard = allCards.firstOrNull { it.isDefault }
                ?: allCards.firstOrNull { it.kind == VirtualCard.KIND_REGULAR }

            val fromCard = if (fromName.isEmpty()) {
                defaultCard
            } else {
                findCardByName(fromName) ?: defaultCard
            }
            val toCard = if (toName.isEmpty()) {
                defaultCard
            } else {
                findCardByName(toName) ?: defaultCard
            }

            if (fromCard == null || toCard == null) {
                return CommandResult(false,
                    "CREATE_CARD_TRANSACTION: bazada kartalar topilmadi. " +
                    "Avval kamida bitta karta yarating.")
            }
            if (fromCard.id == toCard.id) {
                return CommandResult(false,
                    "CREATE_CARD_TRANSACTION: fromCard va toCard bir xil — o'tkazma ma'nosiz. " +
                    "Photo'da kamida bitta karta nomi aniq ko'rsatilishi kerak.")
            }
            val amount = cmd.optDouble("amount", 0.0)
            // Раньше тут была проверка amount > 0 с отказом — убрана.
            // Если фото не показывает сумму → агент присылает 0, и мы создаём
            // перевод с нулевой суммой (валидный сценарий по требованию пользователя).
            val note = cmd.optString("note", "").trim().ifEmpty { null }

            // Внешние карты требуют обязательный note
            val involvesExternal = fromCard.isExternal || toCard.isExternal
            if (involvesExternal && note.isNullOrEmpty()) {
                return CommandResult(false,
                    "CREATE_CARD_TRANSACTION: tashqi kartalar uchun 'note' majburiy")
            }

            val cardRepo = VirtualCardRepository(
                db.virtualCardDao(), db.cardTransactionDao()
            )
            cardRepo.transfer(
                fromCardId = fromCard.id,
                toCardId = toCard.id,
                amount = amount,
                note = note ?: "Skaner orqali o'tkazma: $fromName → $toName"
            )

            return CommandResult(true,
                "✓ Karta o'tkazmasi: $fromName → $toName, ${formatMoney(amount)}")
        } catch (e: Exception) {
            Log.e(TAG, "createCardTransaction failed", e)
            return CommandResult(false, "CREATE_CARD_TRANSACTION xato: ${e.message}")
        }
    }

    // ── Команда: UPDATE_RENTER ──────────────────────────────────────────────
    // Обновляет поля существующего арендатора. Только указанные поля меняются.
    private suspend fun updateRenter(cmd: JSONObject): CommandResult {
        try {
            val renterName = cmd.optString("renterName", "").trim()
            if (renterName.isEmpty()) {
                return CommandResult(false, "UPDATE_RENTER: 'renterName' majburiy")
            }
            val renter = findRenterByName(renterName)
                ?: return CommandResult(false,
                    "UPDATE_RENTER: '$renterName' ijarachi topilmadi")

            val newPhone = cmd.optString("newPhoneNumber", "").trim().ifEmpty { null }
            val newDebt = if (cmd.has("newDebt")) cmd.optDouble("newDebt", 0.0) else null
            val balanceAdjustment = if (cmd.has("balanceAdjustment"))
                cmd.optDouble("balanceAdjustment", 0.0) else null
            val newAddress = cmd.optString("newAddress", "").trim().ifEmpty { null }
            val newPassport = cmd.optString("newPassportData", "").trim().ifEmpty { null }
            val newPinfl = cmd.optString("newPinfl", "").trim().ifEmpty { null }
            val newScooterName = cmd.optString("newScooterName", "").trim().ifEmpty { null }
            // newWeeklyPrice парсится для будущих контрактов — но Renter entity
            // не хранит weeklyPrice (он в settings + в каждом ContractHistoryEntry).
            // Если Mistral прислал это поле, мы его игнорируем на уровне renter,
            // но логируем в notes.
            val newWeeklyPriceStr = if (cmd.has("newWeeklyPrice"))
                formatMoney(cmd.optDouble("newWeeklyPrice", 0.0)) else null
            val newDuration = if (cmd.has("newRentDurationDays"))
                cmd.optInt("newRentDurationDays", 7) else null
            val notes = cmd.optString("notes", "").trim()

            // Находим новый скутер если указан
            val newScooter = newScooterName?.let { findScooterByName(it) }

            // Вычисляем новый баланс
            val currentBalance = renter.balance +
                (balanceAdjustment ?: 0.0) +
                (if (newDebt != null) (-newDebt - renter.debtAmount) else 0.0)
            val newDebtAmount = when {
                newDebt != null -> newDebt
                balanceAdjustment != null -> maxOf(0.0, -(renter.balance + balanceAdjustment))
                else -> renter.debtAmount
            }

            val updated = renter.copy(
                phoneNumber = newPhone ?: renter.phoneNumber,
                balance = currentBalance,
                debtAmount = newDebtAmount,
                address = newAddress ?: renter.address,
                passportData = newPassport ?: renter.passportData,
                pinfl = newPinfl ?: renter.pinfl,
                scooterId = newScooter?.id ?: renter.scooterId,
                scooterName = newScooter?.name ?: newScooterName ?: renter.scooterName,
                rentDurationDays = newDuration ?: renter.rentDurationDays
            )
            db.renterDao().updateRenter(updated)

            val changedFields = mutableListOf<String>()
            if (newPhone != null) changedFields.add("tel")
            if (newDebt != null) changedFields.add("qarz=${formatMoney(newDebt)}")
            if (balanceAdjustment != null) changedFields.add("balans=${formatMoney(balanceAdjustment)}")
            if (newAddress != null) changedFields.add("manzil")
            if (newPassport != null) changedFields.add("pasport")
            if (newPinfl != null) changedFields.add("PINFL")
            if (newScooterName != null) changedFields.add("skuter=$newScooterName")
            if (newWeeklyPriceStr != null) changedFields.add("haftalik=$newWeeklyPriceStr")
            if (newDuration != null) changedFields.add("muddat=${newDuration}kun")
            val changeSummary = if (changedFields.isEmpty()) "yangilandi" else changedFields.joinToString(", ")

            return CommandResult(true,
                "✓ Ijarachi yangilandi: ${renter.name} — $changeSummary" +
                if (notes.isNotEmpty()) " ($notes)" else "")
        } catch (e: Exception) {
            Log.e(TAG, "updateRenter failed", e)
            return CommandResult(false, "UPDATE_RENTER xato: ${e.message}")
        }
    }

    // ── Команда: UPDATE_SCOOTER ─────────────────────────────────────────────
    // Обновляет поля существующего скутера. ТОЛЬКО поля, явно присутствующие
    // в JSON, изменяются — все остальные остаются без изменений.
    // ВАЖНО: ничего не придумываем — если фото не показывает поле, мы его
    // не трогаем.
    private suspend fun updateScooter(cmd: JSONObject): CommandResult {
        try {
            val scooterName = cmd.optString("scooterName", "").trim()
            if (scooterName.isEmpty()) {
                return CommandResult(false, "UPDATE_SCOOTER: 'scooterName' majburiy")
            }
            val scooter = findScooterByName(scooterName)
                ?: return CommandResult(false,
                    "UPDATE_SCOOTER: '$scooterName' skuter topilmadi")

            // Читаем только те поля, которые явно присутствуют в JSON.
            // null = не трогать.
            val newName = if (cmd.has("newName"))
                cmd.optString("newName", "").trim().ifEmpty { null } else null
            val newDocumentedNumber = if (cmd.has("newDocumentedNumber"))
                cmd.optString("newDocumentedNumber", "").trim().ifEmpty { null } else null
            val newVinNumber = if (cmd.has("newVinNumber"))
                cmd.optString("newVinNumber", "").trim().ifEmpty { null } else null
            val newEngineNumber = if (cmd.has("newEngineNumber"))
                cmd.optString("newEngineNumber", "").trim().ifEmpty { null } else null
            val newScooterSerialNumber = if (cmd.has("newScooterSerialNumber"))
                cmd.optString("newScooterSerialNumber", "").trim().ifEmpty { null } else null
            val newBatteryId1 = if (cmd.has("newBatteryId1"))
                cmd.optString("newBatteryId1", "").trim().ifEmpty { null } else null
            val newBatteryId2 = if (cmd.has("newBatteryId2"))
                cmd.optString("newBatteryId2", "").trim().ifEmpty { null } else null
            val newAdditionalInfo = if (cmd.has("newAdditionalInfo"))
                cmd.optString("newAdditionalInfo", "").trim().ifEmpty { null } else null

            val updated = scooter.copy(
                name = newName ?: scooter.name,
                documentedNumber = newDocumentedNumber ?: scooter.documentedNumber,
                vinNumber = newVinNumber ?: scooter.vinNumber,
                engineNumber = newEngineNumber ?: scooter.engineNumber,
                scooterSerialNumber = newScooterSerialNumber ?: scooter.scooterSerialNumber,
                batteryId1 = newBatteryId1 ?: scooter.batteryId1,
                batteryId2 = newBatteryId2 ?: scooter.batteryId2,
                additionalInfo = newAdditionalInfo ?: scooter.additionalInfo
            )
            db.scooterDao().updateScooter(updated)

            val changedFields = mutableListOf<String>()
            if (newName != null) changedFields.add("nomi")
            if (newDocumentedNumber != null) changedFields.add("raqami")
            if (newVinNumber != null) changedFields.add("VIN")
            if (newEngineNumber != null) changedFields.add("dvigatel")
            if (newScooterSerialNumber != null) changedFields.add("seriya")
            if (newBatteryId1 != null) changedFields.add("batareya1")
            if (newBatteryId2 != null) changedFields.add("batareya2")
            if (newAdditionalInfo != null) changedFields.add("info")
            val changeSummary = if (changedFields.isEmpty()) "o'zgartirishsiz" else changedFields.joinToString(", ")

            return CommandResult(true,
                "✓ Skuter yangilandi: ${scooter.name} — $changeSummary")
        } catch (e: Exception) {
            Log.e(TAG, "updateScooter failed", e)
            return CommandResult(false, "UPDATE_SCOOTER xato: ${e.message}")
        }
    }

    // ── Команда: UPDATE_VIRTUAL_CARD ────────────────────────────────────────
    // Обновляет поля существующей виртуальной карты. ТОЛЬКО поля, явно
    // присутствующие в JSON, изменяются — все остальные остаются без изменений.
    private suspend fun updateVirtualCard(cmd: JSONObject): CommandResult {
        try {
            val cardName = cmd.optString("cardName", "").trim()
            if (cardName.isEmpty()) {
                return CommandResult(false, "UPDATE_VIRTUAL_CARD: 'cardName' majburiy")
            }
            val card = findCardByName(cardName)
                ?: return CommandResult(false,
                    "UPDATE_VIRTUAL_CARD: '$cardName' karta topilmadi")

            val newName = if (cmd.has("newName"))
                cmd.optString("newName", "").trim().ifEmpty { null } else null
            val newColorHex = if (cmd.has("newColorHex"))
                cmd.optString("newColorHex", "").trim().ifEmpty { null } else null
            val newInfo = if (cmd.has("newInfo"))
                cmd.optString("newInfo", "").trim().ifEmpty { null } else null
            val balanceAdjustment = if (cmd.has("balanceAdjustment"))
                cmd.optDouble("balanceAdjustment", 0.0) else null

            val newBalance = card.balance + (balanceAdjustment ?: 0.0)

            val updated = card.copy(
                name = newName ?: card.name,
                colorHex = newColorHex ?: card.colorHex,
                info = newInfo ?: card.info,
                balance = newBalance
            )
            db.virtualCardDao().updateCard(updated)

            val changedFields = mutableListOf<String>()
            if (newName != null) changedFields.add("nomi")
            if (newColorHex != null) changedFields.add("rang")
            if (newInfo != null) changedFields.add("info")
            if (balanceAdjustment != null) changedFields.add("balans=${formatMoney(balanceAdjustment)}")
            val changeSummary = if (changedFields.isEmpty()) "o'zgartirishsiz" else changedFields.joinToString(", ")

            return CommandResult(true,
                "✓ Karta yangilandi: ${card.name} — $changeSummary")
        } catch (e: Exception) {
            Log.e(TAG, "updateVirtualCard failed", e)
            return CommandResult(false, "UPDATE_VIRTUAL_CARD xato: ${e.message}")
        }
    }

    // ── Команда: UPDATE_CONTRACT ────────────────────────────────────────────
    // Обновляет поля существующего контракта (по contractId). ТОЛЬКО поля,
    // явно присутствующие в JSON, изменяются — все остальные остаются без изменений.
    private suspend fun updateContract(cmd: JSONObject): CommandResult {
        try {
            val contractId = cmd.optInt("contractId", -1)
            if (contractId <= 0) {
                return CommandResult(false, "UPDATE_CONTRACT: 'contractId' majburiy (>0)")
            }
            val contract = db.contractHistoryDao().getById(contractId)
                ?: return CommandResult(false,
                    "UPDATE_CONTRACT: kontrakt #$contractId topilmadi")

            val newAmount = if (cmd.has("newAmount"))
                cmd.optDouble("newAmount", 0.0) else null
            val newWeekStart = if (cmd.has("newWeekStart"))
                parseDate(cmd.optString("newWeekStart", "").trim()) else null
            val newWeekEnd = if (cmd.has("newWeekEnd"))
                parseDate(cmd.optString("newWeekEnd", "").trim()) else null
            val newIsPaid = if (cmd.has("newIsPaid"))
                cmd.optBoolean("newIsPaid", false) else null
            val newNotes = if (cmd.has("newNotes"))
                cmd.optString("newNotes", "").trim().ifEmpty { null } else null

            val updated = contract.copy(
                amount = newAmount ?: contract.amount,
                weeklyPrice = newAmount ?: contract.weeklyPrice,
                weekStart = newWeekStart ?: contract.weekStart,
                weekEnd = newWeekEnd ?: contract.weekEnd,
                isPaid = newIsPaid ?: contract.isPaid,
                notes = newNotes ?: contract.notes
            )
            db.contractHistoryDao().update(updated)

            val changedFields = mutableListOf<String>()
            if (newAmount != null) changedFields.add("summa=${formatMoney(newAmount)}")
            if (newWeekStart != null) changedFields.add("boshlanish")
            if (newWeekEnd != null) changedFields.add("tugash")
            if (newIsPaid != null) changedFields.add(if (newIsPaid) "to'langan" else "to'lanmagan")
            if (newNotes != null) changedFields.add("izoh")
            val changeSummary = if (changedFields.isEmpty()) "o'zgartirishsiz" else changedFields.joinToString(", ")

            return CommandResult(true,
                "✓ Kontrakt yangilandi: #${contract.id} (${contract.renterName}) — $changeSummary")
        } catch (e: Exception) {
            Log.e(TAG, "updateContract failed", e)
            return CommandResult(false, "UPDATE_CONTRACT xato: ${e.message}")
        }
    }

    // ── Команда: UPDATE_TRANSACTION ─────────────────────────────────────────
    // Обновляет поля существующей транзакции (по transactionId). ТОЛЬКО поля,
    // явно присутствующие в JSON, изменяются — все остальные остаются без изменений.
    private suspend fun updateTransaction(cmd: JSONObject): CommandResult {
        try {
            val transactionId = cmd.optInt("transactionId", -1)
            if (transactionId <= 0) {
                return CommandResult(false, "UPDATE_TRANSACTION: 'transactionId' majburiy (>0)")
            }
            val tx = db.transactionDao().getById(transactionId)
                ?: return CommandResult(false,
                    "UPDATE_TRANSACTION: tranzaksiya #$transactionId topilmadi")

            val newAmount = if (cmd.has("newAmount"))
                cmd.optDouble("newAmount", 0.0) else null
            val newType = if (cmd.has("newType"))
                cmd.optString("newType", "").trim().uppercase().ifEmpty { null } else null
            val newDate = if (cmd.has("newDate"))
                parseDate(cmd.optString("newDate", "").trim()) else null
            val newNotes = if (cmd.has("newNotes"))
                cmd.optString("newNotes", "").trim().ifEmpty { null } else null

            val updated = tx.copy(
                amount = newAmount ?: tx.amount,
                type = newType ?: tx.type,
                timestamp = newDate ?: tx.timestamp,
                notes = newNotes ?: tx.notes
            )
            db.transactionDao().update(updated)

            val changedFields = mutableListOf<String>()
            if (newAmount != null) changedFields.add("summa=${formatMoney(newAmount)}")
            if (newType != null) changedFields.add("tur=$newType")
            if (newDate != null) changedFields.add("sana")
            if (newNotes != null) changedFields.add("izoh")
            val changeSummary = if (changedFields.isEmpty()) "o'zgartirishsiz" else changedFields.joinToString(", ")

            return CommandResult(true,
                "✓ Tranzaksiya yangilandi: #${tx.id} (${tx.renterName}) — $changeSummary")
        } catch (e: Exception) {
            Log.e(TAG, "updateTransaction failed", e)
            return CommandResult(false, "UPDATE_TRANSACTION xato: ${e.message}")
        }
    }

    // ── Команда: RETURN_RENTER ──────────────────────────────────────────────
    // Помечает арендатора как вернувшего скутер. Создаёт RETURNED запись в
    // истории контрактов и RETURNED транзакцию.
    private suspend fun returnRenter(cmd: JSONObject): CommandResult {
        try {
            val renterName = cmd.optString("renterName", "").trim()
            if (renterName.isEmpty()) {
                return CommandResult(false, "RETURN_RENTER: 'renterName' majburiy")
            }
            val renter = findRenterByName(renterName)
                ?: return CommandResult(false,
                    "RETURN_RENTER: '$renterName' ijarachi topilmadi")

            val returnTs = parseDate(cmd.optString("date", "").trim())
                ?: System.currentTimeMillis()
            val notes = cmd.optString("notes", "").trim().ifEmpty { "Skaner orqali qaytarish" }

            val scooter = renter.scooterId?.let { findScooterById(it) }

            // Шаг 1: помечаем арендатора как isReturned
            val updated = renter.copy(
                isReturned = true,
                isOverdueSmsSent = false,
                lastPaymentTimestamp = returnTs
            )
            db.renterDao().updateRenter(updated)

            // Шаг 2: создаём RETURNED запись в истории контрактов
            val entry = ContractHistoryEntry(
                renterId = renter.id,
                timestamp = returnTs,
                type = ContractHistoryEntry.TYPE_RETURNED,
                amount = 0.0,
                notes = notes,
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = renter.rentStartDateTimestamp,
                weekEnd = returnTs,
                weeklyPrice = 0.0,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = false
            )
            db.contractHistoryDao().insert(entry)

            // Шаг 3: создаём RETURNED транзакцию
            val tx = Transaction(
                contractId = null,
                renterId = renter.id,
                scooterId = renter.scooterId,
                timestamp = returnTs,
                type = Transaction.TYPE_RETURNED,
                amount = 0.0,
                notes = notes,
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: "",
                contractLabel = ""
            )
            db.transactionDao().insert(tx)

            val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(returnTs))
            return CommandResult(true,
                "✓ Skuter qaytarildi: ${renter.name}, sana: $dateStr")
        } catch (e: Exception) {
            Log.e(TAG, "returnRenter failed", e)
            return CommandResult(false, "RETURN_RENTER xato: ${e.message}")
        }
    }

    // ── Команда: TERMINATE_RENTER ───────────────────────────────────────────
    // Досрочное расторжение контракта. Создаёт TERMINATED запись в истории
    // контрактов и TERMINATED транзакцию. Помечает арендатора isReturned=true.
    // Если есть finalPayment > 0 — зачисляет его на главную карту.
    private suspend fun terminateRenter(cmd: JSONObject): CommandResult {
        try {
            val renterName = cmd.optString("renterName", "").trim()
            if (renterName.isEmpty()) {
                return CommandResult(false, "TERMINATE_RENTER: 'renterName' majburiy")
            }
            val renter = findRenterByName(renterName)
                ?: return CommandResult(false,
                    "TERMINATE_RENTER: '$renterName' ijarachi topilmadi")

            val finalPayment = cmd.optDouble("finalPayment", 0.0)
            val termTs = parseDate(cmd.optString("date", "").trim())
                ?: System.currentTimeMillis()
            val notes = cmd.optString("notes", "").trim().ifEmpty { "Skaner orqali tugatish" }

            val scooter = renter.scooterId?.let { findScooterById(it) }

            // Шаг 1: корректируем баланс арендатора
            // Если был долг и finalPayment его покрывает — баланс становится 0 или положительным
            // Если был долг и finalPayment < долга — баланс остаётся отрицательным
            val newBalance = renter.balance + finalPayment
            val updated = renter.copy(
                isReturned = true,
                balance = newBalance,
                debtAmount = maxOf(0.0, -newBalance),
                isOverdueSmsSent = false,
                lastPaymentTimestamp = termTs
            )
            db.renterDao().updateRenter(updated)

            // Шаг 2: создаём TERMINATED запись в истории контрактов
            val entry = ContractHistoryEntry(
                renterId = renter.id,
                timestamp = termTs,
                type = ContractHistoryEntry.TYPE_TERMINATED,
                amount = finalPayment,
                notes = notes,
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName,
                weekStart = renter.rentStartDateTimestamp,
                weekEnd = termTs,
                weeklyPrice = finalPayment,
                passportData = renter.passportData,
                address = renter.address,
                pinfl = renter.pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = newBalance >= 0
            )
            db.contractHistoryDao().insert(entry)

            // Шаг 3: создаём TERMINATED транзакцию
            val tx = Transaction(
                contractId = null,
                renterId = renter.id,
                scooterId = renter.scooterId,
                timestamp = termTs,
                type = Transaction.TYPE_TERMINATED,
                amount = finalPayment,
                notes = notes,
                renterName = renter.name,
                renterPhone = renter.phoneNumber,
                scooterName = renter.scooterName ?: "",
                contractLabel = ""
            )
            db.transactionDao().insert(tx)

            // Шаг 4: если был finalPayment — зачисляем на главную карту
            if (finalPayment > 0) {
                try {
                    val cardRepo = VirtualCardRepository(
                        db.virtualCardDao(), db.cardTransactionDao()
                    )
                    cardRepo.depositContractIncome(
                        amount = finalPayment,
                        note = "Skaner: ${renter.name} (tugatish to'lovi)",
                        contractId = null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "depositContractIncome for terminate failed: ${e.message}")
                }
            }

            val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(termTs))
            val paymentNote = if (finalPayment > 0)
                ", to'lov: ${formatMoney(finalPayment)}" else ""
            return CommandResult(true,
                "✓ Kontrakt tugatildi: ${renter.name}$paymentNote, sana: $dateStr")
        } catch (e: Exception) {
            Log.e(TAG, "terminateRenter failed", e)
            return CommandResult(false, "TERMINATE_RENTER xato: ${e.message}")
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
     * Поиск виртуальной карты по имени (case-insensitive, с fallback на
     * startsWith). Если точного совпадения нет — возвращает карту, имя
     * которой начинается с заданной строки (или наоборот).
     *
     * Поддерживает как обычные, так и системные/внешние карты
     * (Glavnaya, Vtorostepennaya, Tashqidan, Tashqiga).
     */
    private suspend fun findCardByName(name: String): VirtualCard? {
        val all = db.virtualCardDao().getAllCardsOnce()
        val trimmed = name.trim()
        return all.firstOrNull { it.name.trim().equals(trimmed, ignoreCase = true) }
            ?: all.firstOrNull { it.name.trim().startsWith(trimmed, ignoreCase = true) }
            ?: all.firstOrNull { trimmed.startsWith(it.name.trim(), ignoreCase = true) }
    }

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
     * Парсит дату из строки. Поддерживаемые форматы:
     *   • "yyyy-MM-dd"        — ISO (2025-03-15)
     *   • "dd.MM.yyyy"        — европейский (15.03.2025)
     *   • "dd/MM/yyyy"        — слэш-разделитель (15/03/2025)
     *   • "dd.MM.yy"          — короткий год (15.03.25)
     *   • "dd.MM"             — без года (используется текущий год)
     *   • "d MMM" / "d MMMM"  — Uzbek/Russian month name ("15 mart", "15 март")
     *
     * Возвращает timestamp в миллисекундах (начало дня, локальный timezone),
     * или null если не удалось распарсить.
     */
    private fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        val input = s.trim()

        // Сначала пробуем строгие форматы
        val patterns = listOf(
            "yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy", "dd-MM-yyyy",
            "dd.MM.yy", "dd/MM/yy", "yy-MM-dd"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                    isLenient = false
                }
                return fmt.parse(input)?.time
            } catch (_: Exception) { /* try next */ }
        }

        // Формат "dd.MM" без года — добавляем текущий год
        if (input.matches(Regex("""^\d{1,2}[./-]\d{1,2}$"""))) {
            try {
                val parts = input.split("[./-]".toRegex())
                val day = parts[0].toInt()
                val month = parts[1].toInt()
                if (month in 1..12 && day in 1..31) {
                    val cal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, get(java.util.Calendar.YEAR))
                        set(java.util.Calendar.MONTH, month - 1)
                        set(java.util.Calendar.DAY_OF_MONTH, day)
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    return cal.timeInMillis
                }
            } catch (_: Exception) { /* fall through */ }
        }

        // Форматы с названием месяца — Uzbek ("mart", "may") и Russian ("марта", "мая")
        val monthFormats = listOf("d MMM yyyy", "d MMMM yyyy", "d MMM", "d MMMM")
        val locales = listOf(Locale("ru"), Locale("uz"), Locale.US)
        for (loc in locales) {
            for (p in monthFormats) {
                try {
                    val fmt = SimpleDateFormat(p, loc).apply {
                        timeZone = TimeZone.getDefault()
                        isLenient = true
                    }
                    val parsed = fmt.parse(input)
                    if (parsed != null) {
                        // Если год не указан в формате — SimpleDateFormat ставит 1970.
                        // Заменяем на текущий год.
                        val cal = java.util.Calendar.getInstance().apply {
                            time = parsed
                            if (get(java.util.Calendar.YEAR) < 2000) {
                                set(java.util.Calendar.YEAR,
                                    java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                            }
                        }
                        return cal.timeInMillis
                    }
                } catch (_: Exception) { /* try next */ }
            }
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
