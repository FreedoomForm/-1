package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.BusinessOperation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Read model for the immutable universal accounting journal. */
class BusinessOperationViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)

    val operations: StateFlow<List<BusinessOperation>> =
        database.businessOperationDao().getActive().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    val rentPeriods: StateFlow<List<com.example.data.RentPeriod>> =
        database.rentPeriodDao().all().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )
}
