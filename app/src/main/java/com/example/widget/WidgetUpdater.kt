package com.example.widget

import android.content.Context

/**
 * Обновляет все нативные виджеты приложения.
 *
 * Вызывается после любого изменения данных (добавление/удаление арендатора,
 * оплата, и т.д.), чтобы виджеты на главном экране Android сразу отображали
 * актуальные данные.
 *
 * 7 виджетов:
 *   1. DashboardWidgetProvider      — сводный
 *   2. ReportsSummaryWidgetProvider — Otchetlar (метрики)
 *   3. QuickActionsWidgetProvider   — Tezkor amallar (кнопки)
 *   4. RentersListWidgetProvider    — Ijarachilar (список)
 *   5. ScootersListWidgetProvider   — Skuterlar (список)
 *   6. ContractsListWidgetProvider  — Kontraktlar (список)
 *   7. TransactionsListWidgetProvider — Tranzaksiyalar (список)
 *
 * Каждый провайдер имеет свой updateAll(context) — он сначала синхронно
 * показывает плейсхолдер (чтобы лаунчер не получил "Failed to load widget"),
 * потом асинхронно грузит данные из Room и применяет их.
 */
object WidgetUpdater {
    fun updateAll(context: Context) {
        try {
            DashboardWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "Dashboard updateAll failed", e)
        }
        try {
            ReportsSummaryWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "Reports updateAll failed", e)
        }
        try {
            QuickActionsWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "QuickActions updateAll failed", e)
        }
        try {
            RentersListWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "Renters updateAll failed", e)
        }
        try {
            ScootersListWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "Scooters updateAll failed", e)
        }
        try {
            ContractsListWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "Contracts updateAll failed", e)
        }
        try {
            TransactionsListWidgetProvider.updateAll(context)
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdater", "Transactions updateAll failed", e)
        }
    }
}
