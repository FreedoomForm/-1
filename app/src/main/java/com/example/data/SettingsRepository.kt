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

    /** Payme-ссылка для подстановки в SMS (по умолчанию — тестовая ссылка). */
    var paymeLink: String
        get() = prefs.getString("payme_link", DEFAULT_PAYME_LINK) ?: DEFAULT_PAYME_LINK
        set(value) = prefs.edit().putString("payme_link", value).apply()

    /** Call-центр, подставляется в SMS. */
    var callCenter: String
        get() = prefs.getString("call_center", DEFAULT_CALL_CENTER) ?: DEFAULT_CALL_CENTER
        set(value) = prefs.edit().putString("call_center", value).apply()

    /** Tanlangan SIM kartaning subscription ID (-1 = tanlanmagan) */
    var selectedSimSubscriptionId: Int
        get() = prefs.getInt("selected_sim_sub_id", -1)
        set(value) = prefs.edit().putInt("selected_sim_sub_id", value).apply()

    companion object {
        const val DEFAULT_WEEKLY_PRICE = 420_000.0
        const val DEFAULT_MONTHLY_PRICE = 1_680_000.0

        const val DEFAULT_PAYME_LINK = "https://transfer.paycom.uz/680a40043fc0407a2e48e8fe"
        const val DEFAULT_CALL_CENTER = "71 200 55 56"

        /**
         * SMS-шаблон по умолчанию.
         *
         * Доступные подстановки:
         *   {name}  — имя арендатора (с маленькой буквы, как в примере пользователя)
         *   {days}  — количество дней просрочки
         *   {debt}  — сумма долга без копеек
         *   {payme} — ссылка на оплату Payme
         *   {call}  — номер call-центра
         */
        const val DEFAULT_TEMPLATE = """Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt}.

{payme}

Call center: {call}."""
    }
}
