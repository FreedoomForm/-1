package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RenterDao {
    @Query("SELECT * FROM renters ORDER BY isReturned ASC, rentStartDateTimestamp DESC")
    fun getAllRenters(): Flow<List<Renter>>

    @Query("SELECT * FROM renters ORDER BY isReturned ASC, rentStartDateTimestamp DESC")
    suspend fun getAllRentersOnce(): List<Renter>

    @Query("SELECT * FROM renters WHERE isReturned = 0")
    suspend fun getActiveRenters(): List<Renter>

    @Query("SELECT * FROM renters WHERE id = :id LIMIT 1")
    suspend fun getRenterById(id: Int): Renter?

    /**
     * Duplicate-name detection (case-insensitive) for new/edit renter.
     * Returns count of renters whose name matches [name] (excluding [excludeId]).
     * Used by RenterViewModel to block creation of duplicate renters.
     */
    @Query("""
        SELECT COUNT(*) FROM renters
        WHERE id != :excludeId AND lower(trim(name)) = lower(trim(:name))
    """)
    suspend fun duplicateNameCount(name: String, excludeId: Int): Int

    /** Same as above but matches by phone — useful for blocking duplicate phones. */
    @Query("""
        SELECT COUNT(*) FROM renters
        WHERE id != :excludeId
          AND :phone != ''
          AND lower(trim(phoneNumber)) = lower(trim(:phone))
    """)
    suspend fun duplicatePhoneCount(phone: String, excludeId: Int): Int

    /** §12: Total renter count — used by migration tests and analytics. */
    @Query("SELECT COUNT(*) FROM renters")
    suspend fun getCount(): Int

    /** Возвращает сгенерированный rowId — нужно для немедленного уведомления при создании. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRenter(renter: Renter): Long

    suspend fun insert(renter: Renter): Long = insertRenter(renter)

    @Update
    suspend fun updateRenter(renter: Renter)

    suspend fun update(renter: Renter) = updateRenter(renter)

    // ── Batch 10 (was HIGH B4): field-specific UPDATE queries ──────────
    // The full-entity @Update above is a lost-update landmine on hot paths:
    // if coroutine A reads the renter, modifies one field, and writes back
    // while coroutine B is doing the same with a different field, B's write
    // clobbers A's. The most common offender is SmsWorker (sets
    // isOverdueSmsSent=true) racing with PaymentCheckWorker.autoRenew
    // (increments rentDurationDays). The field-specific UPDATEs below
    // touch ONLY the column they intend to change, eliminating the race.
    @Query("UPDATE renters SET isOverdueSmsSent = :flag WHERE id = :id")
    suspend fun updateOverdueSmsFlag(id: Int, flag: Boolean)

    @Query("UPDATE renters SET lastPaymentTimestamp = :timestamp WHERE id = :id")
    suspend fun updateLastPaymentTimestamp(id: Int, timestamp: Long)

    // ── Batch 12 (was HIGH B4 carryover): more field-specific UPDATE
    // queries. The remaining full-entity @Update callers in
    // PaymentCheckWorker.autoRenew (rentDurationDays + isOverdueSmsSent),
    // RenterActionUseCase.payWeekly/_terminateInternal (balance, debtAmount,
    // lastPaymentTimestamp, isOverdueSmsSent, isReturned),
    // RentPeriodAccountingService.acceptPayment (balance, debtAmount,
    // lastPaymentTimestamp, isOverdueSmsSent), and
    // ScooterMaintenanceService.replaceScooterForActiveRental (scooterId,
    // scooterName) all rewrite the entire row. A concurrent field-specific
    // UPDATE (e.g. SmsWorker setting isOverdueSmsSent=true, or a future
    // scooter-assignment edit) is silently clobbered. The methods below
    // touch ONLY the columns each caller intends to mutate, eliminating
    // the lost-update race on the Renter table's hottest paths.
    @Query("UPDATE renters SET rentDurationDays = :days WHERE id = :id")
    suspend fun updateRentDurationDays(id: Int, days: Int)

    @Query("UPDATE renters SET isReturned = :returned WHERE id = :id")
    suspend fun updateReturnedFlag(id: Int, returned: Boolean)

    @Query("UPDATE renters SET balance = :balance, debtAmount = :debtAmount WHERE id = :id")
    suspend fun updateBalanceAndDebt(id: Int, balance: Double, debtAmount: Double)

    @Query("UPDATE renters SET scooterId = :scooterId, scooterName = :scooterName WHERE id = :id")
    suspend fun updateScooterAssignment(id: Int, scooterId: Int?, scooterName: String?)

    @Query("DELETE FROM renters WHERE id = :id")
    suspend fun deleteRenter(id: Int)

    suspend fun delete(id: Int) = deleteRenter(id)

    @Query("DELETE FROM renters")
    suspend fun deleteAll()

    /** Обновляет id арендатора (для замены локального id на серверный). */
    @Query("UPDATE renters SET id = :newId WHERE id = :oldId")
    suspend fun updateRenterId(oldId: Int, newId: Int)
}
