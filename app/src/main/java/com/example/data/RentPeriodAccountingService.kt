package com.example.data

import androidx.room.withTransaction

/**
 * The only service intended for accepting a variable renter payment. It writes
 * the cash receipt, universal ledger, FIFO allocations, contract status and
 * legacy balance as one transaction.
 */
class RentPeriodAccountingService(private val db: AppDatabase) {
    suspend fun acceptPayment(
        renterId: Int,
        amountMinor: Long,
        note: String,
        toCardId: Int = VirtualCard.MAIN_CARD_ID,
        actor: String = "LOCAL_SYSTEM",
        occurredAt: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        require(amountMinor > 0) { "Payment must be positive" }
        require(note.isNotBlank()) { "Payment note is required" }
        val renter = db.renterDao().getRenterById(renterId)
            ?: throw IllegalArgumentException("Renter #$renterId does not exist")
        val card = db.virtualCardDao().getCardById(toCardId)
            ?: throw IllegalArgumentException("Card #$toCardId does not exist")
        require(!card.isArchived && !card.isExternal) { "Choose an active business cash account" }

        val open = db.rentPeriodDao().openForRenter(renterId)
        require(open.isNotEmpty() || !renter.isReturned) {
            "A returned renter has no outstanding receivable to pay"
        }
        val allocation = PaymentAllocationPolicy.allocateOldestFirst(
            amountMinor,
            open.map { OpenObligation(it.id, it.endsAt, it.outstandingMinor) }
        )
        val byId = open.associateBy { it.id }
        val primary = allocation.allocations.firstOrNull()?.contractId?.let { byId[it] }

        val operationId = db.businessOperationDao().insert(BusinessOperation(
            occurredAt = occurredAt,
            type = BusinessOperation.TYPE_RENT_PAYMENT,
            direction = BusinessOperation.DIRECTION_INCOME,
            amountMinor = amountMinor,
            renterId = renter.id,
            scooterId = renter.scooterId,
            contractId = primary?.contractHistoryId,
            fromCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            toCardId = toCardId,
            note = note
        ))

        allocation.allocations.forEach { applied ->
            val period = byId.getValue(applied.contractId)
            val paid = period.paidMinor + applied.appliedMinor
            val status = when {
                paid >= period.chargeMinor && renter.isReturned -> RentPeriod.STATUS_CLOSED
                paid >= period.chargeMinor -> RentPeriod.STATUS_PAID
                renter.isReturned -> RentPeriod.STATUS_CLOSED_WITH_DEBT
                else -> RentPeriod.STATUS_PARTIALLY_PAID
            }
            db.rentPeriodDao().update(period.copy(paidMinor = paid, status = status, updatedAt = occurredAt))
            db.paymentAllocationDao().insert(PaymentAllocationEntity(
                operationId = operationId, rentPeriodId = period.id, amountMinor = applied.appliedMinor, createdAt = occurredAt
            ))
            if (status == RentPeriod.STATUS_PAID && period.contractHistoryId != null) {
                db.contractHistoryDao().getById(period.contractHistoryId)?.let { legacy ->
                    db.contractHistoryDao().update(legacy.copy(isPaid = true))
                }
            }
        }

        // Cash is received in full, including any advance that was not yet
        // attached to a future period.
        val amount = BusinessOperation.fromMinor(amountMinor)
        db.virtualCardDao().adjustBalance(toCardId, amount)
        val cardTxId = db.cardTransactionDao().insertTransaction(CardTransaction(
            timestamp = occurredAt,
            fromCardId = CardTransaction.EXTERNAL_SOURCE_ID,
            toCardId = toCardId,
            amount = amount,
            note = note,
            type = CardTransaction.TYPE_CONTRACT_INCOME,
            contractId = primary?.contractHistoryId
        ))
        // Preserve the projection reference after the card movement exists.
        db.businessOperationDao().markCardTransaction(operationId, cardTxId.toInt())

        val remainingDebt = open.sumOf { it.outstandingMinor } - allocation.allocations.sumOf { it.appliedMinor }
        // Preserve a previously recorded advance; only the new unapplied part
        // is added to it. Open-period debt remains negative balance.
        val previousAdvanceMinor = BusinessOperation.toMinor(renter.balance.coerceAtLeast(0.0))
        val newBalanceMinor = previousAdvanceMinor + allocation.unallocatedMinor - remainingDebt
        db.renterDao().updateRenter(renter.copy(
            balance = BusinessOperation.fromMinor(newBalanceMinor),
            debtAmount = BusinessOperation.fromMinor((-newBalanceMinor).coerceAtLeast(0)),
            lastPaymentTimestamp = occurredAt,
            isOverdueSmsSent = false
        ))
        db.auditEventDao().insert(AuditEvent(
            occurredAt = occurredAt,
            actor = actor,
            action = AuditEvent.ACTION_PAYMENT_ACCEPTED,
            entityType = "BUSINESS_OPERATION",
            entityId = operationId.toString(),
            reason = note,
            beforeSnapshot = "balance=${renter.balance}; debt=${renter.debtAmount}",
            afterSnapshot = "balance=${BusinessOperation.fromMinor(newBalanceMinor)}; allocated=${amountMinor - allocation.unallocatedMinor}; advance=${allocation.unallocatedMinor}"
        ))
        operationId
    }
}
