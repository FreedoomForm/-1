package com.example.data

/** Portable management report built solely from the universal journal and periods. */
data class ManagementReport(
    val from: Long,
    val until: Long,
    val revenueMinor: Long,
    val expenseMinor: Long,
    val netProfitMinor: Long,
    val receivablesMinor: Long,
    val advancesMinor: Long,
    val rentedScooters: Int,
    val totalScooters: Int,
    /** Monthly depreciation for the period in minor units */
    val depreciationMinor: Long = 0,
    /** Monthly Recurring Revenue projection in minor units */
    val mrrMinor: Long = 0
) {
    fun toCsv(): String = buildString {
        appendLine("metric,value_minor")
        appendLine("period_from,$from")
        appendLine("period_until,$until")
        appendLine("revenue,$revenueMinor")
        appendLine("expenses,$expenseMinor")
        appendLine("depreciation,$depreciationMinor")
        appendLine("net_profit,$netProfitMinor")
        appendLine("receivables,$receivablesMinor")
        appendLine("customer_advances,$advancesMinor")
        appendLine("rented_scooters,$rentedScooters")
        appendLine("total_scooters,$totalScooters")
        appendLine("mrr,$mrrMinor")
    }

    fun toCsvWithScooters(scooters: List<ScooterProfitability>): String = buildString {
        appendLine("# Management Report")
        appendLine("metric,value_minor")
        appendLine("period_from,$from")
        appendLine("period_until,$until")
        appendLine("revenue,$revenueMinor")
        appendLine("expenses,$expenseMinor")
        appendLine("depreciation,$depreciationMinor")
        appendLine("net_profit,$netProfitMinor")
        appendLine("receivables,$receivablesMinor")
        appendLine("customer_advances,$advancesMinor")
        appendLine("rented_scooters,$rentedScooters")
        appendLine("total_scooters,$totalScooters")
        appendLine("mrr,$mrrMinor")
        appendLine()
        appendLine("# Per-Scooter Profitability")
        appendLine(ScooterProfitability.CSV_HEADER)
        scooters.forEach { appendLine(it.toCsvRow()) }
    }
}

object ManagementReportCalculator {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MONTH_DAYS = 30.0
    
    /** All expense operation types */
    private val EXPENSE_TYPES = setOf(
        BusinessOperation.TYPE_EXPENSE,
        BusinessOperation.TYPE_REPAIR,
        BusinessOperation.TYPE_TAX,
        BusinessOperation.TYPE_COMMISSION,
        BusinessOperation.TYPE_OTHER_EXPENSE
    )
    
    fun calculate(
        from: Long,
        until: Long,
        operations: List<BusinessOperation>,
        periods: List<RentPeriod>,
        allocations: List<PaymentAllocationEntity>,
        totalScooters: Int,
        scooterPriceUzs: Double = 0.0,
        scooterLifespanMonths: Int = 36
    ): ManagementReport {
        require(until > from) { "Report end must be after start" }
        
        val inRange = operations.filter { 
            it.status == BusinessOperation.STATUS_ACTIVE && it.occurredAt in from until until 
        }
        
        // Revenue: all income operations
        val revenue = inRange
            .filter { it.direction == BusinessOperation.DIRECTION_INCOME }
            .sumOf { it.amountMinor }
        
        // Expenses: all expense operations including TAX, COMMISSION, REPAIR
        val expenses = inRange
            .filter { 
                it.direction == BusinessOperation.DIRECTION_EXPENSE || 
                it.type in EXPENSE_TYPES 
            }
            .sumOf { it.amountMinor }
        
        // Calculate depreciation for the period using fractional months
        val periodDays = ((until - from).toDouble() / DAY_MS).coerceAtLeast(1.0)
        val periodMonths = periodDays / MONTH_DAYS
        val monthlyDepreciationPerScooter = if (scooterPriceUzs > 0 && scooterLifespanMonths > 0) {
            BusinessOperation.toMinor(scooterPriceUzs / scooterLifespanMonths)
        } else 0L
        val totalDepreciation = (monthlyDepreciationPerScooter * totalScooters * periodMonths).toLong()
        
        // Net profit = revenue - expenses - depreciation
        val netProfit = revenue - expenses - totalDepreciation
        
        // Receivables and advances
        val balances = AccountingReconciliation.expectedRenterBalances(periods, operations, allocations)
        val receivables = balances.values.filter { it < 0 }.sumOf { -it }
        val advances = balances.values.filter { it > 0 }.sum()
        
        // Count rented scooters
        val rentedScooters = periods.filter {
            it.scooterId != null && it.status in setOf(
                RentPeriod.STATUS_ACTIVE, RentPeriod.STATUS_PARTIALLY_PAID, RentPeriod.STATUS_OVERDUE
            ) && it.startsAt < until && it.endsAt > from
        }.mapNotNull { it.scooterId }.distinct().size
        
        // Calculate MRR
        val mrr = calculateMrr(System.currentTimeMillis(), periods)
        
        return ManagementReport(
            from, until, revenue, expenses, netProfit,
            receivables, advances, rentedScooters, totalScooters, totalDepreciation, mrr
        )
    }

