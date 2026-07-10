package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import com.example.data.AppDatabase
import com.example.data.Renter
import kotlinx.coroutines.runBlocking

/**
 * Adapter для виджета-списка арендаторов. Загружает всех арендаторов из БД
 * и для каждого создаёт RemoteViews с именем, телефоном, балансом и 4 кнопками.
 *
 * Кнопки используют fillInIntent — это позволяет передавать разные action и
 * renterId для каждой кнопки в каждой строке. Template intent задаётся в
 * провайдере через setPendingIntentTemplate.
 */
class RentersListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return RentersListFactory(applicationContext)
    }
}

class RentersListFactory(private val context: android.content.Context) : RemoteViewsService.RemoteViewsFactory {

    private var renters: List<Renter> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        // runBlocking безопасен здесь — onDataSetChanged выполняется в
        // background-потоке bind-сервиса.
        renters = runBlocking {
            try {
                AppDatabase.getDatabase(context).renterDao().getAllRentersOnce()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    override fun onDestroy() {
        renters = emptyList()
    }

    override fun getCount(): Int = renters.size + 1  // +1 для заголовка-кнопки фильтра

    override fun getViewAt(position: Int): RemoteViews {
        return try {
            buildViewAt(position)
        } catch (e: Exception) {
            android.util.Log.e("RentersWidget", "getViewAt($position) failed", e)
            // Возвращаем пустую заглушку, чтобы один битый элемент не ронял весь виджет
            RemoteViews(context.packageName, R.layout.widget_renter_item)
        }
    }

    private fun buildViewAt(position: Int): RemoteViews {
        if (position == 0) {
            // Заголовок-сводка
            val views = RemoteViews(context.packageName, R.layout.widget_renters_header)
            val activeCount = renters.count { !it.isReturned }
            val overdueCount = renters.count { !it.isReturned && it.balance < 0 }
            views.setTextViewText(R.id.header_total, renters.size.toString())
            views.setTextViewText(R.id.header_active, activeCount.toString())
            views.setTextViewText(R.id.header_overdue, overdueCount.toString())
            // Совместимость со старой версией (на случай, если кто-то ещё читает header_summary)
            views.setTextViewText(
                R.id.header_summary,
                "Jami: ${renters.size}  |  Faol: $activeCount  |  Qarzdor: $overdueCount"
            )
            return views
        }

        val renter = renters[position - 1]
        val views = RemoteViews(context.packageName, R.layout.widget_renter_item)

        views.setTextViewText(R.id.renter_name, renter.name)
        views.setTextViewText(R.id.renter_phone, renter.phoneNumber)
        views.setTextViewText(
            R.id.renter_scooter,
            "Skuter: ${renter.scooterName ?: "—"}"
        )
        val balanceText = "${renter.balance.toLong()} UZS"
        views.setTextViewText(R.id.renter_balance, balanceText)
        val balanceColor = when {
            renter.balance < 0 -> 0xFFDC2626.toInt()
            renter.balance > 0 -> 0xFF16A34A.toInt()
            else -> 0xFF251E12.toInt()
        }
        views.setTextColor(R.id.renter_balance, balanceColor)
        // Цветной кружок-индикатор статуса: красный при долге, зелёный иначе
        views.setImageViewResource(
            R.id.renter_status_dot,
            if (renter.balance < 0) R.drawable.widget_dot_overdue else R.drawable.widget_dot_ok
        )

        // fillInIntent для кнопки To'lov.
        // setClassName не нужен — template PendingIntent уже нацелен на провайдер.
        val payIntent = Intent().apply {
            action = RentersListWidgetProvider.ACTION_WIDGET_PAY
            putExtra(RentersListWidgetProvider.EXTRA_RENTER_ID, renter.id)
        }
        views.setOnClickFillInIntent(R.id.btn_pay, payIntent)

        // fillInIntent для кнопки Uzish
        val termIntent = Intent().apply {
            action = RentersListWidgetProvider.ACTION_WIDGET_TERMINATE
            putExtra(RentersListWidgetProvider.EXTRA_RENTER_ID, renter.id)
        }
        views.setOnClickFillInIntent(R.id.btn_terminate, termIntent)

        // fillInIntent для кнопки SMS
        val smsIntent = Intent().apply {
            action = RentersListWidgetProvider.ACTION_WIDGET_SMS
            putExtra(RentersListWidgetProvider.EXTRA_RENTER_ID, renter.id)
        }
        views.setOnClickFillInIntent(R.id.btn_sms, smsIntent)

        // fillInIntent для кнопки O'chir
        val delIntent = Intent().apply {
            action = RentersListWidgetProvider.ACTION_WIDGET_DELETE
            putExtra(RentersListWidgetProvider.EXTRA_RENTER_ID, renter.id)
        }
        views.setOnClickFillInIntent(R.id.btn_delete, delIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 2  // header + item
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
