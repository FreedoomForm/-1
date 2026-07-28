package com.example.data

/**
 * Pricing calculator for rental periods.
 * 
 * Supports two modes:
 * 1. Simple daily rate: days × dailyRate
 * 2. Legacy weekly/monthly rates with rounding modes (PRO_RATA, ROUND_UP, MONTHLY)
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
     * Calculate rental price using weekly/monthly rates with rounding mode.
     * This is the legacy method that supports different pricing policies.
     *
     * @param days Number of rental days (must be positive)
     * @param weeklyMinor Weekly rate in minor currency units (tiyin)
     * @param monthlyMinor Monthly rate in minor currency units (tiyin)
     * @param mode Pricing mode: PRO_RATA, ROUND_UP, or MONTHLY
     * @return Total price in minor currency units
     */
    fun calculate(days: Int, weeklyMinor: Long, monthlyMinor: Long, mode: String): Long {
        require(days > 0) { "days must be positive" }
        return when (mode) {
            SettingsRepository.PARTIAL_PERIOD_PRO_RATA -> {
                // Pro-rata: exact daily rate from weekly price
                // daily = weekly / 7, total = daily * days
                // Use proper rounding: (weekly * days + 3) / 7 for half-up
                (weeklyMinor * days + 3) / 7
            }
            SettingsRepository.PARTIAL_PERIOD_ROUND_UP -> {
                // Round up to next full week
                val weeks = (days + 6) / 7  // ceiling division
                weeklyMinor * weeks
            }
            SettingsRepository.PARTIAL_PERIOD_MONTHLY -> {
                // Use monthly daily rate (30 days per month)
                // daily = monthly / 30, total = daily * days
                // Use proper rounding: (monthly * days + 15) / 30 for half-up
                (monthlyMinor * days + 15) / 30
            }
            else -> {
                // Default: pro-rata from weekly
                (weeklyMinor * days + 3) / 7
            }
        }
    }

    /**
     * Legacy compatibility wrapper.
     * @deprecated Use calculate(days, weeklyMinor, monthlyMinor, mode) instead
     */
    @Deprecated("Use calculate(days, weeklyMinor, monthlyMinor, mode) instead")
    fun calculateLegacy(days: Int, weeklyMinor: Long, monthlyMinor: Long, mode: String): Long {
        return calculate(days, weeklyMinor, monthlyMinor, mode)
    }
}
