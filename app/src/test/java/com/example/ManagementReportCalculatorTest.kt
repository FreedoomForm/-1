package com.example

import com.example.data.BusinessOperation
import com.example.data.ManagementReportCalculator
import com.example.data.RentPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagementReportCalculatorTest {
    @Test fun `profit excludes internal transfer and includes real expense`() {
        val report = ManagementReportCalculator.calculate(
            from = 0, until = 100,
            operations = listOf(
                BusinessOperation(id = 1, occurredAt = 10, type = "RENT_PAYMENT", direction = "INCOME", amountMinor = 42_000_000),
                BusinessOperation(id = 2, occurredAt = 11, type = "TRANSFER", direction = "TRANSFER", amountMinor = 20_000_000),
                BusinessOperation(id = 3, occurredAt = 12, type = "REPAIR", direction = "EXPENSE", amountMinor = 3_000_000)
            ),
            periods = emptyList(), allocations = emptyList(), totalScooters = 10
        )
        assertEquals(42_000_000L, report.revenueMinor)
        assertEquals(3_000_000L, report.expenseMinor)
        assertEquals(39_000_000L, report.netProfitMinor)
    }
}
