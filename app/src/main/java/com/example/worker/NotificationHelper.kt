package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.MainActivity

/**
 * Управляет каналом уведомлений «To'lov eslatmalari» и публикует локальные
 * напоминания о наступлении срока оплаты аренды.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "payment_reminders"
    private const val CHANNEL_NAME = "To'lov eslatmalari"
    private const val CHANNEL_DESC = "Mijoz to'lov muddati eslatmalari"

    /** Создать канал уведомлений (идемпотентно, безопасно вызывать повторно). */
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
     * Опубликовать уведомление: «Клиент {name} ({phone}) сегодня должен заплатить».
     * Безопасно вызывать даже без разрешения POST_NOTIFICATIONS — SecurityException
     * будет поймана.
     */
    fun postPaymentDueNotification(
        context: Context,
        renterId: Int,
        name: String,
        phone: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("renterId", renterId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            renterId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "To'lov muddati yetdi"
        val body = "Mijoz $name ($phone) bugun to'lov qilishi kerak"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            context.getSystemService<NotificationManager>()?.notify(renterId, notification)
        } catch (_: SecurityException) {
            // Android 13+: пользователь не дал POST_NOTIFICATIONS — молча пропускаем.
        }
    }
}
