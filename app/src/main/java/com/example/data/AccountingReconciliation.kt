package com.example.data

/** Pure, deterministic reconciliation calculations used before any repair. */
data class ReconciliationIssue(
    val scope: String,
    val id: String,
    val expectedMinor: Long,
    val actualMinor: Long,
    val message: String
)

object AccountingReconciliation {
    /** Rebuilds business-card balances exclusively from immutable journal rows. */
    fun expectedCardBalances(operations: List<BusinessOperation>): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        fun add(cardId: Int?, delta: Long) {
            if (cardId != null && cardId != CardTransaction.EXTERNAL_SOURCE_ID) {
                result[cardId] = (result[cardId] ?: 0L) + delta
            }
        }
        operations.filter { it.status == BusinessOperation.STATUS_ACTIVE }.forEach { op ->
            when (op.direction) {
                BusinessOperation.DIRECTION_INCOME -> add(op.toCardId, op.amountMinor)
                BusinessOperation.DIRECTION_EXPENSE -> add(op.fromCardId, -op.amountMinor)
                BusinessOperation.DIRECTION_TRANSFER -> {
                    add(op.fromCardId, -op.amountMinor)
                    add(op.toCardId, op.amountMinor)
                }
                // A receivable changes debt, never cash.
                BusinessOperation.DIRECTION_LIABILITY -> Unit
            }
        }
        return result
    }

    /**
     * Renter balance = unapplied advance − active/open receivables. Scheduled
     * periods do not become debt until PaymentCheckWorker activates them.
     * 
     * Non-billable periods (REPAIR_BREAK, SUSPENDED_REPAIR, CANCELLED) are
     * excluded from debt calculation as they have zero or no charge.
     */
    fun expectedRenterBalances(
        periods: List<RentPeriod>,
        paymentOperations: List<BusinessOperation>,
        allocations: List<PaymentAllocationEntity>
    ): Map<Int, Long> {
        val allocatedByOperation = allocations.groupBy { it.operationId }.mapValues { (_, value) -> value.sumOf { it.amountMinor } }
        val advanceByRenter = mutableMapOf<Int, Long>()
        paymentOperations
            .filter { it.status == BusinessOperation.STATUS_ACTIVE && it.direction == BusinessOperation.DIRECTION_INCOME }
            .forEach { operation ->
                val renterId = operation.renterId ?: return@forEach
                val unapplied = (operation.amountMinor - (allocatedByOperation[operation.id] ?: 0L)).coerceAtLeast(0)
                advanceByRenter[renterId] = (advanceByRenter[renterId] ?: 0L) + unapplied
            }
        
        // Statuses that contribute to debt (active receivables)
        val debtStatuses = setOf(
            RentPeriod.STATUS_ACTIVE, 
            RentPeriod.STATUS_PARTIALLY_PAID,
            RentPeriod.STATUS_OVERDUE, 
            RentPeriod.STATUS_CLOSED_WITH_DEBT
        )
        
        return periods.groupBy { it.renterId }.mapValues { (renterId, renterPeriods) ->
            val debt = renterPeriods
                .filter { period -> 
                    period.status in debtStatuses && !period.isNonBillable
                }
                .sumOf { it.outstandingMinor }
            (advanceByRenter[renterId] ?: 0L) - debt
        } + advanceByRenter.filterKeys { it !in periods.map { p -> p.renterId }.toSet() }
    }
}

/** Read-only verifier. Repairs must be an explicit, separately audited action. */
class AccountingIntegrityService(private val db: AppDatabase) {
    suspend fun inspect(): List<ReconciliationIssue> {
        val operations = db.businessOperationDao().getAllOnce()
        val periods = db.rentPeriodDao().getAllOnce()
        val allocations = db.paymentAllocationDao().getAllOnce()
        val cards = db.virtualCardDao().getAllCardsOnce()
        val renters = db.renterDao().getAllRentersOnce()
        val issues = mutableListOf<ReconciliationIssue>()
        val expectedCards = AccountingReconciliation.expectedCardBalances(operations)
        cards.filterNot { it.isExternal }.forEach { card ->
            val expected = expectedCards[card.id] ?: 0L
            val actual = BusinessOperation.toMinor(card.balance)
            if (expected != actual) issues += ReconciliationIssue(
                "CARD", card.id.toString(), expected, actual,
                "Card balance differs from immutable ledger"
            )
        }
        val expectedRenters = AccountingReconciliation.expectedRenterBalances(periods, operations, allocations)
        renters.forEach { renter ->
            val expected = expectedRenters[renter.id] ?: 0L
            val actual = BusinessOperation.toMinor(renter.balance)
            if (expected != actual) issues += ReconciliationIssue(
                "RENTER", renter.id.toString(), expected, actual,
                "Renter balance differs from period receivables and payments"
            )
        }
        return issues
    }
}
