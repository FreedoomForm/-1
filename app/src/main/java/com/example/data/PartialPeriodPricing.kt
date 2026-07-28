package com.example.data

/**
 * Simplified pricing: daily rate only.
 * The owner sets a single daily price, and the total is calculated as days × dailyRate.
 * No complex rounding modes needed anymore.
 */
object PartialPeriodPricing {
    /**
     * Calculate rental price based on daily rate.
     * 
     * @param days Number of rental days (must be positive)
     * @param dailyRateMinor Daily rental rate in minor currency units (tiyin)
     * @return Total price in minor currency units
     */
    fun calculate(days: Int, dailyRateMinor: Long): Long {
        require(days > 0) { "days must be positive" }
        return dailyRateMinor * days
    }

    /**
     * Legacy compatibility: calculate from weekly/monthly rates.
     * Converts to effective daily rate and multiplies by days.
     * Uses half-up rounding for fair pricing.
     */
    @Deprecated("Use calculate(days, dailyRateMinor) instead")
    fun calculateLegacy(days: Int, weeklyMinor: Long, monthlyMinor: Long, mode: String): Long {
        require(days > 0) { "days must be positive" }
        // Convert weekly to daily rate (rounded half-up)
        val dailyFromWeekly = (weeklyMinor + 3) / 7
        return dailyFromWeekly * days
    }
}
