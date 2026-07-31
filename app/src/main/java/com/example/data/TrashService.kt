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
    /**
     * In-process concurrent-restore guard. If a user double-taps "restore"
     * on the same trash item, two coroutines may call restore(itemId)
     * concurrently; without this guard both read the snapshot before either
     * calls purge(itemId), producing two duplicate restored rows + one
     * "Trash item not found" error. The set ensures only the first call
     * proceeds; the second sees the id is already being processed and aborts.
     */
    private val restoringIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

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
            // Capture the renter's own BusinessOperations so the restored
            // renter has the same financial footprint (deposits, debt
            // forgiveness, adjustments not tied to any contract/tx).
            put("businessOperations", JSONArray().apply {
                try { db.businessOperationDao().getAllByRenter(renter.id).forEach { put(it.toJson()) } } catch (_: Exception) {}
            })
            // Capture SMS delivery + notification history. These rows are
            // hard-deleted during the renter trash cascade (see
            // RenterViewModel.deleteRenter). Without this snapshot, the
            // restored renter has zero SMS delivery history — per-renter
            // SMS success-rate reports silently drop all past entries.
            put("smsDeliveries", JSONArray().apply {
                try { db.smsDeliveryDao().forRenterOnce(renter.id).forEach { put(it.toJson()) } } catch (_: Exception) {}
            })
            put("notifications", JSONArray().apply {
                try { db.notificationHistoryDao().forRenterOnce(renter.id).forEach { put(it.toJson()) } } catch (_: Exception) {}
            })
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
     *
     * Также сохраняет связанные RepairOrder и BusinessOperation(ы) для этого
     * скутера — иначе восстановленный скутер теряет всю историю ремонтов
     * (cost, downtime, repeat-failure metrics) и все REPAIR-операции,
     * что делает per-scooter profitability reports молча неверными.
     */
    suspend fun snapshotScooter(scooter: Scooter, reason: String? = null): Long {
        val json = scooter.toJson()
        val repairOrders = try {
            db.repairOrderDao().getAllForScooterOnce(scooter.id)
        } catch (_: Exception) { emptyList() }
        val operations = try {
            db.businessOperationDao().getAllByScooter(scooter.id)
        } catch (_: Exception) { emptyList() }
        json.put("repairOrders", JSONArray().apply { repairOrders.forEach { put(it.toJson()) } })
        json.put("businessOperations", JSONArray().apply { operations.forEach { put(it.toJson()) } })
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
        // Capture the scooterId at snapshot time. The contract row itself
        // only stores scooterName (a denormalized string), so without this
        // field the restore path would have to use renter.scooterId —
        // which may have changed if the renter was reassigned after the
        // contract was trashed. Storing the snapshot's scooterId lets the
        // restore path run the conflict check against the SAME scooter
        // the contract was originally written for.
        val snapshotScooterId = try {
            db.renterDao().getRenterById(contract.renterId)?.scooterId
        } catch (_: Exception) { null }
        json.put("snapshotScooterId", snapshotScooterId)
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
        // Capture CardTransaction rows that reference this contract via
        // contractId (CONTRACT_INCOME type). They are hard-deleted during
        // the renter/scooter trash cascade — without this snapshot, the
        // CashFlowWidget and MainCardIncomeWidget on the Reports screen
        // silently undercount income by the restored contract's amount.
        val cardTransactions = try {
            db.cardTransactionDao().getForContractOnce(contract.id)
        } catch (_: Exception) { emptyList() }

        json.put("businessOperations", JSONArray().apply { operations.forEach { put(it.toJson()) } })
        json.put("rentPeriods", JSONArray().apply { periods.forEach { put(it.toJson()) } })
        json.put("handoverActs", JSONArray().apply { handoverActs.forEach { put(it.toJson()) } })
        json.put("cardTransactions", JSONArray().apply { cardTransactions.forEach { put(it.toJson()) } })
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

    suspend fun restore(itemId: Long): Long {
        synchronized(restoringIds) {
            check(itemId !in restoringIds) {
                "Trash item #$itemId is already being restored — concurrent restore blocked"
            }
            restoringIds.add(itemId)
        }
        try {
            return restoreInternal(itemId)
        } finally {
            synchronized(restoringIds) { restoringIds.remove(itemId) }
        }
    }

    private suspend fun restoreInternal(itemId: Long): Long = db.withTransaction {
        val item = db.deletedItemDao().byId(itemId) ?: error("Trash item not found")
        val restoredId = when (item.sourceType) {
            DeletedItem.TYPE_HISTORY_BRANCH -> db.timelineDao().insertEvent(item.snapshotJson.toTimelineEvent())
            DeletedItem.TYPE_CARD -> {
                val originalId = item.sourceId.toIntOrNull()
                val restored = originalId?.let { db.virtualCardDao().unarchiveCard(it) } ?: 0
                if (restored > 0) requireNotNull(originalId).toLong() else db.virtualCardDao().insertCard(item.snapshotJson.toCard())
            }
            DeletedItem.TYPE_RENTER -> {
                val root = JSONObject(item.snapshotJson)
                val oldRenterId = item.sourceId.toIntOrNull()
                val newRenterId = db.renterDao().insert(root.toRenter())
                // Batch 8 (L2): collect every stale card FK we null out so
                // a single ACTION_TRASH_FK_NULLIFIED audit event can be
                // emitted at the end of the restore.
                val nullifiedFks = mutableListOf<Pair<String, Int>>()
                // Re-insert the renter's own BusinessOperations (deposits,
                // debt forgiveness, adjustments not tied to any contract/tx).
                // Use the new renterId so the restored ops point to the
                // restored renter — the snapshot's renterId is now stale.
                val opsArray = root.optJSONArray("businessOperations")
                if (opsArray != null) {
                    for (i in 0 until opsArray.length()) {
                        val op = opsArray.optJSONObject(i) ?: continue
                        val status = op.optString("status", BusinessOperation.STATUS_ACTIVE)
                        if (status != BusinessOperation.STATUS_ACTIVE) continue
                        // Validate card FKs via the centralised helper so
                        // null-outs are recorded for the audit trail.
                        val safeFromCardId = validateCardFk(
                            op.optInt("fromCardId", 0), "fromCardId", nullifiedFks
                        )
                        val safeToCardId = validateCardFk(
                            op.optInt("toCardId", 0), "toCardId", nullifiedFks
                        )
                        // Validate legacyTransactionId — the original tx may
                        // have been trashed alongside the renter and not yet
                        // restored.
                        val safeLegacyTxId = op.optInt("legacyTransactionId", 0).takeIf { it > 0 }
                            ?.let { if (db.transactionDao().getById(it) != null) it else null }
                        // Validate contractId — same reasoning.
                        val safeContractId = op.optInt("contractId", 0).takeIf { it > 0 }
                            ?.let { if (db.contractHistoryDao().getById(it) != null) it else null }
                        dedupOrInsertBusinessOperation(
                            op = op,
                            renterId = newRenterId.toInt(),
                            scooterId = null,
                            contractId = safeContractId,
                            legacyTransactionId = safeLegacyTxId,
                            fromCardId = safeFromCardId,
                            toCardId = safeToCardId,
                            cardTransactionId = null
                        )
                    }
                }
                auditFkNullifications(itemId, DeletedItem.TYPE_RENTER, nullifiedFks)
                // Critical: dependent TYPE_CONTRACT and TYPE_TRANSACTION
                // snapshots in the recycle bin still reference the OLD
                // renterId. Their restore paths validate renter existence
                // (see lines above for TYPE_TRANSACTION / TYPE_CONTRACT)
                // and would throw "renter no longer exists" on every
                // restore attempt — leaving them stuck in trash forever.
                // Rewrite each dependent snapshot's renterId to the new
                // value so subsequent restores succeed.
                if (oldRenterId != null && oldRenterId > 0) {
                    try {
                        db.deletedItemDao().getAllOnceInclTrash().forEach { dep ->
                            if (dep.id == itemId) return@forEach
                            if (dep.sourceType != DeletedItem.TYPE_CONTRACT &&
                                dep.sourceType != DeletedItem.TYPE_TRANSACTION) return@forEach
                            try {
                                val depRoot = JSONObject(dep.snapshotJson)
                                if (depRoot.optInt("renterId", -1) != oldRenterId) return@forEach
                                depRoot.put("renterId", newRenterId.toInt())
                                db.deletedItemDao().updateSnapshot(dep.id, depRoot.toString())
                            } catch (_: Exception) {
                                // Snapshot may be legacy/malformed — skip silently.
                            }
                        }
                    } catch (_: Exception) {
                        // Best-effort: don't fail the whole renter restore
                        // just because the cross-snapshot rewrite failed.
                    }
                }
                // Re-insert SmsDelivery + NotificationHistory rows tied to
                // the new renterId. Without this, the restored renter has
                // zero SMS delivery history — per-renter SMS success-rate
                // reports silently drop all past entries.
                val smsArray = root.optJSONArray("smsDeliveries")
                if (smsArray != null) {
                    for (i in 0 until smsArray.length()) {
                        val s = smsArray.optJSONObject(i) ?: continue
                        db.smsDeliveryDao().insert(s.toSmsDelivery(renterId = newRenterId.toInt()))
                    }
                }
                val notifArray = root.optJSONArray("notifications")
                if (notifArray != null) {
                    for (i in 0 until notifArray.length()) {
                        val n = notifArray.optJSONObject(i) ?: continue
                        db.notificationHistoryDao().insert(n.toNotification(renterId = newRenterId.toInt()))
                    }
                }
                newRenterId
            }
            DeletedItem.TYPE_SCOOTER -> {
                val root = JSONObject(item.snapshotJson)
                val newScooterId = db.scooterDao().insertScooter(root.toScooter()).toInt()
                // Batch 8 (L2): collect stale card FKs for audit.
                val nullifiedFks = mutableListOf<Pair<String, Int>>()
                // Re-insert RepairOrder rows tied to the NEW scooterId.
                // Without this, the restored scooter has zero repair history
                // — per-scooter profitability and repeat-failure metrics
                // silently drop the scooter's full maintenance past.
                val repairOrdersArray = root.optJSONArray("repairOrders")
                if (repairOrdersArray != null) {
                    for (i in 0 until repairOrdersArray.length()) {
                        val ro = repairOrdersArray.optJSONObject(i) ?: continue
                        // Re-OPEN any order that was OPEN at trash time —
                        // the user can re-close it manually if needed. We
                        // don't auto-close because that would silently
                        // fabricate a closedAt timestamp.
                        db.repairOrderDao().insert(ro.toRepairOrder(scooterId = newScooterId))
                    }
                }
                // Re-insert ACTIVE BusinessOperations tied to the NEW
                // scooterId (typically TYPE_REPAIR expenses). REVERSED
                // ones stay as audit.
                val opsArray = root.optJSONArray("businessOperations")
                if (opsArray != null) {
                    for (i in 0 until opsArray.length()) {
                        val op = opsArray.optJSONObject(i) ?: continue
                        val status = op.optString("status", BusinessOperation.STATUS_ACTIVE)
                        if (status != BusinessOperation.STATUS_ACTIVE) continue
                        val safeFromCardId = validateCardFk(
                            op.optInt("fromCardId", 0), "fromCardId", nullifiedFks
                        )
                        val safeToCardId = validateCardFk(
                            op.optInt("toCardId", 0), "toCardId", nullifiedFks
                        )
                        val safeLegacyTxId = op.optInt("legacyTransactionId", 0).takeIf { it > 0 }
                            ?.let { if (db.transactionDao().getById(it) != null) it else null }
                        val safeContractId = op.optInt("contractId", 0).takeIf { it > 0 }
                            ?.let { if (db.contractHistoryDao().getById(it) != null) it else null }
                        dedupOrInsertBusinessOperation(
                            op = op,
                            renterId = null,
                            scooterId = newScooterId,
                            contractId = safeContractId,
                            legacyTransactionId = safeLegacyTxId,
                            fromCardId = safeFromCardId,
                            toCardId = safeToCardId,
                            cardTransactionId = null
                        )
                    }
                }
                auditFkNullifications(itemId, DeletedItem.TYPE_SCOOTER, nullifiedFks)
                newScooterId.toLong()
            }
            DeletedItem.TYPE_TRANSACTION -> {
                val root = JSONObject(item.snapshotJson)
                val txSnapshot = root.toTransaction()
                // Batch 8 (L2): collect stale card FKs for audit.
                val nullifiedFks = mutableListOf<Pair<String, Int>>()
                // Validate that the renter still exists — same guard the
                // contract-restore path has. A restored transaction whose
                // renterId points to a now-deleted renter would be invisible
                // to per-renter reports and would crash any join on renters.
                db.renterDao().getRenterById(txSnapshot.renterId)
                    ?: error("Cannot restore transaction: renter no longer exists")
                // If the snapshot references a contract that no longer exists,
                // null out contractId so the restored transaction doesn't
                // dangle. The original contract may have been moved to trash
                // and either not restored, or restored with a different id.
                val safeContractId = txSnapshot.contractId?.let { cid ->
                    if (db.contractHistoryDao().getById(cid) != null) cid else null
                }
                val newTxId = db.transactionDao().insert(txSnapshot.copy(contractId = safeContractId))
                // Re-create BusinessOperation rows tied to the NEW transaction
                // id. The old REVERSED ones stay as audit; we insert fresh
                // ACTIVE rows so reports count the restored transaction.
                //
                // No try/catch here: if BO insert fails, the whole restore
                // must roll back. Silently swallowing would re-introduce the
                // Batch 1 / Batch 3 silent-data-loss class of bug where the
                // user sees "success" but reports silently miss the amount.
                val ops = root.optJSONArray("businessOperations")
                if (ops != null) {
                    for (i in 0 until ops.length()) {
                        val op = ops.optJSONObject(i) ?: continue
                        // Only re-create ACTIVE-type snapshots. Already-REVERSED
                        // entries were audit facts before trash and stay audit.
                        val status = op.optString("status", BusinessOperation.STATUS_ACTIVE)
                        if (status != BusinessOperation.STATUS_ACTIVE) continue
                        // Validate fromCardId/toCardId via the centralised
                        // helper so null-outs are recorded for the audit trail.
                        val safeFromCardId = validateCardFk(
                            op.optInt("fromCardId", 0), "fromCardId", nullifiedFks
                        )
                        val safeToCardId = validateCardFk(
                            op.optInt("toCardId", 0), "toCardId", nullifiedFks
                        )
                        dedupOrInsertBusinessOperation(
                            op = op,
                            renterId = txSnapshot.renterId.takeIf { it > 0 },
                            scooterId = txSnapshot.scooterId?.takeIf { it > 0 },
                            contractId = safeContractId,
                            legacyTransactionId = newTxId.toInt(),
                            fromCardId = safeFromCardId,
                            toCardId = safeToCardId,
                            cardTransactionId = null
                        )
                    }
                }
                auditFkNullifications(itemId, DeletedItem.TYPE_TRANSACTION, nullifiedFks)
                newTxId
            }
            DeletedItem.TYPE_CONTRACT -> {
                val root = JSONObject(item.snapshotJson)
                val contract = root.toContract()
                val renter = db.renterDao().getRenterById(contract.renterId)
                    ?: error("Cannot restore contract: renter no longer exists")
                val start = contract.weekStart ?: contract.timestamp
                val end = contract.weekEnd ?: start + 7L * 24 * 60 * 60 * 1000
                // Use the SNAPSHOT's scooterId (captured at trash time) for
                // the conflict check, not the renter's CURRENT scooterId —
                // the renter may have been reassigned after the contract was
                // trashed, and using the wrong scooter would silently allow
                // a conflicting restore. Fall back to current renter.scooterId
                // for legacy pre-Batch 7 snapshots that don't have the field.
                val conflictScooterId = root.optInt("snapshotScooterId", 0).takeIf { it > 0 }
                    ?: renter.scooterId
                conflictScooterId?.let { scooterId ->
                    check(db.rentPeriodDao().conflictsForScooter(scooterId, start, end).isEmpty()) {
                        "Cannot restore contract: scooter period conflicts with an active rental"
                    }
                }
                val contractId = db.contractHistoryDao().insert(contract)
                // Batch 15 (was HIGH D1): guard against the IGNORE-strategy
                // insert returning -1 on id collision. ContractHistoryDao.insert
                // uses OnConflictStrategy.IGNORE — if a contract row with the
                // snapshot's original id still exists (e.g. a prior
                // deleteContractWithCascade failed mid-way and left the row,
                // or two trash items reference the same contractId), insert
                // returns -1. Previously the code used -1 as contractId for
                // every downstream FK (RentPeriod, BusinessOperation,
                // CardTransaction, HandoverAct), producing a swarm of rows
                // pointing at a non-existent contract -1 — which OrphanSweeper
                // would then null-out on next launch, silently losing the
                // restored contract's entire financial context. Now we throw
                // immediately so the db.withTransaction rolls back cleanly.
                require(contractId != -1L) {
                    "Contract id collision: contract #${contract.id} already exists in DB — cannot restore from trash"
                }

                // Re-insert all RentPeriod rows, mapping old period ids to new
                // ids so PaymentAllocations can be re-linked correctly.
                //
                // No try/catch: a failure here means the contract would be
                // restored with no billing period — silently invisible to all
                // per-period reports. Roll back the whole restore instead.
                val periodIdMap = mutableMapOf<Long, Long>() // old id → new id
                val periodsArray = root.optJSONArray("rentPeriods")
                val isLegacySnapshot = periodsArray == null || periodsArray.length() == 0
                val legacyChargeMinor = if (isLegacySnapshot) {
                    BusinessOperation.toMinor(kotlin.math.abs(contract.amount))
                } else 0L
                if (!isLegacySnapshot) {
                    for (i in 0 until periodsArray!!.length()) {
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
                        chargeMinor = legacyChargeMinor,
                        paidMinor = if (contract.isPaid) legacyChargeMinor else 0,
                        status = status
                    ))
                }

                // §9.2 / §0.3 — Backfill: legacy pre-Batch 3 snapshots had no
                // businessOperations array, so the contract's amount was
                // counted by contract_history but NOT by business_operations,
                // producing divergent reports (the exact class of bug Batch 3
                // was supposed to eliminate). Synthesize a single ACTIVE
                // operation so the journal agrees with the contract row.
                // Done only for legacy snapshots — modern snapshots already
                // capture their own ops.
                if (isLegacySnapshot) {
                    db.businessOperationDao().insert(BusinessOperation(
                        occurredAt = contract.timestamp,
                        type = BusinessOperation.TYPE_RENT_PAYMENT,
                        direction = BusinessOperation.DIRECTION_INCOME,
                        amountMinor = legacyChargeMinor,
                        renterId = contract.renterId,
                        scooterId = renter.scooterId,
                        contractId = contractId.toInt(),
                        note = "Synthesized from legacy trash snapshot for contract #${contractId}",
                        status = BusinessOperation.STATUS_ACTIVE,
                        createdAt = System.currentTimeMillis()
                    ))
                }

                // Re-insert CardTransaction rows tied to the new contractId.
                // Build a map from old cardTx id → new cardTx id so the
                // BusinessOperation re-insertion pass below can remap
                // cardTransactionId FK. Without this, restored BusinessOperations
                // would point to non-existent CardTransaction ids — the
                // CashFlowWidget and MainCardIncomeWidget read CardTransactions
                // directly, so they would silently undercount income by the
                // restored contract's amount.
                //
                // No try/catch: a partial restore (contract without its
                // CardTransactions) silently diverges card-cash-flow reports
                // from the journal. Roll back instead.
                //
                // Batch 8 (L2): collect stale card FKs from CardTransaction
                // rows AND from BusinessOperation rows so a single audit
                // event covers the whole restore.
                val nullifiedFks = mutableListOf<Pair<String, Int>>()
                val cardTxIdMap = mutableMapOf<Int, Int>() // old id → new id
                val cardTxsArray = root.optJSONArray("cardTransactions")
                // Pre-fetch valid card ids once so we don't hit the DAO per row.
                val validCardIds = try {
                    db.virtualCardDao().getAllCardsOnce().map { it.id }.toSet()
                } catch (_: Exception) { emptySet() }
                if (cardTxsArray != null) {
                    for (i in 0 until cardTxsArray.length()) {
                        val ctx = cardTxsArray.optJSONObject(i) ?: continue
                        val oldCtxId = ctx.optInt("id", 0)
                        // Record stale card FKs BEFORE the row is built —
                        // toCardTransaction silently maps invalid ids to 0,
                        // so we capture the original values here.
                        val oldFromCardId = ctx.optInt("fromCardId", 0)
                        val oldToCardId = ctx.optInt("toCardId", 0)
                        if (oldFromCardId > 0 && oldFromCardId !in validCardIds) {
                            nullifiedFks.add("cardTx[$oldCtxId].fromCardId" to oldFromCardId)
                        }
                        if (oldToCardId > 0 && oldToCardId !in validCardIds) {
                            nullifiedFks.add("cardTx[$oldCtxId].toCardId" to oldToCardId)
                        }
                        val newCtxId = db.cardTransactionDao().insertTransaction(
                            ctx.toCardTransaction(contractId = contractId.toInt(), validCardIds = validCardIds)
                        ).toInt()
                        if (oldCtxId > 0) cardTxIdMap[oldCtxId] = newCtxId
                    }
                }

                // Re-insert all BusinessOperation rows tied to the new contractId.
                // Build a map from old operation id → new operation id so
                // PaymentAllocations can be re-linked. Only ACTIVE-status
                // snapshots are re-created — already-REVERSED ones are audit.
                //
                // No try/catch: a failure here means the contract's amount
                // silently disappears from income reports. Roll back instead.
                val operationIdMap = mutableMapOf<Long, Long>() // old id → new id
                val opsArray = root.optJSONArray("businessOperations")
                if (opsArray != null) {
                    for (i in 0 until opsArray.length()) {
                        val op = opsArray.optJSONObject(i) ?: continue
                        val status = op.optString("status", BusinessOperation.STATUS_ACTIVE)
                        if (status != BusinessOperation.STATUS_ACTIVE) continue
                        val oldOpId = op.optLong("id", 0L)
                        // Null out legacyTransactionId on restored contract-scoped
                        // ops — the snapshot's txId points to a Transaction row
                        // that may have been trashed alongside the contract.
                        // Without this, the op would dangle and per-tx reports
                        // would silently mis-count.
                        val safeLegacyTxId = op.optInt("legacyTransactionId", 0).takeIf { it > 0 }
                            ?.let { if (db.transactionDao().getById(it) != null) it else null }
                        // Validate fromCardId/toCardId via the centralised
                        // helper so null-outs are recorded for the audit trail.
                        val safeFromCardId = validateCardFk(
                            op.optInt("fromCardId", 0), "bo[$oldOpId].fromCardId", nullifiedFks
                        )
                        val safeToCardId = validateCardFk(
                            op.optInt("toCardId", 0), "bo[$oldOpId].toCardId", nullifiedFks
                        )
                        // Remap cardTransactionId via cardTxIdMap. If the
                        // original CardTransaction wasn't restored (e.g. its
                        // row was missing from the snapshot), null it out —
                        // a stale FK would point to a non-existent card_tx.
                        val safeCardTxId = op.optInt("cardTransactionId", 0).takeIf { it > 0 }
                            ?.let { cardTxIdMap[it] }
                        val newOpId = dedupOrInsertBusinessOperation(
                            op = op,
                            renterId = contract.renterId.takeIf { it > 0 },
                            scooterId = renter.scooterId,
                            contractId = contractId.toInt(),
                            legacyTransactionId = safeLegacyTxId,
                            fromCardId = safeFromCardId,
                            toCardId = safeToCardId,
                            cardTransactionId = safeCardTxId
                        )
                        if (oldOpId > 0L) operationIdMap[oldOpId] = newOpId
                    }
                }
                auditFkNullifications(itemId, DeletedItem.TYPE_CONTRACT, nullifiedFks)

                // Re-insert HandoverAct rows tied to the new contractId.
                // No try/catch: a partial restore (contract without handover
                // history) silently loses §4 audit trail. Roll back instead.
                val actsArray = root.optJSONArray("handoverActs")
                if (actsArray != null) {
                    for (i in 0 until actsArray.length()) {
                        val a = actsArray.optJSONObject(i) ?: continue
                        db.handoverActDao().insert(a.toHandoverAct(
                            contractHistoryId = contractId.toInt()
                        ))
                    }
                }

                // Re-insert PaymentAllocation rows, remapping both operationId
                // and rentPeriodId to their new ids. Allocations whose
                // operation or period wasn't restored (e.g. was REVERSED or
                // CANCELLED) are skipped — they would otherwise point to
                // non-existent rows.
                //
                // No try/catch: a partial restore (period with chargeMinor but
                // no PaymentAllocation rows) silently diverges paidMinor from
                // actual payment history. Roll back instead.
                val allocsArray = root.optJSONArray("paymentAllocations")
                if (allocsArray != null) {
                    for (i in 0 until allocsArray.length()) {
                        val a = allocsArray.optJSONObject(i) ?: continue
                        val oldOpId = a.optLong("originalOperationId", 0L)
                        val newOpId = operationIdMap[oldOpId] ?: continue
                        val periodIndex = a.optInt("periodIndex", -1)
                        if (periodIndex < 0) continue
                        val allocPeriodsArray = root.optJSONArray("rentPeriods") ?: continue
                        val pSnap = allocPeriodsArray.optJSONObject(periodIndex) ?: continue
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

                contractId
            }
            else -> error("Restore for ${item.sourceType} is not implemented")
        }
        db.deletedItemDao().purge(itemId)
        // Note: structured AuditEvent with ACTION_TRASH_RESTORE is inserted
        // below — no need for the legacy audit("TRASH_RESTORED", ...) helper
        // call which previously wrote a duplicate row with a mismatched action
        // code ("TRASH_RESTORED" vs "TRASH_RESTORE"). The AuditEvent below is
        // the authoritative audit record.
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

    /**
     * Batch 8 (was L2): surfaces previously-invisible FK null-outs.
     *
     * When a restore path detects that a BusinessOperation's card FK
     * (fromCardId / toCardId) points to a card that is still in trash
     * (or otherwise gone), the FK is silently nulled to keep the restore
     * from producing a dangling row. Without this audit, the user had
     * no way to discover that a restored operation's card linkage was
     * dropped — they would see the operation in reports but with no
     * card attribution, and the card-balance reports would silently
     * under-count.
     *
     * This helper writes a single ACTION_TRASH_FK_NULLIFIED audit event
     * per restore call, listing every FK that was nulled. It is best-
     * effort: if the audit insert itself fails, the restore still
     * proceeds (the financial data is correct; only the audit trail
     * would be incomplete).
     */
    private suspend fun auditFkNullifications(
        itemId: Long,
        sourceType: String,
        nullifiedFks: List<Pair<String, Int>>
    ) {
        if (nullifiedFks.isEmpty()) return
        try {
            db.auditEventDao().insert(AuditEvent(
                occurredAt = System.currentTimeMillis(),
                action = AuditEvent.ACTION_TRASH_FK_NULLIFIED,
                entityType = sourceType,
                entityId = itemId.toString(),
                reason = "Restore of trash item #$itemId required nulling ${nullifiedFks.size} stale card FK(s)",
                beforeSnapshot = nullifiedFks.joinToString("; ") { (field, oldId) -> "$field=$oldId" },
                afterSnapshot = nullifiedFks.joinToString("; ") { (field, _) -> "$field=null" }
            ))
        } catch (_: Exception) {
            // Best-effort — don't fail the restore if audit insert fails.
        }
    }

    /**
     * Validates a card FK against the live DB. Returns null if the card
     * doesn't exist (so the caller can null out the FK) and records the
     * null-out via [auditFkNullifications] when [nullifiedSink] is provided.
     *
     * Centralising the validate-and-record logic here ensures every
     * restore path uses the same null-out semantics and emits the same
     * audit trail.
     */
    private suspend fun validateCardFk(
        cardId: Int,
        fieldName: String,
        nullifiedSink: MutableList<Pair<String, Int>>? = null
    ): Int? {
        if (cardId <= 0) return null
        return if (db.virtualCardDao().getCardById(cardId) != null) {
            cardId
        } else {
            nullifiedSink?.add(fieldName to cardId)
            null
        }
    }

    /**
     * Cross-snapshot BusinessOperation deduplication helper (Batch 7 — BLOCKER B1).
     *
     * The same `BusinessOperation` row is captured by TWO independent
     * snapshots during cascade trash:
     *   - `snapshotContract` captures it via `getAllByContract(contractId)`
     *   - `snapshotTransaction` captures it via `getAllByLegacyTransactionId(txId)`
     *
     * If the same op has BOTH `contractId` AND `legacyTransactionId` set
     * (the typical case — every Transaction has a BusinessOperation with
     * `legacyTransactionId` AND `contractId` set during migration 23→24),
     * both snapshots store the same JSON. Without dedup, restoring both
     * snapshots inserts TWO identical ACTIVE rows → income/expense
     * reports silently double-count the amount.
     *
     * Solution: before inserting a restored BO, query
     * `findActiveByFingerprint(occurredAt, amountMinor, direction, type,
     * renterId, scooterId)`. If a matching ACTIVE op already exists:
     *   - Don't insert a duplicate.
     *   - Return the existing op's id so the caller can record it in
     *     `operationIdMap` — `PaymentAllocation` rows still link correctly.
     *
     * The fingerprint excludes `contractId`, `legacyTransactionId`,
     * `cardTransactionId`, `fromCardId`, `toCardId`, `note` because:
     *   - `contractId` / `legacyTransactionId` are intentionally DIFFERENT
     *     between the two restore paths (each path nulls out the FK that
     *     points to a not-yet-restored parent). Including them would
     *     prevent dedup.
     *   - `cardTransactionId` / `fromCardId` / `toCardId` are also remapped
     *     per-restore and may differ.
     *   - `note` is free text and may have been edited.
     *
     * The fingerprint uniquely identifies the ORIGINAL operation row
     * because `occurredAt` is a millisecond timestamp set at insertion
     * time and `amountMinor + direction + type + renterId + scooterId`
     * fully describe the financial fact.
     */
    private suspend fun dedupOrInsertBusinessOperation(
        op: JSONObject,
        renterId: Int?,
        scooterId: Int?,
        contractId: Int?,
        legacyTransactionId: Int?,
        fromCardId: Int?,
        toCardId: Int?,
        cardTransactionId: Int?
    ): Long {
        val occurredAt = op.optLong("occurredAt", System.currentTimeMillis())
        val amountMinor = op.optLong("amountMinor", 0L)
        val direction = op.optString("direction", BusinessOperation.DIRECTION_INCOME)
        val type = op.optString("type", BusinessOperation.TYPE_RENT_PAYMENT)
        val existing = db.businessOperationDao().findActiveByFingerprint(
            occurredAt, amountMinor, direction, type, renterId, scooterId
        )
        if (existing != null) {
            // Already restored via the other snapshot path. Don't insert
            // a duplicate — that would double-count income/expense.
            return existing.id
        }
        return db.businessOperationDao().insert(op.toBusinessOperation(
            renterId = renterId,
            scooterId = scooterId,
            contractId = contractId,
            legacyTransactionId = legacyTransactionId,
            status = BusinessOperation.STATUS_ACTIVE,
            fromCardId = fromCardId,
            toCardId = toCardId,
            cardTransactionId = cardTransactionId
        ))
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

    private fun JSONObject.toRenter(): Renter = Renter(
        name = optString("name"), phoneNumber = optString("phone"), debtAmount = optDouble("debt"),
        balance = -optDouble("debt"), rentDurationDays = optInt("duration", 7), rentStartDateTimestamp = optLong("start", System.currentTimeMillis()),
        scooterId = optInt("scooterId").takeIf { !isNull("scooterId") },
        scooterName = optString("scooterName").takeIf { !isNull("scooterName") },
        passportData = optString("passport"), address = optString("address"), pinfl = optString("pinfl")
    )

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

    private fun JSONObject.toScooter(): Scooter = Scooter(
        name = optString("name"),
        documentedNumber = optString("documentedNumber").takeIf { it.isNotBlank() },
        vinNumber = optString("vinNumber"), engineNumber = optString("engineNumber"),
        scooterSerialNumber = optString("scooterSerialNumber"),
        batteryId1 = optString("batteryId1"), batteryId2 = optString("batteryId2"),
        additionalInfo = optString("additionalInfo"),
        lifecycleStatus = optString("lifecycleStatus", Scooter.STATUS_AVAILABLE),
        lastServiceAt = if (isNull("lastServiceAt")) null else optLong("lastServiceAt"),
        nextServiceAt = if (isNull("nextServiceAt")) null else optLong("nextServiceAt"),
        mileageKm = optLong("mileageKm", 0L)
    )

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
     *  override foreign-key fields (renterId, scooterId, contractId,
     *  legacyTransactionId) and status so the restored row points to
     *  the newly-created parent. */
    private fun JSONObject.toBusinessOperation(
        renterId: Int? = optInt("renterId").takeIf { !isNull("renterId") && it > 0 },
        scooterId: Int? = optInt("scooterId").takeIf { !isNull("scooterId") && it > 0 },
        contractId: Int? = optInt("contractId").takeIf { !isNull("contractId") && it > 0 },
        legacyTransactionId: Int? = optInt("legacyTransactionId").takeIf { !isNull("legacyTransactionId") && it > 0 },
        status: String = optString("status", BusinessOperation.STATUS_ACTIVE),
        fromCardId: Int? = optInt("fromCardId").takeIf { !isNull("fromCardId") && it > 0 },
        toCardId: Int? = optInt("toCardId").takeIf { !isNull("toCardId") && it > 0 },
        cardTransactionId: Int? = optInt("cardTransactionId").takeIf { !isNull("cardTransactionId") && it > 0 }
    ): BusinessOperation = BusinessOperation(
        occurredAt = optLong("occurredAt", System.currentTimeMillis()),
        type = optString("type"),
        direction = optString("direction"),
        amountMinor = optLong("amountMinor", 0L),
        renterId = renterId,
        scooterId = scooterId,
        contractId = contractId,
        fromCardId = fromCardId,
        toCardId = toCardId,
        cardTransactionId = cardTransactionId,
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
        // parentPeriodId is intentionally NOT preserved on restore. The
        // unique index on contractHistoryId guarantees at most one period per
        // contract, so within-contract parent links are impossible. Cross-
        // contract parent links would dangle after restore if the parent was
        // also trashed. Null is the only safe value.
        parentPeriodId = null,
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

    /** Serialise a RepairOrder for snapshot storage. Keeps the id for
     *  reference only — restored rows get a new auto-generated id. */
    private fun RepairOrder.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("scenario", scenario); put("status", status); put("openedAt", openedAt)
        put("closedAt", closedAt); put("diagnosis", diagnosis); put("performer", performer)
        put("partsUsed", partsUsed); put("estimatedMinor", estimatedMinor)
        put("actualMinor", actualMinor); put("documentNote", documentNote)
        put("pauseIntervalsJson", pauseIntervalsJson); put("totalPauseMs", totalPauseMs)
        put("currentlyPaused", currentlyPaused); put("lastPausedAt", lastPausedAt)
        // renterId is kept so the restored order still shows who initiated
        // the repair (if known). Caller may override via toRepairOrder.
        put("renterId", renterId)
    }

    /** Deserialise a RepairOrder from snapshot, letting the caller override
     *  the scooterId (always required) so the restored row points to the
     *  newly-created scooter. */
    private fun JSONObject.toRepairOrder(scooterId: Int): RepairOrder = RepairOrder(
        scooterId = scooterId,
        renterId = optInt("renterId").takeIf { !isNull("renterId") && it > 0 },
        scenario = optString("scenario", RepairOrder.SCENARIO_OWNER_REPAIR),
        status = optString("status", RepairOrder.STATUS_OPEN),
        openedAt = optLong("openedAt", System.currentTimeMillis()),
        closedAt = optLong("closedAt").takeIf { !isNull("closedAt") && it > 0 },
        diagnosis = optString("diagnosis"),
        performer = optString("performer").takeIf { !isNull("performer") },
        partsUsed = optString("partsUsed").takeIf { !isNull("partsUsed") },
        estimatedMinor = optLong("estimatedMinor", 0L),
        actualMinor = optLong("actualMinor", 0L),
        documentNote = optString("documentNote").takeIf { !isNull("documentNote") },
        pauseIntervalsJson = optString("pauseIntervalsJson", "[]"),
        totalPauseMs = optLong("totalPauseMs", 0L),
        currentlyPaused = optBoolean("currentlyPaused", false),
        lastPausedAt = optLong("lastPausedAt").takeIf { !isNull("lastPausedAt") && it > 0 }
    )

    /** Serialise a CardTransaction for snapshot storage. Keeps the id for
     *  reference only — restored rows get a new auto-generated id. */
    private fun CardTransaction.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("fromCardId", fromCardId)
        put("toCardId", toCardId); put("amount", amount); put("note", note)
        put("type", type); put("contractId", contractId)
    }

    /** Deserialise a CardTransaction from snapshot, letting the caller
     *  override the contractId so the restored row points to the new
     *  contract. Also validates fromCardId / toCardId against the live DB
     *  — stale card references are nulled (mapped to EXTERNAL_SOURCE_ID = 0
     *  if fromCardId, kept as-is only if valid). */
    private fun JSONObject.toCardTransaction(
        contractId: Int?,
        validCardIds: Set<Int>
    ): CardTransaction = CardTransaction(
        timestamp = optLong("timestamp", System.currentTimeMillis()),
        fromCardId = optInt("fromCardId", 0).let { if (it == 0 || it in validCardIds) it else 0 },
        toCardId = optInt("toCardId", 0).let { if (it in validCardIds) it else 0 },
        amount = optDouble("amount", 0.0),
        note = optString("note").takeIf { !isNull("note") },
        type = optString("type", CardTransaction.TYPE_CARD_TRANSFER),
        contractId = contractId
    )

    /** Serialise an SmsDelivery for snapshot storage. Id is kept for
     *  reference only — restored rows get a new auto-generated id. */
    private fun SmsDelivery.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("status", status)
        put("messagePreview", messagePreview); put("error", error)
    }

    /** Deserialise an SmsDelivery from snapshot, letting the caller override
     *  the renterId so the restored row points to the new renter. */
    private fun JSONObject.toSmsDelivery(renterId: Int): SmsDelivery = SmsDelivery(
        renterId = renterId,
        timestamp = optLong("timestamp", System.currentTimeMillis()),
        status = optString("status", SmsDelivery.STATUS_SENT),
        messagePreview = optString("messagePreview"),
        error = optString("error").takeIf { !isNull("error") }
    )

    /** Serialise a NotificationHistoryEntity for snapshot storage. Id is
     *  kept for reference only — restored rows get a new auto-generated id. */
    private fun NotificationHistoryEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("title", title); put("message", message)
    }

    /** Deserialise a NotificationHistoryEntity from snapshot, letting the
     *  caller override the renterId so the restored row points to the new
     *  renter. */
    private fun JSONObject.toNotification(renterId: Int): NotificationHistoryEntity = NotificationHistoryEntity(
        timestamp = optLong("timestamp", System.currentTimeMillis()),
        renterId = renterId,
        title = optString("title"),
        message = optString("message")
    )
}
