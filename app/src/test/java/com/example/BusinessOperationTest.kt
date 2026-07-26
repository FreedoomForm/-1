package com.example

import com.example.data.BusinessOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessOperationTest {
    @Test fun `money conversion is exact to tiyin`() {
        assertEquals(42_000_000L, BusinessOperation.toMinor(420_000.0))
        assertEquals(20L, BusinessOperation.toMinor(0.2))
        assertEquals(420_000.0, BusinessOperation.fromMinor(42_000_000L), 0.0)
    }

    @Test fun `rounds half up rather than retaining a floating tail`() {
        assertEquals(30L, BusinessOperation.toMinor(0.1 + 0.2))
        assertEquals(101L, BusinessOperation.toMinor(1.005))
    }
}
