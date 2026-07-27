package com.example.data

import androidx.room.withTransaction
import org.json.JSONObject

/** Snapshot-based recoverable deletion for user-owned legacy projections. */
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
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_TRANSACTION,
            sourceId = transaction.id.toString(),
            title = "${transaction.type}: ${transaction.renterName}",
            snapshotJson = transaction.toJson().toString(),
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

    suspend fun moveTransactionToTrash(transaction: Transaction, reason: String? = null): Long = db.withTransaction {
        val itemId = snapshotTransaction(transaction, reason)
        db.transactionDao().deleteById(transaction.id)
        itemId
    }

    /** Saves a contract snapshot before a business-specific cascade removes it. */
    suspend fun snapshotContract(contract: ContractHistoryEntry, reason: String? = null): Long {
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_CONTRACT,
            sourceId = contract.id.toString(),
            title = "Contract #${contract.id}: ${contract.renterName}",
            snapshotJson = contract.toJson().toString(),
            reason = reason
        ))
        audit("TRASH_MOVED", DeletedItem.TYPE_CONTRACT, itemId.toString(), reason)
        return itemId
    }

    suspend fun moveContractToTrash(contract: ContractHistoryEntry, reason: String? = null): Long = db.withTransaction {
        val itemId = snapshotContract(contract, reason)
        db.contractHistoryDao().deleteById(contract.id)
        db.rentPeriodDao().byContractHistoryId(contract.id)?.let { db.rentPeriodDao().update(it.copy(status = RentPeriod.STATUS_CANCELLED)) }
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
            DeletedItem.TYPE_TRANSACTION -> db.transactionDao().insert(item.snapshotJson.toTransaction())
            DeletedItem.TYPE_CONTRACT -> {
                val contract = item.snapshotJson.toContract()
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

    private fun String.toTransaction(): Transaction = JSONObject(this).let { o -> Transaction(
        contractId = o.optInt("contractId").takeIf { !o.isNull("contractId") },
        renterId = o.optInt("renterId"), scooterId = o.optInt("scooterId").takeIf { !o.isNull("scooterId") },
        timestamp = o.optLong("timestamp"), type = o.optString("type"), amount = o.optDouble("amount"),
        notes = o.optString("notes").takeIf { !o.isNull("notes") }, renterName = o.optString("renterName"),
        renterPhone = o.optString("renterPhone"), scooterName = o.optString("scooterName"), contractLabel = o.optString("contractLabel")
    ) }

    private fun ContractHistoryEntry.toJson() = JSONObject().apply {
        put("renterId", renterId); put("timestamp", timestamp); put("type", type); put("amount", amount); put("notes", notes)
        put("renterName", renterName); put("renterPhone", renterPhone); put("scooterName", scooterName); put("weekStart", weekStart); put("weekEnd", weekEnd); put("weeklyPrice", weeklyPrice)
        put("passportData", passportData); put("address", address); put("pinfl", pinfl); put("vinNumber", vinNumber); put("engineNumber", engineNumber)
        put("scooterSerialNumber", scooterSerialNumber); put("batteryId1", batteryId1); put("batteryId2", batteryId2); put("additionalInfo", additionalInfo); put("isPaid", isPaid)
    }

    private fun String.toContract(): ContractHistoryEntry = JSONObject(this).let { o -> ContractHistoryEntry(
        renterId = o.optInt("renterId"), timestamp = o.optLong("timestamp"), type = o.optString("type"), amount = o.optDouble("amount"),
        notes = o.optString("notes").takeIf { !o.isNull("notes") }, renterName = o.optString("renterName"), renterPhone = o.optString("renterPhone"),
        scooterName = o.optString("scooterName").takeIf { !o.isNull("scooterName") }, weekStart = o.optLong("weekStart").takeIf { !o.isNull("weekStart") },
        weekEnd = o.optLong("weekEnd").takeIf { !o.isNull("weekEnd") }, weeklyPrice = o.optDouble("weeklyPrice"), passportData = o.optString("passportData"),
        address = o.optString("address"), pinfl = o.optString("pinfl"), vinNumber = o.optString("vinNumber"), engineNumber = o.optString("engineNumber"),
        scooterSerialNumber = o.optString("scooterSerialNumber"), batteryId1 = o.optString("batteryId1"), batteryId2 = o.optString("batteryId2"),
        additionalInfo = o.optString("additionalInfo"), isPaid = o.optBoolean("isPaid")
    ) }
}
