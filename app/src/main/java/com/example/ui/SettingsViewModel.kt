package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.SettingsRepository
import com.example.worker.SmsWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    private val _smsTemplate = MutableStateFlow(repository.smsTemplate)
    val smsTemplate: StateFlow<String> = _smsTemplate.asStateFlow()

    /** Daily rental price - the single source of truth */
    private val _dailyPrice = MutableStateFlow(
        if (repository.dailyPrice > 0) repository.dailyPrice else SettingsRepository.DEFAULT_DAILY_PRICE
    )
    val dailyPrice: StateFlow<Double> = _dailyPrice.asStateFlow()

    /** Computed weekly price (daily × 7) - for backward compatibility */
    val weeklyPrice: StateFlow<Double> = MutableStateFlow(repository.weeklyPrice).asStateFlow()
    
    /** Computed monthly price (daily × 30) - for backward compatibility */
    val monthlyPrice: StateFlow<Double> = MutableStateFlow(repository.monthlyPrice).asStateFlow()

    /** SMS avto-yuborish rejimi: true = avto, false = faqat qo'llanma. */
    private val _smsAutoSendEnabled = MutableStateFlow(repository.smsAutoSendEnabled)
    val smsAutoSendEnabled: StateFlow<Boolean> = _smsAutoSendEnabled.asStateFlow()

    fun updateTemplate(newTemplate: String) {
        repository.smsTemplate = newTemplate
        _smsTemplate.value = newTemplate
    }

    /**
     * Update the daily rental price.
     * Weekly and monthly prices are computed automatically.
     */
    fun updateDailyPrice(daily: Double) {
        val effectiveDaily = if (daily > 0) daily else SettingsRepository.DEFAULT_DAILY_PRICE
        repository.dailyPrice = effectiveDaily
        _dailyPrice.value = effectiveDaily
    }

    /**
     * Legacy method for backward compatibility.
     * Converts weekly price to daily and stores it.
     */
    @Deprecated("Use updateDailyPrice instead")
    fun updatePrices(weekly: Double, monthly: Double) {
        val effectiveWeekly = if (weekly > 0) weekly else SettingsRepository.DEFAULT_DAILY_PRICE * 7
        // Convert weekly to daily
        val daily = effectiveWeekly / 7
        updateDailyPrice(daily)
    }

    /**
     * SMS avto-yuborish rejimini almashtirish.
     */
    fun updateSmsAutoSend(enabled: Boolean) {
        repository.smsAutoSendEnabled = enabled
        _smsAutoSendEnabled.value = enabled
        try {
            val wm = WorkManager.getInstance(getApplication())
            if (enabled) {
                val req = PeriodicWorkRequestBuilder<SmsWorker>(4, TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(
                    "OverdueSmsWork",
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
                Log.d(TAG, "OverdueSmsWork re-scheduled (auto mode ON)")
            } else {
                wm.cancelUniqueWork("OverdueSmsWork")
                Log.d(TAG, "OverdueSmsWork cancelled (manual mode OFF)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update OverdueSmsWork schedule", e)
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
