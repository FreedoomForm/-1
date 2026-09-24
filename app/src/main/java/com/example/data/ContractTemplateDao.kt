package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с таблицей [ContractTemplate].
 *
 * По образцу [RenterDao] — soft-delete через isDeleted/deletedAt, отдельные
 * Flow для активных (live) и удалённых (trashed) записей, методы getById,
 * CRUD, восстановление из корзины.
 *
 * Особенность: метод [deactivateAllOfType] используется в
 * [ContractTemplateRepository.setActive] внутри транзакции для атомарного
 * переключения активной версии типа.
 */
@Dao
interface ContractTemplateDao {

    // ── Чтение ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type ORDER BY isActive DESC, updatedAt DESC, createdAt DESC")
    fun getForType(type: String): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type ORDER BY isActive DESC, updatedAt DESC, createdAt DESC")
    suspend fun getForTypeOnce(type: String): List<ContractTemplate>

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type AND name LIKE '%' || :query || '%' ORDER BY isActive DESC, updatedAt DESC, createdAt DESC")
    fun search(type: String, query: String): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 ORDER BY type ASC, isActive DESC, updatedAt DESC, createdAt DESC")
    fun getAll(): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashed(): Flow<List<ContractTemplate>>

    @Query("SELECT * FROM contract_templates WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ContractTemplate?

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type AND isActive = 1 LIMIT 1")
    suspend fun getActiveForType(type: String): ContractTemplate?

    @Query("SELECT * FROM contract_templates WHERE isDeleted = 0 AND type = :type AND isActive = 1 LIMIT 1")
    fun getActiveForTypeFlow(type: String): Flow<ContractTemplate?>

    // ── Запись ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: ContractTemplate): Long

    @Update
    suspend fun update(template: ContractTemplate)

    @Query("UPDATE contract_templates SET isActive = 0, updatedAt = :now WHERE type = :type AND isDeleted = 0")
    suspend fun deactivateAllOfType(type: String, now: Long = System.currentTimeMillis())

    // ── Trash mode (по образцу RenterDao / ContractHistoryDao v36) ─────────

    @Query("UPDATE contract_templates SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE contract_templates SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Int)

    @Query("DELETE FROM contract_templates WHERE id = :id")
    suspend fun permanentlyDelete(id: Int)

    @Query("DELETE FROM contract_templates WHERE isDeleted = 1")
    suspend fun emptyTrash()
}
