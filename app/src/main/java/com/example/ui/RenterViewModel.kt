package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import com.example.data.remote.SyncManager
import com.example.worker.NotificationHelper
import com.example.worker.PaymentCheckWorker
import com.example.worker.SimHelper
import com.example.worker.SmsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Результат операции SMS для отображения в UI.
 */
data class SmsResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null
) {
    val fullDetails: String
        get() = buildString {
            appendLine(message)
            if (errorCode != null) appendLine("Xato kodi: $errorCode")
            if (exceptionClass != null) appendLine("Exception: $exceptionClass")
            if (exceptionMessage != null) appendLine("Tushuntirish: $exceptionMessage")
        }
}

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    private val historyRepository: com.example.data.ContractHistoryRepository
    private val sync: SyncManager
    val rentersList: StateFlow<List<Renter>>

    private var autoSyncJob: Job? = null
    private var smsSendCounter = 0

    private val _smsResults = MutableSharedFlow<SmsResult>(extraBufferCapacity = 10)
    val smsResults: SharedFlow<SmsResult> = _smsResults

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RenterRepository(database.renterDao())
        historyRepository = com.example.data.ContractHistoryRepository(
            database.contractHistoryDao()
        )
        sync = SyncManager(application)
        rentersList = repository.allRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Auto-sync started (every 30s, smart merge)")
            while (isActive) {
                try {
                    sync.smartMerge()
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-sync failed", e)
                }
                delay(30_000)
            }
        }
    }

    fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
        Log.d(TAG, "Auto-sync stopped")
    }

    fun addRenter(
        name: String,
        phone: String,
        debt: Double,
        duration: Int,
        startTimestamp: Long,
        scooterId: Int?,
        scooterName: String?,
        weeklyPrice: Double
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val expiryTime = startTimestamp + duration * 24L * 60 * 60 * 1000
            val isOverdueAtCreation = expiryTime < now

            val effectiveWeeklyPrice = if (weeklyPrice > 0) weeklyPrice
                else SettingsRepository.DEFAULT_WEEKLY_PRICE

            val initialBalance = if (isOverdueAtCreation) {
                val overdueMillis = now - expiryTime
                val overdueWeeks = (overdueMillis / (7L * 24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                -(effectiveWeeklyPrice * overdueWeeks)
            } else if (debt > 0) {
                -debt
            } else {
                0.0
            }
            val finalDebt = if (initialBalance < 0) -initialBalance else 0.0

            val provisional = Renter(
                name = name,
                phoneNumber = phone,
                debtAmount = finalDebt,
                rentDurationDays = duration,
                rentStartDateTimestamp = startTimestamp,
                scooterId = scooterId,
                scooterName = scooterName,
                balance = initialBalance
            )

            val localId = repository.insert(provisional).toInt()
            val savedRenter = provisional.copy(id = localId)

            if (isOverdueAtCreation) {
                val context = getApplication<Application>()
                Log.d(TAG, "Renter #${savedRenter.id} is OVERDUE — sending SMS & notification")

                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(
                        context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show notification", e)
                }

                sendSmsWithDeliveryReport(context, savedRenter, expiryTime, now, initialBalance)

                try {
                    val notifEntry = com.example.data.NotificationHistoryEntity(
                        timestamp = now,
                        renterId = savedRenter.id,
                        title = "To'lov muddati yetdi",
                        message = "Mijoz ${savedRenter.name} (${savedRenter.phoneNumber}) bugun to'lov qilishi kerak"
                    )
                    val db = AppDatabase.getDatabase(context)
                    db.notificationHistoryDao().insert(notifEntry)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save notification history locally", e)
                }

                val wm = WorkManager.getInstance(getApplication())
                val paymentCheckRequest =
                    androidx.work.PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(
                    "PaymentCheckWork",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    paymentCheckRequest
                )
            }

            viewModelScope.launch(Dispatchers.IO) {
                val renterIdForSync = savedRenter.id

                try {
                    val serverId = sync.pushRenter(provisional)
                    if (serverId != null && serverId != localId) {
                        val db = AppDatabase.getDatabase(getApplication<Application>())
                        db.withTransaction {
                            db.renterDao().updateRenterId(localId, serverId)
                            db.contractHistoryDao().updateRenterId(localId, serverId)
                            db.notificationHistoryDao().updateRenterId(localId, serverId)
                        }
                        Log.d(TAG, "Renter synced: local #$localId → server #$serverId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pushRenter failed, stays local #$localId", e)
                }

                try {
                    historyRepository.insert(
                        ContractHistoryEntry(
                            renterId = renterIdForSync,
                            timestamp = now,
                            type = ContractHistoryEntry.TYPE_CREATED,
                            amount = effectiveWeeklyPrice,
                            notes = if (isOverdueAtCreation)
                                "Kechikkan holda yaratildi (${(-initialBalance).toBigDecimal().stripTrailingZeros().toPlainString()})"
                            else "Yaratildi"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save contract history locally", e)
                }

                try {
                    sync.pushContractHistory(
                        ContractHistoryEntry(
                            renterId = renterIdForSync,
                            timestamp = now,
                            type = ContractHistoryEntry.TYPE_CREATED,
                            amount = effectiveWeeklyPrice,
                            notes = if (isOverdueAtCreation)
                                "Kechikkan holda yaratildi (${(-initialBalance).toBigDecimal().stripTrailingZeros().toPlainString()})"
                            else "Yaratildi"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to push contract history to server", e)
                }

                if (isOverdueAtCreation) {
                    try {
                        val notifEntry = com.example.data.NotificationHistoryEntity(
                            timestamp = now,
                            renterId = renterIdForSync,
                            title = "To'lov muddati yetdi",
                            message = "Mijoz ${savedRenter.name} (${savedRenter.phoneNumber}) bugun to'lov qilishi kerak"
                        )
                        sync.pushNotification(notifEntry)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to push notification to server", e)
                    }
                }
            }
        }
    }

    /**
     * SMS ni 2 bosqichda yuboradi:
     *
     * 1-urinish: getSmsManagerForSubscriptionId(subId) — aniq SIM bilan
     * 2-urinish (fallback): eski usul — getDefault() / getSystemService()
     *
     * Shuningdek, telefon raqamini normallashtiradi va to'liq diagnostika
     * ko'rsatadi.
     */
    private fun sendSmsWithDeliveryReport(
        context: android.content.Context,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        initialBalance: Double
    ) {
        // 1. Permission tekshirish
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: ruxsat berilmagan",
                errorCode = "SMS_PERMISSION_DENIED",
                exceptionClass = "SecurityException",
                exceptionMessage = "android.permission.SEND_SMS ruxsati berilmagan. Ilova sozlamalaridan ruxsatni yoqing."
            ))
            val smsWork = OneTimeWorkRequestBuilder<SmsWorker>().build()
            WorkManager.getInstance(context).enqueue(smsWork)
            return
        }

        // 2. Telefon raqamini normallashtirish
        val rawPhone = renter.phoneNumber
        val phone = SimHelper.normalizePhoneNumber(rawPhone)

        if (phone.isBlank()) {
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: telefon raqami bo'sh",
                errorCode = "SMS_EMPTY_PHONE",
                exceptionClass = "IllegalArgumentException",
                exceptionMessage = "Telefon raqami kiritilmagan. Raqamni +998... formatida kiriting."
            ))
            return
        }

        // Raqam formati to'g'riligini tekshirish
        if (!SimHelper.isValidUzbekPhone(phone)) {
            Log.w(TAG, "Raqam O'zbekiston formatiga to'g'ri kelmayapti: $rawPhone → $phone")
            // Xabar yuborishga harakat qilamiz — balki boshqa mamlakat raqami
        }

        // 3. SmsManager olish — 1-urinish: subscription ID bilan
        var smsManager = getSmsManager(context)
        var usedSubId = SimHelper.resolveSubscriptionIdPublic(context)

        // Agar SmsManager null bo'lsa — eski usul bilan urinib ko'rish
        if (smsManager == null) {
            Log.w(TAG, "SmsManager (subId) null — eski usul bilan urinilmoqda")
            smsManager = SimHelper.getLegacySmsManager(context)
            usedSubId = -1
        }

        if (smsManager == null) {
            val apiLevel = Build.VERSION.SDK_INT
            val diag = SimHelper.getDiagnostics(context, rawPhone)
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: SmsManager topilmadi",
                errorCode = "SMS_MANAGER_NULL",
                exceptionClass = "IllegalStateException",
                exceptionMessage = "SmsManager null. API=$apiLevel.\n${diag.fullReport}"
            ))
            return
        }

        // 4. Xabarni shakllantirish
        val settingsRepo = SettingsRepository(context)
        val rawTemplate = settingsRepo.smsTemplate
        val daysOverdue = ((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt()
        val debtDisplay = (-initialBalance).toBigDecimal()
            .stripTrailingZeros().toPlainString()
        val message = rawTemplate
            .replace("{name}", renter.name)
            .replace("{days}", daysOverdue.toString())
            .replace("{debt}", debtDisplay)

        Log.d(TAG, "SMS: to=$phone (${rawPhone}→${phone}), ${message.length} chars, subId=$usedSubId")

        // 5. PendingIntent + BroadcastReceiver
        val actionId = "com.example.SMS_SENT_${++smsSendCounter}"
        val sentIntent = PendingIntent.getBroadcast(
            context,
            smsSendCounter,
            Intent(actionId),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                val resultCode = resultCode
                Log.d(TAG, "SMS result for $phone: code=$resultCode (subId=$usedSubId)")

                if (resultCode == android.app.Activity.RESULT_OK) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                    }
                    _smsResults.tryEmit(SmsResult(
                        success = true,
                        message = "SMS muvaffaqiyatli yuborildi: $phone"
                    ))
                } else if (resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE && usedSubId != -1) {
                    // ❗ GENERIC_FAILURE va subId ishlatilmoqda — ESKI USUL bilan qayta urinish
                    Log.w(TAG, "GENERIC_FAILURE with subId=$usedSubId — retrying with legacy SmsManager")
                    retryWithLegacySmsManager(context, renter, phone, message)
                } else {
                    // Boshqa xatolar
                    val (errorName, errorDesc) = smsErrorCodeToText(resultCode, context, rawPhone)
                    val diag = SimHelper.getDiagnostics(context, rawPhone)
                    _smsResults.tryEmit(SmsResult(
                        success = false,
                        message = "SMS yuborilmadi: $errorName",
                        errorCode = "SMS_SEND_FAILED_$resultCode",
                        exceptionClass = errorName,
                        exceptionMessage = "$errorDesc\n\n${diag.fullReport}"
                    ))
                }
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(actionId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SMS receiver", e)
        }

        // 6. SMS yuborish — 1-urinish
        try {
            smsManager.sendTextMessage(phone, null, message, sentIntent, null)
            Log.d(TAG, "SMS 1-urinish: subId=$usedSubId → $phone")

            _smsResults.tryEmit(SmsResult(
                success = true,
                message = "SMS yuborilmoqda: $phone... (natija kutilmoqda)"
            ))
        } catch (e: IllegalArgumentException) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            // Raqam formati xato — normallashtirilgan raqam bilan qayta urinish
            Log.w(TAG, "IllegalArgumentException for $phone — retrying with legacy SmsManager")
            retryWithLegacySmsManager(context, renter, phone, message)
        } catch (e: SecurityException) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: ruxsat xatosi",
                errorCode = "SMS_SECURITY_EXCEPTION",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "SEND_SMS ruxsati rad etildi"
            ))
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            Log.e(TAG, "SMS 1-urinishda xato, eski usul bilan urinilmoqda", e)
            retryWithLegacySmsManager(context, renter, phone, message)
        }
    }

    /**
     * FALLBACK: Eski usul (getDefault / getSystemService) bilan SMS yuborish.
     *
     * Agar getSmsManagerForSubscriptionId bilan GENERIC_FAILURE chiqsa,
     * bu usul ishlaydi — chunki "avatgacha" shu usul ishlagan.
     */
    private fun retryWithLegacySmsManager(
        context: android.content.Context,
        renter: Renter,
        phone: String,
        message: String
    ) {
        val legacyManager = SimHelper.getLegacySmsManager(context)
        if (legacyManager == null) {
            val diag = SimHelper.getDiagnostics(context, phone)
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: SmsManager topilmadi (ikkala usul ham)",
                errorCode = "SMS_MANAGER_NULL_BOTH",
                exceptionClass = "IllegalStateException",
                exceptionMessage = "Na subscription, na legacy SmsManager topilmadi.\n${diag.fullReport}"
            ))
            return
        }

        val actionId = "com.example.SMS_LEGACY_${++smsSendCounter}"
        val sentIntent = PendingIntent.getBroadcast(
            context,
            smsSendCounter,
            Intent(actionId),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                val resultCode = resultCode
                Log.d(TAG, "SMS LEGACY result for $phone: code=$resultCode")

                if (resultCode == android.app.Activity.RESULT_OK) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                    }
                    _smsResults.tryEmit(SmsResult(
                        success = true,
                        message = "SMS muvaffaqiyatli yuborildi (eski usul): $phone"
                    ))
                } else {
                    val (errorName, errorDesc) = smsErrorCodeToText(resultCode, context, phone)
                    val diag = SimHelper.getDiagnostics(context, phone)
                    _smsResults.tryEmit(SmsResult(
                        success = false,
                        message = "SMS yuborilmadi: $errorName (ikkala usul ham)",
                        errorCode = "SMS_SEND_FAILED_${resultCode}_LEGACY",
                        exceptionClass = errorName,
                        exceptionMessage = "$errorDesc\n\nDiagnostika:\n${diag.fullReport}"
                    ))
                }
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(actionId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register LEGACY SMS receiver", e)
        }

        try {
            legacyManager.sendTextMessage(phone, null, message, sentIntent, null)
            Log.d(TAG, "SMS 2-urinish (LEGACY): → $phone")

            _smsResults.tryEmit(SmsResult(
                success = true,
                message = "SMS qayta yuborilmoqda (eski usul): $phone..."
            ))
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            val diag = SimHelper.getDiagnostics(context, phone)
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: ${e.javaClass.simpleName}",
                errorCode = "SMS_LEGACY_EXCEPTION",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = "${e.message}\n\nDiagnostika:\n${diag.fullReport}"
            ))
        }
    }

    private fun smsErrorCodeToText(code: Int, context: Context, phone: String): Pair<String, String> {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                val diag = SimHelper.getDiagnostics(context, phone)
                "GENERIC_FAILURE" to buildString {
                    appendLine("Umumiy xato (kod 1). Mumkin bo'lgan sabablar:")
                    appendLine("1) SIM balans yetarli emas — operator balansini tekshiring")
                    appendLine("2) 2 ta SIM — Sozlamalardan SIM kartani tanlang")
                    appendLine("3) Operator SMS ni rad etmoqda")
                    appendLine("4) Tarmoq muammosi — signal darajasini tekshiring")
                    appendLine("5) Raqam formati noto'g'ri: '$phone'")
                    appendLine()
                    appendLine("Qidirish: 'Android SmsManager RESULT_ERROR_GENERIC_FAILURE'")
                }
            }
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                "RADIO_OFF" to "Radio o'chiq (kod 2). Parvozd rejimini o'chiring."
            SmsManager.RESULT_ERROR_NULL_PDU ->
                "NULL_PDU" to "PDU protokol xatosi (kod 3). Operator SMS formatini qo'llab-quvvatlamayapti."
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                "NO_SERVICE" to "Tarmoq yo'q (kod 4). Signal darajasini tekshiring."
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
                "LIMIT_EXCEEDED" to "SMS limiti oshildi (kod 5). Bir oz kutib qayta urinib ko'ring."
            else ->
                "UNKNOWN_ERROR_$code" to "Noma'lum xato kodi: $code"
        }
    }

    /**
     * SmsManager ni dual-SIM bilan olish.
     */
    private fun getSmsManager(context: android.content.Context): SmsManager? {
        return SimHelper.getSmsManagerForSim(context)
    }

    fun updateRenter(renter: Renter) {
        viewModelScope.launch {
            repository.update(renter)
            sync.pushRenterUpdate(renter)
        }
    }

    fun deleteRenter(id: Int) {
        viewModelScope.launch {
            repository.delete(id)
            sync.pushRenterDelete(id)
        }
    }

    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch {
            applyWeeklyPayment(renter, "Bitta to'lov")
        }
    }

    fun payWeeklyForRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id ->
                val renter = repository.getById(id) ?: return@forEach
                applyWeeklyPayment(renter, "Ommaviy to'lov", weeklyPrice)
            }
        }
    }

    fun terminateRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id ->
                val renter = repository.getById(id) ?: return@forEach
                applyTermination(renter, weeklyPrice)
            }
        }
    }

    private suspend fun applyWeeklyPayment(
        renter: Renter,
        notes: String,
        weeklyPriceOverride: Double? = null
    ) {
        val weeklyPrice = weeklyPriceOverride
            ?: SettingsRepository(getApplication()).weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice
            else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val newBalance = renter.balance + effectivePrice
        val newDebt = maxOf(0.0, -newBalance)
        val updated = renter.copy(
            debtAmount = newDebt,
            balance = newBalance,
            lastPaymentTimestamp = System.currentTimeMillis(),
            isOverdueSmsSent = false
        )
        repository.update(updated)
        sync.pushRenterUpdate(updated)

        val entry = ContractHistoryEntry(
            renterId = renter.id,
            timestamp = System.currentTimeMillis(),
            type = ContractHistoryEntry.TYPE_PAYMENT,
            amount = effectivePrice,
            notes = notes
        )
        historyRepository.insert(entry)
        sync.pushContractHistory(entry)
    }

    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double) {
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice
            else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val newBalance = renter.balance + effectivePrice
        val newDebt = maxOf(0.0, -newBalance)
        val updated = renter.copy(
            debtAmount = newDebt,
            balance = newBalance,
            isReturned = true,
            lastPaymentTimestamp = System.currentTimeMillis(),
            isOverdueSmsSent = false
        )
        repository.update(updated)
        sync.pushRenterUpdate(updated)

        val entry = ContractHistoryEntry(
            renterId = renter.id,
            timestamp = System.currentTimeMillis(),
            type = ContractHistoryEntry.TYPE_TERMINATED,
            amount = effectivePrice,
            notes = "Kontrakt tugatildi"
        )
        historyRepository.insert(entry)
        sync.pushContractHistory(entry)
    }

    companion object {
        private const val TAG = "RenterViewModel"
    }
}
