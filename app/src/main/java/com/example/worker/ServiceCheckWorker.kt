package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Daily reminder for scooters whose nextServiceAt date has arrived. */
class ServiceCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Batch 14 (was LOW 4.3): narrowed the catch from `Exception` to
        // `IOException`. Previously ANY exception (including NPE,
        // IllegalArgumentException, IllegalStateException — programming
        // bugs) was caught and silently retried forever with no log
        // output. A persistent programming error caused the worker to
        // retry indefinitely, masking the bug. Now only transient I/O
        // failures are retried; programming errors propagate as
        // Result.failure() and are logged so they surface in crash
        // reports / logcat instead of being silently swallowed.
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
        } catch (e: IOException) {
            // Transient I/O failure — retry with backoff.
            Log.w(TAG, "ServiceCheckWorker transient I/O failure, will retry", e)
            Result.retry()
        } catch (e: Exception) {
            // Programming error — don't retry forever. Log and fail.
            Log.e(TAG, "ServiceCheckWorker unrecoverable failure, will NOT retry", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ServiceCheckWorker"
    }
}
