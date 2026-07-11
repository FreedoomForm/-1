package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.worker.NotificationActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Нативный виджет «Ijarachilar» — список арендаторов с кнопками
 * To'lov / Uzish / SMS / O'chir для каждого.
 *
 * Применены 3 критических фикса против "Failed to load widget":
 *  1. НЕТ android:layout_marginVertical в layout.
 *  2. RemoteViews возвращаются СИНХРОННО в onUpdate — сначала плейсхолдер
 *     с setRemoteAdapter, потом goAsync() для подсчёта active/total.
 *  3. НЕТ <View>-разделителей в layout (widget_renters_header.xml без divider).
 *  + onAppWidgetOptionsChanged signature: android.os.Bundle.
 */
class RentersListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Шаг 1: Синхронно ставим плейсхолдер со счётчиком "— / —"
        appWidgetIds.forEach { id ->
            buildAndShow(context, appWidgetManager, id, "— / —")
        }

        // Шаг 2: goAsync() для подсчёта реальных чисел
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    val all = AppDatabase.getDatabase(context).renterDao().getAllRentersOnce()
                    val activeCount = all.count { !it.isReturned }
                    val totalCount = all.size
                    buildAndShow(context, appWidgetManager, id, "$activeCount / $totalCount")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Update failed", e)
            } finally {
                try { pendingResult.finish() } catch (_: Throwable) {}
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        buildAndShow(context, appWidgetManager, appWidgetId, "— / —")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val all = AppDatabase.getDatabase(context).renterDao().getAllRentersOnce()
                val activeCount = all.count { !it.isReturned }
                val totalCount = all.size
                buildAndShow(context, appWidgetManager, appWidgetId, "$activeCount / $totalCount")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Options changed update failed", e)
            } finally {
                try { pendingResult.finish() } catch (_: Throwable) {}
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_PAY -> {
                val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
                if (renterId != -1) {
                    val payIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                        action = NotificationActionReceiver.ACTION_PAYMENT_RECEIVED
                        putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
                    }
                    context.sendBroadcast(payIntent)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateAll(context)
                    }, 1500)
                }
            }
            ACTION_WIDGET_TERMINATE -> {
                val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
                if (renterId != -1) {
                    val termIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                        action = NotificationActionReceiver.ACTION_TERMINATE_CONTRACT
                        putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
                    }
                    context.sendBroadcast(termIntent)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateAll(context)
                    }, 1500)
                }
            }
            ACTION_WIDGET_SMS -> {
                val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("widget_action", "send_sms")
                    putExtra("renter_id", renterId)
                }
                context.startActivity(openIntent)
            }
            ACTION_WIDGET_DELETE -> {
                val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
                if (renterId != -1) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            db.renterDao().deleteRenter(renterId)
                            updateAll(context)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Delete failed", e)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RentersWidget"
        const val ACTION_WIDGET_PAY = "com.example.widget.ACTION_PAY"
        const val ACTION_WIDGET_TERMINATE = "com.example.widget.ACTION_TERMINATE"
        const val ACTION_WIDGET_SMS = "com.example.widget.ACTION_SMS"
        const val ACTION_WIDGET_DELETE = "com.example.widget.ACTION_DELETE"
        const val EXTRA_RENTER_ID = "renter_id"

        private fun buildAndShow(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            countText: String
        ) {
            try {
                val intent = Intent(context, RentersListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                val views = RemoteViews(context.packageName, R.layout.widget_renters_list).apply {
                    setRemoteAdapter(R.id.widget_list, intent)
                    setEmptyView(R.id.widget_list, R.id.widget_empty)
                    setTextViewText(R.id.widget_count, countText)
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("open_tab", 0)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context, appWidgetId, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                val templateIntent = Intent(context, RentersListWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_PAY
                }
                val templatePendingIntent = PendingIntent.getBroadcast(
                    context, 0, templateIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.widget_list, templatePendingIntent)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "buildAndShow failed", e)
            }
        }

        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, RentersListWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                // Сначала плейсхолдер (синхронно), потом данные (асинхронно)
                ids.forEach { buildAndShow(context, mgr, it, "— / —") }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ids.forEach { id ->
                            val all = AppDatabase.getDatabase(context).renterDao().getAllRentersOnce()
                            val activeCount = all.count { !it.isReturned }
                            val totalCount = all.size
                            buildAndShow(context, mgr, id, "$activeCount / $totalCount")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "updateAll failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "updateAll entry failed", e)
            }
        }
    }
}
