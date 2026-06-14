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
 * Управляет каналом уведомлений, публикует напоминания о сроке оплаты и
 * сохраняет копию каждого уведомления в таблицу `notification_history`.
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val CHANNEL_ID = "payment_reminders"
    private const val CHANNEL_NAME = "To'lov eslatmalari"
    private const val CHANNEL_DESC = "Mijoz to'lov muddati eslatmalari"

    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Создать канал уведомлений (идемпотентно). */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
        }
    }

    /**
     * Опубликовать уведомление с двумя action-кнопками:
     *  • «To'lov qabul qilindi» — списывает недельный тариф из долга
     *    и помечает скутер как возвращённый.
     *  • «1 soat eslatma»      — ставит напоминание через час.
     * Действия работают и при закрытом приложении.
     */
    fun postPaymentDueNotification(
        context: Context,
        renterId: Int,
        name: String,
        phone: String
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
            context,
            renterId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Принял оплату
        val paymentIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PAYMENT_RECEIVED
            putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
        }
        val paymentPendingIntent = PendingIntent.getBroadcast(
            context,
            renterId * 10 + 0,
            paymentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Напомнить через час
        val remindIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REMIND_IN_ONE_HOUR
            putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
        }
        val remindPendingIntent = PendingIntent.getBroadcast(
            context,
            renterId * 10 + 1,
            remindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Используем только те иконки, которые точно существуют в android.R.drawable
        // на всех API-уровнях. checkbox_on_background ранее был проблемой.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_save,        // «Сохранить» = принять оплату
                "To'lov qabul qilindi",
                paymentPendingIntent
            )
            .addAction(
                android.R.drawable.ic_popup_reminder,   // Колокольчик-напоминание
                "1 soat eslatma",
                remindPendingIntent
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

    private fun saveToHistory(
        context: Context,
        renterId: Int,
        title: String,
        message: String
    ) {
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
