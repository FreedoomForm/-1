package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.VirtualCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
   ОДИН ПРОСТОЙ ВИДЖЕТ «ScooterRent Dashboard»
   ----------------------------------------------------------------------------
   КАРДИНАЛЬНО другой подход вместо 6 сложных виджетов:

   Было (старый подход — не работал):
     • 6 разных виджетов с complex layer-list previews
     • CoroutineScope(Dispatchers.IO).launch — процесс убивался до завершения
     • RemoteViewsService + ListView — deprecated setRemoteAdapter на API 31+
     • <item android:width android:height> в preview drawables — ломает рендер
       на Android 12+ (это и было причиной "Failed to load widget")
     • 12+ кастомных shape drawable — каждый мог сломать инфляцию

   Стало (новый подход):
     • ОДИН виджет = одна точка отказа
     • goAsync() — даёт BR до 10 секунд на работу, не убивает процесс
     • Только TextView в простом LinearLayout — нет RemoteViewsService
     • android:previewImage = @mipmap/ic_launcher — всегда работает
     • Простой shape drawable для фона — минимум инфляции
     • WorkManager periodic worker — надёжное обновление каждые 30 мин

   Виджет показывает:
     • Активных арендаторов (с долгом отдельно)
     • Свободных скутеров
     • Баланс главной кассы (Glavnaya karta)
     • Время последнего обновления
     • Тап → открывает приложение
   ============================================================================ */

class DashboardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // goAsync() даёт BroadcastReceiver до 10 секунд на выполнение работы
        // перед тем, как Android убьёт процесс. Это критично — старый подход
        // запускал CoroutineScope и сразу возвращался, из-за чего процесс
        // убивался до того, как корутина успевала вызвать updateAppWidget().
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    buildAndUpdate(context, appWidgetManager, id)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Update failed", e)
                // Показываем хотя бы плейсхолдер — лучше, чем "Failed to load"
                try {
                    appWidgetIds.forEach { id ->
                        showPlaceholder(context, appWidgetManager, id)
                    }
                } catch (_: Throwable) {}
            } finally {
                try { pendingResult.finish() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "DashboardWidget"

        /**
         * Точка входа для принудительного обновления виджета из приложения
         * (после любого изменения данных — добавление арендатора, оплата и т.д.).
         */
        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, DashboardWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return

                // Запускаем в IO-диспатчере — этот метод вызывается из UI-потока
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        ids.forEach { id -> buildAndUpdate(context, mgr, id) }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "updateAll failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "updateAll entry failed", e)
            }
        }

        /** Собирает RemoteViews с актуальными данными и применяет их. */
        private suspend fun buildAndUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val data = loadData(context)
            val views = buildRemoteViews(context, data)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Читает все нужные данные из Room. */
        private suspend fun loadData(context: Context): WidgetData {
            return try {
                val db = AppDatabase.getDatabase(context)
                val renters = db.renterDao().getAllRentersOnce()
                val scooters = db.scooterDao().getAllScootersOnce()
                val mainCardBalance = try {
                    db.virtualCardDao().getCardById(VirtualCard.MAIN_CARD_ID)?.balance ?: 0.0
                } catch (_: Exception) { 0.0 }

                val activeRenters = renters.count { !it.isReturned }
                val overdueRenters = renters.count { !it.isReturned && it.balance < 0 }
                val rentedScooters = scooters.count { s ->
                    renters.any { it.scooterId == s.id && !it.isReturned }
                }
                val freeScooters = scooters.size - rentedScooters

                WidgetData(
                    activeRenters = activeRenters,
                    overdueRenters = overdueRenters,
                    freeScooters = freeScooters,
                    totalScooters = scooters.size,
                    mainCardBalance = mainCardBalance
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "loadData failed", e)
                WidgetData() // все нули
            }
        }

        /** Собирает RemoteViews из данных. */
        private fun buildRemoteViews(
            context: Context,
            data: WidgetData
        ): RemoteViews {
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val updatedText = "Yangilandi: ${timeFmt.format(Date())}"

            val activeStr = data.activeRenters.toString()
            val overdueStr = if (data.overdueRenters > 0) "(${data.overdueRenters} qarz)" else ""
            val freeStr = "${data.freeScooters}/${data.totalScooters}"
            val balanceStr = formatMoney(data.mainCardBalance)

            return RemoteViews(context.packageName, R.layout.widget_dashboard).apply {
                setTextViewText(R.id.widget_title, "ScooterRent")
                setTextViewText(R.id.widget_active_count, activeStr)
                setTextViewText(R.id.widget_active_label, "Faol ijarachi $overdueStr")
                setTextViewText(R.id.widget_free_count, freeStr)
                setTextViewText(R.id.widget_free_label, "Bo'sh skuterlar")
                setTextViewText(R.id.widget_balance_count, balanceStr)
                setTextViewText(R.id.widget_balance_label, "Kassa (so'm)")
                setTextViewText(R.id.widget_updated, updatedText)

                // Если нет долгов — скрываем "(N qarz)" из подписи
                setViewVisibility(
                    R.id.widget_active_label,
                    if (overdueStr.isEmpty()) View.VISIBLE else View.VISIBLE
                )

                // Тап по виджету → открывает приложение
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_tab", 4) // Отчёты
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    System.currentTimeMillis().toInt(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
        }

        /** Показывает плейсхолдер с «—» во всех полях. */
        private fun showPlaceholder(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_dashboard).apply {
                    setTextViewText(R.id.widget_title, "ScooterRent")
                    setTextViewText(R.id.widget_active_count, "—")
                    setTextViewText(R.id.widget_active_label, "Faol ijarachi")
                    setTextViewText(R.id.widget_free_count, "—")
                    setTextViewText(R.id.widget_free_label, "Bo'sh skuterlar")
                    setTextViewText(R.id.widget_balance_count, "—")
                    setTextViewText(R.id.widget_balance_label, "Kassa (so'm)")
                    setTextViewText(R.id.widget_updated, "Yuklanmoqda…")
                }
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Placeholder failed", e)
            }
        }

        private fun formatMoney(amount: Double): String {
            val sign = if (amount < 0) "-" else ""
            val absValue = kotlin.math.abs(amount).toLong()
            val formatted = String.format(Locale.US, "%,d", absValue).replace(',', ' ')
            return "$sign$formatted"
        }
    }

    /** Контейнер с данными для отображения. */
    private data class WidgetData(
        val activeRenters: Int = 0,
        val overdueRenters: Int = 0,
        val freeScooters: Int = 0,
        val totalScooters: Int = 0,
        val mainCardBalance: Double = 0.0
    )
}