    /**
     * Calculate Monthly Recurring Revenue (MRR) based on active rental periods.
     * 
     * MRR = sum of monthly equivalent rates for all active/scheduled periods
     * that intersect the next 30 days from [now].
     * 
     * Each period contributes proportionally based on:
     * - Its actual charge rate (chargeMinor / period days)
     * - How many days it overlaps with the 30-day MRR window
     */
    fun calculateMrr(now: Long, periods: List<RentPeriod>): Long {
        val mrrWindowEnd = now + 30 * DAY_MS
        
        return periods
            .filter { period ->
                // Include active, partially paid, scheduled periods
                period.status in setOf(
                    RentPeriod.STATUS_ACTIVE,
                    RentPeriod.STATUS_PARTIALLY_PAID,
                    RentPeriod.STATUS_SCHEDULED,
                    RentPeriod.STATUS_OVERDUE
                ) &&
                // Period must intersect MRR window [now, now+30d]
                period.startsAt < mrrWindowEnd && period.endsAt > now &&
                // Exclude non-billable periods
                !period.isNonBillable
            }
            .sumOf { period ->
                // Calculate daily rate for this period
                val periodDays = ((period.endsAt - period.startsAt).toDouble() / DAY_MS).coerceAtLeast(1.0)
                val dailyRateMinor = period.chargeMinor / periodDays
                
                // Calculate overlap with MRR window
                val overlapStart = maxOf(period.startsAt, now)
                val overlapEnd = minOf(period.endsAt, mrrWindowEnd)
                val overlapDays = ((overlapEnd - overlapStart).toDouble() / DAY_MS).coerceAtLeast(0.0)
                
                // Contribution = daily rate * overlap days, scaled to 30 days
                (dailyRateMinor * MONTH_DAYS).toLong()
            }
    }

    fun scooterProfitability(
        from: Long,
        until: Long,
        operations: List<BusinessOperation>,
        periods: List<RentPeriod>
    ): List<ScooterProfitability> {
        require(until > from) { "Range end must be after start" }
        
        val inRangeOps = operations.filter {
            it.status == BusinessOperation.STATUS_ACTIVE && it.occurredAt in from until until
        }
        
        val scooterIds = (inRangeOps.mapNotNull { it.scooterId } +
                          periods.mapNotNull { it.scooterId }).distinct()
        
        return scooterIds.map { sid ->
            val revenue = inRangeOps.filter {
                it.scooterId == sid && it.direction == BusinessOperation.DIRECTION_INCOME
            }.sumOf { it.amountMinor }
            
            val expense = inRangeOps.filter {
                it.scooterId == sid && (it.direction == BusinessOperation.DIRECTION_EXPENSE || it.type in EXPENSE_TYPES)
            }.sumOf { it.amountMinor }
            
            val repairCount = inRangeOps.count {
                it.scooterId == sid && it.type == BusinessOperation.TYPE_REPAIR
            }
            
            // Rental days = sum of overlap of each period with [from, until]
            // Use floor division instead of ceiling to avoid inflating days
            val rentalDays = periods.filter {
                it.scooterId == sid && it.startsAt < until && it.endsAt > from &&
                it.status in setOf(
                    RentPeriod.STATUS_ACTIVE, RentPeriod.STATUS_PARTIALLY_PAID,
                    RentPeriod.STATUS_OVERDUE, RentPeriod.STATUS_PAID,
                    RentPeriod.STATUS_CLOSED, RentPeriod.STATUS_CLOSED_WITH_DEBT
                )
            }.sumOf { period ->
                val overlapStart = maxOf(period.startsAt, from)
                val overlapEnd = minOf(period.endsAt, until)
                if (overlapEnd > overlapStart) (overlapEnd - overlapStart) / DAY_MS
                else 0L
            }
            
            ScooterProfitability(
                scooterId = sid,
                revenueMinor = revenue,
                expenseMinor = expense,
                netProfitMinor = revenue - expense,
                rentalDays = rentalDays,
                repairCount = repairCount
            )
        }.sortedByDescending { it.netProfitMinor }
    }
}

data class ScooterProfitability(
    val scooterId: Int,
    val revenueMinor: Long,
    val expenseMinor: Long,
    val netProfitMinor: Long,
    val rentalDays: Long,
    val repairCount: Int
) {
    fun toCsvRow(): String = "$scooterId,$revenueMinor,$expenseMinor,$netProfitMinor,$rentalDays,$repairCount"

    companion object {
        const val CSV_HEADER = "scooter_id,revenue_minor,expense_minor,net_profit_minor,rental_days,repair_count"
    }
}
