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

    private val _weeklyPrice = MutableStateFlow(repository.weeklyPrice)
    val weeklyPrice: StateFlow<Double> = _weeklyPrice.asStateFlow()

    private val _monthlyPrice = MutableStateFlow(repository.monthlyPrice)
    val monthlyPrice: StateFlow<Double> = _monthlyPrice.asStateFlow()

    fun updateTemplate(newTemplate: String) {
        repository.smsTemplate = newTemplate
        _smsTemplate.value = newTemplate
    }

    fun updatePrices(weekly: Double, monthly: Double) {
        repository.weeklyPrice = weekly
        repository.monthlyPrice = monthly
        _weeklyPrice.value = weekly
        _monthlyPrice.value = monthly
    }
}
