package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Sheet
import java.io.OutputStream

/**
 * Менеджер резервного копирования базы данных в Excel (.xlsx) и восстановления
 * из Excel обратно в базу.
 *
 * Использует библиотеку **FastExcel** (writer + reader), которая:
 *  • работает на Android без тяжёлых зависимостей (в отличие от Apache POI);
 *  • пишет .xlsx в streaming-режиме через [OutputStream];
 *  • читает .xlsx через [java.io.InputStream].
 *
 * Архитектура
 * -----------
 * Каждый лист (sheet) в .xlsx соответствует одной таблице БД. Колонки листа —
 * поля Entity в том же порядке, в котором они объявлены в data class. Первая
 * строка листа — заголовок (имя поля).
 *
 * Поддерживаются 7 таблиц приложения:
 *  1. **Renters**      — арендаторы (Renter)
 *  2. **Scooters**     — скутеры (Scooter)
 *  3. **Contracts**    — история контрактов (ContractHistoryEntry)
 *  4. **Transactions** — транзакции (Transaction)
 *  5. **VirtualCards** — виртуальные карты (VirtualCard)
 *  6. **CardTx**       — транзакции по картам (CardTransaction)
 *  7. **Notifications**— история уведомлений (NotificationHistoryEntity)
 *
 * Порядок импорта важен: сначала таблицы без внешних ссылок (Scooters,
 * VirtualCards, Renters), потом зависимые (Contracts, Transactions, CardTx,
 * Notifications). Так как в схеме нет ForeignKey, порядок нужен только для
 * логической согласованности (renterId / scooterId в импортируемых записях
 * должны указывать на уже существующие записи).
 *
 * При импорте:
 *  • Существующие данные в БД **удаляются** (deleteAll) перед вставкой.
 *  • Вставка идёт с тем же PK (id), что и в .xlsx — OnConflictStrategy.REPLACE
 *    в DAO это поддерживает.
 *  • SQLite AUTOINCREMENT-счётчик не сбрасывается автоматически, но это
 *    безопасно: новые записи получат id больше любого из импортированных.
 *
 * Использование
 * -------------
 * Экспорт:
 * ```
 * val uri = ... // ACTION_CREATE_DOCUMENT, "scooter_backup_YYYY-MM-DD.xlsx"
 * val msg = BackupManager.exportToExcel(context, uri)
 * ```
 *
 * Импорт:
 * ```
 * val uri = ... // ACTION_OPEN_DOCUMENT, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
 * val msg = BackupManager.importFromExcel(context, uri)
 * ```
 *
 * URI работает через Storage Access Framework (SAF) — пользователь сам выбирает
 * куда сохранить / откуда загрузить файл. Никаких разрешений
 * WRITE_EXTERNAL_STORAGE не требуется.
 */
object BackupManager {

    private const val TAG = "BackupManager"

    // ── Имена листов ─────────────────────────────────────────────────────
    private const val SHEET_RENTERS = "Renters"
    private const val SHEET_SCOOTERS = "Scooters"
    private const val SHEET_CONTRACTS = "Contracts"
    private const val SHEET_TRANSACTIONS = "Transactions"
    private const val SHEET_VIRTUAL_CARDS = "VirtualCards"
    private const val SHEET_CARD_TX = "CardTx"
    private const val SHEET_NOTIFICATIONS = "Notifications"

    /* =========================================================================
       ЭКСПОРТ
       ========================================================================= */

