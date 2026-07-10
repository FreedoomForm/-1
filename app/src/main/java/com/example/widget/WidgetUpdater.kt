package com.example.widget

import android.content.Context

/**
 * Обновляет все нативные виджеты приложения. Вызывается после любого
 * изменения данных (добавление/удаление арендатора, оплата, и т.д.),
 * чтобы виджеты на главном экране Android сразу отображали актуальные
 * данные.
 */
object WidgetUpdater {
    fun updateAll(context: Context) {
        try { ReportsSummaryWidgetProvider.updateAll(context) } catch (e: Exception) {}
        try { QuickActionsWidgetProvider.updateAll(context) } catch (e: Exception) {}
        try { RentersListWidgetProvider.updateAll(context) } catch (e: Exception) {}
        try { ScootersListWidgetProvider.updateAll(context) } catch (e: Exception) {}
        try { ContractsListWidgetProvider.updateAll(context) } catch (e: Exception) {}
        try { TransactionsListWidgetProvider.updateAll(context) } catch (e: Exception) {}
    }
}
