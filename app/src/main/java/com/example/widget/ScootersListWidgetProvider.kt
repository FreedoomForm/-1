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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Нативный виджет «Skuterlar» — список скутеров с кнопкой удаления.
 *
 * Применены 3 критических фикса против "Failed to load widget":
 *  1. НЕТ android:layout_marginVertical в layout.
 *  2. RemoteViews возвращаются СИНХРОННО в onUpdate.
 *  3. НЕТ <View>-разделителей в layout.
 *  + onAppWidgetOptionsChanged signature: android.os.Bundle.
 */
class ScootersListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Шаг 1: Синхронно показываем плейсхолдер со счётчиком "—"
        appWidgetIds.forEach { id ->
            buildAndShow(context, appWidgetManager, id, "—")
        }

        // Шаг 2: goAsync() для подсчёта реального количества скутеров
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    val count = AppDatabase.getDatabase(context).scooterDao().getAllScootersOnce().size
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
                val count = AppDatabase.getDatabase(context).scooterDao().getAllScootersOnce().size
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
            val scooterId = intent.getIntExtra(EXTRA_SCOOTER_ID, -1)
            if (scooterId != -1) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.getDatabase(context)
                        db.scooterDao().getAllScootersOnce()
                            .find { it.id == scooterId }
                            ?.let { db.scooterDao().deleteScooter(it) }
                        updateAll(context)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Delete failed", e)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ScootersWidget"
        const val ACTION_WIDGET_DELETE = "com.example.widget.ACTION_DELETE_SCOOTER"
        const val EXTRA_SCOOTER_ID = "scooter_id"

        private fun buildAndShow(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            countText: String
        ) {
            try {
                val intent = Intent(context, ScootersListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                val views = RemoteViews(context.packageName, R.layout.widget_scooters_list).apply {
                    setRemoteAdapter(R.id.widget_list, intent)
                    setEmptyView(R.id.widget_list, R.id.widget_empty)
                    setTextViewText(R.id.widget_count, countText)
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("open_tab", 1)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context, appWidgetId, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                val delTemplateIntent = Intent(context, ScootersListWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_DELETE
                }
                val delPending = PendingIntent.getBroadcast(
                    context, 0, delTemplateIntent,
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
                    ComponentName(context, ScootersListWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                ids.forEach { buildAndShow(context, mgr, it, "—") }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ids.forEach { id ->
                            val count = AppDatabase.getDatabase(context).scooterDao().getAllScootersOnce().size
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

class ScootersListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ScootersListFactory(applicationContext)
}

class ScootersListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var scooters: List<com.example.data.Scooter> = emptyList()
    private var renters: List<com.example.data.Renter> = emptyList()

    override fun onCreate() {}
    override fun onDataSetChanged() {
        runBlocking {
            try {
                val db = AppDatabase.getDatabase(context)
                scooters = db.scooterDao().getAllScootersOnce()
                renters = db.renterDao().getAllRentersOnce()
            } catch (e: Exception) {
                android.util.Log.e("ScootersWidget", "onDataSetChanged failed", e)
            }
        }
    }
    override fun onDestroy() { scooters = emptyList() }
    override fun getCount(): Int = scooters.size
    override fun getViewAt(position: Int): RemoteViews {
        return try {
            val scooter = scooters[position]
            val views = RemoteViews(context.packageName, R.layout.widget_scooter_item)
            views.setTextViewText(R.id.scooter_name, scooter.name)
            val isRented = renters.any { it.scooterId == scooter.id && !it.isReturned }
            views.setTextViewText(
                R.id.scooter_status,
                if (isRented) "Ijarada" else "Bazada"
            )
            views.setTextColor(
                R.id.scooter_status,
                if (isRented) 0xFFDC2626.toInt() else 0xFF16A34A.toInt()
            )
            val delIntent = Intent().apply {
                action = ScootersListWidgetProvider.ACTION_WIDGET_DELETE
                putExtra(ScootersListWidgetProvider.EXTRA_SCOOTER_ID, scooter.id)
            }
            views.setOnClickFillInIntent(R.id.btn_delete, delIntent)
            views
        } catch (e: Exception) {
            android.util.Log.e("ScootersWidget", "getViewAt($position) failed", e)
            RemoteViews(context.packageName, R.layout.widget_scooter_item)
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
