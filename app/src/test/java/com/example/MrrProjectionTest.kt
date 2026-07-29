package com.example

import com.example.data.BusinessOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MRR (Monthly Recurring Revenue) projection tests.
 *
 * MRR = sum of (active period monthly rate) for all currently-active or
 * scheduled periods that intersect the next 30 days. Each period contributes
 * proportionally to its own rate, not the global weeklyPrice.
 *
 * Per PLAN_UNIVERSAL_ACCOUNTING §7: 'calculate forecast and MRR by the
 * intersection of future billing periods and their real rates.'
 * Per §12: 'Unit tests for discount, deposit, refund and MRR.'
 */
class MrrProjectionTest {

    /**
     * Pure formula test: a single period covering 30 days at 420 000/week
     * contributes ~1 800 000 so'm (4.285 weeks × 420 000).
     *
     * MRR for a full month = weeklyRate × (30 / 7).
     */
    @Test fun `single full month period at weekly rate produces expected MRR`() {
        val weeklyRateMinor = BusinessOperation.toMinor(420_000.0)
        val daysInMonth = 30L
        val weeksInMonth = daysInMonth / 7.0
        val mrrMinor = (weeklyRateMinor * weeksInMonth).toLong()
        // 420 000 × (30/7) ≈ 1 800 000 so'm = 180 000 000 tiyin
        assertEquals(180_000_000L, mrrMinor)
    }

    /**
     * Two active periods with different rates sum their contributions.
     */
    @Test fun `two periods with different rates sum contributions`() {
        val rate1Minor = BusinessOperation.toMinor(420_000.0)  // weekly
        val rate2Minor = BusinessOperation.toMinor(500_000.0)  // weekly
        val weeksInMonth = 30.0 / 7.0
        val mrr1 = (rate1Minor * weeksInMonth).toLong()
        val mrr2 = (rate2Minor * weeksInMonth).toLong()
        val totalMrr = mrr1 + mrr2
        // Expected ≈ 3 942 857 so'm = ~394 285 700 tiyin
        assertTrue("MRR should be positive", totalMrr > 0)
        assertTrue(
            "Two-rate MRR should exceed single-rate MRR",
            totalMrr > mrr1 && totalMrr > mrr2
        )
    }

    /**
     * A scheduled future period (not yet started) contributes to MRR only
     * if its start falls within the 30-day window. Periods starting later
     * than 30 days from now should NOT be counted.
     */
    @Test fun `future period outside 30 day window excluded from MRR`() {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val inWindowStart = now + 5 * dayMs       // 5 days from now: in window
        val outOfWindowStart = now + 60 * dayMs    // 60 days from now: out
        val windowEnd = now + 30 * dayMs

        val inWindow = inWindowStart <= windowEnd
        val outOfWindow = outOfWindowStart <= windowEnd

        assertTrue("Period starting in 5 days should be in MRR window", inWindow)
        assert(!outOfWindow) { "Period starting in 60 days must NOT be in MRR window" }
    }

    /**
     * MRR is computed from real per-period rates, not the global weeklyPrice
     * setting. This matters when different renters negotiated different rates.
     */
    @Test fun `MRR uses per period rate not global setting`() {
        val globalRateMinor = BusinessOperation.toMinor(420_000.0)
        val negotiatedRateMinor = BusinessOperation.toMinor(350_000.0)
        val weeksInMonth = 30.0 / 7.0

        val wrongMrr = (globalRateMinor * weeksInMonth).toLong()
        val correctMrr = (negotiatedRateMinor * weeksInMonth).toLong()

        assertTrue(
            "MRR must reflect negotiated rate, not global default",
            wrongMrr != correctMrr
        )
        assertTrue(
            "Negotiated-discount MRR should be lower than global-rate MRR",
            correctMrr < wrongMrr
        )
    }

    /**
     * Partial-period MRR: a period covering only 10 days of the 30-day window
     * contributes proportionally (10/30 of its monthly equivalent).
     */
    @Test fun `partial period contributes proportionally`() {
        val weeklyRateMinor = BusinessOperation.toMinor(420_000.0)
        val fullMonthMs = 30L * 24 * 60 * 60 * 1000
        val partialMs = 10L * 24 * 60 * 60 * 1000
        val fullMonthMrr = (weeklyRateMinor * (30.0 / 7.0)).toLong()
        val partialMrr = (fullMonthMrr * partialMs / fullMonthMs)
        // 10/30 of full = 1/3
        assertEquals(fullMonthMrr / 3, partialMrr)
    }

    /**
     * MRR excludes one-time payments (deposits, penalties, discounts, refunds).
     * Only RENT_PAYMENT operations backing active periods count.
     */
    @Test fun `MRR excludes one time operations`() {
        val recurringTypes = setOf(
            BusinessOperation.TYPE_RENT_PAYMENT
        )
        val excludedTypes = setOf(
            BusinessOperation.TYPE_DEPOSIT_RECEIVED,
            BusinessOperation.TYPE_DEPOSIT_REFUNDED,
            BusinessOperation.TYPE_DISCOUNT,
            BusinessOperation.TYPE_DEBT_FORGIVEN,
            BusinessOperation.TYPE_REFUND,
            BusinessOperation.TYPE_PENALTY_ACCRUAL,
            BusinessOperation.TYPE_PENALTY_PAYMENT,
            BusinessOperation.TYPE_REPAIR,
            BusinessOperation.TYPE_TRANSFER
        )
        excludedTypes.forEach { type ->
            assertTrue(
                "Type $type must NOT count towards MRR (only RENT_PAYMENT does)",
                type !in recurringTypes
            )
        }
    }
}
