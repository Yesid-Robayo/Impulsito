package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurringRegistration
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los gastos recurrentes: se proponen y la persona los confirma u omite; nunca se registran
 * solos. Hoy es 2026-03-15 en la zona de prueba. Montos y textos inventados, y los valores
 * esperados escritos a mano.
 */
class RecurringExpenseUseCasesTest {
    private val world = ExpenseWorld()
    private val rent get() = world.seeded.getValue("RENT")
    private val other get() = world.seeded.getValue("OTHER")

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun save() = SaveRecurringExpenseUseCase(
        world.transactions,
        world.recurring,
        world.categories,
        world.clock,
    )

    private fun skip() =
        SkipRecurringExpenseUseCase(world.transactions, world.recurring, world.clock)

    private fun archive() =
        SetRecurringExpenseArchivedUseCase(world.transactions, world.recurring, world.clock)

    private fun observe(target: ExpenseWorld = world) = ObserveRecurringExpensesUseCase(
        target.recurring,
        BusinessCalendarSource(target.settings),
        target.clock,
    )

    private fun draft(
        amount: Long = 600_000,
        categoryId: Long = rent,
        day: Int = 5,
        next: String = "2026-04-05",
        recurrence: Recurrence = Recurrence.MONTHLY,
    ) = RecurringExpenseDraft(
        amount = Money(amount),
        categoryId = categoryId,
        description = "Alquiler inventado",
        recurrence = recurrence,
        dayOfPeriod = day,
        nextDueDate = date(next),
    )

    /** Deja guardado un gasto recurrente, como si la persona lo hubiera creado antes. */
    private fun seeded(
        id: Long,
        next: String,
        day: Int = 5,
        recurrence: Recurrence = Recurrence.MONTHLY,
        archivedAt: Instant? = null,
        target: ExpenseWorld = world,
    ): RecurringExpense {
        val stored = RecurringExpense(
            id = id,
            details = RecurringExpenseDetails(
                expenseCategoryId = target.seeded.getValue("RENT"),
                amount = Money(600_000),
                description = "Alquiler inventado",
                recurrence = recurrence,
                dayOfPeriod = day,
                nextDueDate = date(next),
                moneyAccountId = null,
                reminderEnabled = true,
            ),
            archivedAt = archivedAt,
            createdAt = ExpenseWorld.SEEDED_AT,
            updatedAt = ExpenseWorld.SEEDED_AT,
        )
        target.database.seed { tables ->
            tables.copy(
                recurringExpenses = tables.recurringExpenses + (id to stored),
                lastIds = tables.lastIds + ("recurring_expenses" to id),
            )
        }
        return stored
    }

    private fun stored(id: Long) = world.database.tables.value.recurringExpenses.getValue(id)

    @Test
    fun `saving a recurring expense records no expense`() = runTest {
        val result = save()(null, draft())

        val id = (result as SaveRecurringExpenseResult.Saved).recurringExpenseId
        assertEquals(date("2026-04-05"), stored(id).details.nextDueDate)
        assertEquals(ExpenseWorld.NOW, stored(id).createdAt)
        assertTrue(world.database.tables.value.expenses.isEmpty())
        assertEquals(1, world.transactions.commits)
    }

    @Test
    fun `a wrong draft writes nothing and says every field`() = runTest {
        val result = save()(null, draft(amount = 0, day = 5, next = "2026-04-06"))

        assertEquals(
            SaveRecurringExpenseResult.Invalid(
                setOf(
                    RecurringExpenseInputError.AMOUNT_NOT_POSITIVE,
                    RecurringExpenseInputError.NEXT_DATE_MISMATCH,
                ),
            ),
            result,
        )
        assertTrue(world.database.tables.value.recurringExpenses.isEmpty())
        assertEquals(0, world.transactions.commits)
    }

    @Test
    fun `a missing or archived category is refused, but an edit keeps its own`() = runTest {
        assertEquals(
            SaveRecurringExpenseResult.CategoryNotFound,
            save()(null, draft(categoryId = 99L)),
        )
        seeded(id = 1L, next = "2026-04-05")
        world.archive()(rent)

        assertEquals(SaveRecurringExpenseResult.CategoryArchived, save()(null, draft()))
        assertEquals(
            SaveRecurringExpenseResult.Saved(1L),
            save()(1L, draft(amount = 650_000)),
        )
        assertEquals(Money(650_000), stored(1L).details.amount)
        val second = seeded(id = 2L, next = "2026-04-05")
        world.database.seed { tables ->
            val changed = second.copy(details = second.details.copy(expenseCategoryId = other))
            tables.copy(recurringExpenses = tables.recurringExpenses + (2L to changed))
        }
        assertEquals(SaveRecurringExpenseResult.CategoryArchived, save()(2L, draft()))
    }

