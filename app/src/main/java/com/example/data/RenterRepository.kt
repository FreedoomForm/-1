package com.example.data

import kotlinx.coroutines.flow.Flow

class RenterRepository(private val renterDao: RenterDao) {
    val allRenters: Flow<List<Renter>> = renterDao.getAllRenters()
    /** Только активные арендаторы (isDeleted = 0) — для обычного режима. */
    val liveRenters: Flow<List<Renter>> = renterDao.getLiveRenters()
    /** Только удалённые в корзину (isDeleted = 1) — для trash mode. */
    val trashedRenters: Flow<List<Renter>> = renterDao.getTrashedRenters()

    suspend fun getActiveRenters(): List<Renter> = renterDao.getActiveRenters()
    suspend fun getById(id: Int): Renter? = renterDao.getRenterById(id)
    suspend fun insert(renter: Renter): Long = renterDao.insertRenter(renter)
    suspend fun update(renter: Renter) = renterDao.updateRenter(renter)
    suspend fun delete(id: Int) = renterDao.deleteRenter(id)

    /** Soft-delete: перемещает арендатора в корзину (isDeleted=1). */
    suspend fun moveToTrash(id: Int) = renterDao.moveToTrash(id)
    /** Восстанавливает арендатора из корзины (isDeleted=0). */
    suspend fun restoreFromTrash(id: Int) = renterDao.restoreFromTrash(id)
}
