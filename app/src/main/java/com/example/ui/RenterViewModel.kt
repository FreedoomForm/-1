package com.example.ui

import android.app.Application
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
            // ВАЖНО: SMS отправляется ДО любых sync-вызовов!
            if (isOverdueAtCreation) {
                val context = getApplication<Application>()
                Log.d(TAG, "Renter #${savedRenter.id} is OVERDUE at creation — sending SMS & notification IMMEDIATELY")
                Log.d(TAG, "expiryTime=$expiryTime, now=$now, phone=${savedRenter.phoneNumber}")

                // 1) Показываем уведомление ПРЯМО СЕЙЧАС
                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(
                        context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber
                    )
                    Log.d(TAG, "Notification shown immediately for renter #${savedRenter.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show notification", e)
                }

                // 2) Отправляем SMS ПРЯМО СЕЙЧАС — с детальным отчётом
                sendSmsWithReport(context, savedRenter, expiryTime, now, initialBalance)

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

                // A) Пуш рентера на сервер → обновляем ID
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

                // B) Локальная история контрактов
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

                // C) Пуш истории контрактов на сервер
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

                // D) Пуш уведомления на сервер
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
     * Отправляет SMS с детальным отчётом об ошибке для UI.
     *
     * Каждый шаг проверяется отдельно, и если что-то не так —
     * отправляется SmsResult с понятным кодом ошибки, который
     * можно скопировать и найти решение в интернете.
     */
    private fun sendSmsWithReport(
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
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: ruxsat berilmagan",
                errorCode = "SMS_PERMISSION_DENIED",
                exceptionClass = "SecurityException",
                exceptionMessage = "android.permission.SEND_SMS ruxsati berilmagan. Ilova sozlamalaridan ruxsatni yoqing."
            )
            Log.w(TAG, result.fullDetails)
            _smsResults.tryEmit(result)

            // Фолбэк на SmsWorker
            val smsWork = OneTimeWorkRequestBuilder<SmsWorker>().build()
            WorkManager.getInstance(context).enqueue(smsWork)
            return
        }

        // Шаг 2: Формирование сообщения
        val settingsRepo = SettingsRepository(context)
        val rawTemplate = settingsRepo.smsTemplate
        val daysOverdue = ((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt()
        val debtDisplay = (-initialBalance).toBigDecimal()
            .stripTrailingZeros().toPlainString()
        val message = rawTemplate
            .replace("{name}", renter.name)
            .replace("{days}", daysOverdue.toString())
            .replace("{debt}", debtDisplay)

        // Шаг 3: Получение SmsManager
        val smsManager = getSmsManager(context)
        if (smsManager == null) {
            val apiLevel = Build.VERSION.SDK_INT
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: SmsManager topilmadi",
                errorCode = "SMS_MANAGER_NULL",
                exceptionClass = "IllegalStateException",
                exceptionMessage = "SmsManager null. API level=$apiLevel. Qurilmada SIM-karta yoq yoki bu emulyator."
            )
            Log.e(TAG, result.fullDetails)
            _smsResults.tryEmit(result)
            return
        }

        // Шаг 4: Проверка номера телефона
        val phone = renter.phoneNumber
        if (phone.isBlank()) {
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: telefon raqami bo'sh",
                errorCode = "SMS_EMPTY_PHONE",
                exceptionClass = "IllegalArgumentException",
                exceptionMessage = "Telefon raqami kiritilmagan. Raqamni +998... formatida kiriting."
            )
            Log.e(TAG, result.fullDetails)
            _smsResults.tryEmit(result)
            return
        }

        // Шаг 5: Отправка SMS
        try {
            smsManager.sendTextMessage(phone, null, message, null, null)
            Log.d(TAG, "SMS sent successfully to $phone")
            repository.update(renter.copy(isOverdueSmsSent = true))

            _smsResults.tryEmit(SmsResult(
                success = true,
                message = "SMS muvaffaqiyatli yuborildi: $phone"
            ))
        } catch (e: SecurityException) {
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: ruxsat xatosi",
                errorCode = "SMS_SECURITY_EXCEPTION",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "SEND_SMS ruxsati rad etildi"
            )
            Log.e(TAG, result.fullDetails, e)
            _smsResults.tryEmit(result)
        } catch (e: IllegalArgumentException) {
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: noto'g'ri raqam formati",
                errorCode = "SMS_INVALID_PHONE",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "Telefon raqami formati noto'g'ri: $phone"
            )
            Log.e(TAG, result.fullDetails, e)
            _smsResults.tryEmit(result)
        } catch (e: NullPointerException) {
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: ichki xato",
                errorCode = "SMS_NULL_POINTER",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "SmsManager ichki xatosi. Qurilmaning SMS ilovasini tekshiring."
            )
            Log.e(TAG, result.fullDetails, e)
            _smsResults.tryEmit(result)
        } catch (e: Exception) {
            val result = SmsResult(
                success = false,
                message = "SMS yuborilmadi: kutilmagan xato",
                errorCode = "SMS_UNEXPECTED_ERROR",
                exceptionClass = e.javaClass.simpleName,
                exceptionMessage = e.message ?: "Noma'lum xato yuz berdi"
            )
            Log.e(TAG, result.fullDetails, e)
            _smsResults.tryEmit(result)
        }
    }

    /**
     * Получает SmsManager с совместимостью для всех версий Android.
     *
     * - API 31+ (Android 12+): context.getSystemService(SmsManager::class.java)
     * - API < 31: SmsManager.getDefault() (deprecated в 31, но работает на старых)
     */
    @Suppress("DEPRECATION")
    private fun getSmsManager(context: android.content.Context): SmsManager? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSmsManager failed", e)
            null
        }
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
