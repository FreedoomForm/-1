package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Получатель для action-кнопок в уведомлениях. Работает даже когда
 * приложение закрыто.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: action=$action renterId=$renterId")

        if (renterId == -1) {
            Log.w(TAG, "No renterId in extras, ignoring")
            return
        }

        when (action) {
            ACTION_PAYMENT_RECEIVED -> handlePaymentReceived(context, renterId)
            ACTION_REMIND_IN_ONE_HOUR -> handleRemindInOneHour(context, renterId)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    /**
     * «Принял оплату»: списываем недельный тариф из долга (но не ниже 0)
     * и помечаем арендатора как вернувшего скутер.
     */
    private fun handlePaymentReceived(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter = db.renterDao().getRenterById(renterId)
                if (renter == null) {
                    Log.w(TAG, "handlePaymentReceived: renter #$renterId not found")
                    return@launch
                }

                val weeklyPrice = SettingsRepository(context).weeklyPrice
                val newDebt = maxOf(0.0, renter.debtAmount - weeklyPrice)
                Log.d(TAG, "Payment received: renter #$renterId " +
                    "debt ${renter.debtAmount} → $newDebt (weekly=$weeklyPrice), mark returned")

                db.renterDao().updateRenter(
                    renter.copy(
                        debtAmount = newDebt,
                        isReturned = true,
                        lastPaymentTimestamp = System.currentTimeMillis(),
                        isOverdueSmsSent = false
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "handlePaymentReceived failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleRemindInOneHour(context: Context, renterId: Int) {
        val work = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .setInputData(
                workDataOf(
                    PaymentCheckWorker.KEY_RENTER_ID to renterId,
                    PaymentCheckWorker.KEY_ONE_TIME to true
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder_$renterId",
            ExistingWorkPolicy.REPLACE,
            work
        )
        Log.d(TAG, "Scheduled 1h reminder for renter #$renterId")
    }

    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_PAYMENT_RECEIVED = "com.example.ACTION_PAYMENT_RECEIVED"
        const val ACTION_REMIND_IN_ONE_HOUR = "com.example.ACTION_REMIND_IN_ONE_HOUR"
        const val EXTRA_RENTER_ID = "renterId"
    }
}
