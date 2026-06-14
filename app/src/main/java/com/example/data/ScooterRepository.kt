package com.example.data

import kotlinx.coroutines.flow.Flow

class ScooterRepository(private val scooterDao: ScooterDao) {
    val allScooters: Flow<List<Scooter>> = scooterDao.getAllScooters()

    suspend fun insert(scooter: Scooter) = scooterDao.insertScooter(scooter)
    suspend fun update(scooter: Scooter) = scooterDao.updateScooter(scooter)
    suspend fun delete(scooter: Scooter) = scooterDao.deleteScooter(scooter)
}
