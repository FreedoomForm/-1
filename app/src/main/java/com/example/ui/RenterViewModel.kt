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
import com.example.worker.SmsWorker
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
     * Создать арендатора.
     *  • Если аренда уже просрочена на момент создания:
     *      — долг = недельный тариф;
     *      — сразу же уходит локальное уведомление;
     *      — сразу же пытаемся отправить SMS (через SmsWorker).
     *  • Если аренда ещё активна — обычное сохранение без уведомлений.
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
                    // Не помечаем заранее — пусть SmsWorker сам поставит true,
                    // когда успешно отправит SMS (или PaymentCheckWorker,
                    // когда пришлёт уведомление).
                    isOverdueSmsSent = false
                )
            )

            if (isOverdueAtCreation) {
                val wm = WorkManager.getInstance(getApplication())

                // Локальное уведомление прямо сейчас
                val notifWork = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
                    .setInputData(
                        workDataOf(
                            PaymentCheckWorker.KEY_RENTER_ID to newId.toInt(),
                            PaymentCheckWorker.KEY_ONE_TIME to true
                        )
                    )
                    .build()
                wm.enqueue(notifWork)

                // SMS прямо сейчас (без ожидания периодического 4-часового тика)
                val smsWork = OneTimeWorkRequestBuilder<SmsWorker>().build()
                wm.enqueueUniqueWork(
                    "immediate_sms_${newId}",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    smsWork
                )
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