    /**
     * Экспортирует все 7 таблиц БД в .xlsx файл по указанному [uri].
     *
     * @param context  контекст приложения (для доступа к БД)
     * @param uri      URI файла, куда писать (через SAF ACTION_CREATE_DOCUMENT)
     * @return строка с сообщением для пользователя: либо
     *         "Eksport tayyor: N yozuvlar" при успехе, либо
     *         "Xato: ..." при ошибке.
     */
    suspend fun exportToExcel(context: Context, uri: Uri): String {
        return try {
            val db = AppDatabase.getDatabase(context)
            val renters = db.renterDao().getAllRentersOnce()
            val scooters = db.scooterDao().getAllScootersOnce()
            val contracts = db.contractHistoryDao().getAllOnce()
            val transactions = db.transactionDao().getAllOnce()
            val cards = db.virtualCardDao().getAllCardsOnce()
            val cardTx = db.cardTransactionDao().getRecentTransactions(Int.MAX_VALUE)
            val notifications = db.notificationHistoryDao().getAllOnce()

            val resolver = context.contentResolver
            // Прямой вызов openOutputStream(Uri, "w") — без reflection.
            // Раньше тут был reflection-стиль, который на некоторых прошивках
            // возвращал OutputStream, не пишущий данные в SAF-провайдер,
            // из-за чего файл оказывался 0 байт. Прямой вызов стабилен.
            val rawOutput: OutputStream = resolver.openOutputStream(uri, "w")
                ?: return "Xato: fayl yaratilmadi (openOutputStream = null)"

            // BufferedOutputStream обязателен — FastExcel пишет много мелких
            // chunk'ов; без буфера каждый chunk уходит через ContentProvider
            // в SAF, что медленно и на некоторых провайдерах приводит к
            // потере данных при finish(). 8 КБ — стандартный размер буфера.
            val output = java.io.BufferedOutputStream(rawOutput, 8192)

            try {
                // Конструктор Workbook(OutputStream, appName, version).
                val wb = Workbook(output, "ScooterRent", "1.0")
                writeRenters(wb, renters)
                writeScooters(wb, scooters)
                writeContracts(wb, contracts)
                writeTransactions(wb, transactions)
                writeVirtualCards(wb, cards)
                writeCardTransactions(wb, cardTx)
                writeNotifications(wb, notifications)
                // finish() пишет финальные ZIP-entries (central directory)
                // и flush'ит внутренний writer. Без этого файл был бы пустым.
                wb.finish()
                // Явный flush буфера в底层 OutputStream ДО close — иначе
                // последние байты могут потеряться при закрытии SAF-стрима.
                output.flush()
            } finally {
                // finally гарантирует close даже при исключении в finish().
                output.close()
            }

            val total = renters.size + scooters.size + contracts.size +
                transactions.size + cards.size + cardTx.size + notifications.size
            "Eksport tayyor: $total ta yozuv (${"${renters.size}r/${scooters.size}s/${contracts.size}c/${transactions.size}t/${cards.size}v/${cardTx.size}k/${notifications.size}n"})"
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            "Xato: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun writeRenters(wb: Workbook, items: List<Renter>) {
        val ws = wb.newWorksheet(SHEET_RENTERS)
        // Заголовок
        val headers = listOf(
            "id", "name", "phoneNumber", "debtAmount", "rentDurationDays",
            "rentStartDateTimestamp", "isReturned", "isOverdueSmsSent",
            "scooterId", "scooterName", "lastPaymentTimestamp", "balance",
            "passportData", "address", "pinfl"
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, r ->
            val r2 = rowIdx + 1
            ws.value(r2, 0, r.id)
            ws.value(r2, 1, r.name)
            ws.value(r2, 2, r.phoneNumber)
            ws.value(r2, 3, r.debtAmount)
            ws.value(r2, 4, r.rentDurationDays)
            ws.value(r2, 5, r.rentStartDateTimestamp)
            ws.value(r2, 6, r.isReturned)
            ws.value(r2, 7, r.isOverdueSmsSent)
            r.scooterId?.let { ws.value(r2, 8, it) }
            r.scooterName?.let { ws.value(r2, 9, it) }
            r.lastPaymentTimestamp?.let { ws.value(r2, 10, it) }
            ws.value(r2, 11, r.balance)
            ws.value(r2, 12, r.passportData)
            ws.value(r2, 13, r.address)
            ws.value(r2, 14, r.pinfl)
        }
    }

    private fun writeScooters(wb: Workbook, items: List<Scooter>) {
        val ws = wb.newWorksheet(SHEET_SCOOTERS)
        val headers = listOf(
            "id", "name", "documentedNumber", "vinNumber", "engineNumber",
            "scooterSerialNumber", "batteryId1", "batteryId2", "additionalInfo"
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, s ->
            val r = rowIdx + 1
            ws.value(r, 0, s.id)
            ws.value(r, 1, s.name)
            s.documentedNumber?.let { ws.value(r, 2, it) }
            ws.value(r, 3, s.vinNumber)
            ws.value(r, 4, s.engineNumber)
            ws.value(r, 5, s.scooterSerialNumber)
            ws.value(r, 6, s.batteryId1)
            ws.value(r, 7, s.batteryId2)
            ws.value(r, 8, s.additionalInfo)
        }
    }

    private fun writeContracts(wb: Workbook, items: List<ContractHistoryEntry>) {
        val ws = wb.newWorksheet(SHEET_CONTRACTS)
        val headers = listOf(
            "id", "renterId", "timestamp", "type", "amount", "notes",
            "renterName", "renterPhone", "scooterName", "weekStart", "weekEnd",
            "weeklyPrice", "passportData", "address", "pinfl",
            "vinNumber", "engineNumber", "scooterSerialNumber",
            "batteryId1", "batteryId2", "additionalInfo", "isPaid"
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, c ->
            val r = rowIdx + 1
            ws.value(r, 0, c.id)
            ws.value(r, 1, c.renterId)
            ws.value(r, 2, c.timestamp)
            ws.value(r, 3, c.type)
            ws.value(r, 4, c.amount)
            c.notes?.let { ws.value(r, 5, it) }
            ws.value(r, 6, c.renterName)
            ws.value(r, 7, c.renterPhone)
            c.scooterName?.let { ws.value(r, 8, it) }
            c.weekStart?.let { ws.value(r, 9, it) }
            c.weekEnd?.let { ws.value(r, 10, it) }
            ws.value(r, 11, c.weeklyPrice)
            ws.value(r, 12, c.passportData)
            ws.value(r, 13, c.address)
            ws.value(r, 14, c.pinfl)
            ws.value(r, 15, c.vinNumber)
            ws.value(r, 16, c.engineNumber)
            ws.value(r, 17, c.scooterSerialNumber)
            ws.value(r, 18, c.batteryId1)
            ws.value(r, 19, c.batteryId2)
            ws.value(r, 20, c.additionalInfo)
            ws.value(r, 21, c.isPaid)
        }
    }

    private fun writeTransactions(wb: Workbook, items: List<Transaction>) {
        val ws = wb.newWorksheet(SHEET_TRANSACTIONS)
        val headers = listOf(
            "id", "contractId", "renterId", "scooterId", "timestamp",
            "type", "amount", "notes", "renterName", "renterPhone",
            "scooterName", "contractLabel"
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, t ->
            val r = rowIdx + 1
            ws.value(r, 0, t.id)
            t.contractId?.let { ws.value(r, 1, it) }
            ws.value(r, 2, t.renterId)
            t.scooterId?.let { ws.value(r, 3, it) }
            ws.value(r, 4, t.timestamp)
            ws.value(r, 5, t.type)
            ws.value(r, 6, t.amount)
            t.notes?.let { ws.value(r, 7, it) }
            ws.value(r, 8, t.renterName)
            ws.value(r, 9, t.renterPhone)
            ws.value(r, 10, t.scooterName)
            ws.value(r, 11, t.contractLabel)
        }
    }

    private fun writeVirtualCards(wb: Workbook, items: List<VirtualCard>) {
        val ws = wb.newWorksheet(SHEET_VIRTUAL_CARDS)
        val headers = listOf(
            "id", "name", "balance", "colorHex", "info", "isDefault",
            "kind", "createdAt"
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, c ->
            val r = rowIdx + 1
            ws.value(r, 0, c.id)
            ws.value(r, 1, c.name)
            ws.value(r, 2, c.balance)
            ws.value(r, 3, c.colorHex)
            c.info?.let { ws.value(r, 4, it) }
            ws.value(r, 5, c.isDefault)
            ws.value(r, 6, c.kind)
            ws.value(r, 7, c.createdAt)
        }
    }

    private fun writeCardTransactions(wb: Workbook, items: List<CardTransaction>) {
        val ws = wb.newWorksheet(SHEET_CARD_TX)
        val headers = listOf(
            "id", "timestamp", "fromCardId", "toCardId", "amount", "note", "type"
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, t ->
            val r = rowIdx + 1
            ws.value(r, 0, t.id)
            ws.value(r, 1, t.timestamp)
            ws.value(r, 2, t.fromCardId)
            ws.value(r, 3, t.toCardId)
            ws.value(r, 4, t.amount)
            t.note?.let { ws.value(r, 5, it) }
            ws.value(r, 6, t.type)
        }
    }

    private fun writeNotifications(wb: Workbook, items: List<NotificationHistoryEntity>) {
        val ws = wb.newWorksheet(SHEET_NOTIFICATIONS)
        val headers = listOf("id", "timestamp", "renterId", "title", "message")
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }
        items.forEachIndexed { rowIdx, n ->
            val r = rowIdx + 1
            ws.value(r, 0, n.id)
            ws.value(r, 1, n.timestamp)
            n.renterId?.let { ws.value(r, 2, it) }
            ws.value(r, 3, n.title)
            ws.value(r, 4, n.message)
        }
    }

