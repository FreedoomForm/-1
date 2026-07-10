package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Нативный виджет домашнего экрана.
 *
 * Показывает три метрики:
 *   • Faol — число активных арендаторов (не вернувших скутер)
 *   • Qarz — суммарный долг всех арендаторов (в тысячах сумов)
 *   • Bo'sh — число свободных скутеров (не привязанных к активным арендаторам)
 *
 * Обновляется:
 *   • Системой каждые 30 минут (updatePeriodMillis)
 *   • По событию UPDATE_STATS — из ViewModel после изменений данных
 *
 * Важно: RemoteViews поддерживает только классические View (не Compose),
 * поэтому разметка лежит в res/layout/widget_stats.xml.
 */
class StatsWidgetProvider : AppWidgetProvider() {

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
        when (intent.action) {
            ACTION_UPDATE_STATS -> {
                // Принудительное обновление всех экземпляров виджета
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, StatsWidgetProvider::class.java))
                ids.forEach { id -> updateWidget(context, mgr, id) }
            }
        }
    }

    /**
     * Собирает RemoteViews и пушит их в конкретный экземпляр виджета.
     */
    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_stats)

        // Клик по виджету открывает приложение
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(context, 0, openIntent, flags)
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        // Метка времени последнего обновления
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        views.setTextViewText(R.id.widget_updated, timeStr)

        // Загружаем данные асинхронно — статистика посчитается в фоновой корутине
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context.applicationContext)
                val renters = db.renterDao().getAllRentersOnce()
                val scooters = db.scooterDao().getAllScootersOnce()

                val activeRenters = renters.count { !it.isReturned }
                val totalDebt = renters.sumOf { it.debtAmount }
                val activeScooterIds = renters.filter { !it.isReturned && it.scooterId != null }
                    .map { it.scooterId }
                    .toSet()
                val freeScooters = scooters.count { it.id !in activeScooterIds }

                // Форматируем долг в тысячах сумов (e.g. 125000 → "125")
                val debtK = (totalDebt / 1000).toInt()

                Log.d(TAG, "Widget stats: active=$activeRenters, debt=$totalDebt ($debtK ming), free=$freeScooters")

                views.setTextViewText(R.id.tv_active_count, activeRenters.toString())
                views.setTextViewText(R.id.tv_debt_amount, formatDebt(debtK))
                views.setTextViewText(R.id.tv_free_count, freeScooters.toString())

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load widget stats", e)
                // Показываем placeholder вместо падения
                views.setTextViewText(R.id.tv_active_count, "—")
                views.setTextViewText(R.id.tv_debt_amount, "—")
                views.setTextViewText(R.id.tv_free_count, "—")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun formatDebt(debtK: Int): String {
        return when {
            debtK >= 1000 -> String.format(Locale.US, "%.1fM", debtK / 1000.0)
            debtK > 0 -> debtK.toString()
            else -> "0"
        }
    }

    companion object {
        private const val TAG = "StatsWidget"
        const val ACTION_UPDATE_STATS = "com.example.ACTION_UPDATE_STATS"

        /**
         * Вызывается из ViewModel/Activity чтобы принудительно обновить все
         * экземпляры виджета после изменения данных.
         */
        fun broadcastUpdate(context: Context) {
            try {
                val intent = Intent(context, StatsWidgetProvider::class.java).apply {
                    action = ACTION_UPDATE_STATS
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to broadcast widget update", e)
            }
        }
    }
}
