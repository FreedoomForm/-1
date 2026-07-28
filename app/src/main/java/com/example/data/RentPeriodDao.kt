package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RentPeriodDao {
    @Query("SELECT * FROM rent_periods WHERE renterId = :renterId ORDER BY startsAt ASC")
    fun forRenter(renterId: Int): Flow<List<RentPeriod>>

    @Query("SELECT * FROM rent_periods WHERE renterId = :renterId ORDER BY startsAt ASC")
    suspend fun getAllForRenter(renterId: Int): List<RentPeriod>

    @Query("SELECT * FROM rent_periods ORDER BY startsAt ASC, id ASC")
    fun all(): Flow<List<RentPeriod>>

    @Query("SELECT * FROM rent_periods ORDER BY id ASC")
    suspend fun getAllOnce(): List<RentPeriod>

    @Query("SELECT * FROM rent_periods WHERE renterId = :renterId AND status NOT IN ('PAID','CLOSED','CANCELLED','SUSPENDED_REPAIR') ORDER BY endsAt ASC, id ASC")
    suspend fun openForRenter(renterId: Int): List<RentPeriod>

    @Query("SELECT * FROM rent_periods WHERE contractHistoryId = :contractHistoryId LIMIT 1")
    suspend fun byContractHistoryId(contractHistoryId: Int): RentPeriod?

    @Query("SELECT * FROM rent_periods WHERE renterId = :renterId AND status = 'SCHEDULED' AND startsAt <= :now ORDER BY startsAt ASC, id ASC")
    suspend fun scheduledDueForRenter(renterId: Int, now: Long): List<RentPeriod>

    @Query("SELECT * FROM rent_periods WHERE scooterId = :scooterId AND status IN ('SCHEDULED','ACTIVE','PARTIALLY_PAID','OVERDUE') AND startsAt < :endsAt AND endsAt > :startsAt")
    suspend fun conflictsForScooter(scooterId: Int, startsAt: Long, endsAt: Long): List<RentPeriod>

    @Query("SELECT * FROM rent_periods WHERE scooterId = :scooterId AND status IN ('ACTIVE','PARTIALLY_PAID','PAID','OVERDUE')")
    suspend fun billableForScooter(scooterId: Int): List<RentPeriod>

    @Query("SELECT * FROM rent_periods WHERE scooterId = :scooterId AND status = 'SUSPENDED_REPAIR'")
    suspend fun suspendedForScooter(scooterId: Int): List<RentPeriod>

    @Query("SELECT * FROM rent_periods WHERE scooterId = :scooterId AND status IN ('SCHEDULED','ACTIVE','PARTIALLY_PAID','OVERDUE','SUSPENDED_REPAIR')")
    suspend fun currentForScooter(scooterId: Int): List<RentPeriod>

    @Query("UPDATE rent_periods SET scooterId = :newScooterId, updatedAt = :timestamp WHERE scooterId = :oldScooterId AND status IN ('SCHEDULED','ACTIVE','PARTIALLY_PAID','OVERDUE','SUSPENDED_REPAIR')")
    suspend fun reassignScooter(oldScooterId: Int, newScooterId: Int, timestamp: Long): Int

    @Insert
    suspend fun insert(period: RentPeriod): Long

    @Update
    suspend fun update(period: RentPeriod)

    @Query("""
        UPDATE rent_periods SET
          status = CASE WHEN paidMinor >= chargeMinor THEN 'CLOSED' ELSE 'CLOSED_WITH_DEBT' END,
          updatedAt = :timestamp
        WHERE renterId = :renterId AND status IN ('ACTIVE','PARTIALLY_PAID','OVERDUE')
    """)
    suspend fun closeOpenForRenter(renterId: Int, timestamp: Long): Int

    @Query("UPDATE rent_periods SET status = 'CANCELLED', updatedAt = :timestamp WHERE renterId = :renterId AND status = 'SCHEDULED'")
    suspend fun cancelScheduledForRenter(renterId: Int, timestamp: Long): Int

    @Query("DELETE FROM rent_periods")
    suspend fun clear()
}

@Dao
interface PaymentAllocationDao {
    @Query("SELECT * FROM payment_allocations ORDER BY id ASC")
    suspend fun getAllOnce(): List<PaymentAllocationEntity>

    @Insert
    suspend fun insert(allocation: PaymentAllocationEntity): Long

    @Query("SELECT * FROM payment_allocations WHERE operationId = :operationId ORDER BY id ASC")
    suspend fun forOperation(operationId: Long): List<PaymentAllocationEntity>

    @Query("DELETE FROM payment_allocations")
    suspend fun clear()
}