    /* =========================================================================
       ИМПОРТ
       ========================================================================= */

    /**
     * Импортирует данные из .xlsx файла в БД.
     *
     * **Важно:** перед импортом все существующие данные удаляются.
     * Это сделано сознательно — иначе при импорте из резервной копии
     * получился бы дубликат с теми же id (REPLACE) и мерцание данных.
     *
     * @return строка с сообщением для пользователя.
     */
    suspend fun importFromExcel(context: Context, uri: Uri): String {
        return try {
            val db = AppDatabase.getDatabase(context)
            val resolver = context.contentResolver
            val input: java.io.InputStream = resolver.openInputStream(uri)
                ?: return "Xato: fayl ochilmadi (openInputStream = null)"

            // Считаем статистику для отчёта
            var rentersCount = 0
            var scootersCount = 0
            var contractsCount = 0
            var transactionsCount = 0
            var cardsCount = 0
            var cardTxCount = 0
            var notifCount = 0

            input.use { stream ->
                ReadableWorkbook(stream).use { wb ->
                    // Считываем все листы один раз и кладём в Map по имени.
                    val sheetMap = mutableMapOf<String, Sheet>()
                    wb.sheets.forEach { sh -> sheetMap[sh.name] = sh }

                    // ── Порядок импорта: сначала независимые таблицы ──────
                    // (Scooters, VirtualCards, Renters), потом зависимые
                    // (Contracts, Transactions, CardTx, Notifications).

                    // 1) Очистка всех таблиц
                    db.notificationHistoryDao().deleteAll()
                    db.cardTransactionDao().deleteAll()
                    db.transactionDao().clear()
                    db.contractHistoryDao().deleteAll()
                    db.renterDao().deleteAll()
                    db.scooterDao().deleteAll()
                    db.virtualCardDao().deleteAll()

                    // 2) Scooters
                    sheetMap[SHEET_SCOOTERS]?.let { sh ->
                        val list = readScooters(sh)
                        list.forEach { db.scooterDao().insertScooter(it) }
                        scootersCount = list.size
                    }

                    // 3) VirtualCards
                    sheetMap[SHEET_VIRTUAL_CARDS]?.let { sh ->
                        val list = readVirtualCards(sh)
                        list.forEach { db.virtualCardDao().insertCard(it) }
                        cardsCount = list.size
                    }

                    // 4) Renters
                    sheetMap[SHEET_RENTERS]?.let { sh ->
                        val list = readRenters(sh)
                        list.forEach { db.renterDao().insertRenter(it) }
                        rentersCount = list.size
                    }

                    // 5) Contracts
                    sheetMap[SHEET_CONTRACTS]?.let { sh ->
                        val list = readContracts(sh)
                        list.forEach { db.contractHistoryDao().insert(it) }
                        contractsCount = list.size
                    }

                    // 6) Transactions
                    sheetMap[SHEET_TRANSACTIONS]?.let { sh ->
                        val list = readTransactions(sh)
                        list.forEach { db.transactionDao().insert(it) }
                        transactionsCount = list.size
                    }

                    // 7) CardTx
                    sheetMap[SHEET_CARD_TX]?.let { sh ->
                        val list = readCardTransactions(sh)
                        list.forEach { db.cardTransactionDao().insertTransaction(it) }
                        cardTxCount = list.size
                    }

                    // 8) Notifications
                    sheetMap[SHEET_NOTIFICATIONS]?.let { sh ->
                        val list = readNotifications(sh)
                        list.forEach { db.notificationHistoryDao().insert(it) }
                        notifCount = list.size
                    }
                }
            }

            val total = rentersCount + scootersCount + contractsCount +
                transactionsCount + cardsCount + cardTxCount + notifCount
            "Import tayyor: $total ta yozuv qo'shildi"
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            "Xato: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /* ── Парсеры листов ─────────────────────────────────────────────────── */

    private fun readRenters(sheet: Sheet): List<Renter> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                Renter(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    name = row.getCell(1)?.asString() ?: "",
                    phoneNumber = row.getCell(2)?.asString() ?: "",
                    debtAmount = row.getCell(3)?.asNumber()?.toDouble() ?: 0.0,
                    rentDurationDays = row.getCell(4)?.asNumber()?.toInt() ?: 0,
                    rentStartDateTimestamp = row.getCell(5)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                    isReturned = row.getCell(6)?.asBoolean() ?: false,
                    isOverdueSmsSent = row.getCell(7)?.asBoolean() ?: false,
                    scooterId = row.getCell(8)?.asNumber()?.toInt(),
                    scooterName = row.getCell(9)?.asString(),
                    lastPaymentTimestamp = row.getCell(10)?.asNumber()?.toLong(),
                    balance = row.getCell(11)?.asNumber()?.toDouble() ?: 0.0,
                    passportData = row.getCell(12)?.asString() ?: "",
                    address = row.getCell(13)?.asString() ?: "",
                    pinfl = row.getCell(14)?.asString() ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip renter row: ${e.message}")
                null
            }
        }
    }

