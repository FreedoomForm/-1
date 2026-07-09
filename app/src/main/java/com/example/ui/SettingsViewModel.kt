package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
}
