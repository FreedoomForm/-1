package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import com.example.data.remote.SyncManager
import com.example.data.remote.toCreateDto
import com.example.data.remote.toEntity
import com.example.data.remote.toUpdateDto
import com.example.worker.PaymentCheckWorker
import com.example.worker.SmsWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    private val historyRepository: com.example.data.ContractHistoryRepository
    private val sync: SyncManager
    val rentersList: StateFlow<List<Renter>>

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
     * Создать арендатора.
     * Сначала шлём на API → получаем server id → пишем в Room с этим id.
     * Если API недоступен — пишем только локально (id=0, автогенерация Room).
     */
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
            val finalDebt = if (isOverdueAtCreation) weeklyPrice else debt

            // Пробуем сначала API — получим server-assigned id.
            val serverId = if (isOverdueAtCreation || debt > 0 || true) {
                val provisional = Renter(
                    name = name,
                    phoneNumber = phone,
                    debtAmount = finalDebt,
                    rentDurationDays = duration,
                    rentStartDateTimestamp = startTimestamp,
                    scooterId = scooterId,
                    scooterName = scooterName,
                    balance = 0.0
                )
                sync.pushRenter(provisional)
            } else null

            val newId = serverId ?: repository.insert(
                Renter(
                    name = name,
                    phoneNumber = phone,
                    debtAmount = finalDebt,
                    rentDurationDays = duration,
                    rentStartDateTimestamp = startTimestamp,
                    scooterId = scooterId,
                    scooterName = scooterName,
                    balance = 0.0
                )
            ).toInt()

            historyRepository.insert(
                ContractHistoryEntry(
                    renterId = newId,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_CREATED,
                    amount = weeklyPrice,
                    notes = if (isOverdueAtCreation) "Kechikkan holda yaratildi" else "Yaratildi"
                )
            )
            sync.pushContractHistory(
                ContractHistoryEntry(
                    renterId = newId,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_CREATED,
                    amount = weeklyPrice,
                    notes = if (isOverdueAtCreation) "Kechikkan holda yaratildi" else "Yaratildi"
                )
            )

            if (isOverdueAtCreation) {
                val wm = WorkManager.getInstance(getApplication())
                val notifWork = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
                    .setInputData(
                        workDataOf(
                            PaymentCheckWorker.KEY_RENTER_ID to newId,
                            PaymentCheckWorker.KEY_ONE_TIME to true
                        )
                    )
                    .build()
                wm.enqueue(notifWork)

                val smsWork = OneTimeWorkRequestBuilder<SmsWorker>().build()
                wm.enqueueUniqueWork(
                    "immediate_sms_$newId",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    smsWork
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
        val newDebt = maxOf(0.0, renter.debtAmount - weeklyPrice)
        val updated = renter.copy(
            debtAmount = newDebt,
            balance = renter.balance + weeklyPrice,
            lastPaymentTimestamp = System.currentTimeMillis(),
            isOverdueSmsSent = false
        )
        repository.update(updated)
        sync.pushRenterUpdate(updated)

        val entry = ContractHistoryEntry(
            renterId = renter.id,
            timestamp = System.currentTimeMillis(),
            type = ContractHistoryEntry.TYPE_PAYMENT,
            amount = weeklyPrice,
            notes = notes
        )
        historyRepository.insert(entry)
        sync.pushContractHistory(entry)
    }

    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double) {
        val updated = renter.copy(
            debtAmount = maxOf(0.0, renter.debtAmount - weeklyPrice),
            balance = renter.balance + weeklyPrice,
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
            amount = weeklyPrice,
            notes = "Kontrakt tugatildi"
        )
        historyRepository.insert(entry)
        sync.pushContractHistory(entry)
    }
}
