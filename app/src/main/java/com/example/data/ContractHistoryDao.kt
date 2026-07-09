package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContractHistoryDao {
    @Query("SELECT * FROM contract_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ContractHistoryEntry>>

    @Query("SELECT * FROM contract_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<ContractHistoryEntry>

    @Query("SELECT * FROM contract_history WHERE renterId = :renterId ORDER BY timestamp DESC")
    suspend fun getForRenter(renterId: Int): List<ContractHistoryEntry>

    @Query("SELECT * FROM contract_history WHERE renterId = :renterId ORDER BY timestamp DESC")
    fun getForRenterFlow(renterId: Int): Flow<List<ContractHistoryEntry>>

    @Query("SELECT * FROM contract_history WHERE scooterName = :scooterName ORDER BY timestamp DESC")
    fun getForScooterFlow(scooterName: String): Flow<List<ContractHistoryEntry>>

    @Query("SELECT * FROM contract_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ContractHistoryEntry?

    /**
     * Возвращает самый ранний неоплаченный контракт арендатора
     * (CREATED или AUTO_RENEW с isPaid = false), отсортированный по weekStart.
     * Используется при оплате: если баланс < 0, нужно пометить оплаченным
     * именно самый ранний неоплаченный контракт.
     */
    @Query("""
        SELECT * FROM contract_history
        WHERE renterId = :renterId
          AND isPaid = 0
          AND type IN ('CREATED', 'AUTO_RENEW')
        ORDER BY weekStart ASC
        LIMIT 1
    """)
    suspend fun getEarliestUnpaidContract(renterId: Int): ContractHistoryEntry?

    /**
     * Возвращает самый поздний оплаченный контракт арендатора
     * (используется при предоплате для вычисления начала нового контракта).
     */
    @Query("""
        SELECT * FROM contract_history
        WHERE renterId = :renterId
          AND isPaid = 1
          AND type IN ('CREATED', 'AUTO_RENEW')
          AND weekEnd IS NOT NULL
        ORDER BY weekEnd DESC
        LIMIT 1
    """)
    suspend fun getLatestPaidContract(renterId: Int): ContractHistoryEntry?

    /**
     * Возвращает все контракты арендатора (CREATED + AUTO_RENEW),
     * отсортированные по weekStart ASC. Используется для экрана
     * истории контрактов, где показываются только контракты с зелёной/красной
     * линией статуса.
     */
    @Query("""
        SELECT * FROM contract_history
        WHERE renterId = :renterId
          AND type IN ('CREATED', 'AUTO_RENEW')
        ORDER BY weekStart ASC
    """)
    fun getContractsForRenterFlow(renterId: Int): Flow<List<ContractHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ContractHistoryEntry): Long

    @Update
    suspend fun update(entry: ContractHistoryEntry)

    @Query("DELETE FROM contract_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM contract_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM contract_history WHERE renterId = :renterId")
    suspend fun deleteForRenter(renterId: Int)

    @Query("DELETE FROM contract_history")
    suspend fun clear()

    @Query("DELETE FROM contract_history")
    suspend fun deleteAll()

    /** Обновляет renterId при смене id арендатора. */
    @Query("UPDATE contract_history SET renterId = :newId WHERE renterId = :oldId")
    suspend fun updateRenterId(oldId: Int, newId: Int)
}
