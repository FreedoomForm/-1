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

    /** URL бэкенда Vercel, например https://my-app.vercel.app. */
    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", "") ?: ""
        set(value) = prefs.edit().putString("api_base_url", value).apply()

    /** Локальный пароль senior admin (fallback, если backend недоступен). */
    var adminPassword: String
        get() = prefs.getString("admin_password", "admin") ?: "admin"
        set(value) = prefs.edit().putString("admin_password", value).apply()

    /** Локальный пароль junior admin (fallback). */
    var viewerPassword: String
        get() = prefs.getString("viewer_password", "viewer") ?: "viewer"
        set(value) = prefs.edit().putString("viewer_password", value).apply()

    var currentRole: String?
        get() = prefs.getString("current_role", null)
        set(value) = prefs.edit().putString("current_role", value).apply()

    companion object {
        const val DEFAULT_TEMPLATE = """Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt}.

https://transfer.paycom.uz/680a40043fc0407a2e48e8fe

Call center: 71 200 55 56."""
    }
}
