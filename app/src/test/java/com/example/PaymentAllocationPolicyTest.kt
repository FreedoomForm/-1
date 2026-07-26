package com.example

import com.example.data.OpenObligation
import com.example.data.PaymentAllocationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentAllocationPolicyTest {
    @Test fun `partial payment leaves the correct unpaid remainder`() {
        val result = PaymentAllocationPolicy.allocateOldestFirst(
            paymentMinor = 20_000_000L, // 200 000 UZS
            obligations = listOf(OpenObligation(1L, 1L, 42_000_000L))
        )
        assertEquals(20_000_000L, result.allocations.single().appliedMinor)
        assertEquals(0L, result.unallocatedMinor)
    }

    @Test fun `one payment settles oldest period first`() {
        val result = PaymentAllocationPolicy.allocateOldestFirst(
            paymentMinor = 42_000_000L,
            obligations = listOf(
                OpenObligation(2L, 2L, 42_000_000L),
                OpenObligation(1L, 1L, 42_000_000L)
            )
        )
        assertEquals(1, result.allocations.size)
        assertEquals(1L, result.allocations.single().contractId)
    }

    @Test fun `overpayment becomes advance credit`() {
        val result = PaymentAllocationPolicy.allocateOldestFirst(
            paymentMinor = 50_000_000L,
            obligations = listOf(OpenObligation(1L, 1L, 42_000_000L))
        )
        assertEquals(42_000_000L, result.allocations.single().appliedMinor)
        assertEquals(8_000_000L, result.unallocatedMinor)
    }
}
