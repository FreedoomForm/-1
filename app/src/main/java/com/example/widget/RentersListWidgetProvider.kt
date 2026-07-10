package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.Renter
import com.example.data.SettingsRepository
import com.example.worker.NotificationActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Виджет-список существующих арендаторов. Для каждого арендатора показывает:
 *   • Имя, телефон, скутер, баланс (красный если < 0, зелёный если > 0)
 *   • Кнопку «To'lov» — оплачивает одну неделю (через broadcast)
 *   • Кнопку «Uzish» — прерывает контракт (через broadcast)
 *   • Кнопку «SMS» — открывает приложение для ручной отправки SMS
 *   • Кнопку «O'chir» — удаляет арендатора
 *
 * Кнопки «To'lov» и «Uzish» используют те же broadcast actions, что и
 * уведомления (NotificationActionReceiver) — это гарантирует, что логика
 * оплаты и расторжения полностью совпадает с логикой в приложении.
 */
class RentersListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Обработка нажатий кнопок внутри элементов списка
        when (intent.action) {
            ACTION_WIDGET_PAY -> {
                val renterId = intent.getIntExtra(EXTRA_RENTER_ID, -1)
                if (renterId != -1) {
                    // Отправляем broadcast в NotificationActionReceiver —
                    // используется та же логика, что и в уведомлении.
                    val payIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                        action = NotificationActionReceiver.ACTION_PAYMENT_RECEIVED
                        putExtra(NotificationActionReceiver.EXTRA_RENTER_ID, renterId)
                    }
                    context.sendBroadcast(payIntent)
                    // Обновляем виджет
                    CoroutineScope(Dispatchers.Main).launch {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            updateAll(context)
                        }, 1500)
                    }
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
                    CoroutineScope(Dispatchers.Main).launch {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            updateAll(context)
                        }, 1500)
                    }
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
                            android.util.Log.e("RentersWidget", "Delete failed", e)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_PAY = "com.example.widget.ACTION_PAY"
        const val ACTION_WIDGET_TERMINATE = "com.example.widget.ACTION_TERMINATE"
        const val ACTION_WIDGET_SMS = "com.example.widget.ACTION_SMS"
        const val ACTION_WIDGET_DELETE = "com.example.widget.ACTION_DELETE"
        const val EXTRA_RENTER_ID = "renter_id"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            // Используем RemoteViewsService для отображения списка
            val intent = Intent(context, RentersListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            // Быстро подтягиваем счётчик арендаторов для шапки виджета
            val (totalCount, activeCount) = try {
                val all = kotlinx.coroutines.runBlocking {
                    AppDatabase.getDatabase(context).renterDao().getAllRentersOnce()
                }
                all.size to all.count { !it.isReturned }
            } catch (_: Exception) { 0 to 0 }

            val views = RemoteViews(context.packageName, R.layout.widget_renters_list).apply {
                setRemoteAdapter(R.id.widget_list, intent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setTextViewText(R.id.widget_count, "$activeCount / $totalCount")
                // Клик по шапке — открывает приложение на вкладке Ijarachilar
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

            // Шаблон PendingIntent для обработки нажатий на кнопки элементов
            val payTemplateIntent = Intent(context, RentersListWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_PAY
            }
            val payPendingIntent = PendingIntent.getBroadcast(
                context, 0, payTemplateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list, payPendingIntent)

            val termTemplateIntent = Intent(context, RentersListWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TERMINATE
            }
            val termPendingIntent = PendingIntent.getBroadcast(
                context, 1, termTemplateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_MUTABLE
            )
            // Для разных действий нужен разный requestCode, но setPendingIntentTemplate
            // принимает только один template на listView. Поэтому мы используем fillInIntent
            // с разными action внутри adapter-а, а template один. Обработчик в onReceive
            // смотрит на intent.action.

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, RentersListWidgetProvider::class.java)
            )
            ids.forEach { updateWidget(context, mgr, it) }
        }
    }
}
