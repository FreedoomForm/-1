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
     * Дневная ставка аренды. Это ЕДИНСТВЕННАЯ настраиваемая цена — все
     * остальные суммы (неделя, месяц, N дней) вычисляются от неё:
     *   • 7 дней  = dailyPrice × 7
     *   • 14 дней = dailyPrice × 14
     *   • 30 дней = dailyPrice × 30
     *   • N дней  = dailyPrice × N
     *
     * Ранее в настройках были раздельные weeklyPrice + monthlyPrice — это
     * приводило к рассинхрону (например, monthly ≠ 4×weekly) и затрудняло
     * понимание пользователем, сколько он реально зарабатывает в день.
     * Теперь ВСЕ расчёты идут от одной цифры.
     *
     * Для обратной совместимости: если dailyPrice не задан (0.0), getter
     * возвращает weeklyPrice / 7 (старая ставка недели), либо дефолт.
     */
    var dailyPrice: Double
        get() {
            val direct = prefs.getFloat("daily_price", 0f).toDouble()
            if (direct > 0) return direct
            // Fallback: вычисляем из старой weeklyPrice
            val weekly = prefs.getFloat("weekly_price", 0f).toDouble()
            return if (weekly > 0) weekly / 7.0 else 0.0
        }
        set(value) = prefs.edit().putFloat("daily_price", value.toFloat()).apply()

    /** Старая недельная цена. Оставлена только для обратной совместимости. */
    var weeklyPrice: Double
        get() {
            val weekly = prefs.getFloat("weekly_price", 0f).toDouble()
            if (weekly > 0) return weekly
            // Если weeklyPrice не задан, но dailyPrice задан — вычисляем
            val daily = prefs.getFloat("daily_price", 0f).toDouble()
            return if (daily > 0) daily * 7.0 else 0.0
        }
        set(value) = prefs.edit().putFloat("weekly_price", value.toFloat()).apply()

    /** Старая месячная цена. Оставлена только для обратной совместимости. */
    var monthlyPrice: Double
        get() {
            val monthly = prefs.getFloat("monthly_price", 0f).toDouble()
            if (monthly > 0) return monthly
            // Если monthlyPrice не задан, но dailyPrice задан — вычисляем
            val daily = prefs.getFloat("daily_price", 0f).toDouble()
            return if (daily > 0) daily * 30.0 else 0.0
        }
        set(value) = prefs.edit().putFloat("monthly_price", value.toFloat()).apply()

    /**
     * Стоимость одного скутера в долларах США. Используется на странице
     * «Отчёты» для расчёта ROI — окупаемости вложений. По умолчанию $660
     * (типичная цена прокатного электросамоката на рынке Узбекистана).
     */
    var scooterPriceUsd: Double
        get() = prefs.getFloat("scooter_price_usd", DEFAULT_SCOOTER_PRICE_USD.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("scooter_price_usd", value.toFloat()).apply()

    /** Курс USD→UZS для расчёта окупаемости. По умолчанию 12 600 сумов. */
    var usdToUzsRate: Double
        get() = prefs.getFloat("usd_to_uzs_rate", DEFAULT_USD_TO_UZS_RATE.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("usd_to_uzs_rate", value.toFloat()).apply()

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

    /**
     * SMS yuborish rejimi:
     *  • true  — AVTO yuborish. Kechikkan mijozga SmsWorker
     *            (4 soatda bir) va kechikgan holda yaratilgan renter uchun
     *            RenterViewModel tomonidan SMS darhol yuboriladi.
     *  • false — FAQAT QO'LLANMA (STANDART). SMS faqat foydalanuvchi "SMS"
     *            tugmasini bosganda yuboriladi. SmsWorker va addRenter()
     *            avto-yuborish o'chiriladi (notif/yozuvlar saqlanadi).
     *
     * Standart qiymat — false (qo'llanma rejimi). Foydalanuvchi avto-yuborishni
     * Sozlamalar sahifasida qo'lda yoqishi mumkin.
     */
    var smsAutoSendEnabled: Boolean
        get() = prefs.getBoolean("sms_auto_send_enabled", false)
        set(value) = prefs.edit().putBoolean("sms_auto_send_enabled", value).apply()

    /**
     * Avto-zaxira nusxa (auto-backup to Downloads/ScooterRent/).
     * Yoqilgan bo'lsa, har bir ma'lumot o'zgarishidan so'ng ilova .xlsx
     * nusxasini public Downloads/ScooterRent/ papkasiga yozadi. Fayl
     * ilovani o'chirishdan keyin ham saqlanib qoladi va qayta o'rnatishda
     * avtomatik tiklanadi.
     *
     * Standart: yoqilgan (true).
     */
    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean("auto_backup_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_backup_enabled", value).apply()

    /**
     * Flag: ilova birinchi marta ishga tushganmi?
     * Avto-tiklash (auto-restore) faqat birinchi ishga tushishda bajariladi.
     * Bu flag true bo'lsa, avto-tiklash allaqachon bajarilgan degani.
     */
    var autoRestoreAttempted: Boolean
        get() = prefs.getBoolean("auto_restore_attempted", false)
        set(value) = prefs.edit().putBoolean("auto_restore_attempted", value).apply()

    companion object {
        /** Дневная ставка по умолчанию: 60 000 UZS (420 000 за неделю / 7). */
        const val DEFAULT_DAILY_PRICE = 60_000.0
        const val DEFAULT_WEEKLY_PRICE = 420_000.0
        const val DEFAULT_MONTHLY_PRICE = 1_680_000.0
        const val DEFAULT_SCOOTER_PRICE_USD = 660.0
        const val DEFAULT_USD_TO_UZS_RATE = 12_600.0

        const val DEFAULT_PAYME_LINK = "https://transfer.paycom.uz/680a40043fc0407a2e48e8fe"
        const val DEFAULT_CALL_CENTER = "71 200 55 56"

        /**
         * SMS-шаблон по умолчанию.
         *
         * Доступные подстановки:
         *   {name}        — имя арендатора (с маленькой буквы)
         *   {days}        — количество дней просрочки (минимум 1)
         *   {unpaidDays}  — сколько дней аренды не оплачено (на основе
         *                   неоплаченных контрактов, а не elapsed-rentDuration)
         *   {unpaidCount} — сколько неоплаченных контрактов у арендатора
         *   {debt}        — сумма долга без копеек (вычисляется как
         *                   unpaidDays × dailyPrice, а НЕ из balance)
         *   {payme}       — ссылка на оплату Payme
         *   {call}        — номер call-центра
         */
        const val DEFAULT_TEMPLATE = """Assalomu alaykum {name}, sizning skuter ijarangiz {unpaidDays} kun to'lanmagan. Umumiy qarz: {debt} so'm. Iltimos, to'lovni o'z vaqtida kiriting.

{payme}

Call center: {call}."""
    }
}
