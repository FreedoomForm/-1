package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SmsDeliveryDao {
    @Insert
    suspend fun insert(delivery: SmsDelivery): Long

    @Query("SELECT * FROM sms_deliveries ORDER BY id ASC")
    suspend fun allOnce(): List<SmsDelivery>

    @Query("SELECT * FROM sms_deliveries WHERE renterId = :renterId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestForRenter(renterId: Int): SmsDelivery?

    /**
     * Returns ALL SmsDelivery rows for a given renter. Used by
     * TrashService.snapshotRenter so SMS delivery history can be rebuilt
     * after restoration from the recycle bin.
     */
    @Query("SELECT * FROM sms_deliveries WHERE renterId = :renterId ORDER BY timestamp ASC, id ASC")
    suspend fun forRenterOnce(renterId: Int): List<SmsDelivery>

    @Query("SELECT * FROM sms_deliveries WHERE renterId = :renterId AND status = 'SENT' ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestSuccessfulForRenter(renterId: Int): SmsDelivery?

    @Query("DELETE FROM sms_deliveries WHERE renterId = :renterId")
    suspend fun deleteByRenter(renterId: Int)

    @Query("DELETE FROM sms_deliveries")
    suspend fun clear()
}
