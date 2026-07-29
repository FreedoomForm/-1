package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LegacyMoneyAmountDao {
    @Query("SELECT * FROM legacy_money_amounts WHERE entityType = :entityType AND entityId = :entityId")
    fun forEntity(entityType: String, entityId: Long): Flow<List<LegacyMoneyAmount>>

    /** Used by BackupManager to export every row. */
    @Query("SELECT * FROM legacy_money_amounts ORDER BY id ASC")
    suspend fun getAllOnce(): List<LegacyMoneyAmount>

    /** Used by BackupManager to truncate before re-import. */
    @Query("DELETE FROM legacy_money_amounts")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(amount: LegacyMoneyAmount): Long

    @Query("SELECT COUNT(*) FROM legacy_money_amounts")
    suspend fun count(): Int

    @Query("DELETE FROM legacy_money_amounts WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: Long)
}
