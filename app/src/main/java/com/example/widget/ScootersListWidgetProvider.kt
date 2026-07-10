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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Виджет-список существующих скутеров. Только отображение —
 * показывает имя и статус (Ijarada / Bazada). Кнопок нет.
 * Клик по строке или по шапке открывает приложение на вкладке Skuterlar.
 */
class ScootersListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            buildAndShow(context, appWidgetManager, appWidgetId, "—")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val count = AppDatabase.getDatabase(context).scooterDao().getAllScootersOnce().size
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
            val intent = Intent(context, ScootersListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra("open_tab", 1)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val views = RemoteViews(context.packageName, R.layout.widget_scooters_list).apply {
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
            val ids = mgr.getAppWidgetIds(ComponentName(context, ScootersListWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
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
            } catch (e: Exception) {}
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
            // Клик по строке — открывает приложение (template в провайдере)
            views.setOnClickFillInIntent(R.id.row_root, Intent())
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
