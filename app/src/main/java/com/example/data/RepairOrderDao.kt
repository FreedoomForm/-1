package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RepairOrderDao {
    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId ORDER BY openedAt DESC")
    fun forScooter(scooterId: Int): Flow<List<RepairOrder>>

    /** Used by BackupManager to export every row regardless of foreign keys. */
    @Query("SELECT * FROM repair_orders ORDER BY id ASC")
    suspend fun getAllOnce(): List<RepairOrder>

    /**
     * Returns ALL repair orders for a scooter (OPEN + COMPLETED + CANCELLED).
     * Used by TrashService.snapshotScooter so the full repair history can be
     * rebuilt after restoration from the recycle bin.
     */
    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId ORDER BY openedAt ASC, id ASC")
    suspend fun getAllForScooterOnce(scooterId: Int): List<RepairOrder>

    /** Used by BackupManager to truncate before re-import. */
    @Query("DELETE FROM repair_orders")
    suspend fun deleteAll()

    @Query("SELECT * FROM repair_orders WHERE status = 'OPEN' ORDER BY openedAt ASC")
    suspend fun openOrders(): List<RepairOrder>

    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId AND status = 'OPEN' ORDER BY openedAt ASC")
    suspend fun openForScooter(scooterId: Int): List<RepairOrder>

    /**
     * Закрывает все открытые repair orders для данного скутера.
     * Используется при удалении скутера, чтобы не оставлять «висящие»
     * OPEN orders со ссылкой на несуществующий скутер.
     */
    @Query("""
        UPDATE repair_orders
        SET status = 'COMPLETED',
            closedAt = :closedAt,
            documentNote = COALESCE(documentNote || ' | ', '') || :reason
        WHERE scooterId = :scooterId AND status = 'OPEN'
    """)
    suspend fun closeOpenForScooter(scooterId: Int, reason: String, closedAt: Long = System.currentTimeMillis())

    @Insert
    suspend fun insert(order: RepairOrder): Long

    @Update
    suspend fun update(order: RepairOrder)

    // ── §8: repair metrics queries ────────────────────────────────────────
    // Used by ScooterMetricsService to compute: total cost, average downtime,
    // repeat failures within 90 days.

    /** All completed repair orders for a scooter, ordered by close time. */
    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId AND status = 'COMPLETED' AND closedAt IS NOT NULL ORDER BY closedAt ASC")
    suspend fun completedForScooter(scooterId: Int): List<RepairOrder>

    /** All repair orders for a scooter in the last [windowMs] milliseconds. */
    @Query("SELECT * FROM repair_orders WHERE scooterId = :scooterId AND openedAt >= :sinceMs ORDER BY openedAt DESC")
    suspend fun forScooterSince(scooterId: Int, sinceMs: Long): List<RepairOrder>

    /** Count of repair orders for a scooter (all statuses). */
    @Query("SELECT COUNT(*) FROM repair_orders WHERE scooterId = :scooterId")
    suspend fun countForScooter(scooterId: Int): Int

    @Query("DELETE FROM repair_orders WHERE scooterId = :scooterId")
    suspend fun deleteByScooter(scooterId: Int)

    @Query("DELETE FROM repair_orders WHERE renterId = :renterId")
    suspend fun deleteByRenter(renterId: Int)
}
