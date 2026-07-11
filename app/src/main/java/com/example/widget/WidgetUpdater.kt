package com.example.widget

import android.content.Context

/**
 * Обновляет единственный нативный виджет приложения.
 *
 * Вызывается после любого изменения данных (добавление/удаление арендатора,
 * оплата, и т.д.), чтобы виджет на главном экране Android сразу отображал
 * актуальные данные.
 *
 * Раньше здесь было 6 вызовов для 6 разных виджетов — теперь один.
 */
object WidgetUpdater {
    fun updateAll(context: Context) {
        try {
            DashboardWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "updateAll failed", e)
        }
    }
}
