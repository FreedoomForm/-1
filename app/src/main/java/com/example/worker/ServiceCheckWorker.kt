package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Daily reminder for scooters whose nextServiceAt date has arrived. */
class ServiceCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val now = System.currentTimeMillis()
            NotificationHelper.createChannel(applicationContext)
            val prefs = applicationContext.getSharedPreferences("service_reminders", Context.MODE_PRIVATE)
            val day = now / (24L * 60 * 60 * 1000)
            db.scooterDao().serviceDue(now).forEach { scooter ->
                val key = "scooter_${scooter.id}_day"
                if (prefs.getLong(key, -1L) != day) {
                    NotificationHelper.postServiceDueNotification(applicationContext, scooter.id, scooter.name)
                    prefs.edit().putLong(key, day).apply()
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
