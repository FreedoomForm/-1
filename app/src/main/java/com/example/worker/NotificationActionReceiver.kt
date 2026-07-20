package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.Renter
import com.example.data.RenterActionUseCase
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Получатель action-кнопок из уведомлений. Работает даже когда
 * приложение закрыто.
 *
 * После любой успешной обработки уведомление автоматически исчезает
 * из шторки (NotificationManagerCompat.cancel).
 *
 * Поддерживает два действия:
 *  • ACTION_PAYMENT_RECEIVED   — «To'lov qabul qilindi»: оплата одной недели.
 *      Делегирует в [RenterActionUseCase.payWeekly] — ТУ ЖЕ логику, что и
 *      кнопка «To'lash» в UI (RenterViewModel.payWeeklyForRenters).
 *      Гарантируется идентичное поведение: гашение долга или создание нового
 *      контракта, обновление баланса, Transaction(PAYMENT), зачисление на
 *      главную карту.
 *  • ACTION_TERMINATE_CONTRACT — «Kontraktni uzish»: расторжение контракта.
 *      Делегирует в [RenterActionUseCase.terminate] — ТУ ЖЕ логику, что и
 *      кнопка «Uzish» в UI (RenterViewModel.terminateRenters).
 *      Гарантируется идентичное поведение: погашение долга (если есть),
 *      пометка isReturned=true, ContractHistoryEntry(TERMINATED) +
 *      Transaction(TERMINATED).
 *
 * ВАЖНО: Раньше здесь была собственная логика, которая НЕ совпадала с
 * пользовательской (не создавались Transaction, не зачислялось на карту,
 * не обновлялся баланс при terminate). Теперь используется общий use-case,
 * что устраняет рассинхрон между UI и уведомлениями.
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
     * «To'lov qabul qilindi»: обрабатываем оплату одной недели.
     *
     * Полностью делегирует в [RenterActionUseCase.payWeekly] — ТУ ЖЕ функцию,
     * которую вызывает кнопка «To'lash» в UI. Это гарантирует идентичное
     * поведение:
     *   • гашение долга (balance < 0) — пометка контракта как оплаченного,
     *     обновление баланса, Transaction(PAYMENT), зачисление на главную карту;
     *   • предоплата (balance >= 0) — создание нового оплаченного контракта
     *     AUTO_RENEW, обновление баланса, Transaction(PAYMENT), зачисление
     *     на главную карту.
     */
    private fun handlePaymentReceived(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter: Renter? = db.renterDao().getRenterById(renterId)
                if (renter == null) {
                    Log.w(TAG, "Renter #$renterId not found, ignoring payment")
                    return@launch
                }

                val useCase = RenterActionUseCase.fromContext(context)
                useCase.payWeekly(
                    renter = renter,
                    notes = "To'lov qabul qilindi (bildirishnoma)"
                )

                NotificationManagerCompat.from(context).cancel(renterId)
                Log.d(TAG, "Payment: renter #$renterId processed via useCase, notification dismissed")
            } catch (e: Exception) {
                Log.e(TAG, "handlePaymentReceived failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * «Kontraktni uzish»: расторжение контракта.
     *
     * Полностью делегирует в [RenterActionUseCase.terminate] — ТУ ЖЕ функцию,
     * которую вызывает кнопка «Uzish» в UI. Это гарантирует идентичное
     * поведение:
     *   • если balance < 0 и есть неоплаченный контракт → оплачиваем его,
     *     создаём PAYMENT-запись + Transaction(PAYMENT) + зачисление на карту;
     *   • если balance < 0, но неоплаченных нет (рассинхрон) → обнуляем баланс;
     *   • если balance >= 0 → ничего не платим;
     *   • в любом случае isReturned=true, ContractHistoryEntry(TERMINATED) +
     *     Transaction(TERMINATED).
     */
    private fun handleTerminateContract(context: Context, renterId: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val renter: Renter? = db.renterDao().getRenterById(renterId)
                if (renter == null) {
                    Log.w(TAG, "Renter #$renterId not found, ignoring terminate")
                    return@launch
                }

                val weeklyPrice = SettingsRepository(context).weeklyPrice
                val useCase = RenterActionUseCase.fromContext(context)
                useCase.terminate(renter = renter, weeklyPrice = weeklyPrice)

                NotificationManagerCompat.from(context).cancel(renterId)
                Log.d(TAG, "Terminate: renter #$renterId processed via useCase, notification dismissed")
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