    @Test
    fun `editing one that is gone, or without changes, writes nothing`() = runTest {
        assertEquals(SaveRecurringExpenseResult.NotFound, save()(5L, draft()))
        seeded(id = 1L, next = "2026-04-05")
        val unchanged = draft()
        val commits = world.transactions.commits

        val result = save()(1L, unchanged.copy(moneyAccountId = null, reminderEnabled = true))

        assertEquals(SaveRecurringExpenseResult.Saved(1L), result)
        assertEquals(commits, world.transactions.commits)
        assertEquals(ExpenseWorld.SEEDED_AT, stored(1L).updatedAt)
    }

    @Test
    fun `a storage failure on save comes back as a result`() = runTest {
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            SaveRecurringExpenseResult.StorageFailed(StorageFailure.DISK_FULL),
            save()(null, draft()),
        )
    }

    @Test
    fun `due ones come first, oldest first, and the rest are scheduled`() = runTest {
        seeded(id = 1L, next = "2026-04-05")
        seeded(id = 2L, next = "2026-03-01", day = 1)
        seeded(id = 3L, next = "2026-03-15", day = 15)
        seeded(id = 4L, next = "2026-01-01", day = 1, archivedAt = ExpenseWorld.SEEDED_AT)

        val overview = observe()().first()

        assertEquals(date("2026-03-15"), overview.today)
        assertEquals(listOf(2L, 3L), overview.due.map { it.id })
        assertEquals(listOf(1L), overview.scheduled.map { it.id })
        assertFalse(overview.isEmpty)
        assertEquals(listOf(4L), observe().archived().first().map { it.id })
    }

    @Test
    fun `without a known zone nothing is proposed`() = runTest {
        val lost = ExpenseWorld(zoneId = "Nowhere/Unknown")
        seeded(id = 1L, next = "2026-03-01", day = 1, target = lost)

        val overview = observe(lost)().first()

        assertNull(overview.today)
        assertTrue(overview.due.isEmpty())
        assertEquals(listOf(1L), overview.scheduled.map { it.id })
        assertTrue(observe()().first().isEmpty)
    }

    @Test
    fun `confirming the day 31 of January records it and moves to the 28 of February`() = runTest {
        // El ejemplo del requisito, con hoy en 2027-01-31.
        val january = ExpenseWorld(now = Instant.parse("2027-01-31T15:00:00Z"))
        seeded(id = 1L, next = "2027-01-31", day = 31, target = january)
        val occurrence = RecurringOccurrence(1L, date("2027-01-31"))

        val result = january.register()(
            ExpenseRequest(
                amount = Money(600_000),
                categoryId = january.seeded.getValue("RENT"),
                date = date("2027-01-31"),
                recurring = occurrence,
            ),
        )

        val id = (result as RegisterExpenseResult.Registered).expenseId
        val tables = january.database.tables.value
        assertEquals(1L, tables.expenses.getValue(id).content.recurringExpenseId)
        assertEquals(
            date("2027-02-28"),
            tables.recurringExpenses.getValue(1L).details.nextDueDate,
        )
        assertEquals(1L, tables.changeSeq)
        val again = ExpenseRequest(
            amount = Money(600_000),
            categoryId = january.seeded.getValue("RENT"),
            recurring = occurrence,
        )
        assertEquals(RegisterExpenseResult.RecurringAlreadyHandled, january.register()(again))
        assertEquals(1, january.database.tables.value.expenses.size)
    }

    @Test
    fun `an old due date moves from the due date, not from the day of confirming`() = runTest {
        seeded(id = 1L, next = "2026-01-05")

        val occurrence = RecurringOccurrence(1L, date("2026-01-05"))
        world.register()(ExpenseRequest(Money(600_000), rent, recurring = occurrence))

        // Sigue atrasado: el de febrero también espera confirmación.
        assertEquals(date("2026-02-05"), stored(1L).details.nextDueDate)
        assertEquals(listOf(1L), observe()().first().due.map { it.id })
    }

    @Test
    fun `an archived or missing recurring expense records nothing`() = runTest {
        seeded(id = 1L, next = "2026-03-05", archivedAt = ExpenseWorld.SEEDED_AT)
        val archived = ExpenseRequest(
            Money(600_000),
            rent,
            recurring = RecurringOccurrence(1L, date("2026-03-05")),
        )

        assertEquals(RegisterExpenseResult.RecurringUnavailable, world.register()(archived))
        val missing = archived.copy(recurring = RecurringOccurrence(9L, date("2026-03-05")))
        assertEquals(RegisterExpenseResult.RecurringUnavailable, world.register()(missing))
        assertTrue(world.database.tables.value.expenses.isEmpty())
    }

    @Test
    fun `a failure at each write leaves no expense and no moved date`() = runTest {
        seeded(id = 1L, next = "2026-03-05")
        val occurrence = RecurringOccurrence(1L, date("2026-03-05"))
        val request = ExpenseRequest(Money(600_000), rent, recurring = occurrence)
        var failAt = 1
        while (true) {
            world.database.failAtWrite = failAt
            val failed = runCatching { world.register()(request) }.exceptionOrNull() ?: break
            assertTrue("Escritura $failAt", failed is InjectedFailure)
            assertTrue(world.database.read().expenses.isEmpty())
            assertEquals(date("2026-03-05"), stored(1L).details.nextDueDate)
            failAt++
        }
        assertTrue("Al menos el gasto y la fecha", failAt > 2)
        assertEquals(date("2026-04-05"), stored(1L).details.nextDueDate)
    }

    @Test
    fun `skipping moves the date without recording, once per due date`() = runTest {
        seeded(id = 1L, next = "2026-03-05")
        val occurrence = RecurringOccurrence(1L, date("2026-03-05"))

        assertEquals(SkipRecurringExpenseResult.Skipped(date("2026-04-05")), skip()(occurrence))
        assertEquals(SkipRecurringExpenseResult.AlreadyHandled, skip()(occurrence))
        assertTrue(world.database.tables.value.expenses.isEmpty())
        assertEquals(
            SkipRecurringExpenseResult.NotFound,
            skip()(RecurringOccurrence(9L, date("2026-03-05"))),
        )
        seeded(id = 2L, next = "2026-03-05", archivedAt = ExpenseWorld.SEEDED_AT)
        assertEquals(
            SkipRecurringExpenseResult.Archived,
            skip()(RecurringOccurrence(2L, date("2026-03-05"))),
        )
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        assertEquals(
            SkipRecurringExpenseResult.StorageFailed(StorageFailure.DISK_FULL),
            skip()(RecurringOccurrence(1L, date("2026-04-05"))),
        )
    }

    @Test
    fun `archiving stops proposing it and reactivating brings it back`() = runTest {
        seeded(id = 1L, next = "2026-03-05")

        assertEquals(RecurringExpenseLifecycleResult.Done, archive()(1L, archived = true))
        assertEquals(ExpenseWorld.NOW, stored(1L).archivedAt)
        assertTrue(observe()().first().due.isEmpty())
        assertEquals(RecurringExpenseLifecycleResult.Unchanged, archive()(1L, archived = true))
        assertEquals(RecurringExpenseLifecycleResult.Done, archive()(1L, archived = false))
        assertNull(stored(1L).archivedAt)
        assertEquals(RecurringExpenseLifecycleResult.NotFound, archive()(9L, archived = true))
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        assertEquals(
            RecurringExpenseLifecycleResult.StorageFailed(StorageFailure.DISK_FULL),
            archive()(1L, archived = true),
        )
    }

    @Test
    fun `preparing a registration brings the template, or nothing once archived`() = runTest {
        seeded(id = 1L, next = "2026-03-05")
        seeded(id = 2L, next = "2026-03-05", archivedAt = ExpenseWorld.SEEDED_AT)
        val prepare = PrepareRecurringRegistrationUseCase(world.recurring, world.categories)

        assertEquals(
            RecurringRegistration(
                occurrence = RecurringOccurrence(1L, date("2026-03-05")),
                amount = Money(600_000),
                categoryId = rent,
                description = "Alquiler inventado",
                moneyAccountId = null,
                categoryLeftOut = false,
            ),
            prepare(1L),
        )
        assertNull(prepare(2L))
        assertNull(prepare(9L))
    }
}
