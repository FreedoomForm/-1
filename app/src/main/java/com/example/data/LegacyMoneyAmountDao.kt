package com.example.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LegacyMoneyAmountDao {
    @Query("SELECT * FROM legacy_money_amounts WHERE entityType = :entityType AND entityId = :entityId")
    fun forEntity(entityType: String, entityId: Long): Flow<List<LegacyMoneyAmount>>

    @Query("SELECT COUNT(*) FROM legacy_money_amounts")
    suspend fun count(): Int
}
