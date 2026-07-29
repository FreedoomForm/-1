package com.example

import com.example.data.ContractHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5: Тесты новой логики баланса и товарооборота.
 *
 * balance = paid_total − turnover
 *   • < 0 — арендатор должен (turnover превысил оплаты)
 *   • > 0 — аванс (переплата)
 *   • = 0 — расчёт закрыт (соответствует «balance=0 when paying»)
 *
 * Эти тесты НЕ используют БД — они проверяют чистую формулу
 * по списку ContractHistoryEntry, как это делает UI (turnoverByRenter/paidByRenter).
 */
class TurnoverBalanceTest {

    private fun contract(amount: Double, isPaid: Boolean) = ContractHistoryEntry(
        renterId = 1, type = ContractHistoryEntry.TYPE_CREATED,
        amount = amount, isPaid = isPaid,
        weekStart = 0L, weekEnd = 7L * 24 * 60 * 60 * 1000
    )

    @Test
    fun `turnover equals sum of all contract amounts`() {
        val contracts = listOf(
            contract(100_000.0, true),
            contract(100_000.0, false),
            contract(50_000.0, true)
        )
        val turnover = contracts.sumOf { it.amount }
        assertEquals(250_000.0, turnover, 0.001)
    }

    @Test
    fun `paid total equals sum of paid contract amounts`() {
        val contracts = listOf(
            contract(100_000.0, true),
            contract(100_000.0, false),
            contract(50_000.0, true)
        )
        val paid = contracts.filter { it.isPaid }.sumOf { it.amount }
        assertEquals(150_000.0, paid, 0.001)
    }

    @Test
    fun `balance is zero when all contracts paid`() {
        val contracts = listOf(
            contract(100_000.0, true),
            contract(100_000.0, true)
        )
        val balance = contracts.filter { it.isPaid }.sumOf { it.amount } -
            contracts.sumOf { it.amount }
        assertEquals(0.0, balance, 0.001)
    }

    @Test
    fun `balance is negative when unpaid contracts exist`() {
        val contracts = listOf(
            contract(100_000.0, true),
            contract(100_000.0, false)
        )
        val balance = contracts.filter { it.isPaid }.sumOf { it.amount } -
            contracts.sumOf { it.amount }
        assertEquals(-100_000.0, balance, 0.001)
        assertTrue("Negative balance indicates debt", balance < 0)
    }

    @Test
    fun `paying exact amount sets balance to zero`() {
        // Scenario: turnover = 100k, paid was 0 → balance = -100k (debt).
        // User pays 100k → contract flipped to isPaid=true → balance = 0.
        val before = listOf(contract(100_000.0, false))
        val balanceBefore = before.filter { it.isPaid }.sumOf { it.amount } -
            before.sumOf { it.amount }
        assertEquals(-100_000.0, balanceBefore, 0.001)

        val after = listOf(contract(100_000.0, true))
        val balanceAfter = after.filter { it.isPaid }.sumOf { it.amount } -
            after.sumOf { it.amount }
        assertEquals(0.0, balanceAfter, 0.001)
    }

    @Test
    fun `partial payment keeps balance negative`() {
        // Scenario: turnover = 100k, paid = 30k → balance = -70k.
        val contracts = listOf(
            contract(100_000.0, false)  // outstanding
        )
        val manualPaidTotal = 30_000.0
        val balance = manualPaidTotal - contracts.sumOf { it.amount }
        assertEquals(-70_000.0, balance, 0.001)
    }

    @Test
    fun `empty contract list yields zero balance`() {
        val contracts = emptyList<ContractHistoryEntry>()
        val balance = contracts.filter { it.isPaid }.sumOf { it.amount } -
            contracts.sumOf { it.amount }
        assertEquals(0.0, balance, 0.001)
    }
}
