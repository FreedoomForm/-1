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
import com.example.data.ContractHistoryEntry
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Нативный виджет «Otchetlar» — показывает ключевые метрики из страницы «Отчёты»:
 * чистую прибыль, активных арендаторов, занятость скутеров и ROI.
 *
 * Применены 3 критических фикса против "Failed to load widget":
 *  1. НЕТ android:layout_marginVertical в layout.
 *  2. RemoteViews возвращаются СИНХРОННО в onUpdate — сначала showPlaceholder(),
 *     потом goAsync() даёт BR до 10 секунд на загрузку данных и реальное обновление.
 *     goAsync() критичен: BroadcastReceiver убивается системой после onReceive,
 *     а CoordinatorScope.launch без goAsync просто не успеет завершить работу.
 *  3. НЕТ <View>-разделителей в layout.
 *  + onAppWidgetOptionsChanged signature: android.os.Bundle.
 */
class ReportsSummaryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Шаг 1: НЕМЕДЛЕННО показываем плейсхолдер для каждого виджета —
        // лаунчер получит RemoteViews синхронно, не покажет "Failed to load".
        appWidgetIds.forEach { id ->
            showPlaceholder(context, appWidgetManager, id)
        }

        // Шаг 2: goAsync() даёт BR до 10 секунд на загрузку данных.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    updateWidgetWithRealData(context, appWidgetManager, id)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Update failed", e)
                // Плейсхолдер уже показан — ничего не делаем, виджет не будет
                // "Failed to load", просто покажет "—" пока данные не загрузятся.
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
        showPlaceholder(context, appWidgetManager, appWidgetId)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgetWithRealData(context, appWidgetManager, appWidgetId)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Options changed update failed", e)
            } finally {
                try { pendingResult.finish() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "ReportsWidget"

        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, ReportsSummaryWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                // Сначала плейсхолдер (синхронно), потом данные (асинхронно)
                ids.forEach { showPlaceholder(context, mgr, it) }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ids.forEach { updateWidgetWithRealData(context, mgr, it) }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "updateAll failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "updateAll entry failed", e)
            }
        }

        /** Немедленно показывает плейсхолдер с «—» во всех полях. */
        private fun showPlaceholder(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_reports_summary).apply {
                    setTextViewText(R.id.widget_net_profit, "—")
                    setTextViewText(R.id.widget_active_renters, "—")
                    setTextViewText(R.id.widget_overdue, "—")
                    setTextViewText(R.id.widget_occupancy, "—")
                    setTextViewText(R.id.widget_roi, "—")
                    setTextViewText(R.id.widget_trend, "·")
                    setTextViewText(R.id.widget_updated, "…")
                }
                attachOpenIntent(context, views, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Placeholder failed", e)
            }
        }

        /** Загружает данные из Room и применяет их к виджету. */
        private suspend fun updateWidgetWithRealData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val db = AppDatabase.getDatabase(context)
            val renters = db.renterDao().getAllRentersOnce()
            val scooters = db.scooterDao().getAllScootersOnce()
            val history = db.contractHistoryDao().getAllOnce()
            val mainCardBalance = try {
                db.virtualCardDao().getCardById(com.example.data.VirtualCard.MAIN_CARD_ID)?.balance ?: 0.0
            } catch (_: Exception) { 0.0 }

            val now = System.currentTimeMillis()
            val dayMs = 24L * 60 * 60 * 1000
            val monthMs = 30L * dayMs
            val monthAgo = now - monthMs
            val twoMonthsAgo = now - 2 * monthMs

            val paymentsThisMonth = history
                .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp >= monthAgo }
                .sumOf { it.amount }

            val effectivePayments = if (paymentsThisMonth > 0) paymentsThisMonth else {
                try {
                    db.cardTransactionDao().getRecentTransactions(100)
                        .filter { it.type == com.example.data.CardTransaction.TYPE_CONTRACT_INCOME && it.timestamp >= monthAgo }
                        .sumOf { it.amount }
                } catch (_: Exception) { 0.0 }
            }

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
            val roiMultiple = if (totalInvestment > 0) effectivePayments / totalInvestment else 0.0

            val paymentsThisMonthSum = history
                .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp in monthAgo..now }
                .sumOf { it.amount }
            val paymentsPrevMonth = history
                .filter { it.type == ContractHistoryEntry.TYPE_PAYMENT && it.timestamp in twoMonthsAgo..monthAgo }
                .sumOf { it.amount }
            val trendArrow = if (paymentsThisMonthSum >= paymentsPrevMonth) "▲" else "▼"
            val trendColor = if (paymentsThisMonthSum >= paymentsPrevMonth) 0xFF16A34A.toInt() else 0xFFDC2626.toInt()

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val updatedText = timeFmt.format(Date(now))

            val netProfitDisplay = if (mainCardBalance != 0.0) mainCardBalance else effectivePayments

            val views = RemoteViews(context.packageName, R.layout.widget_reports_summary).apply {
                setTextViewText(R.id.widget_net_profit, formatUzs(netProfitDisplay))
                setTextViewText(R.id.widget_active_renters, activeRenters.toString())
                setTextViewText(R.id.widget_overdue, overdueRenters.toString())
                setTextViewText(R.id.widget_occupancy, "$occupancyPct%")
                setTextViewText(R.id.widget_roi, "%.2f×".format(roiMultiple))
                setTextViewText(R.id.widget_trend, trendArrow)
                setTextColor(R.id.widget_trend, trendColor)
                setTextViewText(R.id.widget_updated, updatedText)
            }
            attachOpenIntent(context, views, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun attachOpenIntent(context: Context, views: RemoteViews, appWidgetId: Int) {
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
        }

        private fun formatUzs(amount: Double): String {
            val millions = amount / 1_000_000.0
            return if (millions >= 1) "%.1f mln UZS".format(millions)
            else "%,d UZS".format(amount.toLong())
        }
    }
}
