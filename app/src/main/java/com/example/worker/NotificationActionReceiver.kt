package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Получатель для action-кнопок в уведомлениях. Срабатывает даже когда
 * приложение закрыто — пользователь тапает «Принял оплату» или
 * «Напомнить через час» прямо в шторке уведомлений Android.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
        if (renterId == -1) return

        when (intent.action) {
            ACTION_PAYMENT_RECEIVED -> handlePaymentReceived(context, renterId)
            ACTION_REMIND_IN_ONE_HOUR -> handleRemindInOneHour(context, renterId)
        }
    }

    private fun handlePaymentReceived(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter = db.renterDao().getRenterById(renterId)
                if (renter != null) {
                    db.renterDao().updateRenter(
                        renter.copy(
                            debtAmount = 0.0,
                            lastPaymentTimestamp = System.currentTimeMillis(),
                            isOverdueSmsSent = false
                        )
                    )
                }
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
    }

    companion object {
        const val ACTION_PAYMENT_RECEIVED = "com.example.ACTION_PAYMENT_RECEIVED"
        const val ACTION_REMIND_IN_ONE_HOUR = "com.example.ACTION_REMIND_IN_ONE_HOUR"
        const val EXTRA_RENTER_ID = "renterId"
    }
}
