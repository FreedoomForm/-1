package com.example.data

import kotlinx.coroutines.flow.Flow

class ScooterRepository(private val scooterDao: ScooterDao) {
    val allScooters: Flow<List<Scooter>> = scooterDao.getAllScooters()
    val liveScooters: Flow<List<Scooter>> = scooterDao.getLiveScooters()
    val trashedScooters: Flow<List<Scooter>> = scooterDao.getTrashedScooters()

    /** Возвращает id свежесозданного скутера. */
    suspend fun insert(scooter: Scooter): Long = scooterDao.insertScooter(scooter)
    suspend fun update(scooter: Scooter) = scooterDao.updateScooter(scooter)
    suspend fun delete(scooter: Scooter) = scooterDao.deleteScooter(scooter)

    /** Hard-delete по id (используется при окончательном удалении из корзины). */
    suspend fun deleteById(id: Int) = scooterDao.deleteById(id)

    /** Soft-delete: перемещает скутер в корзину. */
    suspend fun moveToTrash(id: Int) = scooterDao.moveToTrash(id)
    /** Восстанавливает скутер из корзины. */
    suspend fun restoreFromTrash(id: Int) = scooterDao.restoreFromTrash(id)
}
