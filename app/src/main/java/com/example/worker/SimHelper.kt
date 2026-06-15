package com.example.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
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
     * SIM karta diagnostika ma'lumotlari — xato paytida ko'rsatish uchun.
     */
    data class SimDiagnostics(
        val simCount: Int,
        val simState: Int,
        val simStateText: String,
        val networkOperator: String?,
        val networkOperatorName: String?,
        val phoneType: Int,
        val selectedSubId: Int,
        val resolvedSubId: Int,
        val isAirplaneMode: Boolean,
        val normalizedPhone: String
    ) {
        /** To'liq diagnostika matni */
        val fullReport: String
            get() = buildString {
                appendLine("SIM diagnostikasi:")
                appendLine("  SIM soni: $simCount")
                appendLine("  SIM holati: $simStateText (kod=$simState)")
                appendLine("  Operator: ${networkOperatorName ?: "noma'lum"} (${networkOperator ?: "-"})")
                appendLine("  Telefon turi: ${if (phoneType == TelephonyManager.PHONE_TYPE_GSM) "GSM" else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) "CDMA" else "SIP"}")
                appendLine("  Tanlangan subId: $selectedSubId")
                appendLine("  Foydalanilgan subId: $resolvedSubId")
                appendLine("  Parvozd rejimi: ${if (isAirplaneMode) "YOQIQ ❌" else "O'chiq ✓"}")
                appendLine("  Raqam formati: $normalizedPhone")
            }
    }

    /**
     * Qurilmadagi barcha faol SIM kartalarni qaytaradi.
     * READ_PHONE_STATE permission talab qilinadi.
     */
    fun getActiveSimCards(context: Context): List<SimInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.w(TAG, "API < 22 — SubscriptionManager mavjud emas")
            return emptyList()
        }

        if (!hasPhoneStatePermission(context)) {
            Log.e(TAG, "READ_PHONE_STATE permission yo'q")
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
     * Telefon raqamini O'zbekiston standartiga normallashtirish.
     *
     * Qabul qilinadigan formatlar:
     * - +998901234567 → +998901234567
     * - 998901234567  → +998901234567
     * - 8901234567    → +998901234567  (eski Sovet formati)
     * - 901234567     → +998901234567
     * - +7901234567   → +998901234567  (Qozog'iston/Kirg'iziston aralash)
     */
    fun normalizePhoneNumber(phone: String): String {
        if (phone.isBlank()) return phone

        // Bo'sh joy, tire, qavslarni olib tashlash
        var normalized = phone.trim()
            .replace("[\\s\\-\\(\\)\\.]".toRegex(), "")

        // Agar + bilan boshlansa, + ni vaqtincha olib tashlash
        val hadPlus = normalized.startsWith("+")
        if (hadPlus) {
            normalized = normalized.substring(1)
        }

        // Eski Sovet formati: 8 bilan boshlansa → 998 ga almashtirish
        if (normalized.startsWith("8") && normalized.length == 10) {
            normalized = "998" + normalized.substring(1)
        }
        // 9 bilan boshlanib 9 raqamli bo'lsa → 998 qo'shish
        else if (normalized.startsWith("9") && normalized.length == 9) {
            normalized = "998" + normalized
        }
        // 998 bilan boshlansa → + qo'shish
        else if (normalized.startsWith("998") && normalized.length == 12) {
            // already correct, just need +
        }
        // 7 bilan boshlansa (Qozog'iston formati +7) → agar 10 raqam bo'lsa, 998 deb hisoblash
        else if (normalized.startsWith("7") && normalized.length == 11) {
            // Bu Qozog'iston +7 formati bo'lishi mumkin,
            // lekin O'zbekiston uchun 998 ga o'zgartirmaymiz
            Log.w(TAG, "Raqam +7 formatida: $phone — O'zbekiston uchun +998 kerak")
        }

        // + qo'shish
        if (!normalized.startsWith("+")) {
            normalized = "+$normalized"
        }

        if (normalized != phone.trim()) {
            Log.d(TAG, "Raqam normallashtirildi: '$phone' → '$normalized'")
        }

        return normalized
    }

    /**
     * Raqam O'zbekiston formatiga to'g'ri ekanligini tekshirish.
     */
    fun isValidUzbekPhone(phone: String): Boolean {
        val normalized = normalizePhoneNumber(phone)
        return normalized.matches("\\+998\\d{9}".toRegex())
    }

    /**
     * Diagnostika ma'lumotlarini olish — xato paytida UI da ko'rsatish uchun.
     */
    @Suppress("DEPRECATION")
    fun getDiagnostics(context: Context, rawPhone: String): SimDiagnostics {
        val settingsRepo = com.example.data.SettingsRepository(context)
        val selectedSubId = settingsRepo.selectedSimSubscriptionId
        val resolvedSubId = resolveSubscriptionId(context, selectedSubId)

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        val simStateText = when (simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "SIM yo'q"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN kerak"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK kerak"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Tarmoq qulfi"
            TelephonyManager.SIM_STATE_READY -> "Tayyor ✓"
            TelephonyManager.SIM_STATE_NOT_READY -> "Tayyor emas"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "Butunlay o'chirilgan"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "SIM karta xatosi"
            else -> "Noma'lum ($simState)"
        }

        val isAirplaneMode = try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
            ) == 1
        } catch (e: Exception) {
            false
        }

        return SimDiagnostics(
            simCount = getActiveSimCards(context).size,
            simState = simState,
            simStateText = simStateText,
            networkOperator = telephonyManager?.networkOperator,
            networkOperatorName = telephonyManager?.networkOperatorName,
            phoneType = telephonyManager?.phoneType ?: TelephonyManager.PHONE_TYPE_NONE,
            selectedSubId = selectedSubId,
            resolvedSubId = resolvedSubId,
            isAirplaneMode = isAirplaneMode,
            normalizedPhone = normalizePhoneNumber(rawPhone)
        )
    }

    /**
     * Berilgan subscription ID uchun SmsManager qaytaradi.
     *
     * Agar subId == -1 bo'lsa (tanlanmagan), avvalo saqlangan SIM ni tekshiradi,
     * keyin birinchi faol SIM ni tanlaydi, oxirda getDefault() ga tushadi.
     */
    @Suppress("DEPRECATION")
    fun getSmsManagerForSim(context: Context, subscriptionId: Int = -1): SmsManager? {
        return try {
            val effectiveSubId = resolveSubscriptionId(context, subscriptionId)

            if (effectiveSubId != -1) {
                Log.d(TAG, "SmsManager subId=$effectiveSubId uchun olinmoqda")
                val manager = getSmsManagerForSubId(effectiveSubId)
                if (manager != null) {
                    Log.d(TAG, "SmsManager subId=$effectiveSubId muvaffaqiyatli olindi")
                    return manager
                }
                // subId bilan olib bo'lmadi — fallback ga o'tamiz
                Log.w(TAG, "SmsManager subId=$effectiveSubId uchun null, default ga o'tilmoqda")
            }

            // Fallback: default SmsManager
            Log.w(TAG, "Default SmsManager ishlatilmoqda")
            getDefaultSmsManager(context)
        } catch (e: Exception) {
            Log.e(TAG, "SmsManager olishda xato", e)
            try {
                getDefaultSmsManager(context)
            } catch (e2: Exception) {
                Log.e(TAG, "Hatto getDefault() ham ishlamadi", e2)
                null
            }
        }
    }

    /**
     * ESKI USUL bilan SmsManager olish — GENERIC_FAILURE bo'lganda
     * fallback sifatida ishlatiladi.
     *
     * Bu usul "avatgacha" ishlagan — ba'zi qurilmalarda
     * getSmsManagerForSubscriptionId ishlamaydi.
     */
    @Suppress("DEPRECATION")
    fun getLegacySmsManager(context: Context): SmsManager? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy SmsManager olishda xato", e)
            null
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
            Log.d(TAG, "Birinchi faol SIM: ${firstSim.displayName} (subId=${firstSim.subscriptionId})")
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
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
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
     * Ommaviy metod — RenterViewModel tomonidan foydalaniladi.
     * Saqlangan subscription ID ni qaytaradi yoki -1.
     */
    fun resolveSubscriptionIdPublic(context: Context): Int {
        return resolveSubscriptionId(context, -1)
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

    @Suppress("DEPRECATION")
    private fun getPhoneNumber(info: SubscriptionInfo): String? {
        return try {
            info.number
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
