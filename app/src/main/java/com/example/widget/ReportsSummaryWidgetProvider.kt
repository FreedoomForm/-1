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
import com.example.data.ContractHistoryEntry
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Нативный Android-виджет для главного экрана — показывает ключевые метрики
 * из страницы «Отчёты»: чистую прибыль, активных арендаторов, занятость
 * скутеров и ROI.
 *
 * Обновляется каждые 30 минут (см. appwidget_info.xml) + при изменении
 * данных в приложении (вызывается WidgetUpdater.updateAll()).
 */
class ReportsSummaryWidgetProvider : AppWidgetProvider() {

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
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val renters = db.renterDao().getAllRentersOnce()
                    val scooters = db.scooterDao().getAllScootersOnce()
                    val history = db.contractHistoryDao().getAllOnce()

                    val now = System.currentTimeMillis()
                    val monthAgo = now - 30L * 24 * 60 * 60 * 1000
                    val paymentsThisMonth = history
                        .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp >= monthAgo }
                        .sumOf { it.amount }

                    val activeRenters = renters.count { !it.isReturned }
                    val overdueRenters = renters.count { !it.isReturned && it.balance < 0 }
                    val scootersRented = scooters.count { s ->
                        renters.any { it.scooterId == s.id && !it.isReturned }
                    }
                    val occupancyPct = if (scooters.isNotEmpty())
                        (scootersRented.toDouble() / scooters.size * 100).toInt() else 0

                    val settings = SettingsRepository(context)
                    val scooterPriceUsd = settings.scooterPriceUsd.let {
                        if (it > 0) it else SettingsRepository.DEFAULT_SCOOTER_PRICE_USD
                    }
                    val usdRate = settings.usdToUzsRate.let {
                        if (it > 0) it else SettingsRepository.DEFAULT_USD_TO_UZS_RATE
                    }
                    val totalInvestment = scooters.size * scooterPriceUsd * usdRate
                    val roiMultiple = if (totalInvestment > 0) paymentsThisMonth / totalInvestment else 0.0

                    val views = RemoteViews(context.packageName, R.layout.widget_reports_summary).apply {
                        setTextViewText(R.id.widget_net_profit, formatUzs(paymentsThisMonth * 0.70))
                        setTextViewText(R.id.widget_active_renters, "$activeRenters (${overdueRenters} qarz)")
                        setTextViewText(R.id.widget_occupancy, "$occupancyPct% — $scootersRented/${scooters.size}")
                        setTextViewText(R.id.widget_roi, "%.2f×".format(roiMultiple))
                    }

                    // Клик по виджету открывает приложение на вкладке Отчёты
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("open_tab", 4)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context, appWidgetId, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (e: Exception) {
                    android.util.Log.e("ReportsWidget", "Update failed", e)
                }
            }
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, ReportsSummaryWidgetProvider::class.java)
            )
            ids.forEach { updateWidget(context, mgr, it) }
        }

        private fun formatUzs(amount: Double): String {
            val millions = amount / 1_000_000.0
            return if (millions >= 1) "%.1f mln UZS".format(millions)
            else "%,d UZS".format(amount.toLong())
        }
    }
}
