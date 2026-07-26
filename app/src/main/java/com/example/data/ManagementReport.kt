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
}
