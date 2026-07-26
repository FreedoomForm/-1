package com.example.data

import androidx.room.withTransaction
import org.json.JSONObject

/** Snapshot-based recoverable deletion for user-owned legacy projections. */
class TrashService(private val db: AppDatabase) {
    suspend fun moveTransactionToTrash(transaction: Transaction, reason: String? = null): Long = db.withTransaction {
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_TRANSACTION,
            sourceId = transaction.id.toString(),
            title = "${transaction.type}: ${transaction.renterName}",
            snapshotJson = transaction.toJson().toString(),
            reason = reason
        ))
        db.transactionDao().deleteById(transaction.id)
        audit("TRASH_MOVED", DeletedItem.TYPE_TRANSACTION, itemId.toString(), reason)
        itemId
    }

    suspend fun moveContractToTrash(contract: ContractHistoryEntry, reason: String? = null): Long = db.withTransaction {
        val itemId = db.deletedItemDao().insert(DeletedItem(
            sourceType = DeletedItem.TYPE_CONTRACT,
            sourceId = contract.id.toString(),
            title = "Contract #${contract.id}: ${contract.renterName}",
            snapshotJson = contract.toJson().toString(),
            reason = reason
        ))
        db.contractHistoryDao().deleteById(contract.id)
        db.rentPeriodDao().byContractHistoryId(contract.id)?.let { db.rentPeriodDao().update(it.copy(status = RentPeriod.STATUS_CANCELLED)) }
        audit("TRASH_MOVED", DeletedItem.TYPE_CONTRACT, itemId.toString(), reason)
        itemId
    }

    suspend fun restore(itemId: Long): Long = db.withTransaction {
        val item = db.deletedItemDao().byId(itemId) ?: error("Trash item not found")
        val restoredId = when (item.sourceType) {
            DeletedItem.TYPE_TRANSACTION -> db.transactionDao().insert(item.snapshotJson.toTransaction())
            DeletedItem.TYPE_CONTRACT -> {
                val contract = item.snapshotJson.toContract()
                db.contractHistoryDao().insert(contract)
            }
            else -> error("Restore for ${item.sourceType} is not implemented")
        }
        db.deletedItemDao().purge(itemId)
        audit("TRASH_RESTORED", item.sourceType, itemId.toString(), "Restored as #$restoredId")
        restoredId
    }

    private suspend fun audit(action: String, type: String, id: String, reason: String?) {
        db.auditEventDao().insert(AuditEvent(action = action, entityType = type, entityId = id, reason = reason))
    }

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
