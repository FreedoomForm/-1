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

    // ── Trash-mode queries (v36+) ────────────────────────────────────────
    // Системные карты (isDefault=1) всегда показываются в обоих режимах —
    // их нельзя удалить в корзину. Пользовательские — фильтруются по isDeleted.
    @Query("SELECT * FROM virtual_cards WHERE isDeleted = 0 ORDER BY id ASC")
    fun getLiveCards(): Flow<List<VirtualCard>>

    @Query("SELECT * FROM virtual_cards WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashedCards(): Flow<List<VirtualCard>>

    @Query("UPDATE virtual_cards SET isDeleted = 1, deletedAt = :now WHERE id = :id AND isDefault = 0")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE virtual_cards SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)

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

    @Query("UPDATE virtual_cards SET balance = balance + :delta WHERE id = :id")
    suspend fun adjustBalance(id: Int, delta: Double)

    @Query("SELECT COUNT(*) FROM virtual_cards")
    suspend fun count(): Int

    /** Удаляет все карты. Используется BackupManager'ом при импорте. */
    @Query("DELETE FROM virtual_cards")
    suspend fun deleteAll()
}
