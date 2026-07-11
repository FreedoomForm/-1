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

/**
 * Нативный виджет «Tezkor amallar» — 4 кнопки быстрого создания:
 *  • + Ijarachi    — открывает приложение с флагом create_renter
 *  • + Skuter      — открывает приложение с флагом create_scooter
 *  • + Kontrakt    — открывает приложение с флагом create_contract
 *  • + Tranzaksiya — открывает приложение с флагом create_transaction
 *
 * Применены 3 критических фикса против "Failed to load widget":
 *  1. НЕТ android:layout_marginVertical в layout (только marginTop + marginBottom).
 *  2. RemoteViews возвращаются СИНХРОННО в onUpdate — лаунчер не ждёт.
 *  3. НЕТ <View>-разделителей в layout.
 *  + onAppWidgetOptionsChanged signature: android.os.Bundle (не AppWidgetManagerInfo).
 */
class QuickActionsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Синхронно строим и применяем RemoteViews для каждого виджета —
        // это критично, лаунчер ждёт RemoteViews сразу после onUpdate.
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // При изменении размера виджета — пересобираем его.
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            try {
                val base = appWidgetId * 10
                val views = RemoteViews(context.packageName, R.layout.widget_quick_actions).apply {
                    setOnClickPendingIntent(
                        R.id.btn_create_renter,
                        buildPendingIntent(context, base, "create_renter")
                    )
                    setOnClickPendingIntent(
                        R.id.btn_create_scooter,
                        buildPendingIntent(context, base + 1, "create_scooter")
                    )
                    setOnClickPendingIntent(
                        R.id.btn_create_contract,
                        buildPendingIntent(context, base + 2, "create_contract")
                    )
                    setOnClickPendingIntent(
                        R.id.btn_create_transaction,
                        buildPendingIntent(context, base + 3, "create_transaction")
                    )
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                android.util.Log.e("QuickActionsWidget", "updateWidget failed", e)
            }
        }

        private fun buildPendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                this.action = "com.example.$action"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("widget_action", action)
            }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, QuickActionsWidgetProvider::class.java)
                )
                ids.forEach { updateWidget(context, mgr, it) }
            } catch (e: Exception) {
                android.util.Log.e("QuickActionsWidget", "updateAll failed", e)
            }
        }
    }
}
