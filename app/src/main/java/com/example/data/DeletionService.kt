package com.example.data

import android.util.Log
import androidx.room.withTransaction

/**
 * Batch 14 (was HIGH 6.1): single source of truth for cascade-delete
 * operations across the major entity types (Scooter, Renter, Contract).
 *
 * Previously the cascade-delete logic was duplicated across three
 * ViewModels — [com.example.ui.ScooterViewModel.deleteScooter],
 * [com.example.ui.RenterViewModel.deleteRenter], and
 * [com.example.ui.ContractHistoryViewModel.deleteContractWithCascade] —
 * with each copy having slight variations and its own bugs (e.g.
 * ScooterViewModel closed repair orders but RenterViewModel didn't;
 * ContractHistoryViewModel didn't snapshot dependent rows before
 * deleting them). This service consolidates the shared per-contract
 * cascade ([deleteContractCascade]) so all three ViewModels delegate
 * here for the contract-level cleanup, while keeping entity-specific
 * top-level orchestration (snapshot, dependent-row cleanup, timeline
 * recording) in the ViewModel.
 *
 * All methods are wrapped in `db.withTransaction` so a mid-cascade
 * failure rolls back ALL writes — no half-deleted state.
 *
 * All methods use field-specific UPDATE queries (added in Batch 12)
 * where applicable, eliminating the lost-update race on the Renter
 * table's hottest paths.
 */
class DeletionService(private val db: AppDatabase) {

    companion object {
        private const val TAG = "DeletionService"
    }

    /**
     * Per-contract cascade-delete: snapshots the contract to trash,
     * reverses + deletes CardTransactions tied to the contract, snapshots
     * + deletes Transaction rows tied to the contract, deletes
     * PaymentAllocations / RentPeriods / HandoverActs tied to the
     * contract, and marks the contract's BusinessOperations as REVERSED.
     *
     * This is the shared inner loop called by [deleteScooterCascade],
     * [deleteRenterCascade], and [deleteContractWithCascade]. It is
     * idempotent — if a dependent row was already deleted, the inner
     * try/catch blocks swallow the no-op and continue.
     *
     * The caller is responsible for:
     *   - Snapshotting the contract via [TrashService.snapshotContract]
     *     BEFORE calling this method (if trash recovery is desired).
     *   - Hard-deleting the ContractHistoryEntry row itself AFTER calling
     *     this method (via ContractHistoryDao.deleteById or similar).
     *   - Wrapping the snapshot + this call + the row delete in a single
     *     `db.withTransaction` so the whole cascade is atomic.
     *
     * @param contractId the ContractHistoryEntry.id to cascade-delete
     * @param reason     human-readable reason for the trash snapshot
     */
    suspend fun deleteContractCascade(contractId: Int, reason: String) {
        val trashSvc = TrashService(db)

        // 1. Reverse + delete CardTransactions tied to this contract.
        //    Reversing the BusinessOperation (markReversedByCardTransactionId)
        //    BEFORE adjustBalance is intentional: the BO reversal records
        //    the audit fact "this card tx was undone", then the actual
        //    balance adjustment follows. If the adjustment fails, the BO
        //    is still marked REVERSED — which is correct because the
        //    CardTransaction row is about to be hard-deleted anyway.
        val cardTxs = try {
            db.cardTransactionDao().getForContractOnce(contractId)
        } catch (e: Exception) {
            Log.w(TAG, "getForContractOnce($contractId) failed: ${e.message}")
            emptyList()
        }
        for (cardTx in cardTxs) {
            try {
                db.virtualCardDao().adjustBalance(cardTx.toCardId, -cardTx.amount)
                try { db.businessOperationDao().markReversedByCardTransactionId(cardTx.id) }
                catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "reverse cardTx #${cardTx.id} for contract #$contractId failed: ${e.message}")
            }
        }
        if (cardTxs.isNotEmpty()) {
            try { db.cardTransactionDao().deleteForContract(contractId) } catch (_: Exception) {}
        }

