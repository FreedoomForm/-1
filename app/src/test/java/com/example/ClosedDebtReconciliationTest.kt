package com.example

import com.example.data.AccountingReconciliation
import com.example.data.RentPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class ClosedDebtReconciliationTest {
    @Test fun `returning scooter does not erase outstanding receivable`() {
        val balances = AccountingReconciliation.expectedRenterBalances(
            periods = listOf(RentPeriod(
                id = 1, renterId = 3, startsAt = 1, endsAt = 2,
                chargeMinor = 42_000_000L, paidMinor = 0,
                status = RentPeriod.STATUS_CLOSED_WITH_DEBT
            )),
            paymentOperations = emptyList(),
            allocations = emptyList()
        )
        assertEquals(-42_000_000L, balances[3])
    }
}
