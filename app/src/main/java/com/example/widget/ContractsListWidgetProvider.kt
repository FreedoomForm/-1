package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет-список контрактов. Только отображение — показывает арендатора,
 * скутер, даты и сумму. Кнопок нет.
 * Клик по строке или по шапке открывает приложение на вкладке Kontraktlar.
 */
class ContractsListWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            buildAndShow(context, appWidgetManager, appWidgetId, "—")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val count = AppDatabase.getDatabase(context).contractHistoryDao().getAllOnce()
                        .count { it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
                    buildAndShow(context, appWidgetManager, appWidgetId, count.toString())
                } catch (_: Exception) { }
            }
        }

        private fun buildAndShow(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            countText: String
        ) {
            val intent = Intent(context, ContractsListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra("open_tab", 2)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val views = RemoteViews(context.packageName, R.layout.widget_contracts_list).apply {
                setRemoteAdapter(R.id.widget_list, intent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setTextViewText(R.id.widget_count, countText)
                val pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
            // Template для клика по строке списка — открывает приложение.
            val templatePendingIntent = PendingIntent.getActivity(
                context, appWidgetId + 1000, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list, templatePendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ContractsListWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
        }
    }
}

class ContractsListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ContractsListFactory(applicationContext)
}

class ContractsListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var contracts: List<ContractHistoryEntry> = emptyList()
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    override fun onCreate() {}
    override fun onDataSetChanged() {
        runBlocking {
            try {
                contracts = AppDatabase.getDatabase(context).contractHistoryDao().getAllOnce()
                    .filter { it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
                    .sortedByDescending { it.weekStart ?: it.timestamp }
                    .take(20)
            } catch (e: Exception) {}
        }
    }
    override fun onDestroy() { contracts = emptyList() }
    override fun getCount(): Int = contracts.size
    override fun getViewAt(position: Int): RemoteViews {
        return try {
            val c = contracts[position]
            val views = RemoteViews(context.packageName, R.layout.widget_contract_item)
            views.setTextViewText(R.id.contract_renter, c.renterName)
            views.setTextViewText(R.id.contract_scooter, c.scooterName ?: "—")
            val dates = "${c.weekStart?.let { dateFmt.format(Date(it)) } ?: "—"} → ${c.weekEnd?.let { dateFmt.format(Date(it)) } ?: "—"}"
            views.setTextViewText(R.id.contract_dates, dates)
            views.setTextViewText(R.id.contract_amount, "${c.amount.toLong()} UZS")
            views.setTextColor(R.id.contract_amount, if (c.isPaid) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
            // Клик по строке — открывает приложение (template в провайдере)
            views.setOnClickFillInIntent(R.id.row_root, Intent())
            views
        } catch (e: Exception) {
            android.util.Log.e("ContractsWidget", "getViewAt($position) failed", e)
            RemoteViews(context.packageName, R.layout.widget_contract_item)
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