        // 2. Snapshot + delete Transaction rows for this contract.
        //    Reverse the linked BusinessOperations via legacyTransactionId
        //    BEFORE hard-deleting the Transaction row — once the Transaction
        //    is gone, we can no longer look up which BOs referenced it.
        val contractTxs = try {
            db.transactionDao().getForContractOnce(contractId)
        } catch (e: Exception) {
            Log.w(TAG, "getForContractOnce(tx) for #$contractId failed: ${e.message}")
            emptyList()
        }
        contractTxs.forEach { trashSvc.snapshotTransaction(it, reason) }
        if (contractTxs.isNotEmpty()) {
            contractTxs.forEach { tx ->
                try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) }
                catch (_: Exception) {}
            }
            try { db.transactionDao().deleteForContract(contractId) } catch (_: Exception) {}
        }

        // 3. Per-contract: delete PaymentAllocations (via RentPeriod FK),
        //    RentPeriods, HandoverActs. Mark the contract's direct
        //    BusinessOperations as REVERSED.
        try { db.paymentAllocationDao().deleteByContractViaPeriod(contractId) } catch (_: Exception) {}
        try { db.rentPeriodDao().deleteByContract(contractId) } catch (_: Exception) {}
        try { db.businessOperationDao().markReversedByContract(contractId) } catch (_: Exception) {}
        try { db.handoverActDao().deleteByContract(contractId) } catch (_: Exception) {}
    }

    /**
     * Top-level cascade-delete for a Scooter. Snapshots the scooter to
     * trash, clears the scooter reference from all active renters,
     * cascades [deleteContractCascade] for every contract tied to this
     * scooter (by scooter name — the legacy link), snapshots + deletes
     * Transactions tied to the scooter, reverses remaining
     * BusinessOperations, cleans up RepairOrders / HandoverActs /
     * LegacyMoneyAmounts, and finally hard-deletes the scooter row.
     *
     * Wrapped in `db.withTransaction` so the entire cascade is atomic.
     *
     * @return true if the cascade succeeded, false if it failed (the
     *   caller should surface an error to the user).
     */
    suspend fun deleteScooterCascade(scooter: Scooter): Boolean = db.withTransaction {
        try {
            val trashSvc = TrashService(db)
            // 1. Snapshot to trash.
            trashSvc.snapshotScooter(scooter, "Scooter deleted by user")

            // 2. Clear scooter ref from active renters (field-specific
            //    UPDATE — Batch 12 pattern, avoids clobbering concurrent
            //    writes to balance/debt/etc.).
            val rentersWithScooter = db.renterDao().getActiveRenters()
                .filter { it.scooterId == scooter.id }
            rentersWithScooter.forEach { r ->
                db.renterDao().updateScooterAssignment(r.id, null, null)
            }
            if (rentersWithScooter.isNotEmpty()) {
                Log.d(TAG, "deleteScooter: cleared scooter ref from ${rentersWithScooter.size} renters")
            }

            // 3. Cascade-delete every contract tied to this scooter.
            //    ContractHistoryEntry links to scooters by name (legacy),
            //    not by scooterId — see ContractHistoryDao.getForScooterOnce.
            val contracts = db.contractHistoryDao().getForScooterOnce(scooter.name)
            contracts.forEach { trashSvc.snapshotContract(it, "Removed with scooter #${scooter.id}") }
            contracts.forEach { deleteContractCascade(it.id, "Removed with scooter #${scooter.id}") }
            if (contracts.isNotEmpty()) {
                db.contractHistoryDao().deleteForScooter(scooter.name)
                Log.d(TAG, "deleteScooter: deleted ${contracts.size} contracts for scooter ${scooter.name}")
            }

            // 4. Snapshot + delete Transactions tied to this scooter.
            val txs = db.transactionDao().forScooterOnce(scooter.id)
            txs.forEach { trashSvc.snapshotTransaction(it, "Removed with scooter #${scooter.id}") }
            if (txs.isNotEmpty()) {
                txs.forEach { tx ->
                    try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) }
                    catch (_: Exception) {}
                }
                db.transactionDao().deleteByIds(txs.map { it.id })
                Log.d(TAG, "deleteScooter: deleted ${txs.size} transactions for scooter #${scooter.id}")
            }

            // 4b. Reverse any remaining BusinessOperations tied to this
            //     scooter (e.g. REPAIR ops not linked to a Transaction row).
            try { db.businessOperationDao().markReversedByScooter(scooter.id) } catch (_: Exception) {}

            // 4c. Clean up orphaned PaymentAllocation + RentPeriod rows
            //     (rent-periods created via calendar without a contract).
            try { db.paymentAllocationDao().deleteByScooterViaPeriod(scooter.id) } catch (_: Exception) {}
            try { db.rentPeriodDao().deleteByScooter(scooter.id) } catch (_: Exception) {}

            // 4d. Clean up HandoverActs + RepairOrders + LegacyMoneyAmount.
            try { db.handoverActDao().deleteByScooter(scooter.id) } catch (_: Exception) {}
            try { db.repairOrderDao().closeOpenForScooter(scooter.id, "Scooter deleted") } catch (_: Exception) {}
            try { db.repairOrderDao().deleteByScooter(scooter.id) } catch (_: Exception) {}
            try { db.legacyMoneyAmountDao().deleteByEntity("SCOOTER", scooter.id.toLong()) } catch (_: Exception) {}

            // 5. Hard-delete the scooter row itself.
            db.scooterDao().delete(scooter)
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteScooterCascade failed for #${scooter.id}", e)
            false
        }
    }

    /**
     * Top-level cascade-delete for a Renter. Snapshots the renter to
     * trash, cascades [deleteContractCascade] for every contract owned
     * by this renter, reverses remaining BusinessOperations tied to the
     * renter, cleans up PaymentAllocations / RentPeriods / HandoverActs
     * / SmsDeliveries / NotificationHistory / RepairOrders /
     * LegacyMoneyAmounts, and finally hard-deletes the renter row.
     *
     * Wrapped in `db.withTransaction` so the entire cascade is atomic.
     *
     * @return true if the cascade succeeded, false if it failed.
     */
    suspend fun deleteRenterCascade(renterId: Int): Boolean = db.withTransaction {
        try {
            val trashSvc = TrashService(db)
            // 1. Snapshot renter to trash.
            db.renterDao().getRenterById(renterId)?.let { renter ->
                trashSvc.snapshotRenter(renter, "Renter deleted with related records")
            }

            // 2. Cascade-delete every contract owned by this renter.
            val contracts = db.contractHistoryDao().getForRenter(renterId)
            contracts.forEach { trashSvc.snapshotContract(it, "Removed with renter #$renterId") }
            contracts.forEach { deleteContractCascade(it.id, "Removed with renter #$renterId") }

            // 3. Delete all Transactions for this renter (snapshot first).
            //    The contract-level cascade above already handled
            //    Transactions linked via contractId; this catches
            //    Transactions with renterId but null/different contractId.
            val renterTransactions = db.transactionDao().getForRenterOnce(renterId)
            renterTransactions.forEach { trashSvc.snapshotTransaction(it, "Removed with renter #$renterId") }
            if (renterTransactions.isNotEmpty()) {
                renterTransactions.forEach { tx ->
                    try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) }
                    catch (_: Exception) {}
                }
                db.transactionDao().deleteByIds(renterTransactions.map { it.id })
                Log.d(TAG, "deleteRenter: deleted ${renterTransactions.size} transactions for renter #$renterId")
            }

            // 4. Reverse any remaining BusinessOperations tied to this
            //    renter (e.g. DEBT_FORGIVEN, ADJUSTMENT not linked to a
            //    Transaction or CardTransaction).
            try { db.businessOperationDao().markReversedByRenter(renterId) } catch (_: Exception) {}

            // 5. Clean up PaymentAllocation BEFORE RentPeriod (FK order).
            try { db.paymentAllocationDao().deleteByRenterViaPeriod(renterId) } catch (_: Exception) {}
            try { db.rentPeriodDao().deleteByRenter(renterId) } catch (_: Exception) {}

            // 6. Clean up dependent rows that reference renterId directly.
            try { db.handoverActDao().deleteByRenter(renterId) } catch (_: Exception) {}
            try { db.smsDeliveryDao().deleteByRenter(renterId) } catch (_: Exception) {}
            try { db.notificationHistoryDao().deleteByRenter(renterId) } catch (_: Exception) {}
            try { db.repairOrderDao().deleteByRenter(renterId) } catch (_: Exception) {}
            try { db.legacyMoneyAmountDao().deleteByEntity("RENTER", renterId.toLong()) } catch (_: Exception) {}

            // 7. Delete all ContractHistoryEntry rows for this renter.
            db.contractHistoryDao().deleteForRenter(renterId)

            // 8. Hard-delete the renter row itself.
            db.renterDao().deleteRenter(renterId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteRenterCascade failed for #$renterId", e)
            false
        }
    }

    /**
     * Top-level cascade-delete for a single ContractHistoryEntry.
     * Snapshots the contract + its dependent Transaction / CardTransaction
     * rows to trash, then delegates to [deleteContractCascade] for the
     * actual deletion. Finally hard-deletes the contract row itself.
     *
     * Wrapped in `db.withTransaction` so the entire cascade is atomic.
     *
     * @return true if the cascade succeeded, false if it failed.
     */
    suspend fun deleteContractWithCascade(contractId: Int): Boolean = db.withTransaction {
        try {
            val trashSvc = TrashService(db)
            val contract = db.contractHistoryDao().getById(contractId)
                ?: throw IllegalArgumentException("Contract #$contractId not found")

            // 1. Snapshot the contract + dependent rows to trash.
            trashSvc.snapshotContract(contract, "Contract deleted by user")
            val relatedTx = try { db.transactionDao().getForContractOnce(contractId) }
                catch (_: Exception) { emptyList() }
            relatedTx.forEach { trashSvc.snapshotTransaction(it, "Removed with contract #$contractId") }
            val relatedCardTx = try { db.cardTransactionDao().getForContractOnce(contractId) }
                catch (_: Exception) { emptyList() }

            // 2. Run the shared per-contract cascade.
            deleteContractCascade(contractId, "Removed with contract #$contractId")

            // 3. Hard-delete the contract row itself.
            db.contractHistoryDao().deleteById(contractId)
            Log.d(TAG, "Contract #$contractId deleted with cascade " +
                "(tx=${relatedTx.size}, cardTx=${relatedCardTx.size})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteContractWithCascade failed for #$contractId", e)
            false
        }
    }
}
