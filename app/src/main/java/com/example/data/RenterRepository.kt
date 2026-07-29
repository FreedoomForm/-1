package com.example.data

import kotlinx.coroutines.flow.Flow

class RenterRepository(
    private val renterDao: RenterDao,
    private val contractHistoryDao: ContractHistoryDao? = null
) {
    val allRenters: Flow<List<Renter>> = renterDao.getAllRenters()

    suspend fun getActiveRenters(): List<Renter> = renterDao.getActiveRenters()
    suspend fun getById(id: Int): Renter? = renterDao.getRenterById(id)
    suspend fun insert(renter: Renter): Long = renterDao.insertRenter(renter)
    suspend fun update(renter: Renter) = renterDao.updateRenter(renter)
    suspend fun delete(id: Int) = renterDao.deleteRenter(id)

    // ── Товарооборот и баланс (§5 — turnover/balance) ─────────────────────
    // Товарооборот = сумма сумм всех контрактов арендатора.
    // Баланс = paid − turnover: <0 долг, >0 аванс, =0 расчёт закрыт.
    suspend fun getTurnoverForRenter(renterId: Int): Double =
        contractHistoryDao?.getTurnoverForRenter(renterId) ?: 0.0

    suspend fun getPaidTotalForRenter(renterId: Int): Double =
        contractHistoryDao?.getPaidTotalForRenter(renterId) ?: 0.0

    suspend fun getPaymentsTotalForRenter(renterId: Int): Double =
        contractHistoryDao?.getPaymentsTotalForRenter(renterId) ?: 0.0

    /**
     * Возвращает вычисленный баланс по формуле: paid − turnover.
     * Если paid = turnover (например, после точной оплаты контракта) — 0.
     */
    suspend fun getComputedBalance(renterId: Int): Double =
        getPaidTotalForRenter(renterId) - getTurnoverForRenter(renterId)
}
