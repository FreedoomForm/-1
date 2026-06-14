package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.NotificationHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Публикует уведомления о наступлении срока оплаты и сохраняет
 * копию каждого в таблицу `notification_history`.
 *
 * У уведомления две action-кнопки:
 *  • «To'lov qabul qilindi» — оплата прошла, но скутер остаётся
 *    у клиента (isReturned не меняется).
 *  • «Kontraktni uzish»      — контракт разорван: скутер возвращён
 *    на базу, оплата тоже прошла.
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val CHANNEL_ID = "payment_reminders"
    private const val CHANNEL_NAME = "To'lov eslatmalari"
    private const val CHANNEL_DESC = "Mijoz to'lov muddati eslatmalari"

    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
        }
    }

    fun postPaymentDueNotification(
        context: Context, renterId: Int, name: String, phone: String
    ) {
        val title = "To'lov muddati yetdi"
        val body = "Mijoz $name ($phone) bugun to'lov qilishi kerak"
        Log.d(TAG, "Posting notification for renter #$renterId: $name")

        // Открыть приложение при тапе на тело уведомления
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("renterId", renterId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, renterId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Принял оплату (долг уменьшается, скутер остаётся у клиента)
        val paymentIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PAYMENT_RECEIVED
            putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
        }
        val paymentPendingIntent = PendingIntent.getBroadcast(
            context, renterId * 10 + 0, paymentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Разорвать контракт (долг уменьшается, скутер возвращается)
        val terminateIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TERMINATE_CONTRACT
            putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
        }
        val terminatePendingIntent = PendingIntent.getBroadcast(
            context, renterId * 10 + 2, terminateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_save,
                "To'lov qabul qilindi",
                paymentPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_delete,
                "Kontraktni uzish",
                terminatePendingIntent
            )
            .build()

        try {
            context.getSystemService<NotificationManager>()?.notify(renterId, notification)
            saveToHistory(context, renterId, title, body)
            Log.d(TAG, "Notification #$renterId posted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification #$renterId", e)
        }
    }

    private fun saveToHistory(context: Context, renterId: Int, title: String, message: String) {
        historyScope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.notificationHistoryDao().insert(
                    NotificationHistoryEntity(
                        timestamp = System.currentTimeMillis(),
                        renterId = renterId,
                        title = title,
                        message = message
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save notification history", e)
            }
        }
    }
}
