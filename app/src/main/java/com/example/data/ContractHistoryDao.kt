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
     * Возвращает ВСЕ неоплаченные контракты арендатора (CREATED/AUTO_RENEW
     * с isPaid=false), отсортированные по weekStart ASC.
     *
     * Используется в SmsWorker для расчёта реальной суммы долга:
     *   unpaidDays = Σ (weekEnd - weekStart) / dayMs по всем неоплаченным
     *   debt = unpaidDays × dailyPrice
     *
     * Также используется в диалоге выбора дней для оплаты — показываем
     * пользователю, сколько недель он должен погасить.
     */
    @Query("""
        SELECT * FROM contract_history
        WHERE renterId = :renterId
          AND isPaid = 0
          AND type IN ('CREATED', 'AUTO_RENEW')
        ORDER BY weekStart ASC
    """)
    suspend fun getUnpaidContractsForRenter(renterId: Int): List<ContractHistoryEntry>

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

    /**
     * Suspend-вариант [getContractsForRenterFlow] — используется каскадным
     * удалением контракта для проверки, остались ли у арендатора ещё контракты
     * после удаления (если не осталось — помечаем isReturned=true).
     */
    @Query("""
        SELECT * FROM contract_history
        WHERE renterId = :renterId
          AND type IN ('CREATED', 'AUTO_RENEW')
        ORDER BY weekStart ASC
    """)
    suspend fun getContractsForRenterOnce(renterId: Int): List<ContractHistoryEntry>

    /**
     * Проверяет, есть ли уже контракт у арендатора с указанным weekStart.
     * Используется в PaymentCheckWorker.autoRenew() для защиты от дубликатов:
     * если для этой недели уже создан контракт (CREATED или AUTO_RENEW),
     * новый AUTO_RENEW не создаём — иначе при многократном запуске Worker'а
     * (например, после импорта старой базы, где сроки уже просрочены) мы бы
     * получили по 2-3 контракта на одну и ту же неделю.
     *
     * Сравнение weekStart = :weekStart — точное. У старой v33-базы weekStart
     * мог храниться как старт аренды (а не начало недели), поэтому при поиске
     * дубликата мы смотрим точное совпадение, а не «та же календарная неделя».
     */
    @Query("""
        SELECT * FROM contract_history
        WHERE renterId = :renterId
          AND type IN ('CREATED', 'AUTO_RENEW')
          AND weekStart = :weekStart
        LIMIT 1
    """)
    suspend fun getContractForWeek(renterId: Int, weekStart: Long): ContractHistoryEntry?

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
