package com.example.data

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Lightweight FK-consistency sweep that runs once per app launch.
 *
 * Batch 8 (was H2): previously there was NO orphan-row sweep at all.
 * When a `DeletedItem` was purged (or a backup import was partial, or
 * a pre-Batch-3 trash snapshot was missing dependent rows), the
 * dependent tables (PaymentAllocation, RentPeriod, HandoverAct,
 * CardTransaction, BusinessOperation) were left with dangling foreign
 * keys. The financial reports silently undercounted because joins
 * against the now-missing parent rows returned nothing.
 *
 * This sweeper is intentionally conservative:
 *   • It only repairs SAFE cases (null out dangling FKs, delete fully-
 *     orphan payment_allocations). It does NOT delete rows from
 *     `business_operations` — those are immutable audit facts (§0.3).
 *   • It runs SYNCHRONOUSLY inside the DB onOpen callback so the
 *     sweep completes before any user-facing query can observe a
 *     half-repaired state. The sweep is fast (3 single-pass queries
 *     + at most a few hundred row updates on a typical DB).
 *   • Every repair is logged via an ACTION_ORPHAN_SWEEP AuditEvent so
 *     post-incident review can reconstruct what was repaired and when.
 *
 * The sweeper is idempotent: running it twice produces the same final
 * state (the second run finds zero orphans and writes no audit event).
 */
object OrphanSweeper {
    private const val TAG = "OrphanSweeper"

    /**
     * Runs the full sweep. Called from AppDatabase.onOpen.
     *
     * Returns a summary string suitable for logging — e.g.
     * "bo.nullRent=2; bo.nullScooter=0; bo.nullContract=1; alloc.del=0".
     */
    fun sweep(db: SupportSQLiteDatabase): String {
        val now = System.currentTimeMillis()
        var boNullRent = 0
        var boNullScooter = 0
        var boNullContract = 0
        var boNullLegacyTx = 0
        var allocDeleted = 0
        var periodDeleted = 0
        var handoverDeleted = 0
        var cardTxNullContract = 0

        try {
            // ── 1. BusinessOperation: null out dangling renterId ────────
            // A BO with renterId pointing to a non-existent renter breaks
            // per-renter reports. We null the FK (the BO itself stays as
            // an immutable audit fact — §0.3).
            boNullRent = execUpdate(db,
                """UPDATE business_operations
                   SET renterId = NULL
                   WHERE renterId IS NOT NULL
                     AND renterId > 0
                     AND renterId NOT IN (SELECT id FROM renters)"""
            )
            // ── 2. BusinessOperation: null out dangling scooterId ───────
            boNullScooter = execUpdate(db,
                """UPDATE business_operations
                   SET scooterId = NULL
                   WHERE scooterId IS NOT NULL
                     AND scooterId > 0
                     AND scooterId NOT IN (SELECT id FROM scooters)"""
            )
            // ── 3. BusinessOperation: null out dangling contractId ──────
            boNullContract = execUpdate(db,
                """UPDATE business_operations
                   SET contractId = NULL
                   WHERE contractId IS NOT NULL
                     AND contractId > 0
                     AND contractId NOT IN (SELECT id FROM contract_history)"""
            )
            // ── 4. BusinessOperation: null out dangling legacyTransactionId
            boNullLegacyTx = execUpdate(db,
                """UPDATE business_operations
                   SET legacyTransactionId = NULL
                   WHERE legacyTransactionId IS NOT NULL
                     AND legacyTransactionId > 0
                     AND legacyTransactionId NOT IN (SELECT id FROM transactions)"""
            )
            // ── 5. PaymentAllocation: delete fully-orphan rows ──────────
            // A PaymentAllocation whose operationId OR rentPeriodId is
            // missing is meaningless — it would be invisible to all
            // reports. Safe to hard-delete (it's a derived allocation
            // table, not an audit-fact table).
            allocDeleted = execUpdate(db,
                """DELETE FROM payment_allocations
                   WHERE operationId NOT IN (SELECT id FROM business_operations)
                      OR rentPeriodId NOT IN (SELECT id FROM rent_periods)"""
            )
            // ── 6. RentPeriod: delete fully-orphan rows ─────────────────
            // A RentPeriod whose contractHistoryId is missing is also
            // meaningless — it would dangle and break per-contract billing
            // reports. Safe to hard-delete.
            periodDeleted = execUpdate(db,
                """DELETE FROM rent_periods
                   WHERE contractHistoryId NOT IN (SELECT id FROM contract_history)"""
            )
            // ── 7. HandoverAct: delete fully-orphan rows ────────────────
            handoverDeleted = execUpdate(db,
                """DELETE FROM handover_acts
                   WHERE contractHistoryId NOT IN (SELECT id FROM contract_history)"""
            )
            // ── 8. CardTransaction: null out dangling contractId ────────
            // A CardTransaction may reference a contract that was purged.
            // We null the FK so the CardTransaction stays (it carries its
            // own financial meaning via the linked BusinessOperation).
            cardTxNullContract = execUpdate(db,
                """UPDATE card_transactions
                   SET contractId = NULL
                   WHERE contractId IS NOT NULL
                     AND contractId > 0
                     AND contractId NOT IN (SELECT id FROM contract_history)"""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Orphan sweep failed — DB will be left partially repaired", e)
            // Don't rethrow: a partial sweep is still better than no sweep.
            // The next app launch will run again and finish the job.
        }

        val totalRepairs = boNullRent + boNullScooter + boNullContract + boNullLegacyTx +
            allocDeleted + periodDeleted + handoverDeleted + cardTxNullContract
        val summary = "bo.nullRent=$boNullRent; bo.nullScooter=$boNullScooter; " +
            "bo.nullContract=$boNullContract; bo.nullLegacyTx=$boNullLegacyTx; " +
            "alloc.del=$allocDeleted; period.del=$periodDeleted; " +
            "handover.del=$handoverDeleted; cardTx.nullContract=$cardTxNullContract"

        if (totalRepairs > 0) {
            // Write a single audit event summarising the sweep. We use a
            // raw INSERT instead of the DAO because the DAO is async and
            // we're inside a synchronous onOpen callback.
            try {
                db.execSQL(
                    """INSERT INTO audit_events
                       (occurredAt, actor, action, entityType, entityId, reason, beforeSnapshot, afterSnapshot)
                       VALUES (?, 'LOCAL_SYSTEM', ?, 'DATABASE', '0', ?, NULL, ?)""",
                    arrayOf<Any>(
                        now,
                        AuditEvent.ACTION_ORPHAN_SWEEP,
                        "FK-consistency sweep repaired $totalRepairs orphan row(s)",
                        summary
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write ORPHAN_SWEEP audit event", e)
            }
        }
        Log.i(TAG, "Sweep complete: $summary")
        return summary
    }

    /**
     * Executes a SQL UPDATE / DELETE statement and returns the number of
     * affected rows. `SupportSQLiteDatabase.execSQL` returns Unit, so we
     * must compile the statement and call `executeUpdateDelete()` to get
     * the count.
     */
    private fun execUpdate(db: SupportSQLiteDatabase, sql: String): Int {
        return db.compileStatement(sql).executeUpdateDelete()
    }
}
