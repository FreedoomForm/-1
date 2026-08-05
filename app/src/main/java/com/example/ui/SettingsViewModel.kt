package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.SettingsRepository
import com.example.worker.SmsWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    /**
     * SMS-шаблон. Чтобы избежать «залипания» старого шаблона при многократном
     * открытии настроек, MutableStateFlow инициализируется значением из prefs
     * при создании ViewModel (один раз на жизненный цикл процесса). Каждый
     * вызов [updateTemplate] пишет в prefs И обновляет flow — все подписчики
     * (включая повторно открытую SettingsScreen) увидят новое значение.
     */
    private val _smsTemplate = MutableStateFlow(repository.smsTemplate)
    val smsTemplate: StateFlow<String> = _smsTemplate.asStateFlow()

    /** Дневная цена — единый источник истины для всех расчётов. */
    private val _dailyPrice = MutableStateFlow(
        if (repository.dailyPrice > 0) repository.dailyPrice else SettingsRepository.DEFAULT_DAILY_PRICE
    )
    val dailyPrice: StateFlow<Double> = _dailyPrice.asStateFlow()

    /** Недельная цена = dailyPrice × 7 (производная, для совместимости). */
    val weeklyPrice: StateFlow<Double> = _dailyPrice
        .map { it * 7.0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _dailyPrice.value * 7.0)

    /** Месячная цена = dailyPrice × 30 (производная, для совместимости). */
    val monthlyPrice: StateFlow<Double> = _dailyPrice
        .map { it * 30.0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _dailyPrice.value * 30.0)

    /** SMS avto-yuborish rejimi: true = avto, false = faqat qo'llanma. */
    private val _smsAutoSendEnabled = MutableStateFlow(repository.smsAutoSendEnabled)
    val smsAutoSendEnabled: StateFlow<Boolean> = _smsAutoSendEnabled.asStateFlow()

    fun updateTemplate(newTemplate: String) {
        // Если значение не изменилось — не пишем в prefs и не обновляем flow.
        // Это предотвращает лишние LaunchedEffect-срабатывания в SettingsScreen.
        if (newTemplate == _smsTemplate.value) return
        Log.d(TAG, "Updating SMS template: '${_smsTemplate.value.take(40)}...' -> '${newTemplate.take(40)}...'")
        repository.smsTemplate = newTemplate
        _smsTemplate.value = newTemplate
    }

    fun updateDailyPrice(daily: Double) {
        val effective = if (daily > 0) daily else SettingsRepository.DEFAULT_DAILY_PRICE
        Log.d(TAG, "Updating daily price: ${_dailyPrice.value} -> $effective")
        repository.dailyPrice = effective
        _dailyPrice.value = effective
        // Также сохраняем weekly/monthly для обратной совместимости.
        repository.weeklyPrice = effective * 7.0
        repository.monthlyPrice = effective * 30.0
    }

    /** Совместимый со старой сигнатурой метод — пересылает в updateDailyPrice. */
    fun updatePrices(weekly: Double, monthly: Double) {
        // Если хотя бы weekly задан (>0) — берём daily = weekly/7.
        // Если только monthly задан — берём daily = monthly/30.
        // Если оба 0 — updateDailyPrice подставит DEFAULT_DAILY_PRICE.
        val daily = when {
            weekly > 0 -> weekly / 7.0
            monthly > 0 -> monthly / 30.0
            else -> 0.0
        }
        updateDailyPrice(daily)
    }

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
