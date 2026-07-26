package com.example.data

/**
 * §8: repair metrics calculator.
 *
 * Computes three metrics per scooter:
 *  1. Total repair cost — sum of actualMinor across all completed orders.
 *  2. Average downtime — average (closedAt - openedAt) across completed orders.
 *  3. Repeat failures within 90 days — count of orders opened within 90 days
 *     of a previous order's open date for the same scooter.
 *
 * Per PLAN_UNIVERSAL_ACCOUNTING §8: 'Add calculation of repair metrics:
 * cost, average downtime and repeat failures within 90 days.'
 */
class ScooterMetricsService(private val db: AppDatabase) {

    data class RepairMetrics(
        val scooterId: Int,
        val totalRepairCostMinor: Long,
        val averageDowntimeMs: Long,
        val repeatFailures90d: Int,
        val totalRepairCount: Int
    ) {
        /** CSV row for export: scooter_id,total_cost,avg_downtime_ms,repeat_90d,total_count */
        fun toCsvRow(): String = "$scooterId,$totalRepairCostMinor,$averageDowntimeMs,$repeatFailures90d,$totalRepairCount"
        companion object {
            const val CSV_HEADER = "scooter_id,total_repair_cost_minor,avg_downtime_ms,repeat_failures_90d,total_repair_count"
        }
    }

    /**
     * Returns repair metrics for [scooterId]. Empty metrics if no repairs.
     */
    suspend fun repairMetrics(scooterId: Int): RepairMetrics {
        val completed = db.repairOrderDao().completedForScooter(scooterId)
        val totalCount = db.repairOrderDao().countForScooter(scooterId)

        // Total repair cost = sum of actualMinor across completed orders
        val totalCost = completed.sumOf { it.actualMinor }

        // Average downtime = average (closedAt - openedAt) across completed
        val avgDowntime = if (completed.isEmpty()) 0L
                          else completed.sumOf { (it.closedAt ?: it.openedAt) - it.openedAt } / completed.size

        // Repeat failures within 90 days — count orders that opened within
        // 90 days of a PREVIOUS order's open date for the same scooter.
        // We sort by openedAt and for each order check if there's another
        // order within 90 days before it; if yes, it's a "repeat".
        val allOrders = db.repairOrderDao().forScooterSince(
            scooterId, System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        ).sortedBy { it.openedAt }
        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
        var repeatCount = 0
        for (i in 1 until allOrders.size) {
            val prev = allOrders[i - 1]
            val curr = allOrders[i]
            if (curr.openedAt - prev.openedAt <= ninetyDaysMs) {
                repeatCount++
            }
        }

        return RepairMetrics(
            scooterId = scooterId,
            totalRepairCostMinor = totalCost,
            averageDowntimeMs = avgDowntime,
            repeatFailures90d = repeatCount,
            totalRepairCount = totalCount
        )
    }
}
