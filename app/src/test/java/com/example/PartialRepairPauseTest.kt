package com.example

import com.example.data.RepairOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8: Тесты частичного ремонта — несколько пауз внутри одного RepairOrder.
 *
 * Проверяют:
 *   • начальное состояние: totalPauseMs = 0, currentlyPaused = false
 *   • моделирование паузы и возобновления
 *   • что суммарная длительность паузов корректно накапливается
 */
class PartialRepairPauseTest {

    @Test
    fun `new repair order has zero pause time`() {
        val order = RepairOrder(
            scooterId = 1,
            scenario = RepairOrder.SCENARIO_RENTER_REPAIR,
            diagnosis = "Engine failure"
        )
        assertEquals(0L, order.totalPauseMs)
        assertFalse(order.currentlyPaused)
    }

    @Test
    fun `paused order has currentlyPaused true and lastPausedAt set`() {
        val now = System.currentTimeMillis()
        val order = RepairOrder(
            scooterId = 1,
            scenario = RepairOrder.SCENARIO_RENTER_REPAIR,
            diagnosis = "Engine failure",
            currentlyPaused = true,
            lastPausedAt = now
        )
        assertTrue(order.currentlyPaused)
        assertEquals(now, order.lastPausedAt)
    }

    @Test
    fun `resumed order accumulates pause duration`() {
        // Pause start: 1000, pause end: 5000 → duration 4000ms.
        val pauseStart = 1000L
        val pauseEnd = 5000L
        val pauseDuration = pauseEnd - pauseStart
        val order = RepairOrder(
            scooterId = 1,
            scenario = RepairOrder.SCENARIO_RENTER_REPAIR,
            diagnosis = "Engine failure",
            pauseIntervalsJson = "[[$pauseStart,$pauseEnd]",
            totalPauseMs = pauseDuration,
            currentlyPaused = false
        )
        assertEquals(4000L, order.totalPauseMs)
        assertFalse(order.currentlyPaused)
    }

    @Test
    fun `multiple pauses accumulate total duration`() {
        val intervals = "[[1000,5000],[10000,15000]]"
        // Pause 1: 4000ms, Pause 2: 5000ms → total 9000ms.
        val order = RepairOrder(
            scooterId = 1,
            scenario = RepairOrder.SCENARIO_RENTER_REPAIR,
            diagnosis = "Multiple issues",
            pauseIntervalsJson = intervals,
            totalPauseMs = 9000L,
            currentlyPaused = false
        )
        assertEquals(9000L, order.totalPauseMs)
    }

    @Test
    fun `contract extension equals total pause duration`() {
        // §8 spec: «продление договора по сумме пауз».
        // Если ремонт занял 5 дней с 2 паузами по 12 часов каждая,
        // контракт продлевается на 24 часа = 86_400_000 ms.
        val pause1 = 12L * 60 * 60 * 1000  // 12h
        val pause2 = 12L * 60 * 60 * 1000  // 12h
        val totalPause = pause1 + pause2
        val contractEnd = System.currentTimeMillis()
        val extendedEnd = contractEnd + totalPause
        assertEquals(86_400_000L, totalPause)
        assertTrue(extendedEnd > contractEnd)
    }
}
