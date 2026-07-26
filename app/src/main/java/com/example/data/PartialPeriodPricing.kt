package com.example.data

/** Exact, testable tariff policy for the final non-weekly rental period. */
object PartialPeriodPricing {
    fun calculate(days: Int, weeklyMinor: Long, monthlyMinor: Long, mode: String): Long {
        require(days > 0) { "days must be positive" }
        return when (mode) {
            SettingsRepository.PARTIAL_PERIOD_ROUND_UP -> {
                val weeks = (days + 6) / 7
                weeklyMinor * weeks
            }
            SettingsRepository.PARTIAL_PERIOD_MONTHLY -> {
                // Round once, at the smallest money unit.
                (monthlyMinor * days + 15) / 30
            }
            else -> (weeklyMinor * days + 3) / 7
        }
    }
}
