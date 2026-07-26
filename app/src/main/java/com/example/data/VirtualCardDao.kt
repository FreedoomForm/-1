package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VirtualCardDao {
    @Query("SELECT * FROM virtual_cards ORDER BY id ASC")
    fun getAllCards(): Flow<List<VirtualCard>>

    @Query("SELECT * FROM virtual_cards ORDER BY id ASC")
    suspend fun getAllCardsOnce(): List<VirtualCard>

    @Query("SELECT * FROM virtual_cards WHERE id = :id LIMIT 1")
    suspend fun getCardById(id: Int): VirtualCard?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: VirtualCard): Long

    @Update
    suspend fun updateCard(card: VirtualCard)

    @Delete
    suspend fun deleteCard(card: VirtualCard)

    @Query("DELETE FROM virtual_cards WHERE id = :id AND isDefault = 0")
    suspend fun deleteCardIfNotDefault(id: Int): Int

    @Query("UPDATE virtual_cards SET balance = balance + :delta WHERE id = :id AND isArchived = 0")
    suspend fun adjustBalance(id: Int, delta: Double): Int

    @Query("UPDATE virtual_cards SET isArchived = 1 WHERE id = :id AND isDefault = 0 AND balance = 0")
    suspend fun archiveCard(id: Int): Int

    @Query("UPDATE virtual_cards SET isArchived = 0 WHERE id = :id AND isDefault = 0")
    suspend fun unarchiveCard(id: Int): Int

    @Query("SELECT COUNT(*) FROM virtual_cards")
    suspend fun count(): Int

    /** Удаляет все карты. Используется BackupManager'ом при импорте. */
    @Query("DELETE FROM virtual_cards")
    suspend fun deleteAll()
}
