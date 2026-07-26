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

    private val _weeklyPrice = MutableStateFlow(
        if (repository.weeklyPrice > 0) repository.weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
    )
    val weeklyPrice: StateFlow<Double> = _weeklyPrice.asStateFlow()

    private val _monthlyPrice = MutableStateFlow(
        if (repository.monthlyPrice > 0) repository.monthlyPrice else SettingsRepository.DEFAULT_MONTHLY_PRICE
    )
    val monthlyPrice: StateFlow<Double> = _monthlyPrice.asStateFlow()

    /** SMS avto-yuborish rejimi: true = avto, false = faqat qo'llanma. */
    private val _smsAutoSendEnabled = MutableStateFlow(repository.smsAutoSendEnabled)
    val smsAutoSendEnabled: StateFlow<Boolean> = _smsAutoSendEnabled.asStateFlow()

    fun updateTemplate(newTemplate: String) {
        repository.smsTemplate = newTemplate
        _smsTemplate.value = newTemplate
    }

    fun updatePrices(weekly: Double, monthly: Double) {
        val effectiveWeekly = if (weekly > 0) weekly else SettingsRepository.DEFAULT_WEEKLY_PRICE
        val effectiveMonthly = if (monthly > 0) monthly else SettingsRepository.DEFAULT_MONTHLY_PRICE
        repository.weeklyPrice = effectiveWeekly
        repository.monthlyPrice = effectiveMonthly
        _weeklyPrice.value = effectiveWeekly
        _monthlyPrice.value = effectiveMonthly
    }

    /**
     * SMS avto-yuborish rejimini almashtirish.
     *
     * Rejim o'zgarganda nafaqat SharedPreferences yangilanadi, balki
     * WorkManager'dagi «OverdueSmsWork» ham boshqariladi:
     *  • enabled = true  → ish qayta rejalashtiriladi (4 soatda bir).
     *  • enabled = false → ish BEKOR QILINADI. SmsWorker.doWork() ichida
     *    ham tekshiruv bor, lekin ish umuman ishlamasligi aniqroq —
     *    hech qanday SMS yuborilmaydi.
     */
    fun updateSmsAutoSend(enabled: Boolean) {
        repository.smsAutoSendEnabled = enabled
        _smsAutoSendEnabled.value = enabled
        try {
            val wm = WorkManager.getInstance(getApplication())
            if (enabled) {
                // Re-jadval: 4 soatda bir. CANCEL_AND_REPLACE emas, KEEP —
                // agar allaqachon rejalashtirilgan bo'lsa, o'z holida qoldiradi.
                val req = PeriodicWorkRequestBuilder<SmsWorker>(4, TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(
                    "OverdueSmsWork",
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
                Log.d(TAG, "OverdueSmsWork re-scheduled (auto mode ON)")
            } else {
                // Manual rejim — ishni butunlay bekor qilamiz.
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