    private fun readScooters(sheet: Sheet): List<Scooter> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                Scooter(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    name = row.getCell(1)?.asString() ?: "",
                    documentedNumber = row.getCell(2)?.asString(),
                    vinNumber = row.getCell(3)?.asString() ?: "",
                    engineNumber = row.getCell(4)?.asString() ?: "",
                    scooterSerialNumber = row.getCell(5)?.asString() ?: "",
                    batteryId1 = row.getCell(6)?.asString() ?: "",
                    batteryId2 = row.getCell(7)?.asString() ?: "",
                    additionalInfo = row.getCell(8)?.asString() ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip scooter row: ${e.message}")
                null
            }
        }
    }

    private fun readContracts(sheet: Sheet): List<ContractHistoryEntry> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                ContractHistoryEntry(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    renterId = row.getCell(1)?.asNumber()?.toInt() ?: 0,
                    timestamp = row.getCell(2)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                    type = row.getCell(3)?.asString() ?: ContractHistoryEntry.TYPE_CREATED,
                    amount = row.getCell(4)?.asNumber()?.toDouble() ?: 0.0,
                    notes = row.getCell(5)?.asString(),
                    renterName = row.getCell(6)?.asString() ?: "",
                    renterPhone = row.getCell(7)?.asString() ?: "",
                    scooterName = row.getCell(8)?.asString(),
                    weekStart = row.getCell(9)?.asNumber()?.toLong(),
                    weekEnd = row.getCell(10)?.asNumber()?.toLong(),
                    weeklyPrice = row.getCell(11)?.asNumber()?.toDouble() ?: 0.0,
                    passportData = row.getCell(12)?.asString() ?: "",
                    address = row.getCell(13)?.asString() ?: "",
                    pinfl = row.getCell(14)?.asString() ?: "",
                    vinNumber = row.getCell(15)?.asString() ?: "",
                    engineNumber = row.getCell(16)?.asString() ?: "",
                    scooterSerialNumber = row.getCell(17)?.asString() ?: "",
                    batteryId1 = row.getCell(18)?.asString() ?: "",
                    batteryId2 = row.getCell(19)?.asString() ?: "",
                    additionalInfo = row.getCell(20)?.asString() ?: "",
                    isPaid = row.getCell(21)?.asBoolean() ?: false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip contract row: ${e.message}")
                null
            }
        }
    }

    private fun readTransactions(sheet: Sheet): List<Transaction> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                Transaction(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    contractId = row.getCell(1)?.asNumber()?.toInt(),
                    renterId = row.getCell(2)?.asNumber()?.toInt() ?: 0,
                    scooterId = row.getCell(3)?.asNumber()?.toInt(),
                    timestamp = row.getCell(4)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                    type = row.getCell(5)?.asString() ?: Transaction.TYPE_PAYMENT,
                    amount = row.getCell(6)?.asNumber()?.toDouble() ?: 0.0,
                    notes = row.getCell(7)?.asString(),
                    renterName = row.getCell(8)?.asString() ?: "",
                    renterPhone = row.getCell(9)?.asString() ?: "",
                    scooterName = row.getCell(10)?.asString() ?: "",
                    contractLabel = row.getCell(11)?.asString() ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip transaction row: ${e.message}")
                null
            }
        }
    }

    private fun readVirtualCards(sheet: Sheet): List<VirtualCard> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                VirtualCard(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    name = row.getCell(1)?.asString() ?: "",
                    balance = row.getCell(2)?.asNumber()?.toDouble() ?: 0.0,
                    colorHex = row.getCell(3)?.asString() ?: "#FF6B6B",
                    info = row.getCell(4)?.asString(),
                    isDefault = row.getCell(5)?.asBoolean() ?: false,
                    kind = row.getCell(6)?.asString() ?: VirtualCard.KIND_REGULAR,
                    createdAt = row.getCell(7)?.asNumber()?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip virtual card row: ${e.message}")
                null
            }
        }
    }

    private fun readCardTransactions(sheet: Sheet): List<CardTransaction> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                CardTransaction(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    timestamp = row.getCell(1)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                    fromCardId = row.getCell(2)?.asNumber()?.toInt() ?: 0,
                    toCardId = row.getCell(3)?.asNumber()?.toInt() ?: 0,
                    amount = row.getCell(4)?.asNumber()?.toDouble() ?: 0.0,
                    note = row.getCell(5)?.asString(),
                    type = row.getCell(6)?.asString() ?: CardTransaction.TYPE_CARD_TRANSFER
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip card tx row: ${e.message}")
                null
            }
        }
    }

    private fun readNotifications(sheet: Sheet): List<NotificationHistoryEntity> {
        val rows = sheet.read()
        if (rows.size <= 1) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            try {
                NotificationHistoryEntity(
                    id = row.getCell(0)?.asNumber()?.toInt() ?: 0,
                    timestamp = row.getCell(1)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                    renterId = row.getCell(2)?.asNumber()?.toInt(),
                    title = row.getCell(3)?.asString() ?: "",
                    message = row.getCell(4)?.asString() ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip notification row: ${e.message}")
                null
            }
        }
    }
}
