package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.port.RecurringReminder
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los recordatorios: solo los vencidos con recordatorio encendido, una vez por vencimiento, y nada
 * cuenta como avisado si el sistema no deja avisar. Hoy es 2026-03-15. Datos inventados.
 */
class DeliverRecurringRemindersUseCaseTest {
    private val world = ExpenseWorld()
    private val shown = mutableListOf<List<RecurringReminder>>()
    private var allowed = true

    private val observe = ObserveRecurringExpensesUseCase(
        world.recurring,
        BusinessCalendarSource(world.settings),
        world.clock,
    )
    private val deliver = DeliverRecurringRemindersUseCase(observe, world.categories) {
        shown += it
        allowed
    }

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun seed(id: Long, next: String, reminder: Boolean) {
        val stored = RecurringExpense(
            id = id,
            details = RecurringExpenseDetails(
                expenseCategoryId = world.seeded.getValue("UTILITIES"),
                amount = Money(80_000),
                description = null,
                recurrence = Recurrence.MONTHLY,
                dayOfPeriod = date(next).date.day,
                nextDueDate = date(next),
                moneyAccountId = null,
                reminderEnabled = reminder,
            ),
            archivedAt = null,
            createdAt = ExpenseWorld.SEEDED_AT,
            updatedAt = ExpenseWorld.SEEDED_AT,
        )
        world.database.seed {
            it.copy(recurringExpenses = it.recurringExpenses + (id to stored))
        }
    }

    @Test
    fun `only due ones with the reminder on are shown, once each`() = runTest {
        seed(id = 1L, next = "2026-03-10", reminder = true)
        seed(id = 2L, next = "2026-03-12", reminder = false)
        seed(id = 3L, next = "2026-03-20", reminder = true)

        val delivered = deliver(emptySet())

        val expected = setOf(RecurringOccurrence(1L, date("2026-03-10")))
        assertEquals(expected, delivered)
        assertEquals(listOf(1L), shown.single().map { it.recurring.id })
        assertEquals("UTILITIES", shown.single().single().category?.systemKey)
        assertEquals(expected, deliver.remindable(observe().first()))
        assertTrue(deliver(delivered).isEmpty())
        assertEquals(1, shown.size)
    }

    @Test
    fun `nothing counts as shown when the system does not allow it`() = runTest {
        seed(id = 1L, next = "2026-03-10", reminder = true)
        allowed = false

        assertTrue(deliver(emptySet()).isEmpty())
        assertEquals(1, shown.size)
    }
}
