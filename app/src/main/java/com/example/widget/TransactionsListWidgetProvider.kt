package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.Transaction
import com.example.ui.TransactionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Нативный виджет «Tranzaksiyalar» — список последних транзакций с кнопкой удаления.
 *
 * Применены 3 критических фикса против "Failed to load widget":
 *  1. НЕТ android:layout_marginVertical в layout.
 *  2. RemoteViews возвращаются СИНХРОННО в onUpdate.
 *  3. НЕТ <View>-разделителей в layout.
 *  + onAppWidgetOptionsChanged signature: android.os.Bundle.
 */
class TransactionsListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Шаг 1: Синхронно показываем плейсхолдер со счётчиком "—"
        appWidgetIds.forEach { id ->
            buildAndShow(context, appWidgetManager, id, "—")
        }

        // Шаг 2: goAsync() для подсчёта реального количества транзакций
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    val count = AppDatabase.getDatabase(context).transactionDao().getAllOnce().size
                    buildAndShow(context, appWidgetManager, id, count.toString())
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Update failed", e)
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
        buildAndShow(context, appWidgetManager, appWidgetId, "—")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val count = AppDatabase.getDatabase(context).transactionDao().getAllOnce().size
                buildAndShow(context, appWidgetManager, appWidgetId, count.toString())
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Options changed update failed", e)
            } finally {
                try { pendingResult.finish() } catch (_: Throwable) {}
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_DELETE) {
            val txId = intent.getIntExtra(EXTRA_TX_ID, -1)
            if (txId != -1) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AppDatabase.getDatabase(context).transactionDao().deleteById(txId)
                        updateAll(context)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Delete failed", e)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "TransactionsWidget"
        const val ACTION_WIDGET_DELETE = "com.example.widget.ACTION_DELETE_TX"
        const val EXTRA_TX_ID = "tx_id"

        private fun buildAndShow(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            countText: String
        ) {
            try {
                val intent = Intent(context, TransactionsListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                val views = RemoteViews(context.packageName, R.layout.widget_transactions_list).apply {
                    setRemoteAdapter(R.id.widget_list, intent)
                    setEmptyView(R.id.widget_list, R.id.widget_empty)
                    setTextViewText(R.id.widget_count, countText)
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("open_tab", 3)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context, appWidgetId, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                val delTemplate = Intent(context, TransactionsListWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_DELETE
                }
                val delPending = PendingIntent.getBroadcast(
                    context, 0, delTemplate,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.widget_list, delPending)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "buildAndShow failed", e)
            }
        }

        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, TransactionsListWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                ids.forEach { buildAndShow(context, mgr, it, "—") }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ids.forEach { id ->
                            val count = AppDatabase.getDatabase(context).transactionDao().getAllOnce().size
                            buildAndShow(context, mgr, id, count.toString())
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "updateAll failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "updateAll entry failed", e)
            }
        }
    }
}

class TransactionsListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TransactionsListFactory(applicationContext)
}

class TransactionsListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var txs: List<Transaction> = emptyList()
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    override fun onCreate() {}
    override fun onDataSetChanged() {
        runBlocking {
            try {
                txs = AppDatabase.getDatabase(context).transactionDao().getAllOnce()
                    .sortedByDescending { it.timestamp }
                    .take(20)
            } catch (e: Exception) {
                android.util.Log.e("TransactionsWidget", "onDataSetChanged failed", e)
            }
        }
    }
    override fun onDestroy() { txs = emptyList() }
    override fun getCount(): Int = txs.size
    override fun getViewAt(position: Int): RemoteViews {
        return try {
            val tx = txs[position]
            val views = RemoteViews(context.packageName, R.layout.widget_transaction_item)
            views.setTextViewText(R.id.tx_renter, tx.renterName)
            views.setTextViewText(R.id.tx_type, TransactionViewModel.typeLabel(tx.type))
            views.setTextViewText(R.id.tx_date, dateFmt.format(Date(tx.timestamp)))
            val isPositive = TransactionViewModel.typeIsPositive(tx.type)
            val sign = if (isPositive) "+" else "−"
            views.setTextViewText(R.id.tx_amount, "$sign${tx.amount.toLong()}")
            views.setTextColor(
                R.id.tx_amount,
                when (tx.type) {
                    Transaction.TYPE_CUSTOM -> 0xFF71624B.toInt()
                    else -> if (isPositive) 0xFF16A34A.toInt() else 0xFFDC2626.toInt()
                }
            )
            val delIntent = Intent().apply {
                action = TransactionsListWidgetProvider.ACTION_WIDGET_DELETE
                putExtra(TransactionsListWidgetProvider.EXTRA_TX_ID, tx.id)
            }
            views.setOnClickFillInIntent(R.id.btn_delete, delIntent)
            views
        } catch (e: Exception) {
            android.util.Log.e("TransactionsWidget", "getViewAt($position) failed", e)
            RemoteViews(context.packageName, R.layout.widget_transaction_item)
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
