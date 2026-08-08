package com.example.data

import kotlinx.coroutines.flow.Flow

class ContractHistoryRepository(private val dao: ContractHistoryDao) {
    val allHistory: Flow<List<ContractHistoryEntry>> = dao.getAll()
    val liveContracts: Flow<List<ContractHistoryEntry>> = dao.getLiveContracts()
    val trashedContracts: Flow<List<ContractHistoryEntry>> = dao.getTrashedContracts()

    fun forRenter(renterId: Int): Flow<List<ContractHistoryEntry>> = dao.getForRenterFlow(renterId)
    fun forScooter(scooterName: String): Flow<List<ContractHistoryEntry>> = dao.getForScooterFlow(scooterName)

    /** Только контракты (CREATED + AUTO_RENEW) — для экрана истории контрактов. */
    fun contractsForRenter(renterId: Int): Flow<List<ContractHistoryEntry>> =
        dao.getContractsForRenterFlow(renterId)

    suspend fun getById(id: Int): ContractHistoryEntry? = dao.getById(id)
    suspend fun getForRenterOnce(renterId: Int): List<ContractHistoryEntry> = dao.getForRenter(renterId)
    suspend fun contractsForRenterOnce(renterId: Int): List<ContractHistoryEntry> = dao.getContractsForRenterOnce(renterId)

    /**
     * Возвращает ВСЕ записи истории контрактов (включая STOP/RESUME маркеры и
     * мягко удалённые). Используется в [RenterViewModel.restoreRenterFromArchive]
     * для вычисления «какие арендаторы сейчас в архиве» при пересчёте
     * занятости скутеров (нужно знать, чьи скутеры считать свободными).
     */
    suspend fun getAllOnce(): List<ContractHistoryEntry> = dao.getAllOnce()
    suspend fun getEarliestUnpaidContract(renterId: Int): ContractHistoryEntry? =
        dao.getEarliestUnpaidContract(renterId)
    suspend fun getUnpaidContractsForRenter(renterId: Int): List<ContractHistoryEntry> =
        dao.getUnpaidContractsForRenter(renterId)
    suspend fun getLatestPaidContract(renterId: Int): ContractHistoryEntry? =
        dao.getLatestPaidContract(renterId)
    suspend fun insert(entry: ContractHistoryEntry): Long = dao.insert(entry)
    suspend fun update(entry: ContractHistoryEntry) = dao.update(entry)
    suspend fun deleteById(id: Int) = dao.deleteById(id)
    suspend fun deleteByIds(ids: List<Int>) = dao.deleteByIds(ids)
    suspend fun deleteForRenter(renterId: Int) = dao.deleteForRenter(renterId)
    suspend fun clear() = dao.clear()

    // ── Trash-mode operations ────────────────────────────────────────────
    suspend fun moveToTrash(id: Int) = dao.moveToTrash(id)
    suspend fun moveToTrashBatch(ids: List<Int>) = dao.moveToTrashBatch(ids)
    suspend fun moveToTrashForRenter(renterId: Int) = dao.moveToTrashForRenter(renterId)
    suspend fun restoreFromTrash(id: Int) = dao.restoreFromTrash(id)
    suspend fun restoreFromTrashBatch(ids: List<Int>) = dao.restoreFromTrashBatch(ids)
    suspend fun restoreFromTrashForRenter(renterId: Int) = dao.restoreFromTrashForRenter(renterId)
}
