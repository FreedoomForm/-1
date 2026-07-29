package com.example

import com.example.data.PartialPeriodPricing
import com.example.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class PartialPeriodPricingTest {
    private val weekly = 42_000_000L // 420,000 UZS
    private val monthly = 168_000_000L // 1,680,000 UZS

    @Test fun `pro rata charges exactly two sevenths for two days`() {
        assertEquals(12_000_000L, PartialPeriodPricing.calculate(2, weekly, monthly, SettingsRepository.PARTIAL_PERIOD_PRO_RATA))
    }

    @Test fun `round up charges full next week`() {
        assertEquals(42_000_000L, PartialPeriodPricing.calculate(2, weekly, monthly, SettingsRepository.PARTIAL_PERIOD_ROUND_UP))
        assertEquals(84_000_000L, PartialPeriodPricing.calculate(8, weekly, monthly, SettingsRepository.PARTIAL_PERIOD_ROUND_UP))
    }

    @Test fun `monthly policy uses exact daily monthly rate`() {
        assertEquals(11_200_000L, PartialPeriodPricing.calculate(2, weekly, monthly, SettingsRepository.PARTIAL_PERIOD_MONTHLY))
    }
}
