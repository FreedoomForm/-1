package com.example

import com.example.data.AccountingReconciliation
import com.example.data.BusinessOperation
import com.example.data.PaymentAllocationEntity
import com.example.data.RentPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountingReconciliationTest {
    @Test fun `transfer is neutral for profit but projects both card balances`() {
        val result = AccountingReconciliation.expectedCardBalances(listOf(
            BusinessOperation(id = 1, type = "RENT_PAYMENT", direction = "INCOME", amountMinor = 42_000_000, toCardId = 1),
            BusinessOperation(id = 2, type = "TRANSFER", direction = "TRANSFER", amountMinor = 10_000_000, fromCardId = 1, toCardId = 2)
        ))
        assertEquals(32_000_000L, result[1])
        assertEquals(10_000_000L, result[2])
    }

    @Test fun `scheduled period is not debt until activated`() {
        val result = AccountingReconciliation.expectedRenterBalances(
            periods = listOf(
                RentPeriod(id = 1, renterId = 7, startsAt = 1, endsAt = 2, chargeMinor = 42_000_000, status = RentPeriod.STATUS_SCHEDULED),
                RentPeriod(id = 2, renterId = 7, startsAt = 2, endsAt = 3, chargeMinor = 42_000_000, paidMinor = 20_000_000, status = RentPeriod.STATUS_PARTIALLY_PAID)
            ),
            paymentOperations = emptyList(), allocations = emptyList()
        )
        assertEquals(-22_000_000L, result[7])
    }

    @Test fun `unallocated payment remains renter advance`() {
        val payment = BusinessOperation(id = 9, type = "RENT_PAYMENT", direction = "INCOME", amountMinor = 50_000_000, renterId = 7)
        val result = AccountingReconciliation.expectedRenterBalances(
            periods = emptyList(), paymentOperations = listOf(payment),
            allocations = listOf(PaymentAllocationEntity(operationId = 9, rentPeriodId = 1, amountMinor = 42_000_000))
        )
        assertEquals(8_000_000L, result[7])
    }
}
