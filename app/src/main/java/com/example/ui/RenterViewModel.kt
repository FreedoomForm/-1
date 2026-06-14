package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
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
import java.util.concurrent.TimeUnit

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

    fun addRenter(
        name: String,
        phone: String,
        debt: Double,
        duration: Int,
        startTimestamp: Long,
        scooterId: Int?,
        scooterName: String?
    ) {
        viewModelScope.launch {
            repository.insert(
                Renter(
                    name = name,
                    phoneNumber = phone,
                    debtAmount = debt,
                    rentDurationDays = duration,
                    rentStartDateTimestamp = startTimestamp,
                    scooterId = scooterId,
                    scooterName = scooterName
                )
            )
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

    /** "Принял оплату": обнуляем долг и фиксируем время платежа. */
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

    /** "Уведомить через час": одноразовый worker через 1 час для конкретного арендатора. */
    fun scheduleOneHourReminder(context: Context, renter: Renter) {
        val oneTimeWork = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .setInputData(
                workDataOf(
                    PaymentCheckWorker.KEY_RENTER_ID to renter.id,
                    PaymentCheckWorker.KEY_ONE_TIME to true
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder_${renter.id}",
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
    }
}
