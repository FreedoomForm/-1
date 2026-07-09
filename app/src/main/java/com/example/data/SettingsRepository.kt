package com.example.data

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var smsTemplate: String
        get() = prefs.getString("sms_template", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(value) = prefs.edit().putString("sms_template", value).apply()

    var weeklyPrice: Double
        get() = prefs.getFloat("weekly_price", 0f).toDouble()
        set(value) = prefs.edit().putFloat("weekly_price", value.toFloat()).apply()

    var monthlyPrice: Double
        get() = prefs.getFloat("monthly_price", 0f).toDouble()
        set(value) = prefs.edit().putFloat("monthly_price", value.toFloat()).apply()

    /** Tanlangan SIM kartaning subscription ID (-1 = tanlanmagan) */
    var selectedSimSubscriptionId: Int
        get() = prefs.getInt("selected_sim_sub_id", -1)
        set(value) = prefs.edit().putInt("selected_sim_sub_id", value).apply()

    companion object {
        const val DEFAULT_WEEKLY_PRICE = 150_000.0
        const val DEFAULT_TEMPLATE = """Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt}.

Call center: 71 200 55 56."""
    }
}
