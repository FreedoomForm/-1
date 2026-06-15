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
            while (isActive) {
                try { sync.pushOnly() } catch (e: Exception) { Log.w(TAG, "Auto-sync failed", e) }
                delay(30_000)
            }
        }
    }

    fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    fun addRenter(
        name: String, phone: String, debt: Double, duration: Int,
        startTimestamp: Long, scooterId: Int?, scooterName: String?, weeklyPrice: Double
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val expiryTime = startTimestamp + duration * 24L * 60 * 60 * 1000
            val isOverdueAtCreation = expiryTime < now
            val effectiveWeeklyPrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE

            val initialBalance = when {
                isOverdueAtCreation -> {
                    val overdueWeeks = ((now - expiryTime) / (7L * 24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                    -(effectiveWeeklyPrice * overdueWeeks)
                }
                debt > 0 -> -debt
                else -> 0.0
            }
            val finalDebt = if (initialBalance < 0) -initialBalance else 0.0

            val provisional = Renter(
                name = name, phoneNumber = phone, debtAmount = finalDebt,
                rentDurationDays = duration, rentStartDateTimestamp = startTimestamp,
                scooterId = scooterId, scooterName = scooterName, balance = initialBalance
            )

            val localId = repository.insert(provisional).toInt()
            val savedRenter = provisional.copy(id = localId)

            if (isOverdueAtCreation) {
                val context = getApplication<Application>()
                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber)
                } catch (e: Exception) { Log.e(TAG, "Notification xato", e) }

                sendSmsWithFullRetry(context, savedRenter, expiryTime, now, initialBalance)

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.notificationHistoryDao().insert(com.example.data.NotificationHistoryEntity(
                        timestamp = now, renterId = savedRenter.id,
                        title = "To'lov muddati yetdi",
                        message = "Mijoz ${savedRenter.name} (${savedRenter.phoneNumber}) bugun to'lov qilishi kerak"
                    ))
                } catch (e: Exception) { Log.w(TAG, "Notif save xato", e) }

                val wm = WorkManager.getInstance(getApplication())
                wm.enqueueUniquePeriodicWork("PaymentCheckWork", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS).build())
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
                    }
                } catch (e: Exception) { Log.w(TAG, "pushRenter failed", e) }

                try {
                    historyRepository.insert(ContractHistoryEntry(
                        renterId = renterIdForSync, timestamp = now,
                        type = ContractHistoryEntry.TYPE_CREATED, amount = effectiveWeeklyPrice,
                        notes = if (isOverdueAtCreation) "Kechikkan holda yaratildi" else "Yaratildi"
                    ))
                } catch (e: Exception) { Log.w(TAG, "History save xato", e) }

                try { sync.pushContractHistory(ContractHistoryEntry(
                    renterId = renterIdForSync, timestamp = now,
                    type = ContractHistoryEntry.TYPE_CREATED, amount = effectiveWeeklyPrice,
                    notes = if (isOverdueAtCreation) "Kechikkan holda yaratildi" else "Yaratildi"
                )) } catch (e: Exception) { Log.w(TAG, "pushHistory xato", e) }
            }
        }
    }

    /**
     * SMS yuborish — 3 ta usul bilan ketma-ket uriniladi:
     *
     * 1-urinish: getSmsManagerForSubscriptionId(subId) + PendingIntent
     * 2-urinish: getSystemService/getDefault + PendingIntent
     * 3-urinish: getDefault() + FIRE-AND-FORGET (PendingIntentsiz)
     *
     * Har bir urinishda sendMultipartTextMessage ishlatiladi
     * (xabar 160 belgidan uzun bo'lsa).
     */
    private fun sendSmsWithFullRetry(
        context: android.content.Context,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        initialBalance: Double
    ) {
        // 1. Permission
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: ruxsat berilmagan", "SMS_PERMISSION_DENIED", "SecurityException", "SEND_SMS ruxsati berilmagan"))
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SmsWorker>().build())
            return
        }

        // 2. Raqam normallashtirish
        val rawPhone = renter.phoneNumber
        val phone = SimHelper.normalizePhoneNumber(rawPhone)
        if (phone.isBlank()) {
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: raqam bo'sh", "SMS_EMPTY_PHONE", "IllegalArgumentException", "Telefon raqami kiritilmagan"))
            return
        }

        // 3. Xabar shakllantirish
        val settingsRepo = SettingsRepository(context)
        val message = settingsRepo.smsTemplate
            .replace("{name}", renter.name)
            .replace("{days}", (((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt()).toString())
            .replace("{debt}", (-initialBalance).toBigDecimal().stripTrailingZeros().toPlainString())

        Log.d(TAG, "SMS yuborish boshlandi: to=$phone (${rawPhone}→$phone), ${message.length} chars")

        // 4. Barcha SmsManager larni olish
        val attempts = SimHelper.getAllSmsManagers(context)
        val validAttempts = attempts.filter { it.smsManager != null }

        if (validAttempts.isEmpty()) {
            val diag = SimHelper.getDiagnostics(context, rawPhone)
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: SmsManager topilmadi",
                "SMS_MANAGER_NULL", "IllegalStateException", "Hech qanday SmsManager topilmadi.\n${diag.fullReport}"))
            return
        }

        // 5. Ketma-ket urinishlar
        trySmsAttempts(context, renter, phone, message, validAttempts, 0)
    }

    /**
     * Ketma-ket SMS urinishlari — biri muvaffaq bo'lgunga qadar yoki hammasi xato bo'lgunga qadar.
     */
    private fun trySmsAttempts(
        context: android.content.Context,
        renter: Renter,
        phone: String,
        message: String,
        attempts: List<SimHelper.SmsSendAttempt>,
        currentIndex: Int
    ) {
        if (currentIndex >= attempts.size) {
            // Barcha urinishlar muvaffaqiyatsiz — fire-and-forget bilan so'nggi urinish
            val lastManager = attempts.last().smsManager ?: return
            Log.w(TAG, "Barcha PendingIntentli urinishlar xato — fire-and-forget bilan urinilmoqda")
            val sent = SimHelper.sendSmsFireAndForget(lastManager, phone, message)
            if (sent) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
                _smsResults.tryEmit(SmsResult(true, "SMS yuborildi (fire-and-forget): $phone"))
            } else {
                val diag = SimHelper.getDiagnostics(context, phone)
                _smsResults.tryEmit(SmsResult(false,
                    "SMS yuborilmadi: barcha usullar xato (${attempts.size} ta urinish)",
                    "SMS_ALL_METHODS_FAILED", "GENERIC_FAILURE",
                    buildString {
                        appendLine("Ilova ${attempts.size} xil usul bilan urinib ko'rdi — hammasida GENERIC_FAILURE.")
                        appendLine()
                        appendLine("Eng ehtimol sabablar:")
                        appendLine("1) SIM balans YETARLI EMAS — ${diag.networkOperatorName ?: "operator"} balansini tekshiring!")
                        appendLine("2) Operator SMS ni rad etmoqda — operatorga qo'ng'iroq qiling")
                        appendLine("3) Ilova DEFAULT SMS APP emas — Android sozlamalaridan 'Default SMS app' qiling")
                        appendLine()
                        appendLine(diag.fullReport)
                    }))
            }
            return
        }

        val attempt = attempts[currentIndex]
        val smsManager = attempt.smsManager ?: return trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)

        // Oxirgi urinish bo'lsa — fire-and-forget (PendingIntentsiz)
        val isLastAttempt = currentIndex == attempts.size - 1

        if (isLastAttempt) {
            // Oxirgi urinish — PendingIntentsiz fire-and-forget
            val sent = SimHelper.sendSmsFireAndForget(smsManager, phone, message)
            if (sent) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
                _smsResults.tryEmit(SmsResult(true, "SMS yuborildi (${attempt.method}): $phone"))
            } else {
                // Fire-and-forget ham ishlamadi — xato
                val diag = SimHelper.getDiagnostics(context, phone)
                _smsResults.tryEmit(SmsResult(false,
                    "SMS yuborilmadi: barcha usullar xato",
                    "SMS_ALL_METHODS_FAILED", "GENERIC_FAILURE",
                    buildString {
                        appendLine("${attempts.size} ta usul bilan urinildi — hammasi GENERIC_FAILURE.")
                        appendLine()
                        appendLine("Sabablar:")
                        appendLine("1) SIM BALANS yetarli emas — ${diag.networkOperatorName ?: "operator"} balansini tekshiring!")
                        appendLine("2) Operator SMS ni rad etmoqda")
                        appendLine("3) Ilova Default SMS app emas")
                        appendLine()
                        appendLine(diag.fullReport)
                    }))
            }
            return
        }

        // O'rta urinishlar — PendingIntent bilan
        val actionId = "com.example.SMS_SENT_${++smsSendCounter}_A${attempt.attempt}"
        val sentIntent = PendingIntent.getBroadcast(
            context, smsSendCounter, Intent(actionId),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                val code = resultCode
                Log.d(TAG, "SMS result [${attempt.method}]: code=$code for $phone")

                if (code == android.app.Activity.RESULT_OK) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                    }
                    _smsResults.tryEmit(SmsResult(true, "SMS muvaffaqiyatli yuborildi (${attempt.method}): $phone"))
                } else if (code == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
                    // GENERIC_FAILURE — keyingi usul bilan urinish
                    Log.w(TAG, "GENERIC_FAILURE [${attempt.method}] → keyingi usulga o'tilmoqda")
                    _smsResults.tryEmit(SmsResult(true, "SMS usul ${attempt.attempt} xato, keyingi usul sinab ko'rilmoca..."))
                    trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
                } else {
                    // Boshqa xato — keyingi usul bilan urinish
                    val (errorName, _) = smsErrorCodeToText(code)
                    Log.w(TAG, "$errorName [${attempt.method}] → keyingi usulga o'tilmoqda")
                    trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
                }
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(actionId))
        } catch (e: Exception) {
            Log.e(TAG, "Receiver ro'yxatdan o'tkazilmadi", e)
        }

        try {
            SimHelper.sendSmsAuto(smsManager, phone, message, sentIntent, null)
            Log.d(TAG, "SMS ${attempt.attempt}-urinish [${attempt.method}]: → $phone (${message.length} chars)")
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            Log.w(TAG, "SMS ${attempt.attempt}-urinishda exception: ${e.message}")
            trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
        }
    }

    private fun smsErrorCodeToText(code: Int): Pair<String, String> {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE" to "Umumiy xato (kod 1)"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF" to "Radio o'chiq (kod 2)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU" to "PDU xatosi (kod 3)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE" to "Tarmoq yo'q (kod 4)"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED" to "SMS limiti (kod 5)"
            else -> "UNKNOWN_$code" to "Noma'lum ($code)"
        }
    }

    fun updateRenter(renter: Renter) {
        viewModelScope.launch { repository.update(renter); sync.pushRenterUpdate(renter) }
    }

    fun deleteRenter(id: Int) {
        viewModelScope.launch { repository.delete(id); sync.pushRenterDelete(id) }
    }

    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch { applyWeeklyPayment(renter, "Bitta to'lov") }
    }

    fun payWeeklyForRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id -> repository.getById(id)?.let { applyWeeklyPayment(it, "Ommaviy to'lov", weeklyPrice) } }
        }
    }

    fun terminateRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id -> repository.getById(id)?.let { applyTermination(it, weeklyPrice) } }
        }
    }

    private suspend fun applyWeeklyPayment(renter: Renter, notes: String, weeklyPriceOverride: Double? = null) {
        val weeklyPrice = weeklyPriceOverride ?: SettingsRepository(getApplication()).weeklyPrice
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val newBalance = renter.balance + effectivePrice
        val updated = renter.copy(debtAmount = maxOf(0.0, -newBalance), balance = newBalance,
            lastPaymentTimestamp = System.currentTimeMillis(), isOverdueSmsSent = false)
        repository.update(updated); sync.pushRenterUpdate(updated)
        val entry = ContractHistoryEntry(renterId = renter.id, timestamp = System.currentTimeMillis(),
            type = ContractHistoryEntry.TYPE_PAYMENT, amount = effectivePrice, notes = notes)
        historyRepository.insert(entry); sync.pushContractHistory(entry)
    }

    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double) {
        val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val newBalance = renter.balance + effectivePrice
        val updated = renter.copy(debtAmount = maxOf(0.0, -newBalance), balance = newBalance,
            isReturned = true, lastPaymentTimestamp = System.currentTimeMillis(), isOverdueSmsSent = false)
        repository.update(updated); sync.pushRenterUpdate(updated)
        val entry = ContractHistoryEntry(renterId = renter.id, timestamp = System.currentTimeMillis(),
            type = ContractHistoryEntry.TYPE_TERMINATED, amount = effectivePrice, notes = "Kontrakt tugatildi")
        historyRepository.insert(entry); sync.pushContractHistory(entry)
    }

    companion object {
        private const val TAG = "RenterViewModel"
    }
}
