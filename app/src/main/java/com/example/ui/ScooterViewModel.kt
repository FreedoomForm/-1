package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Scooter
import com.example.data.ScooterRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScooterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ScooterRepository
    val scootersList: StateFlow<List<Scooter>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ScooterRepository(database.scooterDao())
        scootersList = repository.allScooters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addScooter(
        name: String,
        documentedNumber: String?,
        vinNumber: String = "",
        engineNumber: String = "",
        scooterSerialNumber: String = "",
        batteryId1: String = "",
        batteryId2: String = "",
        additionalInfo: String = ""
    ) {
        viewModelScope.launch {
            val scooter = Scooter(
                name = name,
                documentedNumber = documentedNumber,
                vinNumber = vinNumber,
                engineNumber = engineNumber,
                scooterSerialNumber = scooterSerialNumber,
                batteryId1 = batteryId1,
                batteryId2 = batteryId2,
                additionalInfo = additionalInfo
            )
            repository.insert(scooter)
        }
    }

    fun updateScooter(scooter: Scooter) {
        viewModelScope.launch {
            repository.update(scooter)
        }
    }

    fun deleteScooter(scooter: Scooter) {
        viewModelScope.launch {
            repository.delete(scooter)
        }
    }
}
