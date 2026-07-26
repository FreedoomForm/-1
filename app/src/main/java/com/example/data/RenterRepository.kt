package com.example.data

import kotlinx.coroutines.flow.Flow

class RenterRepository(private val renterDao: RenterDao) {
    val allRenters: Flow<List<Renter>> = renterDao.getAllRenters()

    suspend fun getActiveRenters(): List<Renter> = renterDao.getActiveRenters()
    suspend fun getById(id: Int): Renter? = renterDao.getRenterById(id)
    suspend fun insert(renter: Renter): Long = renterDao.insertRenter(renter)
    suspend fun update(renter: Renter) = renterDao.updateRenter(renter)
    suspend fun delete(id: Int) = renterDao.deleteRenter(id)
}
