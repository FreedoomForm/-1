package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Renter
import com.example.data.RenterRepository
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

    fun addRenter(name: String, phone: String, debt: Double, duration: Int, scooterId: Int?, scooterName: String?) {
        viewModelScope.launch {
            repository.insert(Renter(
                name = name,
                phoneNumber = phone,
                debtAmount = debt,
                rentDurationDays = duration,
                scooterId = scooterId,
                scooterName = scooterName
            ))
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
}
