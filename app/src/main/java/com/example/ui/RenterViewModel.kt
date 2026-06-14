package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
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
     * Создать арендатора. Если на момент создания срок аренды уже истёк,
     * долг автоматически = недельный тариф и сразу же уходит уведомление
     * (без ожидания 01:00 следующего дня).
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
                    scooterName = scooterName,
                    // Помечаем сразу, чтобы периодический worker не
                    // дублировал уведомление на 01:00 для уже просроченных
                    isOverdueSmsSent = isOverdueAtCreation
                )
            )

            if (isOverdueAtCreation) {
                // Моментальная отправка (без ожидания 01:00) — по запросу
                // пользователя: «если клиент создаётся уже с просроченной
                // датой, отправка сообщения в момент создания».
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

    /**
     * «Оплатил» через приложение (форма редактирования, если кнопка
     * когда-либо вернётся). Скутер НЕ возвращается — только долг
     * уменьшается на недельный тариф.
     */
    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            repository.update(
                renter.copy(
                    debtAmount = maxOf(0.0, renter.debtAmount - weeklyPrice),
                    lastPaymentTimestamp = System.currentTimeMillis(),
                    isOverdueSmsSent = false
                )
            )
        }
    }
}
