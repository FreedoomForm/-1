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

class ContractsListWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_DELETE) {
            val contractId = intent.getIntExtra(EXTRA_CONTRACT_ID, -1)
            if (contractId != -1) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        AppDatabase.getDatabase(context).contractHistoryDao().deleteById(contractId)
                        updateAll(context)
                    } catch (e: Exception) {}
                }
            }
        }
    }
    companion object {
        const val ACTION_WIDGET_DELETE = "com.example.widget.ACTION_DELETE_CONTRACT"
        const val EXTRA_CONTRACT_ID = "contract_id"
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val intent = Intent(context, ContractsListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val totalCount = try {
                runBlocking {
                    AppDatabase.getDatabase(context).contractHistoryDao().getAllOnce()
                        .count { it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
                }
            } catch (_: Exception) { 0 }

            val views = RemoteViews(context.packageName, R.layout.widget_contracts_list).apply {
                setRemoteAdapter(R.id.widget_list, intent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setTextViewText(R.id.widget_count, totalCount.toString())
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    putExtra("open_tab", 2)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
            val delTemplate = Intent(context, ContractsListWidgetProvider::class.java).apply { action = ACTION_WIDGET_DELETE }
            val delPending = PendingIntent.getBroadcast(context, 0, delTemplate,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_MUTABLE)
            views.setPendingIntentTemplate(R.id.widget_list, delPending)
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
        val c = contracts[position]
        val views = RemoteViews(context.packageName, R.layout.widget_contract_item)
        views.setTextViewText(R.id.contract_renter, c.renterName)
        views.setTextViewText(R.id.contract_scooter, c.scooterName ?: "—")
        val dates = "${c.weekStart?.let { dateFmt.format(Date(it)) } ?: "—"} → ${c.weekEnd?.let { dateFmt.format(Date(it)) } ?: "—"}"
        views.setTextViewText(R.id.contract_dates, dates)
        views.setTextViewText(R.id.contract_amount, "${c.amount.toLong()} UZS")
        views.setTextColor(R.id.contract_amount, if (c.isPaid) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
        val delIntent = Intent().apply {
            action = ContractsListWidgetProvider.ACTION_WIDGET_DELETE
            putExtra(ContractsListWidgetProvider.EXTRA_CONTRACT_ID, c.id)
        }
        views.setOnClickFillInIntent(R.id.btn_delete, delIntent)
        return views
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
