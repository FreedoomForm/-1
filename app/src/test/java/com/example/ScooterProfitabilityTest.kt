package com.example

import com.example.data.BusinessOperation
import com.example.data.ManagementReportCalculator
import com.example.data.RentPeriod
import com.example.data.ScooterProfitability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7: per-scooter profitability calculation tests.
 *
 * Verifies that scooterProfitability() correctly:
 *  - sums revenue and expenses per scooter
 *  - computes net profit (revenue - expense)
 *  - counts rental days from period overlap with [from, until]
 *  - counts distinct repair operations
 *  - excludes reversed operations (status != ACTIVE)
 *  - sorts by net profit descending
 */
class ScooterProfitabilityTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L  // arbitrary fixed timestamp
    private val from = now - 30 * dayMs
    private val until = now

    @Test fun `single scooter with one payment has positive revenue`() {
        val ops = listOf(
            BusinessOperation(
                id = 1, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 42_000_000, scooterId = 1
            )
        )
        val periods = listOf(
            RentPeriod(
                id = 1, contractHistoryId = 1, renterId = 1, scooterId = 1,
                startsAt = now - 14 * dayMs, endsAt = now - 7 * dayMs,
                chargeMinor = 42_000_000, paidMinor = 42_000_000,
                status = RentPeriod.STATUS_PAID
            )
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, ops, periods)
        assertEquals(1, result.size)
        assertEquals(42_000_000L, result[0].revenueMinor)
        assertEquals(0L, result[0].expenseMinor)
        assertEquals(42_000_000L, result[0].netProfitMinor)
        assertEquals(7L, result[0].rentalDays)  // 7 days of rental
    }

    @Test fun `revenue minus repair expense yields net profit`() {
        val ops = listOf(
            BusinessOperation(
                id = 1, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 84_000_000, scooterId = 1
            ),
            BusinessOperation(
                id = 2, occurredAt = now - 5 * dayMs,
                type = BusinessOperation.TYPE_REPAIR,
                direction = BusinessOperation.DIRECTION_EXPENSE,
                amountMinor = 15_000_000, scooterId = 1
            )
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, ops, emptyList())
        assertEquals(1, result.size)
        assertEquals(84_000_000L, result[0].revenueMinor)
        assertEquals(15_000_000L, result[0].expenseMinor)
        assertEquals(69_000_000L, result[0].netProfitMinor)
        assertEquals(1, result[0].repairCount)
    }

    @Test fun `multiple scooters sorted by net profit descending`() {
        val ops = listOf(
            BusinessOperation(id = 1, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 42_000_000, scooterId = 1),
            BusinessOperation(id = 2, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 84_000_000, scooterId = 2),
            BusinessOperation(id = 3, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 21_000_000, scooterId = 3)
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, ops, emptyList())
        assertEquals(3, result.size)
        // Sorted by netProfit descending: scooter 2 (84M) > 1 (42M) > 3 (21M)
        assertEquals(2, result[0].scooterId)
        assertEquals(1, result[1].scooterId)
        assertEquals(3, result[2].scooterId)
    }

    @Test fun `reversed operations excluded from profitability`() {
        val ops = listOf(
            BusinessOperation(id = 1, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 42_000_000, scooterId = 1),
            // Reversed operation — should NOT count
            BusinessOperation(id = 2, occurredAt = now - 5 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 42_000_000, scooterId = 1,
                status = BusinessOperation.STATUS_REVERSED)
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, ops, emptyList())
        assertEquals(1, result.size)
        // Only the first (active) operation counts
        assertEquals(42_000_000L, result[0].revenueMinor)
    }

    @Test fun `operations outside range excluded`() {
        val ops = listOf(
            // In range
            BusinessOperation(id = 1, occurredAt = now - 10 * dayMs,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 42_000_000, scooterId = 1),
            // Out of range (before from)
            BusinessOperation(id = 2, occurredAt = from - 1,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 100_000_000, scooterId = 1),
            // Out of range (after until)
            BusinessOperation(id = 3, occurredAt = until + 1,
                type = BusinessOperation.TYPE_RENT_PAYMENT,
                direction = BusinessOperation.DIRECTION_INCOME,
                amountMinor = 200_000_000, scooterId = 1)
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, ops, emptyList())
        assertEquals(1, result.size)
        assertEquals(42_000_000L, result[0].revenueMinor)
    }

    @Test fun `rental days count overlap with report window only`() {
        // Period starts 5 days before `from` and ends 5 days after `from`.
        // Overlap with [from, until] = 5 days.
        val periods = listOf(
            RentPeriod(
                id = 1, contractHistoryId = 1, renterId = 1, scooterId = 1,
                startsAt = from - 5 * dayMs, endsAt = from + 5 * dayMs,
                chargeMinor = 42_000_000, paidMinor = 42_000_000,
                status = RentPeriod.STATUS_PAID
            )
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, emptyList(), periods)
        assertEquals(1, result.size)
        assertEquals(5L, result[0].rentalDays)
    }

    @Test fun `cancelled periods excluded from rental days`() {
        val periods = listOf(
            RentPeriod(
                id = 1, contractHistoryId = 1, renterId = 1, scooterId = 1,
                startsAt = from + 1, endsAt = from + 10 * dayMs,
                chargeMinor = 42_000_000, paidMinor = 0,
                status = RentPeriod.STATUS_CANCELLED  // excluded
            )
        )
        val result = ManagementReportCalculator.scooterProfitability(from, until, emptyList(), periods)
        // Scooter 1 still appears (because period references it) but with 0 rental days
        if (result.isNotEmpty()) {
            assertEquals(0L, result[0].rentalDays)
        }
    }

    @Test fun `csv row format is correct`() {
        val sp = ScooterProfitability(
            scooterId = 5,
            revenueMinor = 84_000_000,
            expenseMinor = 15_000_000,
            netProfitMinor = 69_000_000,
            rentalDays = 21,
            repairCount = 2
        )
        assertEquals("5,84000000,15000000,69000000,21,2", sp.toCsvRow())
        assertEquals(
            "scooter_id,revenue_minor,expense_minor,net_profit_minor,rental_days,repair_count",
            ScooterProfitability.CSV_HEADER
        )
    }

    @Test fun `empty operations and periods return empty list`() {
        val result = ManagementReportCalculator.scooterProfitability(from, until, emptyList(), emptyList())
        assertTrue("Empty input should produce empty list", result.isEmpty())
    }
}
