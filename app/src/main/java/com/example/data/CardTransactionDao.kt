package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardTransactionDao {
    /**
     * All CardTransactions EXCLUDING those whose linked BusinessOperation
     * has status='REVERSED'.
     *
     * Batch 7 (fixes BLOCKER B2 — the E2 landmine): a CardTransaction row
     * has no `status` column of its own. The only signal that its financial
     * effect has been reversed is the `status='REVERSED'` flag on its linked
     * `BusinessOperation` (via `cardTransactionId`). Without this JOIN, any
     * code path that REVERSES a BusinessOperation without also hard-deleting
     * the linked CardTransaction (e.g. an imported legacy backup, or a
     * future code path that replaces the current hard-delete cascade)
     * would silently double-count the reversed amount in:
     *   - `CashFlowWidget` (ReportsScreen.kt:1477-1489)
     *   - `MainCardIncomeWidget` (ReportsScreen.kt:1564-1571)
     *   - `ReportsSummaryWidgetProvider` (lines 154-183)
     *   - The E1 fallback in `ReportsScreen.kt:191`
     *
     * `LEFT JOIN` + `(bo.id IS NULL OR bo.status = 'ACTIVE')` preserves
     * CardTransactions with NO linked BusinessOperation (legacy pre-
     * migration-23 rows that pre-date the universal journal) — they
     * continue to be reported as before.
     */
    @Query("""
        SELECT ct.* FROM card_transactions ct
        LEFT JOIN business_operations bo ON bo.cardTransactionId = ct.id
        WHERE bo.id IS NULL OR bo.status = 'ACTIVE'
        ORDER BY ct.timestamp DESC
    """)
    fun getAllTransactions(): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<CardTransaction>

    /**
     * CardTransactions for a specific card, EXCLUDING reversed ones
     * (same JOIN filter as `getAllTransactions`).
     *
     * Batch 7: previously this returned ALL rows for the card including
     * those whose linked BO had been reversed (silent double-count in
     * CardTransactionHistoryScreen). Now applies the same status filter
     * so the per-card history matches the financial reality.
     */
    @Query("""
        SELECT ct.* FROM card_transactions ct
        LEFT JOIN business_operations bo ON bo.cardTransactionId = ct.id
        WHERE (bo.id IS NULL OR bo.status = 'ACTIVE')
          AND (ct.fromCardId = :cardId OR ct.toCardId = :cardId)
        ORDER BY ct.timestamp DESC
    """)
    fun getTransactionsForCard(cardId: Int): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions WHERE type = :type ORDER BY timestamp DESC")
    fun getTransactionsByType(type: String): Flow<List<CardTransaction>>

    @Query("SELECT * FROM card_transactions WHERE contractId = :contractId ORDER BY timestamp DESC")
    suspend fun getForContractOnce(contractId: Int): List<CardTransaction>

    /**
     * Returns ALL CardTransaction rows. Used by BackupManager.importFromExcel
     * post-import diagnostic to detect rows referencing non-existent card
     * ids (would silently dangle against an empty virtual_cards table).
     */
    @Query("SELECT * FROM card_transactions ORDER BY id ASC")
    suspend fun getAllOnce(): List<CardTransaction>

    @Query("SELECT * FROM card_transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): CardTransaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: CardTransaction): Long

    @Update
    suspend fun updateTransaction(tx: CardTransaction)

    @Query("DELETE FROM card_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Int)

    @Query("SELECT COUNT(*) FROM card_transactions WHERE fromCardId = :cardId OR toCardId = :cardId")
    suspend fun countForCard(cardId: Int): Int

    @Query("DELETE FROM card_transactions WHERE contractId = :contractId")
    suspend fun deleteForContract(contractId: Int)

    @Query("DELETE FROM card_transactions")
    suspend fun deleteAll()
}
