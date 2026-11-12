package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de gastos de un mes: por día, del más nuevo al más antiguo, con el total de los activos y
 * el filtro por categoría. Montos inventados, totales escritos a mano.
 */
class ObserveExpenseMonthUseCaseTest {
    private val world = ExpenseWorld()
    private val observe = ObserveExpenseMonthUseCase(world.expenses, world.categories)
    private val rent get() = world.seeded.getValue("RENT")
    private val other get() = world.seeded.getValue("OTHER")
    private val march = YearMonth(2026, 3)

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private suspend fun spend(amount: Long, categoryId: Long, date: String): Long {
        val request = ExpenseRequest(Money(amount), categoryId, date = date(date))
        return (world.register()(request) as RegisterExpenseResult.Registered).expenseId
    }

    @Test
    fun `the month goes by day, newest first, and voided ones do not add up`() = runTest {
        val first = spend(600_000, rent, "2026-03-02")
        val second = spend(15_000, other, "2026-03-10")
        val third = spend(8_000, other, "2026-03-10")
        spend(99_000, other, "2026-02-28")
        world.void()(second, VoidReason.ENTRY_ERROR, null)

        val month = observe(march, categoryId = null).first()

        assertEquals(listOf(date("2026-03-10"), date("2026-03-02")), month.days.map { it.date })
        assertEquals(listOf(third, second), month.days[0].rows.map { it.id })
        assertEquals(listOf(false, true), month.days[0].rows.map { it.voided })
        // 8000 del día 10, sin los 15000 anulados.
        assertEquals(Money(8_000), month.days[0].total)
        assertEquals(listOf(first), month.days[1].rows.map { it.id })
        // 600000 + 8000.
        assertEquals(Money(608_000), month.total)
        assertTrue(month.hasAny)
        assertEquals("RENT", month.days[1].rows.single().category.systemKey)
    }

    @Test
    fun `the category filter keeps the month and its total to that category`() = runTest {
        spend(600_000, rent, "2026-03-02")
        spend(8_000, other, "2026-03-10")

        val month = observe(march, categoryId = rent).first()

        assertEquals(listOf(Money(600_000)), month.days.flatMap { it.rows }.map { it.amount })
        assertEquals(Money(600_000), month.total)
        assertTrue(month.hasAny)
    }

    @Test
    fun `an empty month and an empty filter are told apart`() = runTest {
        assertFalse(observe(march, null).first().hasAny)
        assertEquals(Money.ZERO, observe(march, null).first().total)

        spend(8_000, other, "2026-03-10")
        val filtered = observe(march, categoryId = rent).first()

        assertTrue(filtered.days.isEmpty())
        assertTrue(filtered.hasAny)
    }

    @Test
    fun `archived categories are still offered, and their expenses still show`() = runTest {
        spend(600_000, rent, "2026-03-02")
        world.archive()(rent)

        val month = observe(march, null).first()

        assertEquals(9, month.categories.size)
        assertTrue(month.categories.single { it.id == rent }.isArchived)
        assertEquals(Money(600_000), month.total)
    }
}
