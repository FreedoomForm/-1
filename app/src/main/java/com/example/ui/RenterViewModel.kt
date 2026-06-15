package com.example.ui

import android.app.Application
import android.content.pm.PackageManager
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    private val historyRepository: com.example.data.ContractHistoryRepository
    private val sync: SyncManager
    val rentersList: StateFlow<List<Renter>>

    private var autoSyncJob: Job? = null

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
     *
     * ВАЖНО: Использует smartMerge() вместо pullAll()!
     * smartMerge() НЕ удаляет локальные данные — он обновляет
     * существующие и добавляет новые с сервера.
     * Это устраняет проблему исчезновения данных.
     *
     * Интервал увеличен с 5с до 30с для снижения нагрузки на сервер.
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
            var savedRenter = provisional.copy(id = localId)

            // ПОТОМ шлём на API — получаем server id
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val serverId = sync.pushRenter(provisional)
                    if (serverId != null && serverId != localId) {
                        // Обновляем id в одной транзакции — без delete+insert,
                        // чтобы UI не «моргал» (исчезновение арендатора)
                        val db = AppDatabase.getDatabase(getApplication<Application>())
                        db.withTransaction {
                            db.renterDao().updateRenterId(localId, serverId)
                            db.renterDao().updateContractHistoryRenterId(localId, serverId)
                            db.renterDao().updateNotificationHistoryRenterId(localId, serverId)
                        }
                        savedRenter = savedRenter.copy(id = serverId)
                        Log.d(TAG, "Renter synced: local #$localId → server #$serverId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pushRenter failed, stays local #$localId", e)
                }
            }

            // Записываем в историю контрактов
            historyRepository.insert(
                ContractHistoryEntry(
                    renterId = savedRenter.id,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_CREATED,
                    amount = effectiveWeeklyPrice,
                    notes = if (isOverdueAtCreation)
                        "Kechikkan holda yaratildi (${(-initialBalance).toBigDecimal().stripTrailingZeros().toPlainString()})"
                    else "Yaratildi"
                )
            )
            sync.pushContractHistory(
                ContractHistoryEntry(
                    renterId = savedRenter.id,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_CREATED,
                    amount = effectiveWeeklyPrice,
                    notes = if (isOverdueAtCreation)
                        "Kechikkan holda yaratildi (${(-initialBalance).toBigDecimal().stripTrailingZeros().toPlainString()})"
                    else "Yaratildi"
                )
            )

            // ===== НЕМЕДЛЕННОЕ УВЕДОМЛЕНИЕ И SMS =====
            if (isOverdueAtCreation) {
                val context = getApplication<Application>()

                // 1) Показываем уведомление ПРЯМО СЕЙЧАС — не через Worker
                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(
                        context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber
                    )
                    Log.d(TAG, "Notification shown immediately for renter #${savedRenter.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show notification", e)
                }

                // 2) Отправляем SMS ПРЯМО СЕЙЧАС — не через Worker
                try {
                    if (ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.SEND_SMS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val settingsRepo = SettingsRepository(context)
                        val rawTemplate = settingsRepo.smsTemplate
                        val daysOverdue = ((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt()
                        val debtDisplay = (-initialBalance).toBigDecimal()
                            .stripTrailingZeros().toPlainString()
                        val message = rawTemplate
                            .replace("{name}", savedRenter.name)
                            .replace("{days}", daysOverdue.toString())
                            .replace("{debt}", debtDisplay)

                        val smsManager = context.getSystemService(
                            android.telephony.SmsManager::class.java
                        )
                        if (smsManager != null) {
                            smsManager.sendTextMessage(
                                savedRenter.phoneNumber, null, message, null, null
                            )
                            Log.d(TAG, "SMS sent immediately to ${savedRenter.phoneNumber}")
                            // Помечаем что SMS отправлено
                            repository.update(
                                savedRenter.copy(isOverdueSmsSent = true)
                            )
                        } else {
                            Log.w(TAG, "SmsManager is null — no SIM card")
                        }
                    } else {
                        Log.w(TAG, "SEND_SMS permission not granted — scheduling SmsWorker")
                        // Если нет разрешения, пробуем через Worker
                        val smsWork = OneTimeWorkRequestBuilder<SmsWorker>().build()
                        WorkManager.getInstance(context).enqueue(smsWork)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SMS SecurityException", e)
                } catch (e: Exception) {
                    Log.e(TAG, "SMS failed", e)
                }

                // 3) Также сохраняем уведомление в историю уведомлений на сервере
                try {
                    val notifEntry = com.example.data.NotificationHistoryEntity(
                        timestamp = now,
                        renterId = savedRenter.id,
                        title = "To'lov muddati yetdi",
                        message = "Mijoz ${savedRenter.name} (${savedRenter.phoneNumber}) bugun to'lov qilishi kerak"
                    )
                    val db = AppDatabase.getDatabase(context)
                    db.notificationHistoryDao().insert(notifEntry)
                    sync.pushNotification(notifEntry)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save notification history", e)
                }

                // 4) Регистрируем периодические проверки (для будущих напоминаний)
                val wm = WorkManager.getInstance(getApplication())
                val paymentCheckRequest =
                    androidx.work.PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(
                    "PaymentCheckWork",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    paymentCheckRequest
                )
            }
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
