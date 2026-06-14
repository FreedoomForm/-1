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
 * Получатель action-кнопок из уведомлений. Работает даже когда
 * приложение закрыто.
 *
 * Поддерживает два действия:
 *  • ACTION_PAYMENT_RECEIVED   — «To'lov qabul qilindi»:
 *      долг -= weeklyPrice, скутер остаётся у клиента.
 *  • ACTION_TERMINATE_CONTRACT — «Kontraktni uzish»:
 *      долг -= weeklyPrice, isReturned = true (контракт закрыт).
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
            ACTION_TERMINATE_CONTRACT -> handleTerminateContract(context, renterId)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    /**
     * «To'lov qabul qilindi»: списываем недельный тариф из долга (не ниже 0).
     * Скутер у клиента НЕ забираем — isReturned остаётся прежним.
     */
    private fun handlePaymentReceived(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter = db.renterDao().getRenterById(renterId) ?: return@launch

                val weeklyPrice = SettingsRepository(context).weeklyPrice
                val newDebt = maxOf(0.0, renter.debtAmount - weeklyPrice)
                Log.d(TAG, "Payment: renter #$renterId " +
                    "debt ${renter.debtAmount} → $newDebt (weekly=$weeklyPrice), scooter stays")

                db.renterDao().updateRenter(
                    renter.copy(
                        debtAmount = newDebt,
                        lastPaymentTimestamp = System.currentTimeMillis(),
                        isOverdueSmsSent = false
                        // isReturned НЕ меняется — клиент держит скутер
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "handlePaymentReceived failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * «Kontraktni uzish»: списываем недельный тариф из долга и помечаем
     * арендатора как вернувшего скутер (isReturned = true).
     */
    private fun handleTerminateContract(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter = db.renterDao().getRenterById(renterId) ?: return@launch

                val weeklyPrice = SettingsRepository(context).weeklyPrice
                val newDebt = maxOf(0.0, renter.debtAmount - weeklyPrice)
                Log.d(TAG, "Terminate: renter #$renterId " +
                    "debt ${renter.debtAmount} → $newDebt, mark returned")

                db.renterDao().updateRenter(
                    renter.copy(
                        debtAmount = newDebt,
                        isReturned = true,
                        lastPaymentTimestamp = System.currentTimeMillis(),
                        isOverdueSmsSent = false
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "handleTerminateContract failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_PAYMENT_RECEIVED = "com.example.ACTION_PAYMENT_RECEIVED"
        const val ACTION_TERMINATE_CONTRACT = "com.example.ACTION_TERMINATE_CONTRACT"
        const val EXTRA_RENTER_ID = "renterId"

        /** Запланировать напоминание через час. (Оставлено на случай, если
         *  понадобится для отладки или альтернативных сценариев.) */
        @Suppress("unused")
        fun scheduleOneHourReminder(context: Context, renterId: Int) {
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
    }
}
