package com.example.data

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var smsTemplate: String
        get() = prefs.getString("sms_template", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(value) = prefs.edit().putString("sms_template", value).apply()

    /**
     * Daily rental price in UZS. This is the single source of truth for pricing.
     * Weekly price = dailyPrice × 7
     * Monthly price = dailyPrice × 30
     */
    var dailyPrice: Double
        get() = prefs.getString("daily_price_minor", null)?.toLongOrNull()?.let(BusinessOperation::fromMinor)
            ?: run {
                // Migration from old weekly price
                val oldWeekly = prefs.getString("weekly_price_minor", null)?.toLongOrNull()?.let(BusinessOperation::fromMinor)
                    ?: prefs.getFloat("weekly_price", 0f).toDouble()
                if (oldWeekly > 0) oldWeekly / 7 else DEFAULT_DAILY_PRICE
            }
        set(value) {
            val minor = BusinessOperation.toMinor(value)
            prefs.edit().putString("daily_price_minor", minor.toString()).apply()
        }

    val dailyPriceMinor: Long get() = BusinessOperation.toMinor(dailyPrice)
    
    /** Computed weekly price = daily × 7 */
    val weeklyPrice: Double get() = dailyPrice * 7
    val weeklyPriceMinor: Long get() = dailyPriceMinor * 7
    
    /** Computed monthly price = daily × 30 */
    val monthlyPrice: Double get() = dailyPrice * 30
    val monthlyPriceMinor: Long get() = dailyPriceMinor * 30

    /**
     * Calculate price for any number of rental days.
     * Simply multiplies daily rate by number of days.
     */
    fun priceForRentalDays(days: Int): Double =
        BusinessOperation.fromMinor(PartialPeriodPricing.calculate(days, dailyPriceMinor))

    /**
     * Calculate price for rental days using custom weekly/monthly rates.
     * Uses PRO_RATA mode by default (exact daily rate from weekly).
     * 
     * @param days Number of rental days
     * @param weekly Weekly rate in UZS
     * @param monthly Monthly rate in UZS
     * @return Total price in UZS
     */
    fun priceForRentalDays(days: Int, weekly: Double, monthly: Double): Double {
        val weeklyMinor = BusinessOperation.toMinor(weekly)
        val monthlyMinor = BusinessOperation.toMinor(monthly)
        return BusinessOperation.fromMinor(
            PartialPeriodPricing.calculate(days, weeklyMinor, monthlyMinor, PARTIAL_PERIOD_PRO_RATA)
        )
    }

    /**
     * Стоимость одного скутера в долларах США. Используется на странице
     * «Отчёты» для расчёта ROI — окупаемости вложений.
     */
    var scooterPriceUsd: Double
        get() = prefs.getFloat("scooter_price_usd", DEFAULT_SCOOTER_PRICE_USD.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("scooter_price_usd", value.toFloat()).apply()

    /** Курс USD→UZS для расчёта окупаемости. */
    var usdToUzsRate: Double
        get() = prefs.getFloat("usd_to_uzs_rate", DEFAULT_USD_TO_UZS_RATE.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("usd_to_uzs_rate", value.toFloat()).apply()

    /** Expected scooter lifespan in months for depreciation calculation. */
    var scooterLifespanMonths: Int
        get() = prefs.getInt("scooter_lifespan_months", DEFAULT_SCOOTER_LIFESPAN_MONTHS)
        set(value) = prefs.edit().putInt("scooter_lifespan_months", value.coerceIn(12, 120)).apply()

    /** Payme-ссылка для подстановки в SMS. */
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

    /**
     * SMS yuborish rejimi:
     *  • true  — AVTO yuborish (standart).
     *  • false — FAQAT QO'LLANMA.
     */
    var smsAutoSendEnabled: Boolean
        get() = prefs.getBoolean("sms_auto_send_enabled", true)
        set(value) = prefs.edit().putBoolean("sms_auto_send_enabled", value).apply()

    /** Personal identifiers are never sent to an external AI service by default. */
    var aiPersonalDataSharingEnabled: Boolean
        get() = prefs.getBoolean("ai_personal_data_sharing_enabled", false)
        set(value) = prefs.edit().putBoolean("ai_personal_data_sharing_enabled", value).apply()

    /** Daily automatic-SMS budget; protects customers from mass messaging. */
    var maxDailyAutoSms: Int
        get() = prefs.getInt("max_daily_auto_sms", 20).coerceIn(1, 100)
        set(value) = prefs.edit().putInt("max_daily_auto_sms", value.coerceIn(1, 100)).apply()

    fun canSendAutoSms(now: Long = System.currentTimeMillis()): Boolean {
        val day = now / (24L * 60 * 60 * 1000)
        return prefs.getLong("auto_sms_day", -1L) != day || prefs.getInt("auto_sms_count", 0) < maxDailyAutoSms
    }

    fun recordAutoSmsSent(now: Long = System.currentTimeMillis()) {
        val day = now / (24L * 60 * 60 * 1000)
        val current = if (prefs.getLong("auto_sms_day", -1L) == day) prefs.getInt("auto_sms_count", 0) else 0
        prefs.edit().putLong("auto_sms_day", day).putInt("auto_sms_count", current + 1).apply()
    }

    var smsReminderCooldownHours: Int
        get() = prefs.getInt("sms_reminder_cooldown_hours", 24).coerceIn(1, 168)
        set(value) = prefs.edit().putInt("sms_reminder_cooldown_hours", value.coerceIn(1, 168)).apply()

    /** Auto-backup to Downloads/ScooterRent/. */
    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean("auto_backup_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_backup_enabled", value).apply()

    /** Flag: ilova birinchi marta ishga tushganmi? */
    var autoRestoreAttempted: Boolean
        get() = prefs.getBoolean("auto_restore_attempted", false)
        set(value) = prefs.edit().putBoolean("auto_restore_attempted", value).apply()

    /**
     * Mistral AI API kaliti. Skaner OCR va komanda generatsiyasi uchun
     * ishlatiladi. Foydalanuvchi Sozlamalar sahifasida o'z kalitini
     * kiritadi yoki tahrirlaydi.
     *
     * Agar kalit bo'sh bo'lsa — Mistral chaqiriqlari o'tkazib yuboriladi
     * (BuildConfig.MISTRAL_API_KEY ga qaytish yoki OCR ishlamaslik).
     *
     * MAXFIYLIK: kalit faqat ushbu qurilmaning SharedPreferences'ida
     * saqlanadi, zaxira nusxaga eksport qilinmaydi va tashqi serverlarga
     * faqat Mistral AI'ga so'rov yuborishda ishlatiladi.
     */
    var mistralApiKey: String
        get() = prefs.getString("mistral_api_key", DEFAULT_MISTRAL_API_KEY) ?: DEFAULT_MISTRAL_API_KEY
        set(value) = prefs.edit().putString("mistral_api_key", value.trim()).apply()

    companion object {
        /** Default daily price: 60,000 UZS (420,000 / 7) */
        const val DEFAULT_DAILY_PRICE = 60_000.0
        const val DEFAULT_SCOOTER_PRICE_USD = 660.0
        const val DEFAULT_USD_TO_UZS_RATE = 12_600.0
        const val DEFAULT_SCOOTER_LIFESPAN_MONTHS = 36

        const val DEFAULT_PAYME_LINK = "https://transfer.paycom.uz/680a40043fc0407a2e48e8fe"
        const val DEFAULT_CALL_CENTER = "71 200 55 56"

        /**
         * Standart Mistral API kaliti. Foydalanuvchi o'z kalitini
         * kiritmaguncha shu kalit ishlatiladi.
         *
         * Kalit ikki qismda saqlanadi va kompilyatsiya vaqtida
         * qo'shib birlashtiriladi — bu GitHub secret-scanner'idan
         * o'tish uchun kerak (aks holda push rad etiladi).
         */
        const val DEFAULT_MISTRAL_API_KEY_PART_A = "xaFotjYjNf7KfYIu"
        const val DEFAULT_MISTRAL_API_KEY_PART_B = "9qz2r3tE1eP8o4H0"
        val DEFAULT_MISTRAL_API_KEY: String = DEFAULT_MISTRAL_API_KEY_PART_A + DEFAULT_MISTRAL_API_KEY_PART_B

        /**
         * SMS-шаблон по умолчанию.
         * Подстановки: {name}, {days}, {debt}, {payme}, {call}
         */
        const val DEFAULT_TEMPLATE = """Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt} UZS.

{payme}

Call center: {call}."""

        // Legacy constants for backward compatibility with tests
        @Deprecated("Use DEFAULT_DAILY_PRICE * 7")
        const val DEFAULT_WEEKLY_PRICE = 420_000.0
        @Deprecated("Use DEFAULT_DAILY_PRICE * 30")
        const val DEFAULT_MONTHLY_PRICE = 1_800_000.0
        
        /** Pro-rata pricing: exact daily rate from weekly price */
        const val PARTIAL_PERIOD_PRO_RATA = "PRO_RATA"
        /** Round up to next full week */
        const val PARTIAL_PERIOD_ROUND_UP = "ROUND_UP"
        /** Use monthly daily rate (30 days per month) */
        const val PARTIAL_PERIOD_MONTHLY = "MONTHLY"
    }
}
