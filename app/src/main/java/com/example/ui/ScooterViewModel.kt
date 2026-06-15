package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Scooter
import com.example.data.ScooterRepository
import com.example.data.remote.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScooterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ScooterRepository
    private val sync: SyncManager
    val scootersList: StateFlow<List<Scooter>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ScooterRepository(database.scooterDao())
        sync = SyncManager(application)
        scootersList = repository.allScooters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addScooter(name: String, documentedNumber: String?) {
        viewModelScope.launch {
            // СНАЧАЛА сохраняем локально — скутер появляется мгновенно
            val provisional = Scooter(name = name, documentedNumber = documentedNumber)
            val localId = repository.insert(provisional).toInt()

            // ПОТОМ шлём на API — получаем server id
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val serverId = sync.pushScooter(provisional)
                    if (serverId != null && serverId != localId) {
                        val db = AppDatabase.getDatabase(getApplication<Application>())
                        db.scooterDao().deleteScooter(Scooter(id = localId, name = name, documentedNumber = documentedNumber))
                        db.scooterDao().insertScooter(Scooter(id = serverId, name = name, documentedNumber = documentedNumber))
                        Log.d("ScooterViewModel", "Scooter synced: local #$localId → server #$serverId")
                    }
                } catch (e: Exception) {
                    Log.w("ScooterViewModel", "pushScooter failed, stays local #$localId", e)
                }
            }
        }
    }

    fun updateScooter(scooter: Scooter) {
        viewModelScope.launch {
            repository.update(scooter)
            sync.pushScooterUpdate(scooter)
        }
    }

    fun deleteScooter(scooter: Scooter) {
        viewModelScope.launch {
            repository.delete(scooter)
            sync.pushScooterDelete(scooter.id)
        }
    }
}
