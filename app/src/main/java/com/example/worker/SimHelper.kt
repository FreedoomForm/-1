package com.example.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Dual-SIM qurilmalarda SIM kartani tanlash va SmsManager ni olish uchun yordamchi klass.
 *
 * Asosiy muammo: SmsManager.getDefault() 2 ta SIM bo'lganda qaysi SIM dan
 * foydalanishni bilmaydi va GENERIC_FAILURE qaytaradi.
 *
 * Yechim: SmsManager.getSmsManagerForSubscriptionId(subId) orqali
 * aniq SIM kartani tanlash.
 */
object SimHelper {

    private const val TAG = "SimHelper"

    /**
     * SIM karta ma'lumotlari.
     */
    data class SimInfo(
        val subscriptionId: Int,
        val slotIndex: Int,
        val carrierName: String,
        val phoneNumber: String?,
        val mcc: Int,
        val mnc: Int
    ) {
        /** UI da ko'rsatish uchun matn: "SIM 1: Umobile" */
        val displayName: String
            get() = "SIM ${slotIndex + 1}: $carrierName"

        /** To'liq ma'lumot: "SIM 1: Umobile (+998901234567)" */
        val fullDisplayName: String
            get() = if (!phoneNumber.isNullOrBlank()) {
                "SIM ${slotIndex + 1}: $carrierName ($phoneNumber)"
            } else {
                displayName
            }
    }

