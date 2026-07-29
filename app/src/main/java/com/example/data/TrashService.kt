package com.example.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot-based recoverable deletion for user-owned legacy projections.
 *
 * §9.2 Корзина: при перемещении объекта в корзину сохраняется не только
 * снимок самой строки, но и связанных с ним зависимых строк
 * (BusinessOperation, RentPeriod, HandoverAct, PaymentAllocation). При
 * восстановлении зависимые строки вставляются заново с новыми id, а
 * ссылки в них перемапируются на новый id восстановленного объекта —
 * поэтому отчёты и финансовый журнал продолжают корректно учитывать
 * восстановленный объект.
 *
 * Изначальные REVERSED-операции, помеченные во время move-to-trash,
 * остаются в БД как неизменяемый аудит. Восстановление создаёт НОВЫЕ
 * ACTIVE-операции, что соответствует принципу «исправление оформляется
 * отдельной компенсирующей операцией, а не переписыванием факта» (§0.3).
 */
class TrashService(private val db: AppDatabase) {
    suspend fun archiveTimelineEvent(event: TimelineEvent, reason: String? = null): Long = db.withTransaction {
        val json = JSONObject().apply {
            put("branchId", event.branchId); put("timestamp", event.timestamp); put("actionType", event.actionType)
            put("screen", event.screen); put("entityType", event.entityType); put("entityId", event.entityId)
            put("title", event.title); put("payloadJson", event.payloadJson); put("isMajor", event.isMajor)
        }
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_HISTORY_BRANCH,
            sourceId = event.id.toString(), title = event.title, snapshotJson = json.toString(), reason = reason
        ))
        db.timelineDao().archiveEvent(event.id)
        audit("TIMELINE_EVENT_ARCHIVED", DeletedItem.TYPE_HISTORY_BRANCH, itemId.toString(), reason)
        itemId
    }

    suspend fun snapshotTransaction(transaction: Transaction, reason: String? = null): Long {
        // Capture linked BusinessOperation rows so restore can rebuild
        // the financial context. Without this, a restored transaction
        // would be an orphan with no journal entry — reports would
        // silently miss its amount.
        val operations = try {
            db.businessOperationDao().getAllByLegacyTransactionId(transaction.id)
        } catch (_: Exception) { emptyList() }
        val json = transaction.toJson()
        json.put("businessOperations", JSONArray().apply {
            operations.forEach { op -> put(op.toJson()) }
        })
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_TRANSACTION,
            sourceId = transaction.id.toString(),
            title = "${transaction.type}: ${transaction.renterName}",
            snapshotJson = json.toString(),
            reason = reason
        ))
        audit("TRASH_MOVED", DeletedItem.TYPE_TRANSACTION, itemId.toString(), reason)
        return itemId
    }

    suspend fun snapshotCard(card: VirtualCard, reason: String? = null): Long {
        val json = JSONObject().apply {
            put("name", card.name); put("balance", card.balance); put("color", card.colorHex); put("info", card.info)
            put("default", card.isDefault); put("kind", card.kind); put("created", card.createdAt)
        }
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_CARD, sourceId = card.id.toString(), title = card.name,
            snapshotJson = json.toString(), reason = reason
        ))
        audit("TRASH_MOVED", DeletedItem.TYPE_CARD, itemId.toString(), reason)
        return itemId
    }

    suspend fun snapshotRenter(renter: Renter, reason: String? = null): Long {
        val json = JSONObject().apply {
            put("name", renter.name); put("phone", renter.phoneNumber); put("debt", renter.debtAmount)
            put("duration", renter.rentDurationDays); put("start", renter.rentStartDateTimestamp); put("scooterId", renter.scooterId); put("scooterName", renter.scooterName)
            put("passport", renter.passportData); put("address", renter.address); put("pinfl", renter.pinfl)
        }
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_RENTER, sourceId = renter.id.toString(), title = renter.name,
            snapshotJson = json.toString(), reason = reason
        ))
        audit("TRASH_MOVED", DeletedItem.TYPE_RENTER, itemId.toString(), reason)
        return itemId
    }

    /**
     * Snapshot скутера перед удалением. Сохраняет все реквизиты (VIN, двигатель,
     * серийный номер, аккумуляторы, доп. информация, lifecycle status, сервисные
     * даты), чтобы скутер можно было восстановить из корзины.
     */
    suspend fun snapshotScooter(scooter: Scooter, reason: String? = null): Long {
        val json = scooter.toJson()
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_SCOOTER, sourceId = scooter.id.toString(), title = scooter.name,
            snapshotJson = json.toString(), reason = reason
        ))
        audit("TRASH_MOVED", DeletedItem.TYPE_SCOOTER, itemId.toString(), reason)
        return itemId
    }

    suspend fun moveTransactionToTrash(transaction: Transaction, reason: String? = null): Long = db.withTransaction {
        val itemId = snapshotTransaction(transaction, reason)
        // Reverse the linked BusinessOperation so reports stop counting the
        // deleted transaction. legacyTransactionId is the canonical link.
        // We mark REVERSED here for audit; on restore we create NEW ACTIVE
        // operations — we never silently un-reverse an audit fact.
        try {
            db.businessOperationDao().markReversedByLegacyTransactionId(transaction.id)
        } catch (_: Exception) {}
        // Clean up any PaymentAllocation rows that pointed at the reversed
        // operation — they would otherwise dangle against a REVERSED op.
        try {
            db.businessOperationDao().getAllByLegacyTransactionId(transaction.id).forEach { bo ->
                if (bo.id > 0) db.paymentAllocationDao().deleteByOperation(bo.id)
            }
        } catch (_: Exception) {}
        db.transactionDao().deleteById(transaction.id)
        itemId
    }

    /** Saves a contract snapshot (plus its financial context) before a
     *  business-specific cascade removes it. */
    suspend fun snapshotContract(contract: ContractHistoryEntry, reason: String? = null): Long {
        val json = contract.toJson()
        // Capture all dependent rows so restore can rebuild the full
        // billing & financial history of the contract. Without this,
        // a restored contract would be an empty shell with one RentPeriod
        // and no journal entries — silently invisible to all reports.
        val operations = try {
            db.businessOperationDao().getAllByContract(contract.id)
        } catch (_: Exception) { emptyList() }
        val periods = try {
            db.rentPeriodDao().getAllByContract(contract.id)
        } catch (_: Exception) { emptyList() }
        val handoverActs = try {
            db.handoverActDao().forContract(contract.id)
        } catch (_: Exception) { emptyList() }
        val periodIds = periods.map { it.id }
        val allocations = try {
            if (periodIds.isNotEmpty()) db.paymentAllocationDao().forRentPeriods(periodIds)
            else emptyList()
        } catch (_: Exception) { emptyList() }

        json.put("businessOperations", JSONArray().apply { operations.forEach { put(it.toJson()) } })
        json.put("rentPeriods", JSONArray().apply { periods.forEach { put(it.toJson()) } })
        json.put("handoverActs", JSONArray().apply { handoverActs.forEach { put(it.toJson()) } })
        json.put("paymentAllocations", JSONArray().apply {
            // Each allocation is stored together with the index of its
            // original rentPeriod in the rentPeriods array, so on restore
            // we can remap to the new period id without relying on the
            // now-stale original id.
            allocations.forEach { alloc ->
                val periodIndex = periods.indexOfFirst { it.id == alloc.rentPeriodId }
                put(JSONObject().apply {
                    put("amountMinor", alloc.amountMinor)
                    put("createdAt", alloc.createdAt)
                    put("periodIndex", periodIndex) // -1 if not found — skipped on restore
                    put("originalOperationId", alloc.operationId)
                })
            }
        })

        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_CONTRACT,
            sourceId = contract.id.toString(),
            title = "Contract #${contract.id}: ${contract.renterName}",
            snapshotJson = json.toString(),
            reason = reason
        ))
        audit("TRASH_MOVED", DeletedItem.TYPE_CONTRACT, itemId.toString(), reason)
        return itemId
    }

    suspend fun moveContractToTrash(contract: ContractHistoryEntry, reason: String? = null): Long = db.withTransaction {
        val itemId = snapshotContract(contract, reason)
        db.contractHistoryDao().deleteById(contract.id)
        // Cancel the linked RentPeriod (don't hard-delete — preserves billing
        // history for reports) and clean up PaymentAllocation rows that
        // referenced it. Reverse BusinessOperations tied to this contract.
        try {
            db.rentPeriodDao().byContractHistoryId(contract.id)?.let {
                db.rentPeriodDao().update(it.copy(status = RentPeriod.STATUS_CANCELLED))
            }
        } catch (_: Exception) {}
        try { db.paymentAllocationDao().deleteByContractViaPeriod(contract.id) } catch (_: Exception) {}
        try { db.businessOperationDao().markReversedByContract(contract.id) } catch (_: Exception) {}
        try { db.handoverActDao().deleteByContract(contract.id) } catch (_: Exception) {}
        itemId
    }

    suspend fun restore(itemId: Long): Long = db.withTransaction {
        val item = db.deletedItemDao().byId(itemId) ?: error("Trash item not found")
        val restoredId = when (item.sourceType) {
            DeletedItem.TYPE_HISTORY_BRANCH -> db.timelineDao().insertEvent(item.snapshotJson.toTimelineEvent())
            DeletedItem.TYPE_CARD -> {
                val originalId = item.sourceId.toIntOrNull()
                val restored = originalId?.let { db.virtualCardDao().unarchiveCard(it) } ?: 0
                if (restored > 0) requireNotNull(originalId).toLong() else db.virtualCardDao().insertCard(item.snapshotJson.toCard())
            }
            DeletedItem.TYPE_RENTER -> db.renterDao().insert(item.snapshotJson.toRenter())
            DeletedItem.TYPE_SCOOTER -> db.scooterDao().insertScooter(item.snapshotJson.toScooter())
            DeletedItem.TYPE_TRANSACTION -> {
                val root = JSONObject(item.snapshotJson)
                val newTxId = db.transactionDao().insert(root.toTransaction())
                // Re-create BusinessOperation rows tied to the NEW transaction
                // id. The old REVERSED ones stay as audit; we insert fresh
                // ACTIVE rows so reports count the restored transaction.
                try {
                    val ops = root.optJSONArray("businessOperations")
                    if (ops != null) {
                        for (i in 0 until ops.length()) {
                            val op = ops.optJSONObject(i) ?: continue
                            // Only re-create ACTIVE-type snapshots. Already-REVERSED
                            // entries were audit facts before trash and stay audit.
                            val status = op.optString("status", BusinessOperation.STATUS_ACTIVE)
                            if (status != BusinessOperation.STATUS_ACTIVE) continue
                            db.businessOperationDao().insert(op.toBusinessOperation(
                                legacyTransactionId = newTxId.toInt(),
                                status = BusinessOperation.STATUS_ACTIVE
                            ))
                        }
                    }
                } catch (_: Exception) {}
                newTxId
            }
            DeletedItem.TYPE_CONTRACT -> {
                val root = JSONObject(item.snapshotJson)
                val contract = root.toContract()
                val renter = db.renterDao().getRenterById(contract.renterId)
                    ?: error("Cannot restore contract: renter no longer exists")
                val start = contract.weekStart ?: contract.timestamp
                val end = contract.weekEnd ?: start + 7L * 24 * 60 * 60 * 1000
                renter.scooterId?.let { scooterId ->
                    check(db.rentPeriodDao().conflictsForScooter(scooterId, start, end).isEmpty()) {
                        "Cannot restore contract: scooter period conflicts with an active rental"
                    }
                }
                val contractId = db.contractHistoryDao().insert(contract)

                // Re-insert all RentPeriod rows, mapping old period ids to new
                // ids so PaymentAllocations can be re-linked correctly.
                val periodIdMap = mutableMapOf<Long, Long>() // old id → new id
                try {
                    val periodsArray = root.optJSONArray("rentPeriods")
                    if (periodsArray != null && periodsArray.length() > 0) {
                        for (i in 0 until periodsArray.length()) {
                            val p = periodsArray.optJSONObject(i) ?: continue
                            val oldId = p.optLong("id", 0L)
                            val restoredStatus = p.optString("status", RentPeriod.STATUS_ACTIVE)
                            // Skip CANCELLED periods — they were cancelled for a
                            // reason and restoring them would re-bill the renter.
                            if (restoredStatus == RentPeriod.STATUS_CANCELLED) continue
                            val newPeriodId = db.rentPeriodDao().insert(p.toRentPeriod(
                                contractHistoryId = contractId.toInt(),
                                renterId = contract.renterId,
                                scooterId = renter.scooterId,
                                status = restoredStatus
                            ))
                            if (oldId > 0L) periodIdMap[oldId] = newPeriodId
                        }
                    } else {
                        // Legacy snapshot (pre-Batch 3) — has no rentPeriods array.
                        // Preserve old behaviour: create a single period from the
                        // contract's weekStart/weekEnd.
                        val status = when {
                            contract.isPaid -> RentPeriod.STATUS_PAID
                            end <= System.currentTimeMillis() -> RentPeriod.STATUS_OVERDUE
                            else -> RentPeriod.STATUS_ACTIVE
                        }
                        db.rentPeriodDao().insert(RentPeriod(
                            contractHistoryId = contractId.toInt(), renterId = contract.renterId,
                            scooterId = renter.scooterId, startsAt = start, endsAt = end,
                            chargeMinor = BusinessOperation.toMinor(kotlin.math.abs(contract.amount)),
                            paidMinor = if (contract.isPaid) BusinessOperation.toMinor(kotlin.math.abs(contract.amount)) else 0,
                            status = status
                        ))
                    }
                } catch (_: Exception) {}

                // Re-insert all BusinessOperation rows tied to the new contractId.
                // Build a map from old operation id → new operation id so
                // PaymentAllocations can be re-linked. Only ACTIVE-status
                // snapshots are re-created — already-REVERSED ones are audit.
                val operationIdMap = mutableMapOf<Long, Long>() // old id → new id
                try {
                    val opsArray = root.optJSONArray("businessOperations")
                    if (opsArray != null) {
                        for (i in 0 until opsArray.length()) {
                            val op = opsArray.optJSONObject(i) ?: continue
                            val status = op.optString("status", BusinessOperation.STATUS_ACTIVE)
                            if (status != BusinessOperation.STATUS_ACTIVE) continue
                            val oldOpId = op.optLong("id", 0L)
                            val newOpId = db.businessOperationDao().insert(op.toBusinessOperation(
                                contractId = contractId.toInt(),
                                status = BusinessOperation.STATUS_ACTIVE
                            ))
                            if (oldOpId > 0L) operationIdMap[oldOpId] = newOpId
                        }
                    }
                } catch (_: Exception) {}

                // Re-insert HandoverAct rows tied to the new contractId.
                try {
                    val actsArray = root.optJSONArray("handoverActs")
                    if (actsArray != null) {
                        for (i in 0 until actsArray.length()) {
                            val a = actsArray.optJSONObject(i) ?: continue
                            db.handoverActDao().insert(a.toHandoverAct(
                                contractHistoryId = contractId.toInt()
                            ))
                        }
                    }
                } catch (_: Exception) {}

                // Re-insert PaymentAllocation rows, remapping both operationId
                // and rentPeriodId to their new ids. Allocations whose
                // operation or period wasn't restored (e.g. was REVERSED or
                // CANCELLED) are skipped — they would otherwise point to
                // non-existent rows.
                try {
                    val allocsArray = root.optJSONArray("paymentAllocations")
                    if (allocsArray != null) {
                        for (i in 0 until allocsArray.length()) {
                            val a = allocsArray.optJSONObject(i) ?: continue
                            val oldOpId = a.optLong("originalOperationId", 0L)
                            val newOpId = operationIdMap[oldOpId] ?: continue
                            val periodIndex = a.optInt("periodIndex", -1)
                            if (periodIndex < 0) continue
                            val periodsArray = root.optJSONArray("rentPeriods") ?: continue
                            val pSnap = periodsArray.optJSONObject(periodIndex) ?: continue
                            val oldPeriodId = pSnap.optLong("id", 0L)
                            val newPeriodId = periodIdMap[oldPeriodId] ?: continue
                            db.paymentAllocationDao().insert(PaymentAllocationEntity(
                                operationId = newOpId,
                                rentPeriodId = newPeriodId,
                                amountMinor = a.optLong("amountMinor", 0L),
                                createdAt = a.optLong("createdAt", System.currentTimeMillis())
                            ))
                        }
                    }
                } catch (_: Exception) {}

                contractId
            }
            else -> error("Restore for ${item.sourceType} is not implemented")
        }
        db.deletedItemDao().purge(itemId)
        audit("TRASH_RESTORED", item.sourceType, itemId.toString(), "Restored as #$restoredId")
        // §9.2: запись события дерева истории — восстановление из корзины
        // фиксируется в активной ветке Timeline, чтобы обеспечить полный
        // аудируемый след операции.
        try {
            val mainBranch = db.timelineDao().mainBranch()
            if (mainBranch != null) {
                db.timelineDao().insertEvent(TimelineEvent(
                    branchId = mainBranch.id,
                    timestamp = System.currentTimeMillis(),
                    actionType = "TRASH_RESTORE",
                    screen = "TRASH",
                    entityType = item.sourceType,
                    entityId = restoredId.toString(),
                    title = "Восстановлено из корзины: ${item.title}",
                    payloadJson = "{\"itemId\":${itemId},\"sourceType\":\"${item.sourceType}\"}",
                    isMajor = true
                ))
            }
        } catch (_: Exception) {
            // Не блокируем восстановление, если записать событие не удалось.
        }
        // §10: отдельная audit-запись с новым action-кодом.
        db.auditEventDao().insert(AuditEvent(
            occurredAt = System.currentTimeMillis(),
            action = AuditEvent.ACTION_TRASH_RESTORE,
            entityType = item.sourceType,
            entityId = restoredId.toString(),
            reason = "Restored from trash (itemId=$itemId)"
        ))
        restoredId
    }

    private suspend fun audit(action: String, type: String, id: String, reason: String?) {
        db.auditEventDao().insert(AuditEvent(action = action, entityType = type, entityId = id, reason = reason))
    }

    private fun String.toTimelineEvent(): TimelineEvent = JSONObject(this).let { o -> TimelineEvent(
        branchId = o.optLong("branchId"), timestamp = o.optLong("timestamp"), actionType = o.optString("actionType"),
        screen = o.optString("screen"), entityType = o.optString("entityType").takeIf { !o.isNull("entityType") },
        entityId = o.optString("entityId").takeIf { !o.isNull("entityId") }, title = o.optString("title"),
        payloadJson = o.optString("payloadJson", "{}"), isMajor = o.optBoolean("isMajor", true)
    ) }

    private fun String.toCard(): VirtualCard = JSONObject(this).let { o -> VirtualCard(
        name = o.optString("name"), balance = o.optDouble("balance"), colorHex = o.optString("color", "#FF1565C0"),
        info = o.optString("info").takeIf { !o.isNull("info") }, isDefault = false,
        kind = o.optString("kind", VirtualCard.KIND_REGULAR), createdAt = o.optLong("created", System.currentTimeMillis())
    ) }

    private fun String.toRenter(): Renter = JSONObject(this).let { o -> Renter(
        name = o.optString("name"), phoneNumber = o.optString("phone"), debtAmount = o.optDouble("debt"),
        balance = -o.optDouble("debt"), rentDurationDays = o.optInt("duration", 7), rentStartDateTimestamp = o.optLong("start", System.currentTimeMillis()),
        scooterId = o.optInt("scooterId").takeIf { !o.isNull("scooterId") },
        scooterName = o.optString("scooterName").takeIf { !o.isNull("scooterName") },
        passportData = o.optString("passport"), address = o.optString("address"), pinfl = o.optString("pinfl")
    ) }

    private fun Transaction.toJson() = JSONObject().apply {
        put("id", id); put("contractId", contractId); put("renterId", renterId); put("scooterId", scooterId)
        put("timestamp", timestamp); put("type", type); put("amount", amount); put("notes", notes)
        put("renterName", renterName); put("renterPhone", renterPhone); put("scooterName", scooterName); put("contractLabel", contractLabel)
    }

    private fun JSONObject.toTransaction(): Transaction = Transaction(
        contractId = optInt("contractId").takeIf { !isNull("contractId") },
        renterId = optInt("renterId"), scooterId = optInt("scooterId").takeIf { !isNull("scooterId") },
        timestamp = optLong("timestamp"), type = optString("type"), amount = optDouble("amount"),
        notes = optString("notes").takeIf { !isNull("notes") }, renterName = optString("renterName"),
        renterPhone = optString("renterPhone"), scooterName = optString("scooterName"), contractLabel = optString("contractLabel")
    )

    private fun ContractHistoryEntry.toJson() = JSONObject().apply {
        put("renterId", renterId); put("timestamp", timestamp); put("type", type); put("amount", amount); put("notes", notes)
        put("renterName", renterName); put("renterPhone", renterPhone); put("scooterName", scooterName); put("weekStart", weekStart); put("weekEnd", weekEnd); put("weeklyPrice", weeklyPrice)
        put("passportData", passportData); put("address", address); put("pinfl", pinfl); put("vinNumber", vinNumber); put("engineNumber", engineNumber)
        put("scooterSerialNumber", scooterSerialNumber); put("batteryId1", batteryId1); put("batteryId2", batteryId2); put("additionalInfo", additionalInfo); put("isPaid", isPaid)
    }

    private fun Scooter.toJson() = JSONObject().apply {
        put("name", name); put("documentedNumber", documentedNumber); put("vinNumber", vinNumber)
        put("engineNumber", engineNumber); put("scooterSerialNumber", scooterSerialNumber)
        put("batteryId1", batteryId1); put("batteryId2", batteryId2); put("additionalInfo", additionalInfo)
        put("lifecycleStatus", lifecycleStatus); put("lastServiceAt", lastServiceAt); put("nextServiceAt", nextServiceAt)
        put("mileageKm", mileageKm)
    }

    private fun String.toScooter(): Scooter = JSONObject(this).let { o -> Scooter(
        name = o.optString("name"),
        documentedNumber = o.optString("documentedNumber").takeIf { it.isNotBlank() },
        vinNumber = o.optString("vinNumber"), engineNumber = o.optString("engineNumber"),
        scooterSerialNumber = o.optString("scooterSerialNumber"),
        batteryId1 = o.optString("batteryId1"), batteryId2 = o.optString("batteryId2"),
        additionalInfo = o.optString("additionalInfo"),
        lifecycleStatus = o.optString("lifecycleStatus", Scooter.STATUS_AVAILABLE),
        lastServiceAt = if (o.isNull("lastServiceAt")) null else o.optLong("lastServiceAt"),
        nextServiceAt = if (o.isNull("nextServiceAt")) null else o.optLong("nextServiceAt"),
        mileageKm = o.optLong("mileageKm", 0L)
    ) }

    private fun JSONObject.toContract(): ContractHistoryEntry = ContractHistoryEntry(
        renterId = optInt("renterId"), timestamp = optLong("timestamp"), type = optString("type"), amount = optDouble("amount"),
        notes = optString("notes").takeIf { !isNull("notes") }, renterName = optString("renterName"), renterPhone = optString("renterPhone"),
        scooterName = optString("scooterName").takeIf { !isNull("scooterName") }, weekStart = optLong("weekStart").takeIf { !isNull("weekStart") },
        weekEnd = optLong("weekEnd").takeIf { !isNull("weekEnd") }, weeklyPrice = optDouble("weeklyPrice"), passportData = optString("passportData"),
        address = optString("address"), pinfl = optString("pinfl"), vinNumber = optString("vinNumber"), engineNumber = optString("engineNumber"),
        scooterSerialNumber = optString("scooterSerialNumber"), batteryId1 = optString("batteryId1"), batteryId2 = optString("batteryId2"),
        additionalInfo = optString("additionalInfo"), isPaid = optBoolean("isPaid")
    )

    /** Serialise a BusinessOperation for snapshot storage. Drops the id field
     *  since a restored row gets a new auto-generated id. */
    private fun BusinessOperation.toJson(): JSONObject = JSONObject().apply {
        put("id", id) // kept for reference, but toBusinessOperation ignores it
        put("occurredAt", occurredAt); put("type", type); put("direction", direction)
        put("amountMinor", amountMinor); put("renterId", renterId); put("scooterId", scooterId)
        put("contractId", contractId); put("fromCardId", fromCardId); put("toCardId", toCardId)
        put("cardTransactionId", cardTransactionId); put("legacyTransactionId", legacyTransactionId)
        put("note", note); put("status", status); put("reversesOperationId", reversesOperationId)
        put("createdAt", createdAt)
    }

    /** Deserialise a BusinessOperation from snapshot, allowing callers to
     *  override foreign-key fields (contractId, legacyTransactionId) and
     *  status so the restored row points to the newly-created parent. */
    private fun JSONObject.toBusinessOperation(
        contractId: Int? = optInt("contractId").takeIf { !isNull("contractId") && it > 0 },
        legacyTransactionId: Int? = optInt("legacyTransactionId").takeIf { !isNull("legacyTransactionId") && it > 0 },
        status: String = optString("status", BusinessOperation.STATUS_ACTIVE)
    ): BusinessOperation = BusinessOperation(
        occurredAt = optLong("occurredAt", System.currentTimeMillis()),
        type = optString("type"),
        direction = optString("direction"),
        amountMinor = optLong("amountMinor", 0L),
        renterId = optInt("renterId").takeIf { !isNull("renterId") && it > 0 },
        scooterId = optInt("scooterId").takeIf { !isNull("scooterId") && it > 0 },
        contractId = contractId,
        fromCardId = optInt("fromCardId").takeIf { !isNull("fromCardId") && it > 0 },
        toCardId = optInt("toCardId").takeIf { !isNull("toCardId") && it > 0 },
        cardTransactionId = optInt("cardTransactionId").takeIf { !isNull("cardTransactionId") && it > 0 },
        legacyTransactionId = legacyTransactionId,
        note = optString("note").takeIf { !isNull("note") },
        status = status,
        reversesOperationId = optLong("reversesOperationId").takeIf { !isNull("reversesOperationId") && it > 0 },
        createdAt = optLong("createdAt", System.currentTimeMillis())
    )

    /** Serialise a RentPeriod for snapshot storage. Keeps the id so the
     *  restore logic can map old → new ids when re-linking PaymentAllocations. */
    private fun RentPeriod.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("startsAt", startsAt); put("endsAt", endsAt)
        put("chargeMinor", chargeMinor); put("paidMinor", paidMinor); put("discountMinor", discountMinor)
        put("status", status); put("suspendedAt", suspendedAt); put("suspensionReason", suspensionReason)
        put("parentPeriodId", parentPeriodId); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    /** Deserialise a RentPeriod from snapshot, letting the caller override
     *  the foreign keys (contractHistoryId, renterId, scooterId) and status
     *  so the restored row points to the newly-created parent contract. */
    private fun JSONObject.toRentPeriod(
        contractHistoryId: Int,
        renterId: Int,
        scooterId: Int?,
        status: String = optString("status", RentPeriod.STATUS_ACTIVE)
    ): RentPeriod = RentPeriod(
        contractHistoryId = contractHistoryId,
        renterId = renterId,
        scooterId = scooterId,
        startsAt = optLong("startsAt", System.currentTimeMillis()),
        endsAt = optLong("endsAt", System.currentTimeMillis()),
        chargeMinor = optLong("chargeMinor", 0L),
        paidMinor = optLong("paidMinor", 0L),
        discountMinor = optLong("discountMinor", 0L),
        status = status,
        suspendedAt = optLong("suspendedAt").takeIf { !isNull("suspendedAt") && it > 0 },
        suspensionReason = optString("suspensionReason").takeIf { !isNull("suspensionReason") },
        parentPeriodId = optLong("parentPeriodId").takeIf { !isNull("parentPeriodId") && it > 0 },
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        updatedAt = System.currentTimeMillis()
    )

    /** Serialise a HandoverAct for snapshot storage. Drops the id since a
     *  restored row gets a new auto-generated id. */
    private fun HandoverAct.toJson(): JSONObject = JSONObject().apply {
        put("id", id) // kept for reference, but toHandoverAct ignores it
        put("timestamp", timestamp); put("actType", actType); put("renterId", renterId); put("scooterId", scooterId)
        put("mileageKm", mileageKm); put("equipmentChecklist", equipmentChecklist)
        put("conditionNotes", conditionNotes); put("signedBy", signedBy)
    }

    /** Deserialise a HandoverAct from snapshot, letting the caller override
     *  the contractHistoryId so the restored row points to the new contract. */
    private fun JSONObject.toHandoverAct(contractHistoryId: Int): HandoverAct = HandoverAct(
        timestamp = optLong("timestamp", System.currentTimeMillis()),
        actType = optString("actType", HandoverAct.TYPE_HANDOVER),
        renterId = optInt("renterId"),
        scooterId = optInt("scooterId"),
        contractHistoryId = contractHistoryId,
        mileageKm = optLong("mileageKm", 0L),
        equipmentChecklist = optString("equipmentChecklist"),
        conditionNotes = optString("conditionNotes"),
        signedBy = optString("signedBy", "LOCAL_SYSTEM")
    )
}
