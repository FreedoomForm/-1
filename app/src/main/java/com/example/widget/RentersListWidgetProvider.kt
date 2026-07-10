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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Виджет-список существующих арендаторов. Только отображение —
 * показывает имя, телефон, скутер и баланс. Кнопок в строках нет.
 * Клик по строке или по шапке открывает приложение на вкладке Ijarachilar.
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

    companion object {

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            // Сразу строим виджет с плейсхолдером, чтобы не блокировать главный поток.
            buildAndShow(context, appWidgetManager, appWidgetId, "— / —")

            // Асинхронно подтягиваем счётчик арендаторов и обновляем виджет целиком.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val all = AppDatabase.getDatabase(context).renterDao().getAllRentersOnce()
                    val activeCount = all.count { !it.isReturned }
                    val totalCount = all.size
                    buildAndShow(context, appWidgetManager, appWidgetId, "$activeCount / $totalCount")
                } catch (_: Exception) { }
            }
        }

        private fun buildAndShow(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            countText: String
        ) {
            val intent = Intent(context, RentersListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra("open_tab", 0)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val views = RemoteViews(context.packageName, R.layout.widget_renters_list).apply {
                setRemoteAdapter(R.id.widget_list, intent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setTextViewText(R.id.widget_count, countText)
                // Клик по шапке виджета — открывает приложение
                val pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
            // Template для клика по строке списка — открывает приложение.
            // fillInIntent на row_root задаётся в фабрике.
            val templatePendingIntent = PendingIntent.getActivity(
                context, appWidgetId + 1000, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list, templatePendingIntent)
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
