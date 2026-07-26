package com.example.ui

import android.app.Application
import android.util.Log
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
    private val scooterDao: com.example.data.ScooterDao
    val scootersList: StateFlow<List<Scooter>>

    init {
        val database = AppDatabase.getDatabase(application)
        scooterDao = database.scooterDao()
        repository = ScooterRepository(scooterDao)
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
            if (scooterDao.duplicateIdentifierCount(vinNumber.trim(), engineNumber.trim(), scooterSerialNumber.trim(), 0) > 0) {
                Log.w(TAG, "Scooter creation blocked: duplicate VIN/engine/serial")
                return@launch
            }
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
            if (scooterDao.duplicateIdentifierCount(scooter.vinNumber.trim(), scooter.engineNumber.trim(), scooter.scooterSerialNumber.trim(), scooter.id) > 0) {
                Log.w(TAG, "Scooter update blocked: duplicate VIN/engine/serial")
                return@launch
            }
            repository.update(scooter)
        }
    }

    companion object { private const val TAG = "ScooterViewModel" }

    fun deleteScooter(scooter: Scooter) {
        viewModelScope.launch {
            repository.delete(scooter)
        }
    }
}
