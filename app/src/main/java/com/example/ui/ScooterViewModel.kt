package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Scooter
import com.example.data.ScooterRepository
import com.example.data.remote.SyncManager
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
            val serverId = sync.pushScooter(Scooter(name = name, documentedNumber = documentedNumber))
            val id = serverId ?: repository.insert(Scooter(name = name, documentedNumber = documentedNumber)).toInt()
            // Если API дал другой id, обновим локальную запись.
            if (serverId != null) {
                // (insert с явным id, чтобы Room-ссылки (renters.scooterId) совпадали)
                repository.update(Scooter(id = id, name = name, documentedNumber = documentedNumber))
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
