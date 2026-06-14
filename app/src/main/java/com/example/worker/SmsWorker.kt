package com.example.worker

import android.content.Context
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = RenterRepository(db.renterDao())
            val settingsRepo = SettingsRepository(applicationContext)
            val activeRenters = repository.getActiveRenters()
            val currentTime = System.currentTimeMillis()

            activeRenters.forEach { renter ->
                val elapsedMillis = currentTime - renter.rentStartDateTimestamp
                val elapsedDays = (elapsedMillis / (1000 * 60 * 60 * 24)).toInt()

                if (elapsedDays > renter.rentDurationDays && !renter.isOverdueSmsSent) {
                    val daysOverdue = elapsedDays - renter.rentDurationDays
                    val rawTemplate = settingsRepo.smsTemplate
                    val message = rawTemplate
                        .replace("{name}", renter.name)
                        .replace("{days}", daysOverdue.toString())
                        .replace("{debt}", renter.debtAmount.toString())
                    
                    sendSms(renter.phoneNumber, message)
                    
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun sendSms(phone: String, message: String) {
        try {
            val smsManager = applicationContext.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phone, null, message, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
