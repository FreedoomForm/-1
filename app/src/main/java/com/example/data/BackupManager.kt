package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
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
    private const val SHEET_BUSINESS_OPERATIONS = "BusinessOperations"
    private const val SHEET_RENT_PERIODS = "RentPeriods"
    private const val SHEET_PAYMENT_ALLOCATIONS = "PaymentAllocations"
    private const val SHEET_AUDIT_EVENTS = "AuditEvents"
    private const val SHEET_APP_USERS = "AppUsers"
    private const val SHEET_METADATA = "Metadata"
    private const val SHEET_SMS_DELIVERIES = "SmsDeliveries"
    private const val SHEET_HANDOVER_ACTS = "HandoverActs"
    private const val SHEET_REPAIR_ORDERS = "RepairOrders"
    private const val SHEET_LEGACY_MONEY = "LegacyMoneyAmounts"
    private const val SHEET_DELETED_ITEMS = "DeletedItems"
    private const val SHEET_TIMELINE_BRANCHES = "TimelineBranches"
    private const val SHEET_TIMELINE_EVENTS = "TimelineEvents"
    private const val SHEET_TIMELINE_SNAPSHOTS = "TimelineSnapshots"
    private const val BACKUP_SCHEMA_VERSION = 3

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
            val businessOperations = db.businessOperationDao().getAllOnce()
            val rentPeriods = db.rentPeriodDao().getAllOnce()
            val paymentAllocations = db.paymentAllocationDao().getAllOnce()
            val auditEvents = db.auditEventDao().getAllOnce()
            val appUsers = db.appUserDao().getAllOnce()
            val smsDeliveries = db.smsDeliveryDao().allOnce()
            // §Batch 4: previously these 7 tables were silently dropped by
            // backup — handover acts, repair orders, exact-Long money mirrors,
            // the recycle bin, and the entire timeline tree. Each one holds
            // data the user actively entered and expects to survive restore.
            val handoverActs = db.handoverActDao().getAllOnce()
            val repairOrders = db.repairOrderDao().getAllOnce()
            val legacyMoney = db.legacyMoneyAmountDao().getAllOnce()
            val deletedItems = db.deletedItemDao().getAllOnce()
            val timelineBranches = db.timelineDao().getAllBranchesOnce()
            val timelineEvents = db.timelineDao().getAllEventsOnce()
            val timelineSnapshots = db.timelineDao().getAllSnapshotsOnce()

            // ── Двухфазная запись: сначала в temp-файл, потом копирование в SAF ──
            //
            // ПРЯМАЯ запись FastExcel в SAF OutputStream на Android 11+ часто
            // даёт 0-байтный файл. Причина: FastExcel внутри использует
            // java.util.zip.ZipOutputStream, который буферизует ВСЁ в памяти
            // до вызова close(). Когда close() дойдёт до SAF-провайдера,
            // bytes иногда не коммитятся на диск (особенность реализации
            // ContentProvider для ACTION_CREATE_DOCUMENT на некоторых
            // прошивках — MIUI, One UI, ColorOS).
            //
            // Решение: пишем .xlsx во временный файл в cacheDir (обычный
            // FileOutputStream, всегда работает), затем копируем bytes 1:1
            // в SAF OutputStream. После close() SAF-стрима файл становится
            // валидным .xlsx.
            val tempFile = java.io.File(context.cacheDir, "export_tmp_${System.currentTimeMillis()}.xlsx")
            try {
                // ── Фаза 1: пишем в tempFile через обычный FileOutputStream ──
                //
                // ⚠ ВАЖНО: FastExcel 0.18.4 Workbook.finish() НЕ закрывает
                //   и НЕ flush'ит переданный OutputStream! finish() только
                //   flush'ит внутренний OutputStreamWriter — байты доходят
                //   до BufferedOutputStream, но остаются в его 8КБ-буфере
                //   и на диск не попадают. Поэтому после finish() ОБЯЗАТЕЛЬНО
                //   нужно явно flush()+close() обёртки — иначе tempFile будет
                //   0 байт и в SAF скопируется пустота.
                //
                //   Предыдущая версия этого кода полагалась на комментарий
                //   «finish() сам всё закроет» — это было НЕВЕРНО, и именно
                //   поэтому экспорт выдавал 0-байтный файл.
                val fos = java.io.FileOutputStream(tempFile)
                val buf = java.io.BufferedOutputStream(fos, 8192)
                try {
                    val wb = Workbook(buf, "ScooterRent", "1.0")
                    writeBackupMetadata(
                        wb, renters.size, scooters.size, contracts.size, transactions.size,
                        cards.size, cardTx.size, businessOperations.size, rentPeriods.size, paymentAllocations.size
                    )
                    writeRenters(wb, renters)
                    writeScooters(wb, scooters)
                    writeContracts(wb, contracts)
                    writeTransactions(wb, transactions)
                    writeVirtualCards(wb, cards)
                    writeCardTransactions(wb, cardTx)
                    writeNotifications(wb, notifications)
                    writeBusinessOperations(wb, businessOperations)
                    writeRentPeriods(wb, rentPeriods)
                    writePaymentAllocations(wb, paymentAllocations)
                    writeAuditEvents(wb, auditEvents)
                    writeAppUsers(wb, appUsers)
                    writeSmsDeliveries(wb, smsDeliveries)
                    writeHandoverActs(wb, handoverActs)
                    writeRepairOrders(wb, repairOrders)
                    writeLegacyMoneyAmounts(wb, legacyMoney)
                    writeDeletedItems(wb, deletedItems)
                    writeTimelineBranches(wb, timelineBranches)
                    writeTimelineEvents(wb, timelineEvents)
                    writeTimelineSnapshots(wb, timelineSnapshots)
                    wb.finish()
                    // ⚠ КРИТИЧНО: flush буфера в FileOutputStream, иначе
                    // байты останутся в памяти и tempFile будет 0 байт.
                    buf.flush()
                    fos.fd.sync()  // дополнительно — fsync на диск
                } finally {
                    // close() гарантированно flush'ит буфер повторно и
                    // освобождает fd даже при исключении в finish().
                    try { buf.close() } catch (_: Throwable) {}
                }

                // Проверка: tempFile должен быть не пустой. Если пустой —
                // что-то не так с FastExcel (например, все таблицы пустые
                // и finish() не записал central directory).
                if (tempFile.length() == 0L) {
                    return "Xato: eksport bo'sh (ma'lumotlar bazasi bo'sh yoki FastExcel xatosi)"
                }

                // ── Фаза 2: копируем tempFile → SAF OutputStream ──
                val resolver = context.contentResolver
                val rawOutput: OutputStream = resolver.openOutputStream(uri, "w")
                    ?: return "Xato: fayl yaratilmadi (openOutputStream = null)"
                java.io.BufferedInputStream(java.io.FileInputStream(tempFile), 8192).use { input ->
                    java.io.BufferedOutputStream(rawOutput, 8192).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            } finally {
                // Удаляем temp-файл в любом случае.
                tempFile.delete()
            }

            val total = renters.size + scooters.size + contracts.size +
                transactions.size + cards.size + cardTx.size + notifications.size +
                handoverActs.size + repairOrders.size + legacyMoney.size +
                deletedItems.size + timelineBranches.size + timelineEvents.size + timelineSnapshots.size
            "Eksport tayyor: $total ta yozuv (${"${renters.size}r/${scooters.size}s/${contracts.size}c/${transactions.size}t/${cards.size}v/${cardTx.size}k/${notifications.size}n + ${handoverActs.size}h/${repairOrders.size}ro/${legacyMoney.size}lm/${deletedItems.size}di/${timelineBranches.size}tb/${timelineEvents.size}te/${timelineSnapshots.size}ts"})"
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            "Xato: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** First worksheet: enables safe forward-compatibility checks on import. */
    private fun writeBackupMetadata(
        wb: Workbook, renters: Int, scooters: Int, contracts: Int, transactions: Int,
        cards: Int, cardTransactions: Int, operations: Int, periods: Int, allocations: Int
    ) {
        val ws = wb.newWorksheet(SHEET_METADATA)
        val headers = listOf(
            "schemaVersion", "exportedAt", "appDatabaseVersion", "renters", "scooters",
            "contracts", "transactions", "cards", "cardTransactions", "operations", "periods", "allocations"
        )
        headers.forEachIndexed { i, value -> ws.value(0, i, value) }
        val values = listOf(BACKUP_SCHEMA_VERSION.toLong(), System.currentTimeMillis(), 33L, renters.toLong(), scooters.toLong(),
            contracts.toLong(), transactions.toLong(), cards.toLong(), cardTransactions.toLong(), operations.toLong(), periods.toLong(), allocations.toLong())
        values.forEachIndexed { i, value -> ws.value(1, i, value) }
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
        // Headers include the 4 lifecycle/service fields added after the
        // initial backup schema. Older backups (pre-Batch 4) lack these
        // columns — readers fall back to defaults when cells are null.
        val headers = listOf(
            "id", "name", "documentedNumber", "vinNumber", "engineNumber",
            "scooterSerialNumber", "batteryId1", "batteryId2", "additionalInfo",
            "lifecycleStatus", "lastServiceAt", "nextServiceAt", "mileageKm"
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
            ws.value(r, 9, s.lifecycleStatus)
            s.lastServiceAt?.let { ws.value(r, 10, it) }
            s.nextServiceAt?.let { ws.value(r, 11, it) }
            ws.value(r, 12, s.mileageKm)
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
            "kind", "isArchived", "createdAt"
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
            ws.value(r, 7, c.isArchived)
            ws.value(r, 8, c.createdAt)
        }
    }

    private fun writeCardTransactions(wb: Workbook, items: List<CardTransaction>) {
        val ws = wb.newWorksheet(SHEET_CARD_TX)
        val headers = listOf(
            "id", "timestamp", "fromCardId", "toCardId", "amount", "note", "type",
            "contractId"
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
            t.contractId?.let { ws.value(r, 7, it) }
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

    private fun writeBusinessOperations(wb: Workbook, items: List<BusinessOperation>) {
        val ws = wb.newWorksheet(SHEET_BUSINESS_OPERATIONS)
        val h = listOf("id","occurredAt","type","direction","amountMinor","renterId","scooterId","contractId","fromCardId","toCardId","cardTransactionId","legacyTransactionId","note","status","reversesOperationId","createdAt")
        h.forEachIndexed { i, v -> ws.value(0, i, v) }
        items.forEachIndexed { index, o ->
            val r = index + 1
            ws.value(r,0,o.id); ws.value(r,1,o.occurredAt); ws.value(r,2,o.type); ws.value(r,3,o.direction); ws.value(r,4,o.amountMinor)
            o.renterId?.let { ws.value(r,5,it) }; o.scooterId?.let { ws.value(r,6,it) }; o.contractId?.let { ws.value(r,7,it) }
            o.fromCardId?.let { ws.value(r,8,it) }; o.toCardId?.let { ws.value(r,9,it) }; o.cardTransactionId?.let { ws.value(r,10,it) }
            o.legacyTransactionId?.let { ws.value(r,11,it) }; o.note?.let { ws.value(r,12,it) }; ws.value(r,13,o.status)
            o.reversesOperationId?.let { ws.value(r,14,it) }; ws.value(r,15,o.createdAt)
        }
    }

    private fun writeRentPeriods(wb: Workbook, items: List<RentPeriod>) {
        val ws = wb.newWorksheet(SHEET_RENT_PERIODS)
        val h = listOf("id","contractHistoryId","renterId","scooterId","startsAt","endsAt","chargeMinor","paidMinor","status","suspendedAt","suspensionReason","createdAt","updatedAt")
        h.forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,p ->
            val r=index+1; ws.value(r,0,p.id); p.contractHistoryId?.let { ws.value(r,1,it) }; ws.value(r,2,p.renterId); p.scooterId?.let { ws.value(r,3,it) }
            ws.value(r,4,p.startsAt); ws.value(r,5,p.endsAt); ws.value(r,6,p.chargeMinor); ws.value(r,7,p.paidMinor); ws.value(r,8,p.status); p.suspendedAt?.let { ws.value(r,9,it) }; p.suspensionReason?.let { ws.value(r,10,it) }; ws.value(r,11,p.createdAt); ws.value(r,12,p.updatedAt)
        }
    }

    private fun writePaymentAllocations(wb: Workbook, items: List<PaymentAllocationEntity>) {
        val ws = wb.newWorksheet(SHEET_PAYMENT_ALLOCATIONS)
        listOf("id","operationId","rentPeriodId","amountMinor","createdAt").forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,a -> val r=index+1; ws.value(r,0,a.id); ws.value(r,1,a.operationId); ws.value(r,2,a.rentPeriodId); ws.value(r,3,a.amountMinor); ws.value(r,4,a.createdAt) }
    }

    private fun writeAuditEvents(wb: Workbook, items: List<AuditEvent>) {
        val ws = wb.newWorksheet(SHEET_AUDIT_EVENTS)
        listOf("id","occurredAt","actor","action","entityType","entityId","reason","beforeSnapshot","afterSnapshot").forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,e -> val r=index+1; ws.value(r,0,e.id); ws.value(r,1,e.occurredAt); ws.value(r,2,e.actor); ws.value(r,3,e.action); ws.value(r,4,e.entityType); ws.value(r,5,e.entityId); e.reason?.let { ws.value(r,6,it) }; e.beforeSnapshot?.let { ws.value(r,7,it) }; e.afterSnapshot?.let { ws.value(r,8,it) } }
    }

    private fun writeSmsDeliveries(wb: Workbook, items: List<SmsDelivery>) {
        val ws = wb.newWorksheet(SHEET_SMS_DELIVERIES)
        listOf("id","renterId","timestamp","status","messagePreview","error").forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,d -> val r=index+1; ws.value(r,0,d.id); ws.value(r,1,d.renterId); ws.value(r,2,d.timestamp); ws.value(r,3,d.status); ws.value(r,4,d.messagePreview); d.error?.let { ws.value(r,5,it) } }
    }

    private fun writeAppUsers(wb: Workbook, items: List<AppUser>) {
        val ws = wb.newWorksheet(SHEET_APP_USERS)
        listOf("id","displayName","role","isActive","createdAt").forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,u -> val r=index+1; ws.value(r,0,u.id); ws.value(r,1,u.displayName); ws.value(r,2,u.role); ws.value(r,3,u.isActive); ws.value(r,4,u.createdAt) }
    }

    private fun writeHandoverActs(wb: Workbook, items: List<HandoverAct>) {
        val ws = wb.newWorksheet(SHEET_HANDOVER_ACTS)
        listOf("id","timestamp","actType","renterId","scooterId","contractHistoryId","mileageKm","equipmentChecklist","conditionNotes","signedBy")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,a -> val r=index+1
            ws.value(r,0,a.id); ws.value(r,1,a.timestamp); ws.value(r,2,a.actType); ws.value(r,3,a.renterId); ws.value(r,4,a.scooterId)
            a.contractHistoryId?.let { ws.value(r,5,it) }
            ws.value(r,6,a.mileageKm); ws.value(r,7,a.equipmentChecklist); ws.value(r,8,a.conditionNotes); ws.value(r,9,a.signedBy)
        }
    }

    private fun writeRepairOrders(wb: Workbook, items: List<RepairOrder>) {
        val ws = wb.newWorksheet(SHEET_REPAIR_ORDERS)
        listOf("id","scooterId","renterId","scenario","status","openedAt","closedAt","diagnosis","performer","partsUsed","estimatedMinor","actualMinor","documentNote","pauseIntervalsJson","totalPauseMs","currentlyPaused","lastPausedAt")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,o -> val r=index+1
            ws.value(r,0,o.id); ws.value(r,1,o.scooterId); o.renterId?.let { ws.value(r,2,it) }
            ws.value(r,3,o.scenario); ws.value(r,4,o.status); ws.value(r,5,o.openedAt); o.closedAt?.let { ws.value(r,6,it) }
            ws.value(r,7,o.diagnosis); o.performer?.let { ws.value(r,8,it) }; o.partsUsed?.let { ws.value(r,9,it) }
            ws.value(r,10,o.estimatedMinor); ws.value(r,11,o.actualMinor); o.documentNote?.let { ws.value(r,12,it) }
            ws.value(r,13,o.pauseIntervalsJson); ws.value(r,14,o.totalPauseMs); ws.value(r,15,o.currentlyPaused); o.lastPausedAt?.let { ws.value(r,16,it) }
        }
    }

    private fun writeLegacyMoneyAmounts(wb: Workbook, items: List<LegacyMoneyAmount>) {
        val ws = wb.newWorksheet(SHEET_LEGACY_MONEY)
        listOf("id","entityType","entityId","field","amountMinor","migratedAt")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,m -> val r=index+1
            ws.value(r,0,m.id); ws.value(r,1,m.entityType); ws.value(r,2,m.entityId); ws.value(r,3,m.field)
            ws.value(r,4,m.amountMinor); ws.value(r,5,m.migratedAt)
        }
    }

    private fun writeDeletedItems(wb: Workbook, items: List<DeletedItem>) {
        val ws = wb.newWorksheet(SHEET_DELETED_ITEMS)
        listOf("id","sourceType","sourceId","title","snapshotJson","deletedAt","deletedBy","reason")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,d -> val r=index+1
            ws.value(r,0,d.id); ws.value(r,1,d.sourceType); ws.value(r,2,d.sourceId); ws.value(r,3,d.title)
            ws.value(r,4,d.snapshotJson); ws.value(r,5,d.deletedAt); ws.value(r,6,d.deletedBy); d.reason?.let { ws.value(r,7,it) }
        }
    }

    private fun writeTimelineBranches(wb: Workbook, items: List<TimelineBranch>) {
        val ws = wb.newWorksheet(SHEET_TIMELINE_BRANCHES)
        listOf("id","name","parentBranchId","forkEventId","createdAt","isMain")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,b -> val r=index+1
            ws.value(r,0,b.id); ws.value(r,1,b.name); b.parentBranchId?.let { ws.value(r,2,it) }
            b.forkEventId?.let { ws.value(r,3,it) }; ws.value(r,4,b.createdAt); ws.value(r,5,b.isMain)
        }
    }

    private fun writeTimelineEvents(wb: Workbook, items: List<TimelineEvent>) {
        val ws = wb.newWorksheet(SHEET_TIMELINE_EVENTS)
        listOf("id","branchId","timestamp","actionType","screen","entityType","entityId","title","payloadJson","isMajor","isArchived")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,e -> val r=index+1
            ws.value(r,0,e.id); ws.value(r,1,e.branchId); ws.value(r,2,e.timestamp); ws.value(r,3,e.actionType)
            ws.value(r,4,e.screen); e.entityType?.let { ws.value(r,5,it) }; e.entityId?.let { ws.value(r,6,it) }
            ws.value(r,7,e.title); ws.value(r,8,e.payloadJson); ws.value(r,9,e.isMajor); ws.value(r,10,e.isArchived)
        }
    }

    private fun writeTimelineSnapshots(wb: Workbook, items: List<TimelineSnapshot>) {
        val ws = wb.newWorksheet(SHEET_TIMELINE_SNAPSHOTS)
        listOf("id","branchId","eventId","timestamp","stateJson")
            .forEachIndexed { i,v -> ws.value(0,i,v) }
        items.forEachIndexed { index,s -> val r=index+1
            ws.value(r,0,s.id); ws.value(r,1,s.branchId); s.eventId?.let { ws.value(r,2,it) }
            ws.value(r,3,s.timestamp); ws.value(r,4,s.stateJson)
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

            // Batch 9 (was BLOCKER A1): wrap the ENTIRE truncate + parse +
            // insert sequence in a single db.withTransaction. Previously a
            // mid-import failure (malformed xlsx row, OOM, disk full, parser
            // exception) left the DB in an inconsistent state: some tables
            // fully truncated, others partially populated, foreign keys
            // pointing to nothing. The user lost all existing data AND got
            // a half-imported set. Now any exception rolls back the entire
            // import — the DB is left in its pre-import state.
            //
            // We deliberately read the .xlsx inside the transaction too:
            // parsing happens lazily during sheet.read(), and if the parser
            // hits a malformed cell mid-way, the rollback still covers all
            // writes done up to that point.
            db.withTransaction {
                _importFromExcelInternal(context, db, input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            "Xato: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun _importFromExcelInternal(
        context: Context,
        db: AppDatabase,
        input: java.io.InputStream
    ): String {

            // Считаем статистику для отчёта
            var rentersCount = 0
            var scootersCount = 0
            var contractsCount = 0
            var transactionsCount = 0
            var cardsCount = 0
            var cardTxCount = 0
            var notifCount = 0
            // Batch 7: extra warnings collected during import (e.g. stub-card
            // recovery notice). Merged into the final summary string so the
            // user is informed of any non-fatal data-loss prevention steps.
            val importExtraWarnings = mutableListOf<String>()
            // Hoisted out of the inner ReadableWorkbook scope so the result-
            // string builder below can detect a missing/empty VirtualCards
            // sheet and surface a warning instead of silently reporting
            // cardsCount = 0.
            var hadCardsSheet = false

            input.use { stream ->
                ReadableWorkbook(stream).use { wb ->
                    // Считываем все листы один раз и кладём в Map по имени.
                    val sheetMap = mutableMapOf<String, Sheet>()
                    wb.sheets.forEach { sh -> sheetMap[sh.name] = sh }
                    val backupMetadata = sheetMap[SHEET_METADATA]?.let { metadata ->
                        val values = readBackupMetadata(metadata)
                        val version = values["schemaVersion"]?.toInt() ?: 1
                        require(version <= BACKUP_SCHEMA_VERSION) {
                            "Backup schema v$version is newer than this app supports (v$BACKUP_SCHEMA_VERSION)"
                        }
                        values
                    } ?: emptyMap()

                    // ── Порядок импорта: сначала независимые таблицы ──────
                    // (Scooters, VirtualCards, Renters), потом зависимые
                    // (Contracts, Transactions, CardTx, Notifications).

                    // 1) Очистка всех таблиц
                    db.notificationHistoryDao().deleteAll()
                    db.auditEventDao().clear()
                    db.smsDeliveryDao().clear()
                    db.appUserDao().clear()
                    db.paymentAllocationDao().clear()
                    db.rentPeriodDao().clear()
                    db.businessOperationDao().clear()
                    db.cardTransactionDao().deleteAll()
                    db.transactionDao().clear()
                    db.contractHistoryDao().deleteAll()
                    db.renterDao().deleteAll()
                    db.scooterDao().deleteAll()
                    db.virtualCardDao().deleteAll()
                    // §Batch 4: also truncate the 7 tables that were previously
                    // not exported — without this, restore would mix imported
                    // rows with stale local rows from before the backup.
                    db.handoverActDao().clear()
                    db.repairOrderDao().deleteAll()
                    db.legacyMoneyAmountDao().deleteAll()
                    db.deletedItemDao().deleteAll()
                    db.timelineDao().deleteAllSnapshots()
                    db.timelineDao().deleteAllEvents()
                    db.timelineDao().deleteAllBranches()

                    // 2) Scooters
                    sheetMap[SHEET_SCOOTERS]?.let { sh ->
                        val list = readScooters(sh)
                        list.forEach { db.scooterDao().insertScooter(it) }
                        scootersCount = list.size
                    }

                    // 3) VirtualCards
                    hadCardsSheet = sheetMap.containsKey(SHEET_VIRTUAL_CARDS)
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

                    // New backups preserve the exact journal/allocation graph.
                    // Older files are rebuilt from their legacy projections.
                    val hasNativeLedger = sheetMap.containsKey(SHEET_BUSINESS_OPERATIONS)
                    if (hasNativeLedger) {
                        sheetMap[SHEET_RENT_PERIODS]?.let { sh ->
                            readRentPeriods(sh).forEach { db.rentPeriodDao().insert(it) }
                        }
                        sheetMap[SHEET_BUSINESS_OPERATIONS]?.let { sh ->
                            readBusinessOperations(sh).forEach { db.businessOperationDao().insert(it) }
                        }
                        sheetMap[SHEET_PAYMENT_ALLOCATIONS]?.let { sh ->
                            readPaymentAllocations(sh).forEach { db.paymentAllocationDao().insert(it) }
                        }
                        sheetMap[SHEET_AUDIT_EVENTS]?.let { sh ->
                            readAuditEvents(sh).forEach { db.auditEventDao().insert(it) }
                        }
                        sheetMap[SHEET_APP_USERS]?.let { sh ->
                            readAppUsers(sh).forEach { db.appUserDao().insert(it) }
                        }
                        sheetMap[SHEET_SMS_DELIVERIES]?.let { sh ->
                            readSmsDeliveries(sh).forEach { db.smsDeliveryDao().insert(it) }
                        }
                        // §Batch 4: restore the 7 previously-dropped tables.
                        // Order matters for FK sanity: handover acts and
                        // repair orders reference renters/scooters/contracts
                        // (already imported above). Timeline branches →
                        // events → snapshots (events reference branches,
                        // snapshots reference events).
                        sheetMap[SHEET_HANDOVER_ACTS]?.let { sh ->
                            readHandoverActs(sh).forEach { db.handoverActDao().insert(it) }
                        }
                        sheetMap[SHEET_REPAIR_ORDERS]?.let { sh ->
                            readRepairOrders(sh).forEach { db.repairOrderDao().insert(it) }
                        }
                        sheetMap[SHEET_LEGACY_MONEY]?.let { sh ->
                            readLegacyMoneyAmounts(sh).forEach { db.legacyMoneyAmountDao().insert(it) }
                        }
                        sheetMap[SHEET_DELETED_ITEMS]?.let { sh ->
                            readDeletedItems(sh).forEach { db.deletedItemDao().insert(it) }
                        }
                        sheetMap[SHEET_TIMELINE_BRANCHES]?.let { sh ->
                            readTimelineBranches(sh).forEach { db.timelineDao().insertBranch(it) }
                        }
                        sheetMap[SHEET_TIMELINE_EVENTS]?.let { sh ->
                            readTimelineEvents(sh).forEach { db.timelineDao().insertEvent(it) }
                        }
                        sheetMap[SHEET_TIMELINE_SNAPSHOTS]?.let { sh ->
                            readTimelineSnapshots(sh).forEach { db.timelineDao().insertSnapshot(it) }
                        }
                    } else {
                        LegacyProjectionRebuilder.rebuild(db)
                    }
                    // Legacy backups predate local roles; always leave a
                    // usable owner account after restore.
                    if (db.appUserDao().first() == null) {
                        db.appUserDao().insert(AppUser(id = 1, displayName = "Owner", role = AppUser.ROLE_OWNER))
                    }
                    // A versioned backup must restore exactly the rows it says
                    // it contains; mismatches are surfaced instead of silently
                    // accepting a truncated export.
                    fun verifyCount(key: String, actual: Int) {
                        backupMetadata[key]?.let { expected ->
                            require(expected.toInt() == actual) { "Backup integrity mismatch for $key: expected $expected, restored $actual" }
                        }
                    }
                    verifyCount("renters", rentersCount)
                    verifyCount("scooters", scootersCount)
                    verifyCount("contracts", contractsCount)
                    verifyCount("transactions", transactionsCount)
                    verifyCount("cards", cardsCount)
                    verifyCount("cardTransactions", cardTxCount)

                    // Self-healing системных карт после импорта.
                    //
                    // Если в импортируемом .xlsx-файле не было листа VirtualCards
                    // (или лист был пуст), таблица virtual_cards останется пустой
                    // после deleteAll на шаге 1. Без системных карт (Glavnaya,
                    // Vtorostepennaya, Tashqidan, Tashqiga) приложение теряет
                    // ключевую функциональность: оплаты контрактов некуда зачислять,
                    // внешние переводы невозможны.
                    //
                    // Выполняем ТОТ ЖЕ INSERT OR IGNORE, что и в AppDatabase.onOpen,
                    // через openHelper — это даёт прямой доступ к SupportSQLiteDatabase.
                    // INSERT OR IGNORE безопасен для бэкапов, в которых системные
                    // карты уже есть: существующие записи (с пользовательскими
                    // балансами) не трогаются.
                    db.openHelper.writableDatabase.execSQL("""
                        INSERT OR IGNORE INTO `virtual_cards`
                            (id, name, balance, colorHex, info, isDefault, createdAt, kind)
                        VALUES
                            (1, 'Glavnaya', 0.0, '#FF1565C0', 'Asosiy kassa — contract to''lovlari shu yerga tushadi', 1, strftime('%s','now') * 1000, 'REGULAR'),
                            (2, 'Vtorostepennaya', 0.0, '#FF2E7D32', 'Qo`shimcha karta', 1, strftime('%s','now') * 1000, 'REGULAR'),
                            (3, 'Tashqidan', 0.0, '#FF00838F', 'Tashqidan kirgan pul (bank, naqd va h.k.)', 1, strftime('%s','now') * 1000, 'EXTERNAL_IN'),
                            (4, 'Tashqiga',  0.0, '#FFC62828', 'Tashqiga chiqarilgan pul (yechib olish, to''lovlar)', 1, strftime('%s','now') * 1000, 'EXTERNAL_OUT')
                    """.trimIndent())

                    // Batch 7 (fixes HIGH-1 / deferred FINDING G): stub-card
                    // recovery. When the VirtualCards sheet is missing/empty,
                    // only the 4 system cards above exist after self-healing.
                    // But the imported CardTransactions and BusinessOperations
                    // sheets may reference OTHER card ids (user-created cards
                    // from the original DB that weren't included in the backup).
                    //
                    // Without stub recovery, those CardTransactions and
                    // BusinessOperations silently reference non-existent card
                    // ids — CashFlowWidget, MainCardIncomeWidget, and any
                    // per-card report filter them out, silently losing income
                    // or expense data.
                    //
                    // We can't restore the user's original card names/colors
                    // (that metadata was only in the missing VirtualCards
                    // sheet), but we CAN create placeholder stubs so the FK
                    // references resolve. The user can manually rename/recolor
                    // the stubs in the Finansi screen after import.
                    //
                    // Only run if the VirtualCards sheet was missing/empty —
                    // otherwise the sheet already contains all cards and there
                    // are no missing ids to stub.
                    if (!hadCardsSheet || cardsCount == 0) {
                        try {
                            val existingCardIds = db.virtualCardDao().getAllCardsOnce().map { it.id }.toSet()
                            // Scan CardTransactions and BusinessOperations
                            // for referenced card ids not in existingCardIds.
                            val missingIds = mutableSetOf<Int>()
                            db.cardTransactionDao().getAllOnce().forEach { ctx ->
                                if (ctx.fromCardId != 0 && ctx.fromCardId !in existingCardIds) {
                                    missingIds.add(ctx.fromCardId)
                                }
                                if (ctx.toCardId != 0 && ctx.toCardId !in existingCardIds) {
                                    missingIds.add(ctx.toCardId)
                                }
                            }
                            try {
                                db.businessOperationDao().getAllOnce().forEach { bo ->
                                    bo.fromCardId?.let { if (it !in existingCardIds) missingIds.add(it) }
                                    bo.toCardId?.let { if (it !in existingCardIds) missingIds.add(it) }
                                }
                            } catch (_: Exception) {
                                // businessOperations sheet may not exist in
                                // older backups — skip silently.
                            }
                            // Insert a stub card for each missing id. Use
                            // INSERT OR IGNORE so we don't fail if a stub
                            // already exists from a previous import attempt.
                            // kind=REGULAR so the card appears in the user-
                            // visible card list (the user can rename/recolor).
                            // colorHex=#FF888888 (neutral grey) signals "stub".
                            // balance=0 — we can't reconstruct the original
                            // balance, so we start at 0 and let the imported
                            // CardTransactions naturally recompute it via the
                            // cash-flow ledger (or the user can manually adjust).
                            val now = System.currentTimeMillis()
                            missingIds.sorted().forEach { id ->
                                db.openHelper.writableDatabase.execSQL(
                                    """INSERT OR IGNORE INTO `virtual_cards`
                                       (id, name, balance, colorHex, info, isDefault, createdAt, kind)
                                       VALUES (?, ?, 0.0, '#FF888888', ?, 0, ?, 'REGULAR')""",
                                    arrayOf<Any>(
                                        id,
                                        "Restored Card #$id",
                                        "Auto-restored stub from imported transactions (original VirtualCards sheet missing)",
                                        now
                                    )
                                )
                            }
                            // Surface the stubbed ids in the import result
                            // so the user knows to rename/recolor them.
                            if (missingIds.isNotEmpty()) {
                                importExtraWarnings.add(
                                    "Restored ${missingIds.size} ta stub karta (id=${missingIds.sorted().joinToString(",")}) — iltimos nomlarini/ranglarini Finansi ekranida o'zgartiring"
                                )
                            }
                        } catch (_: Exception) {
                            // Best-effort: don't fail import on stub-recovery error.
                        }
                    }
                }
            }

            val total = rentersCount + scootersCount + contractsCount +
                transactionsCount + cardsCount + cardTxCount + notifCount
            // Build a detailed result string that surfaces data-loss risks
            // instead of silently reporting a number. Three specific gaps
            // are detected:
            //   1) VirtualCards sheet missing or empty — only the 4 system
            //      cards are restored by the self-healing step below; any
            //      user-created cards are silently lost.
            //   2) CardTransactions referencing non-existent card ids —
            //      those rows dangle against an empty virtual_cards table
            //      and silently disappear from CashFlowWidget / MainCardIncomeWidget.
            //   3) Full per-table counts so the user can verify the import.
            val warnings = mutableListOf<String>()
            if (!hadCardsSheet || cardsCount == 0) {
                // Batch 7: previously this branch said "user cards were
                // not restored, only 4 system cards added". With stub-card
                // recovery now in place, user cards referenced by imported
                // CardTransactions/BusinessOperations ARE recovered as
                // placeholder stubs. The user just needs to rename/recolor
                // them. The detailed stub list is appended below from
                // importExtraWarnings.
                warnings.add("Diqqat: VirtualCards varaqi topilmadi — foydalanuvchi kartalari avtomatik stub sifatida tiklandi (nomlarini Finansi ekranida o'zgartiring)")
            }
            // Merge in any extra warnings collected during import (e.g.
            // the list of stub card ids that were auto-created).
            warnings.addAll(importExtraWarnings)
            // After import + self-heal, scan CardTransactions for stale
            // cardId references. Cheap: one query against virtual_cards
            // to fetch all valid ids, then iterate the imported CardTx
            // rows in memory.
            try {
                val validCardIds = db.virtualCardDao().getAllCardsOnce().map { it.id }.toSet()
                var staleCount = 0
                // Re-read the imported CardTransactions from DB (not the
                // sheet — the sheet's rows have already been consumed).
                val importedCardTx = db.cardTransactionDao().getAllOnce()
                importedCardTx.forEach { ctx ->
                    if ((ctx.fromCardId != 0 && ctx.fromCardId !in validCardIds) ||
                        ctx.toCardId !in validCardIds) {
                        staleCount++
                    }
                }
                if (staleCount > 0) {
                    warnings.add("Diqqat: $staleCount ta CardTransaction mavjud bo'lmagan kartaga ishora qilmoqda — ular card-cash-flow hisobotlarida ko'rinmaydi")
                }
            } catch (_: Exception) {
                // Best-effort diagnostic; don't fail import on diagnostic error.
            }
            val summary = buildString {
                append("Import tayyor: $total ta yozuv qo'shildi")
                append(" (renters=$rentersCount, scooters=$scootersCount, contracts=$contractsCount")
                append(", tx=$transactionsCount, cards=$cardsCount, cardTx=$cardTxCount, notif=$notifCount)")
                if (warnings.isNotEmpty()) {
                    append(" | ")
                    append(warnings.joinToString("; "))
                }
            }
            return summary
        // No outer try/catch here — the wrapper importFromExcel() handles
        // all exceptions and converts them to a user-facing error string.
        // Reaching this point means the import succeeded inside the
        // transaction and the result string is returned to the caller.
    }

    /* ── Парсеры листов ─────────────────────────────────────────────────── */

    private fun readBackupMetadata(sheet: Sheet): Map<String, Long> {
        val rows = sheet.read()
        val headers = rows.getOrNull(0) ?: return emptyMap()
        val values = rows.getOrNull(1) ?: return emptyMap()
        return headers.mapIndexedNotNull { index, cell ->
            cell.asString()?.let { key -> values.getCell(index)?.asNumber()?.toLong()?.let { key to it } }
        }.toMap()
    }

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
                    additionalInfo = row.getCell(8)?.asString() ?: "",
                    // Cols 9-12 were added in Batch 4. Older .xlsx files have
                    // only 9 columns — these cells will be null and defaults
                    // kick in (AVAILABLE / null / null / 0).
                    lifecycleStatus = row.getCell(9)?.asString() ?: Scooter.STATUS_AVAILABLE,
                    lastServiceAt = row.getCell(10)?.asNumber()?.toLong(),
                    nextServiceAt = row.getCell(11)?.asNumber()?.toLong(),
                    mileageKm = row.getCell(12)?.asNumber()?.toLong() ?: 0L
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
                    // Old backups have no isArchived column: null means false.
                    isArchived = row.getCell(7)?.asBoolean() ?: false,
                    createdAt = row.getCell(8)?.asNumber()?.toLong()
                        ?: row.getCell(7)?.asNumber()?.toLong()
                        ?: System.currentTimeMillis()
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
                    type = row.getCell(6)?.asString() ?: CardTransaction.TYPE_CARD_TRANSFER,
                    // contractId — опциональное поле (добавлено в миграции 14→15).
                    // Старые .xlsx без этой колонки дадут null — это корректно.
                    contractId = row.getCell(7)?.asNumber()?.toInt()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skip card tx row: ${e.message}")
                null
            }
        }
    }

    private fun readBusinessOperations(sheet: Sheet): List<BusinessOperation> = sheet.read().drop(1).mapNotNull { row ->
        try { BusinessOperation(
            id=row.getCell(0)?.asNumber()?.toLong() ?: 0, occurredAt=row.getCell(1)?.asNumber()?.toLong() ?: 0,
            type=row.getCell(2)?.asString() ?: BusinessOperation.TYPE_ADJUSTMENT, direction=row.getCell(3)?.asString() ?: BusinessOperation.DIRECTION_TRANSFER,
            amountMinor=row.getCell(4)?.asNumber()?.toLong() ?: 0, renterId=row.getCell(5)?.asNumber()?.toInt(), scooterId=row.getCell(6)?.asNumber()?.toInt(),
            contractId=row.getCell(7)?.asNumber()?.toInt(), fromCardId=row.getCell(8)?.asNumber()?.toInt(), toCardId=row.getCell(9)?.asNumber()?.toInt(),
            cardTransactionId=row.getCell(10)?.asNumber()?.toInt(), legacyTransactionId=row.getCell(11)?.asNumber()?.toInt(), note=row.getCell(12)?.asString(),
            status=row.getCell(13)?.asString() ?: BusinessOperation.STATUS_ACTIVE, reversesOperationId=row.getCell(14)?.asNumber()?.toLong(), createdAt=row.getCell(15)?.asNumber()?.toLong() ?: System.currentTimeMillis()
        ) } catch (e: Exception) { Log.w(TAG,"Skip operation row: ${e.message}"); null }
    }

    private fun readRentPeriods(sheet: Sheet): List<RentPeriod> = sheet.read().drop(1).mapNotNull { row ->
        try { RentPeriod(
            id=row.getCell(0)?.asNumber()?.toLong() ?: 0, contractHistoryId=row.getCell(1)?.asNumber()?.toInt(), renterId=row.getCell(2)?.asNumber()?.toInt() ?: 0,
            scooterId=row.getCell(3)?.asNumber()?.toInt(), startsAt=row.getCell(4)?.asNumber()?.toLong() ?: 0, endsAt=row.getCell(5)?.asNumber()?.toLong() ?: 0,
            chargeMinor=row.getCell(6)?.asNumber()?.toLong() ?: 0, paidMinor=row.getCell(7)?.asNumber()?.toLong() ?: 0,
            status=row.getCell(8)?.asString() ?: RentPeriod.STATUS_ACTIVE, suspendedAt=row.getCell(9)?.asNumber()?.toLong(), suspensionReason=row.getCell(10)?.asString(),
            createdAt=row.getCell(11)?.asNumber()?.toLong() ?: row.getCell(9)?.asNumber()?.toLong() ?: System.currentTimeMillis(), updatedAt=row.getCell(12)?.asNumber()?.toLong() ?: row.getCell(10)?.asNumber()?.toLong() ?: System.currentTimeMillis()
        ) } catch (e: Exception) { Log.w(TAG,"Skip period row: ${e.message}"); null }
    }

    private fun readPaymentAllocations(sheet: Sheet): List<PaymentAllocationEntity> = sheet.read().drop(1).mapNotNull { row ->
        try { PaymentAllocationEntity(id=row.getCell(0)?.asNumber()?.toLong() ?: 0, operationId=row.getCell(1)?.asNumber()?.toLong() ?: 0, rentPeriodId=row.getCell(2)?.asNumber()?.toLong() ?: 0, amountMinor=row.getCell(3)?.asNumber()?.toLong() ?: 0, createdAt=row.getCell(4)?.asNumber()?.toLong() ?: System.currentTimeMillis()) }
        catch (e: Exception) { Log.w(TAG,"Skip allocation row: ${e.message}"); null }
    }

    private fun readAuditEvents(sheet: Sheet): List<AuditEvent> = sheet.read().drop(1).mapNotNull { row ->
        try { AuditEvent(id=row.getCell(0)?.asNumber()?.toLong() ?: 0, occurredAt=row.getCell(1)?.asNumber()?.toLong() ?: System.currentTimeMillis(), actor=row.getCell(2)?.asString() ?: "LOCAL_SYSTEM", action=row.getCell(3)?.asString() ?: "IMPORTED", entityType=row.getCell(4)?.asString() ?: "UNKNOWN", entityId=row.getCell(5)?.asString() ?: "", reason=row.getCell(6)?.asString(), beforeSnapshot=row.getCell(7)?.asString(), afterSnapshot=row.getCell(8)?.asString()) }
        catch (e: Exception) { Log.w(TAG,"Skip audit row: ${e.message}"); null }
    }

    private fun readSmsDeliveries(sheet: Sheet): List<SmsDelivery> = sheet.read().drop(1).mapNotNull { row ->
        try { SmsDelivery(id=row.getCell(0)?.asNumber()?.toLong() ?: 0, renterId=row.getCell(1)?.asNumber()?.toInt() ?: 0, timestamp=row.getCell(2)?.asNumber()?.toLong() ?: System.currentTimeMillis(), status=row.getCell(3)?.asString() ?: SmsDelivery.STATUS_FAILED, messagePreview=row.getCell(4)?.asString() ?: "", error=row.getCell(5)?.asString()) }
        catch (e: Exception) { Log.w(TAG,"Skip SMS delivery row: ${e.message}"); null }
    }

    private fun readAppUsers(sheet: Sheet): List<AppUser> = sheet.read().drop(1).mapNotNull { row ->
        try { AppUser(id=row.getCell(0)?.asNumber()?.toLong() ?: 0, displayName=row.getCell(1)?.asString() ?: "User", role=row.getCell(2)?.asString() ?: AppUser.ROLE_VIEWER, isActive=row.getCell(3)?.asBoolean() ?: true, createdAt=row.getCell(4)?.asNumber()?.toLong() ?: System.currentTimeMillis()) }
        catch (e: Exception) { Log.w(TAG,"Skip user row: ${e.message}"); null }
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

    private fun readHandoverActs(sheet: Sheet): List<HandoverAct> = sheet.read().drop(1).mapNotNull { row ->
        try {
            HandoverAct(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                timestamp = row.getCell(1)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                actType = row.getCell(2)?.asString() ?: HandoverAct.TYPE_HANDOVER,
                renterId = row.getCell(3)?.asNumber()?.toInt() ?: 0,
                scooterId = row.getCell(4)?.asNumber()?.toInt() ?: 0,
                contractHistoryId = row.getCell(5)?.asNumber()?.toInt(),
                mileageKm = row.getCell(6)?.asNumber()?.toLong() ?: 0L,
                equipmentChecklist = row.getCell(7)?.asString() ?: "",
                conditionNotes = row.getCell(8)?.asString() ?: "",
                signedBy = row.getCell(9)?.asString() ?: "LOCAL_SYSTEM"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip handover act row: ${e.message}"); null
        }
    }

    private fun readRepairOrders(sheet: Sheet): List<RepairOrder> = sheet.read().drop(1).mapNotNull { row ->
        try {
            RepairOrder(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                scooterId = row.getCell(1)?.asNumber()?.toInt() ?: 0,
                renterId = row.getCell(2)?.asNumber()?.toInt(),
                scenario = row.getCell(3)?.asString() ?: RepairOrder.SCENARIO_OWNER_REPAIR,
                status = row.getCell(4)?.asString() ?: RepairOrder.STATUS_OPEN,
                openedAt = row.getCell(5)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                closedAt = row.getCell(6)?.asNumber()?.toLong(),
                diagnosis = row.getCell(7)?.asString() ?: "",
                performer = row.getCell(8)?.asString(),
                partsUsed = row.getCell(9)?.asString(),
                estimatedMinor = row.getCell(10)?.asNumber()?.toLong() ?: 0L,
                actualMinor = row.getCell(11)?.asNumber()?.toLong() ?: 0L,
                documentNote = row.getCell(12)?.asString(),
                pauseIntervalsJson = row.getCell(13)?.asString() ?: "[]",
                totalPauseMs = row.getCell(14)?.asNumber()?.toLong() ?: 0L,
                currentlyPaused = row.getCell(15)?.asBoolean() ?: false,
                lastPausedAt = row.getCell(16)?.asNumber()?.toLong()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip repair order row: ${e.message}"); null
        }
    }

    private fun readLegacyMoneyAmounts(sheet: Sheet): List<LegacyMoneyAmount> = sheet.read().drop(1).mapNotNull { row ->
        try {
            LegacyMoneyAmount(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                entityType = row.getCell(1)?.asString() ?: "",
                entityId = row.getCell(2)?.asNumber()?.toLong() ?: 0L,
                field = row.getCell(3)?.asString() ?: "",
                amountMinor = row.getCell(4)?.asNumber()?.toLong() ?: 0L,
                migratedAt = row.getCell(5)?.asNumber()?.toLong() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip legacy money row: ${e.message}"); null
        }
    }

    private fun readDeletedItems(sheet: Sheet): List<DeletedItem> = sheet.read().drop(1).mapNotNull { row ->
        try {
            DeletedItem(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                sourceType = row.getCell(1)?.asString() ?: "",
                sourceId = row.getCell(2)?.asString() ?: "",
                title = row.getCell(3)?.asString() ?: "",
                snapshotJson = row.getCell(4)?.asString() ?: "{}",
                deletedAt = row.getCell(5)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                deletedBy = row.getCell(6)?.asString() ?: "Owner",
                reason = row.getCell(7)?.asString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip deleted item row: ${e.message}"); null
        }
    }

    private fun readTimelineBranches(sheet: Sheet): List<TimelineBranch> = sheet.read().drop(1).mapNotNull { row ->
        try {
            TimelineBranch(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                name = row.getCell(1)?.asString() ?: "Main",
                parentBranchId = row.getCell(2)?.asNumber()?.toLong(),
                forkEventId = row.getCell(3)?.asNumber()?.toLong(),
                createdAt = row.getCell(4)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                isMain = row.getCell(5)?.asBoolean() ?: false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip timeline branch row: ${e.message}"); null
        }
    }

    private fun readTimelineEvents(sheet: Sheet): List<TimelineEvent> = sheet.read().drop(1).mapNotNull { row ->
        try {
            TimelineEvent(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                branchId = row.getCell(1)?.asNumber()?.toLong() ?: 0L,
                timestamp = row.getCell(2)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                actionType = row.getCell(3)?.asString() ?: "",
                screen = row.getCell(4)?.asString() ?: "",
                entityType = row.getCell(5)?.asString(),
                entityId = row.getCell(6)?.asString(),
                title = row.getCell(7)?.asString() ?: "",
                payloadJson = row.getCell(8)?.asString() ?: "{}",
                isMajor = row.getCell(9)?.asBoolean() ?: true,
                isArchived = row.getCell(10)?.asBoolean() ?: false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip timeline event row: ${e.message}"); null
        }
    }

    private fun readTimelineSnapshots(sheet: Sheet): List<TimelineSnapshot> = sheet.read().drop(1).mapNotNull { row ->
        try {
            TimelineSnapshot(
                id = row.getCell(0)?.asNumber()?.toLong() ?: 0,
                branchId = row.getCell(1)?.asNumber()?.toLong() ?: 0L,
                eventId = row.getCell(2)?.asNumber()?.toLong(),
                timestamp = row.getCell(3)?.asNumber()?.toLong() ?: System.currentTimeMillis(),
                stateJson = row.getCell(4)?.asString() ?: "{}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skip timeline snapshot row: ${e.message}"); null
        }
    }
}
