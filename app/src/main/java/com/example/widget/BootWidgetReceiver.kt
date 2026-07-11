package com.example.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Обновляет нативные виджеты после перезагрузки устройства.
 *
 * Android не сохраняет RemoteViews после reboot — виджеты показывают
 * "не удалось загрузить виджет" до следующего onUpdate (раз в 30 минут).
 * Этот receiver ловит BOOT_COMPLETED и сразу дёргает WidgetUpdater.updateAll,
 * чтобы виджеты получили актуальные данные сразу после загрузки.
 */
class BootWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.appwidget.action.APPWIDGET_ENABLED"
        ) {
            try {
                WidgetUpdater.updateAll(context.applicationContext)
            } catch (_: Exception) {
                // Игнорируем — виджет обновится при следующем onUpdate
            }
        }
    }
}