    /**
     * Qurilmadagi barcha faol SIM kartalarni qaytaradi.
     * READ_PHONE_STATE permission talab qilinadi.
     *
     * @return SIM kartalar ro'yxati yoki bo'sh ro'yxat (permission yo'q yoki SIM yo'q)
     */
    fun getActiveSimCards(context: Context): List<SimInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.w(TAG, "API < 22 — SubscriptionManager mavjud emas, 1 SIM deb hisoblaymiz")
            return emptyList()
        }

        if (!hasPhoneStatePermission(context)) {
            Log.e(TAG, "READ_PHONE_STATE permission yo'q — SIM ro'yxatini olib bo'lmadi")
            return emptyList()
        }

        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
                ?: return emptyList()

            @Suppress("DEPRECATION")
            val subscriptions: List<SubscriptionInfo> =
                subscriptionManager.activeSubscriptionInfoList ?: return emptyList()

            subscriptions.mapNotNull { info ->
                try {
                    SimInfo(
                        subscriptionId = info.subscriptionId,
                        slotIndex = info.simSlotIndex,
                        carrierName = info.carrierName?.toString() ?: "Noma'lum",
                        phoneNumber = getPhoneNumber(info),
                        mcc = info.mcc,
                        mnc = info.mnc
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "SIM info o'qishda xato: ${e.message}")
                    null
                }
            }.also { simList ->
                Log.d(TAG, "Topilgan SIM kartalar: ${simList.size}")
                simList.forEach { sim ->
                    Log.d(TAG, "  ${sim.fullDisplayName} (subId=${sim.subscriptionId})")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: READ_PHONE_STATE yo'q", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "SIM ro'yxatini olishda xato", e)
            emptyList()
        }
    }

    /**
     * SubscriptionInfo dan telefon raqamini olish.
     * Android 10+ da READ_PHONE_NUMBERS talab qilinadi,
     * aks holda null qaytariladi.
     */
    @Suppress("DEPRECATION")
    private fun getPhoneNumber(info: SubscriptionInfo): String? {
        return try {
            info.number
        } catch (e: SecurityException) {
            Log.w(TAG, "Telefon raqamini olish uchun READ_PHONE_NUMBERS kerak")
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Berilgan subscription ID uchun SmsManager qaytaradi.
     *
     * Agar subId == -1 bo'lsa (tanlanmagan), avvalo saqlangan SIM ni tekshiradi,
     * keyin birinchi faol SIM ni tanlaydi, oxirda getDefault() ga tushadi.
     *
     * @return SmsManager yoki null (SIM yo'q yoki emulyator)
     */
    @Suppress("DEPRECATION")
    fun getSmsManagerForSim(context: Context, subscriptionId: Int = -1): SmsManager? {
        return try {
            val effectiveSubId = resolveSubscriptionId(context, subscriptionId)

            if (effectiveSubId != -1) {
                // Aniq SIM tanlangan — getSmsManagerForSubscriptionId ishlatamiz
                Log.d(TAG, "SmsManager subId=$effectiveSubId uchun olinmoqda")
                getSmsManagerForSubId(effectiveSubId)
            } else {
                // SIM tanlanmagan va topilmadi — fallback
                Log.w(TAG, "SIM topilmadi, getDefault() ishlatilmoqda (GENERIC_FAILURE xavfi bor!)")
                getDefaultSmsManager(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsManager olishda xato", e)
            // Oxirgi chora: getDefault()
            try {
                getDefaultSmsManager(context)
            } catch (e2: Exception) {
                Log.e(TAG, "Hatto getDefault() ham ishlamadi", e2)
                null
            }
        }
    }

    /**
     * Subscription ID ni aniqlash: berilgan, saqlangan yoki birinchi faol SIM.
     */
    private fun resolveSubscriptionId(context: Context, providedSubId: Int): Int {
        // 1. Agar aniq ID berilgan bo'lsa
        if (providedSubId != -1) {
            Log.d(TAG, "Berilgan subId=$providedSubId ishlatilmoqda")
            return providedSubId
        }

        // 2. Saqlangan ID ni tekshirish
        val settingsRepo = com.example.data.SettingsRepository(context)
        val savedSubId = settingsRepo.selectedSimSubscriptionId
        if (savedSubId != -1) {
            // Saqlangan SIM hali ham faol ekanligini tekshirish
            val activeSims = getActiveSimCards(context)
            if (activeSims.any { it.subscriptionId == savedSubId }) {
                Log.d(TAG, "Saqlangan subId=$savedSubId ishlatilmoqda")
                return savedSubId
            } else {
                Log.w(TAG, "Saqlangan subId=$savedSubId endi faol emas, yangilanmoqda")
                settingsRepo.selectedSimSubscriptionId = -1
            }
        }

        // 3. Birinchi faol SIM ni tanlash
        val activeSims = getActiveSimCards(context)
        if (activeSims.isNotEmpty()) {
            val firstSim = activeSims.first()
            Log.d(TAG, "Birinchi faol SIM ishlatilmoqda: ${firstSim.displayName} (subId=${firstSim.subscriptionId})")
            settingsRepo.selectedSimSubscriptionId = firstSim.subscriptionId
            return firstSim.subscriptionId
        }

        // 4. Hech narsa topilmadi
        return -1
    }

    /**
     * Subscription ID bo'yicha SmsManager olish.
     * API 31+ da SmsManager.getSmsManagerForSubscriptionId()
     * API 22-30 da reflection orqali
     */
    private fun getSmsManagerForSubId(subId: Int): SmsManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31): Rasmiy API
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // Android 5.1+ (API 22-30): Reflection orqali
            try {
                val method = SmsManager::class.java.getDeclaredMethod(
                    "getSmsManagerForSubscriptionId", Int::class.javaPrimitiveType
                )
                method.invoke(null, subId) as? SmsManager
            } catch (e: Exception) {
                Log.e(TAG, "Reflection bilan SmsManager olish muvaffaqiyatsiz", e)
                null
            }
        } else {
            Log.w(TAG, "API < 22 — getSmsManagerForSubscriptionId mavjud emas")
            null
        }
    }

    /**
     * Default SmsManager olish (fallback).
     */
    @Suppress("DEPRECATION")
    private fun getDefaultSmsManager(context: Context): SmsManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }

    /**
     * READ_PHONE_STATE permission bor-yo'qligini tekshirish.
     */
    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Qurilmada nechta faol SIM borligini qaytaradi.
     */
    fun getSimCount(context: Context): Int {
        return getActiveSimCards(context).size
    }

    /**
     * Berilgan subscription ID haqiqiy va faol ekanligini tekshiradi.
     */
    fun isValidSubscriptionId(context: Context, subId: Int): Boolean {
        if (subId == -1) return false
        return getActiveSimCards(context).any { it.subscriptionId == subId }
    }
}
