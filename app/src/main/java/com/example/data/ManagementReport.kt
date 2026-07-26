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
    val totalScooters: Int
) {
    fun toCsv(): String = buildString {
        appendLine("metric,value_minor")
        appendLine("period_from,$from")
        appendLine("period_until,$until")
        appendLine("revenue,$revenueMinor")
        appendLine("expenses,$expenseMinor")
        appendLine("net_profit,$netProfitMinor")
        appendLine("receivables,$receivablesMinor")
        appendLine("customer_advances,$advancesMinor")
        appendLine("rented_scooters,$rentedScooters")
        appendLine("total_scooters,$totalScooters")
    }

    /**
     * §7: Combined CSV export — management report + per-scooter profitability.
     * One file, two sections. Suitable for XLSX/PDF downstream processing.
     */
    fun toCsvWithScooters(scooters: List<ScooterProfitability>): String = buildString {
        appendLine("# Management Report")
        appendLine("metric,value_minor")
        appendLine("period_from,$from")
        appendLine("period_until,$until")
        appendLine("revenue,$revenueMinor")
        appendLine("expenses,$expenseMinor")
        appendLine("net_profit,$netProfitMinor")
        appendLine("receivables,$receivablesMinor")
        appendLine("customer_advances,$advancesMinor")
        appendLine("rented_scooters,$rentedScooters")
        appendLine("total_scooters,$totalScooters")
        appendLine()
        appendLine("# Per-Scooter Profitability")
        appendLine(ScooterProfitability.CSV_HEADER)
        scooters.forEach { appendLine(it.toCsvRow()) }
    }
}

object ManagementReportCalculator {
    fun calculate(
        from: Long,
        until: Long,
        operations: List<BusinessOperation>,
        periods: List<RentPeriod>,
        allocations: List<PaymentAllocationEntity>,
        totalScooters: Int
    ): ManagementReport {
        require(until > from) { "Report end must be after start" }
        val inRange = operations.filter { it.status == BusinessOperation.STATUS_ACTIVE && it.occurredAt in from until until }
        val revenue = inRange.filter { it.direction == BusinessOperation.DIRECTION_INCOME }.sumOf { it.amountMinor }
        val expenses = inRange.filter { it.direction == BusinessOperation.DIRECTION_EXPENSE }.sumOf { it.amountMinor }
        val balances = AccountingReconciliation.expectedRenterBalances(periods, operations, allocations)
        val receivables = balances.values.filter { it < 0 }.sumOf { -it }
        val advances = balances.values.filter { it > 0 }.sum()
        val rentedScooters = periods.filter {
            it.scooterId != null && it.status in setOf(
                RentPeriod.STATUS_ACTIVE, RentPeriod.STATUS_PARTIALLY_PAID, RentPeriod.STATUS_OVERDUE
            ) && it.startsAt < until && it.endsAt > from
        }.mapNotNull { it.scooterId }.distinct().size
        return ManagementReport(
            from, until, revenue, expenses, revenue - expenses,
            receivables, advances, rentedScooters, totalScooters
        )
    }

    /**
     * §7: Per-scooter profitability. For each scooter, returns:
     *  - revenueMinor: sum of RENT_PAYMENT operations tagged with scooterId
     *  - expenseMinor: sum of REPAIR + TYPE_EXPENSE operations tagged with scooterId
     *  - netProfitMinor: revenue - expense
     *  - rentalDays: total days the scooter was in an active/partial/overdue period
     *                overlapping [from, until]
     *  - repairCount: number of distinct RepairOrder-like operations (REPAIR type)
     *
     * Only active operations in [from, until] are counted. Reversed operations
     * are excluded by status filter.
     */
    fun scooterProfitability(
        from: Long,
        until: Long,
        operations: List<BusinessOperation>,
        periods: List<RentPeriod>
    ): List<ScooterProfitability> {
        require(until > from) { "Range end must be after start" }
        val dayMs = 24L * 60 * 60 * 1000
        val inRangeOps = operations.filter {
            it.status == BusinessOperation.STATUS_ACTIVE && it.occurredAt in from until until
        }
        // Group by scooterId, summing revenue/expense.
        val scooterIds = (inRangeOps.mapNotNull { it.scooterId } +
                          periods.mapNotNull { it.scooterId }).distinct()
        return scooterIds.map { sid ->
            val revenue = inRangeOps.filter {
                it.scooterId == sid && it.direction == BusinessOperation.DIRECTION_INCOME
            }.sumOf { it.amountMinor }
            val expense = inRangeOps.filter {
                it.scooterId == sid && it.direction == BusinessOperation.DIRECTION_EXPENSE
            }.sumOf { it.amountMinor }
            val repairCount = inRangeOps.count {
                it.scooterId == sid && it.type == BusinessOperation.TYPE_REPAIR
            }
            // Rental days = sum of overlap of each period with [from, until]
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
                if (overlapEnd > overlapStart) (overlapEnd - overlapStart + dayMs - 1) / dayMs
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

/**
 * §7: per-scooter profitability summary.
 */
data class ScooterProfitability(
    val scooterId: Int,
    val revenueMinor: Long,
    val expenseMinor: Long,
    val netProfitMinor: Long,
    val rentalDays: Long,
    val repairCount: Int
) {
    /** CSV row for export: scooter_id,revenue,expense,net_profit,rental_days,repair_count */
    fun toCsvRow(): String = "$scooterId,$revenueMinor,$expenseMinor,$netProfitMinor,$rentalDays,$repairCount"

    companion object {
        const val CSV_HEADER = "scooter_id,revenue_minor,expense_minor,net_profit_minor,rental_days,repair_count"
    }
}
