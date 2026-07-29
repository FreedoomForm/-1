package com.example.data

/**
 * Deterministic FIFO allocator for a renter payment.
 *
 * All figures are tийин and are deliberately independent from UI/Room. The
 * caller persists the resulting allocations and leaves [unallocatedMinor] as
 * the renter's advance credit. A payment can never make an obligation negative.
 */
data class OpenObligation(
    val contractId: Long,
    val dueAt: Long,
    val outstandingMinor: Long
)

data class PaymentAllocation(
    val contractId: Long,
    val appliedMinor: Long
)

data class AllocationResult(
    val allocations: List<PaymentAllocation>,
    val unallocatedMinor: Long
)

object PaymentAllocationPolicy {
    fun allocateOldestFirst(paymentMinor: Long, obligations: List<OpenObligation>): AllocationResult {
        require(paymentMinor > 0) { "Payment must be positive" }
        var remaining = paymentMinor
        val result = mutableListOf<PaymentAllocation>()
        obligations
            .filter { it.outstandingMinor > 0 }
            .sortedWith(compareBy<OpenObligation> { it.dueAt }.thenBy { it.contractId })
            .forEach { obligation ->
                if (remaining == 0L) return@forEach
                val applied = minOf(remaining, obligation.outstandingMinor)
                result += PaymentAllocation(obligation.contractId, applied)
                remaining -= applied
            }
        return AllocationResult(result, remaining)
    }
}
