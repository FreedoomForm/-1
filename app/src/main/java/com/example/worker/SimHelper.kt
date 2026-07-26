package com.example.worker

import android.Manifest
import android.app.PendingIntent
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
 * 3 qatlamli SmsManager olish:
 * 1. getSmsManagerForSubscriptionId(subId) — aniq SIM
 * 2. context.getSystemService(SmsManager::class.java) — default SIM (Android 12+)
 * 3. SmsManager.getDefault() — eski usul (deprecated lekin ba'zi qurilmalarda ishlaydi)
 */
object SimHelper {

    private const val TAG = "SimHelper"

    /** SMS maksimal uzunligi — bundan uzun bo'lsa multipart kerak */
    const val SMS_MAX_LENGTH = 160

    data class SimInfo(
        val subscriptionId: Int,
        val slotIndex: Int,
        val carrierName: String,
        val phoneNumber: String?,
        val mcc: Int,
        val mnc: Int
    ) {
        val displayName: String
            get() = "SIM ${slotIndex + 1}: $carrierName"

        val fullDisplayName: String
            get() = if (!phoneNumber.isNullOrBlank()) {
                "SIM ${slotIndex + 1}: $carrierName ($phoneNumber)"
            } else {
                displayName
            }
    }

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
        val normalizedPhone: String,
        val defaultSmsSubId: Int
    ) {
        val fullReport: String
            get() = buildString {
                appendLine("SIM diagnostikasi:")
                appendLine("  SIM soni: $simCount")
                appendLine("  SIM holati: $simStateText (kod=$simState)")
                appendLine("  Operator: ${networkOperatorName ?: "noma'lum"} (${networkOperator ?: "-"})")
                appendLine("  Telefon turi: ${if (phoneType == TelephonyManager.PHONE_TYPE_GSM) "GSM" else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) "CDMA" else "SIP"}")
                appendLine("  Tanlangan subId: $selectedSubId")
                appendLine("  Foydalanilgan subId: $resolvedSubId")
                appendLine("  Default SMS subId: $defaultSmsSubId")
                appendLine("  Parvozd rejimi: ${if (isAirplaneMode) "YOQIQ" else "O'chiq"}")
                appendLine("  Raqam formati: $normalizedPhone")
            }
    }

    /**
     * SMS yuborish natijasi — har bir urinish uchun alohida.
     */
    data class SmsSendAttempt(
        val attempt: Int,
        val method: String,
        val smsManager: SmsManager?,
        val subId: Int
    )

    fun getActiveSimCards(context: Context): List<SimInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
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
     */
    fun normalizePhoneNumber(phone: String): String {
        if (phone.isBlank()) return phone

        var normalized = phone.trim()
            .replace("[\\s\\-\\(\\)\\.]".toRegex(), "")

        val hadPlus = normalized.startsWith("+")
        if (hadPlus) {
            normalized = normalized.substring(1)
        }

        if (normalized.startsWith("8") && normalized.length == 10) {
            normalized = "998" + normalized.substring(1)
        } else if (normalized.startsWith("9") && normalized.length == 9) {
            normalized = "998" + normalized
        }

        if (!normalized.startsWith("+")) {
            normalized = "+$normalized"
        }

        if (normalized != phone.trim()) {
            Log.d(TAG, "Raqam normallashtirildi: '$phone' → '$normalized'")
        }

        return normalized
    }

    fun isValidUzbekPhone(phone: String): Boolean {
        val normalized = normalizePhoneNumber(phone)
        return normalized.matches("\\+998\\d{9}".toRegex())
    }

    /**
     * Diagnostika ma'lumotlarini olish.
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
            TelephonyManager.SIM_STATE_READY -> "Tayyor"
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

        // Default SMS subscription ID ni olish
        val defaultSmsSubId = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            } else -1
        } catch (e: Exception) {
            -1
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
            normalizedPhone = normalizePhoneNumber(rawPhone),
            defaultSmsSubId = defaultSmsSubId
        )
    }

    /**
     * SmsManager ni 3 ta usul bilan olishga urinadi.
     * Har bir usul alohida SmsManager qaytaradi — birinchisi ishlamasa
     * keyingisi ishlatiladi.
     *
     * @return SmsSendAttempt ro'yxati — qaysi usul qachon ishlatilganini ko'rsatadi
     */
    @Suppress("DEPRECATION")
    fun getAllSmsManagers(context: Context): List<SmsSendAttempt> {
        val attempts = mutableListOf<SmsSendAttempt>()
        val subId = resolveSubscriptionId(context, -1)

        // 1-urinish: getSmsManagerForSubscriptionId(subId)
        if (subId != -1) {
            val manager1 = getSmsManagerForSubId(subId)
            attempts.add(SmsSendAttempt(1, "getSmsManagerForSubscriptionId($subId)", manager1, subId))
        }

        // 2-urinish: context.getSystemService(SmsManager::class.java) — Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manager2 = context.getSystemService(SmsManager::class.java)
                attempts.add(SmsSendAttempt(2, "getSystemService(SmsManager)", manager2, -1))
            } catch (e: Exception) {
                Log.w(TAG, "getSystemService(SmsManager) xato: ${e.message}")
            }
        }

        // 3-urinish: SmsManager.getDefault() — eng eski usul, deprecated lekin ba'zi qurilmalarda ishlaydi
        try {
            val manager3 = SmsManager.getDefault()
            attempts.add(SmsSendAttempt(3, "SmsManager.getDefault()", manager3, -1))
        } catch (e: Exception) {
            Log.w(TAG, "SmsManager.getDefault() xato: ${e.message}")
        }

        attempts.forEach { attempt ->
            Log.d(TAG, "SMS attempt ${attempt.attempt}: ${attempt.method} → ${if (attempt.smsManager != null) "OK" else "NULL"}")
        }

        return attempts
    }

    /**
     * SmsManager ni olish — birinchi topilganini qaytaradi.
     */
    @Suppress("DEPRECATION")
    fun getSmsManagerForSim(context: Context, subscriptionId: Int = -1): SmsManager? {
        val effectiveSubId = if (subscriptionId != -1) subscriptionId
            else resolveSubscriptionId(context, -1)

        // 1. Subscription ID bilan
        if (effectiveSubId != -1) {
            val manager = getSmsManagerForSubId(effectiveSubId)
            if (manager != null) return manager
        }

        // 2. getSystemService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manager = context.getSystemService(SmsManager::class.java)
                if (manager != null) return manager
            } catch (_: Exception) {}
        }

        // 3. getDefault()
        return try {
            SmsManager.getDefault()
        } catch (e: Exception) {
            Log.e(TAG, "Barcha SmsManager usullari muvaffaqiyatsiz", e)
            null
        }
    }

    /**
     * Eski usul bilan SmsManager — GENERIC_FAILURE fallback uchun.
     * Bu usul "avatgacha" ishlagan.
     */
    @Suppress("DEPRECATION")
    fun getLegacySmsManager(context: Context): SmsManager? {
        // 1. getSystemService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manager = context.getSystemService(SmsManager::class.java)
                if (manager != null) return manager
            } catch (_: Exception) {}
        }

        // 2. getDefault()
        return try {
            SmsManager.getDefault()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * SmsManager orqali SMS yuborish — avtomatik multipart bilan.
     *
     * Agar xabar 160 belgidan uzun bo'lsa, sendMultipartTextMessage ishlatiladi.
     * Aks holda sendTextMessage ishlatiladi.
     */
    fun sendSmsAuto(
        smsManager: SmsManager,
        phone: String,
        message: String,
        sentIntent: PendingIntent? = null,
        deliveryIntent: PendingIntent? = null
    ) {
        if (message.length > SMS_MAX_LENGTH) {
            // Uzun xabar — multipart
            val parts = smsManager.divideMessage(message)
            Log.d(TAG, "SMS multipart: ${parts.size} qism, ${message.length} chars")

            val sentIntents = if (sentIntent != null) {
                ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
            } else null

            val deliveryIntents = if (deliveryIntent != null) {
                ArrayList<PendingIntent>().apply { repeat(parts.size) { add(deliveryIntent) } }
            } else null

            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
        } else {
            // Qisqa xabar — oddiy
            smsManager.sendTextMessage(phone, null, message, sentIntent, deliveryIntent)
        }
    }

    /**
     * SmsManager orqali SMS yuborish — PendingIntentsiz (fire-and-forget).
     * Bu eng sodda usul — agar PendingIntent muammo bo'lsa ishlatiladi.
     */
    fun sendSmsFireAndForget(
        smsManager: SmsManager,
        phone: String,
        message: String
    ): Boolean {
        return try {
            sendSmsAuto(smsManager, phone, message, null, null)
            Log.d(TAG, "SMS fire-and-forget OK: $phone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS fire-and-forget xato: $phone", e)
            false
        }
    }

    private fun resolveSubscriptionId(context: Context, providedSubId: Int): Int {
        if (providedSubId != -1) return providedSubId

        val settingsRepo = com.example.data.SettingsRepository(context)
        val savedSubId = settingsRepo.selectedSimSubscriptionId
        if (savedSubId != -1) {
            val activeSims = getActiveSimCards(context)
            if (activeSims.any { it.subscriptionId == savedSubId }) {
                return savedSubId
            } else {
                settingsRepo.selectedSimSubscriptionId = -1
            }
        }

        val activeSims = getActiveSimCards(context)
        if (activeSims.isNotEmpty()) {
            val firstSim = activeSims.first()
            settingsRepo.selectedSimSubscriptionId = firstSim.subscriptionId
            return firstSim.subscriptionId
        }

        return -1
    }

    fun resolveSubscriptionIdPublic(context: Context): Int {
        return resolveSubscriptionId(context, -1)
    }

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

    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getSimCount(context: Context): Int {
        return getActiveSimCards(context).size
    }

    fun isValidSubscriptionId(context: Context, subId: Int): Boolean {
        if (subId == -1) return false
        return getActiveSimCards(context).any { it.subscriptionId == subId }
    }

    @Suppress("DEPRECATION")
    private fun getPhoneNumber(info: SubscriptionInfo): String? {
        return try {
            info.number
        } catch (e: Exception) {
            null
        }
    }
}
