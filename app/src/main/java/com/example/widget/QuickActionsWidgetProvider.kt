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

/**
 * Плавающий виджет с 4 кнопками быстрого создания:
 *  • + Ijarachi  — открывает приложение с флагом create_renter
 *  • + Skuter    — открывает приложение с флагом create_scooter
 *  • + Kontrakt  — открывает приложение с флагом create_contract
 *  • + Tranzaksiya — открывает приложение с флагом create_transaction
 *
 * Каждая кнопка запускает MainActivity с extras, MainActivity читает
 * эти extras и открывает соответствующий диалог создания.
 */
class QuickActionsWidgetProvider : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions).apply {
                // Кнопка "Создать арендатора"
                setOnClickPendingIntent(
                    R.id.btn_create_renter,
                    buildPendingIntent(context, appWidgetId, "create_renter")
                )
                // Кнопка "Создать скутер"
                setOnClickPendingIntent(
                    R.id.btn_create_scooter,
                    buildPendingIntent(context, appWidgetId + 1, "create_scooter")
                )
                // Кнопка "Создать контракт"
                setOnClickPendingIntent(
                    R.id.btn_create_contract,
                    buildPendingIntent(context, appWidgetId + 2, "create_contract")
                )
                // Кнопка "Создать транзакцию"
                setOnClickPendingIntent(
                    R.id.btn_create_transaction,
                    buildPendingIntent(context, appWidgetId + 3, "create_transaction")
                )
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
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
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, QuickActionsWidgetProvider::class.java)
            )
            ids.forEach { updateWidget(context, mgr, it) }
        }
    }
}
