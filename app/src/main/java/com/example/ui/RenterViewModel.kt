package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.worker.PaymentCheckWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    val rentersList: StateFlow<List<Renter>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RenterRepository(database.renterDao())

        rentersList = repository.allRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    /**
     * Создать арендатора. Если на момент создания срок аренды уже истёк
     * (например, сегодня 11-е, дата выдачи 3-е, длительность 7 дней →
     *  окончание было 10-го), то:
     *  • долг автоматически выставляется равным недельному тарифу;
     *  • сразу же запускается одноразовое уведомление.
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

            val newId = repository.insert(
                Renter(
                    name = name,
                    phoneNumber = phone,
                    debtAmount = finalDebt,
                    rentDurationDays = duration,
                    rentStartDateTimestamp = startTimestamp,
                    scooterId = scooterId,
                    scooterName = scooterName
                )
            )

            if (isOverdueAtCreation) {
                // Сразу же шлём уведомление по этому арендатору
                val immediateWork = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
                    .setInputData(
                        workDataOf(
                            PaymentCheckWorker.KEY_RENTER_ID to newId.toInt(),
                            PaymentCheckWorker.KEY_ONE_TIME to true
                        )
                    )
                    .build()
                WorkManager.getInstance(getApplication()).enqueue(immediateWork)
            }
        }
    }

    fun markReturned(renter: Renter) {
        viewModelScope.launch {
            repository.update(renter.copy(isReturned = true))
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

    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch {
            repository.update(
                renter.copy(
                    debtAmount = 0.0,
                    lastPaymentTimestamp = System.currentTimeMillis(),
                    isOverdueSmsSent = false
                )
            )
        }
    }

    fun scheduleOneHourReminder(context: Context, renter: Renter) {
        val oneTimeWork = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)
            .setInputData(
                workDataOf(
                    PaymentCheckWorker.KEY_RENTER_ID to renter.id,
                    PaymentCheckWorker.KEY_ONE_TIME to true
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder_${renter.id}",
            androidx.work.ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
    }
}
