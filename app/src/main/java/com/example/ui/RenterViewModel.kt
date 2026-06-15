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
 * Содержит понятное сообщение, которое можно скопировать и найти в интернете.
 */
data class SmsResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,      // Короткий код ошибки для поиска
    val exceptionClass: String? = null,  // Имя класса исключения
    val exceptionMessage: String? = null // Полное сообщение исключения
) {
    /** Полный текст для копирования — включает все детали */
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

    /** Результаты SMS-отправки — UI подписывается и показывает диалог */
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

    /**
     * Авто-синхронизация каждые 30 секунд.
     */
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

            // СНАЧАЛА пишем в Room — рентер появляется мгновенно
            val localId = repository.insert(provisional).toInt()
            val savedRenter = provisional.copy(id = localId)

            // ===== НЕМЕДЛЕННОЕ УВЕДОМЛЕНИЕ И SMS =====
            if (isOverdueAtCreation) {
                val context = getApplication<Application>()
                Log.d(TAG, "Renter #${savedRenter.id} is OVERDUE at creation — sending SMS & notification IMMEDIATELY")

                // 1) Показываем уведомление ПРЯМО СЕЙЧАС
                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(
                        context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show notification", e)
                }

                // 2) Отправляем SMS с отслеживанием реального результата
                sendSmsWithDeliveryReport(context, savedRenter, expiryTime, now, initialBalance)

                // 3) Сохраняем уведомление в историю локально
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

                // 4) Регистрируем периодические проверки
                val wm = WorkManager.getInstance(getApplication())
                val paymentCheckRequest =
                    androidx.work.PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(
                    "PaymentCheckWork",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    paymentCheckRequest
                )
            }

            // ===== ВСЕ СЕТВОВЫЕ ВЫЗОВЫ — В ОТДЕЛЬНОЙ КОРУТИНЕ =====
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
     * Отправляет SMS с PendingIntent для отслеживания РЕАЛЬНОГО результата.
     *
     * sendTextMessage() без sentIntent только ставит SMS в очередь и
     * НЕ сообщает об ошибке. С sentIntent мы получаем реальный результат:
     * - RESULT_OK — SMS реально отправлено оператором
     * - RESULT_ERROR_GENERIC_FAILURE — общая ошибка
     * - RESULT_ERROR_NO_SERVICE — нет сети
     * - RESULT_ERROR_RADIO_OFF — радио выключено (авиарежим)
     * - RESULT_ERROR_NULL_PDU — ошибка протокола
     * - RESULT_ERROR_LIMIT_EXCEEDED — лимит SMS превышен
     */
    private fun sendSmsWithDeliveryReport(
        context: android.content.Context,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        initialBalance: Double
    ) {
        // Шаг 1: Проверка разрешения
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

        // Шаг 2: Получение SmsManager
        val smsManager = getSmsManager(context)
        if (smsManager == null) {
            val apiLevel = Build.VERSION.SDK_INT
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: SmsManager topilmadi",
                errorCode = "SMS_MANAGER_NULL",
                exceptionClass = "IllegalStateException",
                exceptionMessage = "SmsManager null. API level=$apiLevel. Qurilmada SIM-karta yoq yoki bu emulyator."
            ))
            return
        }

        // Шаг 3: Проверка номера телефона
        val phone = renter.phoneNumber
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

        // Шаг 4: Формирование сообщения
        val settingsRepo = SettingsRepository(context)
        val rawTemplate = settingsRepo.smsTemplate
        val daysOverdue = ((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt()
        val debtDisplay = (-initialBalance).toBigDecimal()
            .stripTrailingZeros().toPlainString()
        val message = rawTemplate
            .replace("{name}", renter.name)
            .replace("{days}", daysOverdue.toString())
            .replace("{debt}", debtDisplay)

        Log.d(TAG, "SMS message length: ${message.length} chars, to: $phone")

        // Шаг 5: Регистрируем BroadcastReceiver для получения РЕАЛЬНОГО результата
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
                Log.d(TAG, "SMS sent result for $phone: resultCode=$resultCode")

                if (resultCode == android.app.Activity.RESULT_OK) {
                    // SMS реально отправлено оператором!
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                    }
                    _smsResults.tryEmit(SmsResult(
                        success = true,
                        message = "SMS muvaffaqiyatli yuborildi: $phone"
                    ))
                } else {
                    // SMS провалилась — показываем конкретную причину
                    val (errorName, errorDesc) = smsErrorCodeToText(resultCode)
                    _smsResults.tryEmit(SmsResult(
                        success = false,
                        message = "SMS yuborilmadi: $errorName",
                        errorCode = "SMS_SEND_FAILED_$resultCode",
                        exceptionClass = errorName,
                        exceptionMessage = errorDesc
                    ))
                }
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(actionId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SMS receiver", e)
        }

        // Шаг 6: Отправка SMS с sentIntent
        try {
            smsManager.sendTextMessage(phone, null, message, sentIntent, null)
            Log.d(TAG, "SMS queued for sending to $phone (waiting for result...)")

            // Показываем промежуточное сообщение — SMS в очереди
            _smsResults.tryEmit(SmsResult(
                success = true,
                message = "SMS yuborilmoqda: $phone... (natija kutilmoqda)"
            ))
        } catch (e: SecurityException) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: ruxsat xatosi",
                errorCode = "SMS_SECURITY_EXCEPTION",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "SEND_SMS ruxsati rad etildi"
            ))
        } catch (e: IllegalArgumentException) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: noto'g'ri raqam formati",
                errorCode = "SMS_INVALID_PHONE",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "Telefon raqami formati noto'g'ri: $phone"
            ))
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            _smsResults.tryEmit(SmsResult(
                success = false,
                message = "SMS yuborilmadi: kutilmagan xato",
                errorCode = "SMS_UNEXPECTED_ERROR",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "Noma'lum xato yuz berdi"
            ))
        }
    }

    /**
     * Конвертирует код ошибки SmsManager в понятный текст.
     *
     * Коды ошибок:
     * - RESULT_ERROR_GENERIC_FAILURE (1) — общая ошибка (SIM, сеть, оператор)
     * - RESULT_ERROR_NO_SERVICE (4) — нет сотовой сети
     * - RESULT_ERROR_NULL_PDU (3) — ошибка протокола PDU
     * - RESULT_ERROR_RADIO_OFF (2) — радио выключено (авиарежим)
     * - RESULT_ERROR_LIMIT_EXCEEDED (5) — лимит SMS превышен
     */
    private fun smsErrorCodeToText(code: Int): Pair<String, String> {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                "GENERIC_FAILURE" to
                "Umumiy xato (kod 1). Sabablari: 1) 2 ta SIM kartadan foydalanmoqdasiz — Ilova sozlamalaridan SIM kartani tanlang. 2) SIM-karta ishlamayapti yoki balans yetarli emas. 3) Operator rad etdi yoki tarmoqda muammo. Internetda qidirish: 'Android SmsManager RESULT_ERROR_GENERIC_FAILURE dual SIM'"
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                "RADIO_OFF" to
                "Radio o'chiq (kod 2). Telefon parvozd rejimida yoki tarmoq o'chiq. Parvozd rejimini o'chiring va qayta urinib ko'ring."
            SmsManager.RESULT_ERROR_NULL_PDU ->
                "NULL_PDU" to
                "PDU protokol xatosi (kod 3). Operator SMS formatini qo'llab-quvvatlamayapti. Boshqa telefon raqamiga yuborib ko'ring."
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                "NO_SERVICE" to
                "Tarmoq yo'q (kod 4). Telefon tarmoqqa ulanmagan. Signal darajasini tekshiring va qayta urinib ko'ring."
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
                "LIMIT_EXCEEDED" to
                "SMS limiti oshildi (kod 5). Judayam ko'p SMS yuborildi. Bir oz kutib qayta urinib ko'ring."
            else ->
                "UNKNOWN_ERROR_$code" to
                "Noma'lum xato kodi: $code. Internetda qidirish: 'Android SmsManager error code $code'"
        }
    }

    /**
     * SmsManager ni dual-SIM qo'llab-quvvatlash bilan olish.
     *
     * SimHelper orqali aniq SIM kartani tanlaydi.
     * Agar SIM tanlanmagan bo'lsa, birinchi faol SIM ni avto-tanlaydi.
     */
    private fun getSmsManager(context: android.content.Context): SmsManager? {
        return com.example.worker.SimHelper.getSmsManagerForSim(context)
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
