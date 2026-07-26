package com.example.data

import androidx.room.withTransaction

/**
 * Rebuilds native accounting projections after importing a legacy XLSX backup.
 * The import format predates the universal journal, therefore rebuilding is
 * safer than leaving stale journal rows from a previous database.
 */
object LegacyProjectionRebuilder {
    suspend fun rebuild(db: AppDatabase) = db.withTransaction {
        db.paymentAllocationDao().clear()
        db.rentPeriodDao().clear()
        db.businessOperationDao().clear()

        val now = System.currentTimeMillis()
        val renters = db.renterDao().getAllRentersOnce().associateBy { it.id }
        db.contractHistoryDao().getAllOnce()
            .filter { it.type == ContractHistoryEntry.TYPE_CREATED || it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
            .forEach { contract ->
                val start = contract.weekStart ?: contract.timestamp
                val end = contract.weekEnd ?: start + 7L * 24 * 60 * 60 * 1000
                val amountMinor = BusinessOperation.toMinor(kotlin.math.abs(contract.amount))
                val status = when {
                    contract.isPaid -> RentPeriod.STATUS_PAID
                    start > now -> RentPeriod.STATUS_SCHEDULED
                    end <= now -> RentPeriod.STATUS_OVERDUE
                    else -> RentPeriod.STATUS_ACTIVE
                }
                db.rentPeriodDao().insert(RentPeriod(
                    contractHistoryId = contract.id,
                    renterId = contract.renterId,
                    scooterId = renters[contract.renterId]?.scooterId,
                    startsAt = start,
                    endsAt = end,
                    chargeMinor = amountMinor,
                    paidMinor = if (contract.isPaid) amountMinor else 0,
                    status = status,
                    createdAt = contract.timestamp,
                    updatedAt = contract.timestamp
                ))
            }

        db.cardTransactionDao().getRecentTransactions(Int.MAX_VALUE).forEach { tx ->
            if (tx.amount == 0.0) return@forEach
            val type = if (tx.type == CardTransaction.TYPE_CONTRACT_INCOME)
                BusinessOperation.TYPE_RENT_PAYMENT else BusinessOperation.TYPE_TRANSFER
            val direction = if (tx.type == CardTransaction.TYPE_CONTRACT_INCOME)
                BusinessOperation.DIRECTION_INCOME else BusinessOperation.DIRECTION_TRANSFER
            db.businessOperationDao().insert(BusinessOperation(
                occurredAt = tx.timestamp,
                type = type,
                direction = direction,
                amountMinor = BusinessOperation.toMinor(kotlin.math.abs(tx.amount)),
                contractId = tx.contractId,
                fromCardId = tx.fromCardId,
                toCardId = tx.toCardId,
                cardTransactionId = tx.id,
                note = tx.note,
                createdAt = tx.timestamp
            ))
        }
    }
}
