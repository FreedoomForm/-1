package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.ContractHistoryRepository
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import com.example.worker.PaymentCheckWorker
import com.example.worker.SmsWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    private val historyRepository: ContractHistoryRepository
    val rentersList: StateFlow<List<Renter>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RenterRepository(database.renterDao())
        historyRepository = ContractHistoryRepository(database.contractHistoryDao())

        rentersList = repository.allRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
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

            val finalDebt = if (isOverdueAtCreation) weeklyPrice else debt

            val newId = repository.insert(
                Renter(
                    name = name,
                    phoneNumber = phone,
                    debtAmount = finalDebt,
                    rentDurationDays = duration,
                    rentStartDateTimestamp = startTimestamp,
                    scooterId = scooterId,
                    scooterName = scooterName,
                    isOverdueSmsSent = false,
                    balance = 0.0
                )
            )

            historyRepository.insert(
                ContractHistoryEntry(
                    renterId = newId.toInt(),
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
                            PaymentCheckWorker.KEY_RENTER_ID to newId.toInt(),
                            PaymentCheckWorker.KEY_ONE_TIME to true
                        )
                    )
                    .build()
                wm.enqueue(notifWork)

                val smsWork = OneTimeWorkRequestBuilder<SmsWorker>().build()
                wm.enqueueUniqueWork(
                    "immediate_sms_${newId}",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    smsWork
                )
            }
        }
    }

    fun updateRenter(renter: Renter) {
        viewModelScope.launch {
            repository.update(renter)
        }
    }

    fun deleteRenter(id: Int) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    /** «Оплатить за неделю» для одного арендатора. */
    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch {
            applyWeeklyPayment(renter, "Bitta to'lov")
        }
    }

    /** Bulk: «Оплатить за неделю» для нескольких арендаторов сразу. */
    fun payWeeklyForRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id ->
                val renter = repository.getById(id) ?: return@forEach
                applyWeeklyPayment(renter, "Ommaviy to'lov", weeklyPrice)
            }
        }
    }

    /** Bulk: «Прекратить контракт» для нескольких арендаторов. */
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
        repository.update(
            renter.copy(
                debtAmount = newDebt,
                balance = renter.balance + weeklyPrice,
                lastPaymentTimestamp = System.currentTimeMillis(),
                isOverdueSmsSent = false
            )
        )
        historyRepository.insert(
            ContractHistoryEntry(
                renterId = renter.id,
                timestamp = System.currentTimeMillis(),
                type = ContractHistoryEntry.TYPE_PAYMENT,
                amount = weeklyPrice,
                notes = notes
            )
        )
    }

    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double) {
        repository.update(
            renter.copy(
                debtAmount = maxOf(0.0, renter.debtAmount - weeklyPrice),
                balance = renter.balance + weeklyPrice,
                isReturned = true,
                lastPaymentTimestamp = System.currentTimeMillis(),
                isOverdueSmsSent = false
            )
        )
        historyRepository.insert(
            ContractHistoryEntry(
                renterId = renter.id,
                timestamp = System.currentTimeMillis(),
                type = ContractHistoryEntry.TYPE_TERMINATED,
                amount = weeklyPrice,
                notes = "Kontrakt tugatildi"
            )
        )
    }
}
