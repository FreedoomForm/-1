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
        additionalInfo: String = "",
        nextServiceAt: Long? = null
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
                additionalInfo = additionalInfo,
                nextServiceAt = nextServiceAt
            )
            repository.insert(scooter)
            // §9.0: таймкод критического действия — SCOOTER_CREATE.
            try {
                val inserted = scooterList.value.firstOrNull { it.name == scooter.name }
                com.example.data.TimelineService(AppDatabase.getDatabase(getApplication()))
                    .recordCriticalAction(
                        actionType = "SCOOTER_CREATE",
                        screen = "SCOOTERS",
                        title = "Yangi skuter: ${scooter.name}",
                        entityType = "SCOOTER",
                        entityId = (inserted?.id ?: 0).toString(),
                        payloadJson = "{\"name\":\"${scooter.name}\",\"vin\":\"${scooter.vinNumber}\"}"
                    )
            } catch (_: Exception) {}
        }
    }

    fun updateScooter(scooter: Scooter) {
        viewModelScope.launch {
            if (scooterDao.duplicateIdentifierCount(scooter.vinNumber.trim(), scooter.engineNumber.trim(), scooter.scooterSerialNumber.trim(), scooter.id) > 0) {
                Log.w(TAG, "Scooter update blocked: duplicate VIN/engine/serial")
                return@launch
            }
            repository.update(scooter)
            // §9.0: таймкод критического действия — SCOOTER_UPDATE.
            try {
                com.example.data.TimelineService(AppDatabase.getDatabase(getApplication()))
                    .recordCriticalAction(
                        actionType = "SCOOTER_UPDATE",
                        screen = "SCOOTERS",
                        title = "Skuter yangilandi: ${scooter.name}",
                        entityType = "SCOOTER",
                        entityId = scooter.id.toString(),
                        payloadJson = "{\"name\":\"${scooter.name}\",\"status\":\"${scooter.lifecycleStatus}\"}"
                    )
            } catch (_: Exception) {}
        }
    }

    fun sendToRepair(
        scooterId: Int,
        reason: String,
        scenario: String = com.example.data.RepairOrder.SCENARIO_RENTER_REPAIR
    ) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .changeStatus(scooterId, Scooter.STATUS_REPAIR, reason, scenario) }
            catch (e: Exception) { Log.e(TAG, "Failed to pause rental for repair", e) }
        }
    }

    fun replaceScooterForRental(oldScooterId: Int, newScooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .replaceScooterForActiveRental(oldScooterId, newScooterId, reason) }
            catch (e: Exception) { Log.e(TAG, "Failed to replace scooter for rental", e) }
        }
    }

    fun resumeAfterRepair(scooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .resumeAfterRepair(scooterId, reason) }
            catch (e: Exception) { Log.e(TAG, "Failed to resume rental after repair", e) }
        }
    }

    companion object { private const val TAG = "ScooterViewModel" }

    fun deleteScooter(scooter: Scooter) {
        viewModelScope.launch {
            // §9.0: таймкод критического действия — SCOOTER_DELETE (до удаления,
            // чтобы entity id ещё был валиден).
            try {
                com.example.data.TimelineService(AppDatabase.getDatabase(getApplication()))
                    .recordCriticalAction(
                        actionType = "SCOOTER_DELETE",
                        screen = "SCOOTERS",
                        title = "Skuter o'chirildi: ${scooter.name}",
                        entityType = "SCOOTER",
                        entityId = scooter.id.toString(),
                        payloadJson = "{\"name\":\"${scooter.name}\"}"
                    )
            } catch (_: Exception) {}
            repository.delete(scooter)
        }
    }
}
