package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registrar y anular un gasto: cada uno en una sola transacción, y anular compensa sin borrar.
 * Los montos, fechas y textos son inventados, y los valores esperados van escritos a mano.
 */
class ExpenseUseCasesTest {
    private val world = ExpenseWorld()
    private val rent get() = world.seeded.getValue("RENT")
    private val other get() = world.seeded.getValue("OTHER")

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private suspend fun registered(request: ExpenseRequest): Long {
        val result = world.register()(request)
        return (result as RegisterExpenseResult.Registered).expenseId
    }

    /** Lo que suman los gastos activos de [period]: la cifra que la ganancia neta resta. */
    private suspend fun activeTotal(period: ReportPeriod): Long = world.expenses.observeIn(period)
        .first()
        .filter { it.status == DocumentStatus.ACTIVE }
        .sumOf { it.content.amount.minor }

    @Test
    fun `the rent of the example stays on today and counts as one change`() = runTest {
        val id = registered(ExpenseRequest(Money(600_000), rent))

        val stored = world.database.tables.value.expenses.getValue(id)
        assertEquals(
            Expense(
                id = id,
                content = NewExpense(
                    expenseCategoryId = rent,
                    amount = Money(600_000),
                    description = null,
                    moneyAccountId = null,
                    recurringExpenseId = null,
                    occurredAt = ExpenseWorld.NOW,
                    businessDate = date("2026-03-15"),
                    createdAt = ExpenseWorld.NOW,
                ),
                voiding = null,
            ),
            stored,
        )
        assertEquals(1, world.transactions.writeCalls)
        assertEquals(1, world.transactions.commits)
        assertEquals(1L, world.database.tables.value.changeSeq)
        assertEquals(600_000L, activeTotal(ReportPeriod.Month(YearMonth(2026, 3))))
    }

    @Test
    fun `the first expense locks the currency in its own transaction`() = runTest {
        registered(ExpenseRequest(Money(1_500), other))

        assertEquals(listOf(ExpenseWorld.NOW), world.settings.lockCalls)
        assertEquals(ExpenseWorld.NOW, world.settings.settings.value.regional.currency.lockedAt)
    }

    @Test
    fun `a zero amount is rejected without writing anything`() = runTest {
        val result = world.register()(ExpenseRequest(Money.ZERO, rent))

        assertEquals(
            RegisterExpenseResult.Invalid(setOf(ExpenseInputError.AMOUNT_NOT_POSITIVE)),
            result,
        )
        assertTrue(world.database.tables.value.expenses.isEmpty())
        assertEquals(0L, world.database.tables.value.changeSeq)
        assertEquals(1, world.transactions.rollbacks)
        assertTrue(world.settings.lockCalls.isEmpty())
    }

    @Test
    fun `the description is stored trimmed`() = runTest {
        val id = registered(ExpenseRequest(Money(8_000), other, description = "  Bolsas de papel "))

        assertEquals(
            "Bolsas de papel",
            world.database.tables.value.expenses.getValue(id).content.description,
        )
    }

    @Test
    fun `a past date keeps its day at noon in the business zone`() = runTest {
        val id = registered(ExpenseRequest(Money(45_000), rent, date = date("2026-02-10")))

        val content = world.database.tables.value.expenses.getValue(id).content
        assertEquals(date("2026-02-10"), content.businessDate)
        // Las 12:00 en una zona cinco horas al oeste son las 17:00 en UTC.
        assertEquals(Instant.parse("2026-02-10T17:00:00Z"), content.occurredAt)
        assertEquals(ExpenseWorld.NOW, content.createdAt)
    }

    @Test
    fun `a future date is rejected, judged by the business zone and not by UTC`() = runTest {
        // Las 03:00 en UTC del 15 son todavía las 22:00 del 14 en la zona del negocio.
        val world = ExpenseWorld(now = Instant.parse("2026-03-15T03:00:00Z"))
        val other = world.seeded.getValue("OTHER")

        assertEquals(
            RegisterExpenseResult.FutureDate,
            world.register()(ExpenseRequest(Money(100), other, date = date("2026-03-15"))),
        )
        val today = world.register()(ExpenseRequest(Money(100), other, date = date("2026-03-14")))
        val id = (today as RegisterExpenseResult.Registered).expenseId
        assertEquals(
            Instant.parse("2026-03-15T03:00:00Z"),
            world.database.tables.value.expenses.getValue(id).content.occurredAt,
        )
    }

    @Test
    fun `an archived or missing category does not take new expenses`() = runTest {
        world.archive()(rent)

        assertEquals(
            RegisterExpenseResult.CategoryArchived,
            world.register()(ExpenseRequest(Money(100), rent)),
        )
        assertEquals(
            RegisterExpenseResult.CategoryNotFound,
            world.register()(ExpenseRequest(Money(100), categoryId = 999L)),
        )
        assertTrue(world.database.tables.value.expenses.isEmpty())
    }

    @Test
    fun `an unknown zone or an unconfirmed currency saves nothing`() = runTest {
        val lost = ExpenseWorld(zoneId = "Nowhere/Unknown")
        assertEquals(
            RegisterExpenseResult.UnknownTimeZone("Nowhere/Unknown"),
            lost.register()(ExpenseRequest(Money(100), lost.seeded.getValue("OTHER"))),
        )

        val unconfirmed = ExpenseWorld(currencyConfirmed = false)
        assertEquals(
            RegisterExpenseResult.CurrencyNotConfirmed,
            unconfirmed.register()(
                ExpenseRequest(Money(100), unconfirmed.seeded.getValue("OTHER")),
            ),
        )
        assertTrue(unconfirmed.database.tables.value.expenses.isEmpty())
        assertEquals(0L, unconfirmed.database.tables.value.changeSeq)
    }

    @Test
    fun `a storage failure comes back as a result and leaves nothing`() = runTest {
        world.database.failAtWrite = 2
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            RegisterExpenseResult.StorageFailed(StorageFailure.DISK_FULL),
            world.register()(ExpenseRequest(Money(100), other)),
        )
        assertTrue(world.database.tables.value.expenses.isEmpty())
        assertEquals(0L, world.database.tables.value.changeSeq)
    }

    @Test(expected = InjectedFailure::class)
    fun `an unexpected failure is not swallowed`() = runTest {
        world.database.failAtWrite = 1
        world.register()(ExpenseRequest(Money(100), other))
    }

    @Test
    fun `voiding marks the expense and keeps it, in one change`() = runTest {
        val id = registered(ExpenseRequest(Money(30_000), other))
        val voidedAt = Instant.parse("2026-03-20T15:00:00Z")
        assertEquals(
            VoidExpenseResult.Voided,
            world.void(voidedAt)(id, VoidReason.ENTRY_ERROR, "  Doble "),
        )

        val stored = world.database.tables.value.expenses.getValue(id)
        assertEquals(DocumentStatus.VOIDED, stored.status)
        assertEquals(Voiding(voidedAt, VoidReason.ENTRY_ERROR, "Doble"), stored.voiding)
        // Lo demás no cambia: anular no edita el gasto.
        assertEquals(Money(30_000), stored.content.amount)
        assertEquals(date("2026-03-15"), stored.content.businessDate)
        assertEquals(1, world.database.tables.value.expenses.size)
        assertEquals(2L, world.database.tables.value.changeSeq)
    }

    @Test
    fun `voiding last month lowers last month and leaves this month as it was`() = runTest {
        val february = registered(ExpenseRequest(Money(120_000), rent, date = date("2026-02-27")))
        registered(ExpenseRequest(Money(50_000), other))
        val lastMonth = ReportPeriod.Month(YearMonth(2026, 2))
        val thisMonth = ReportPeriod.Month(YearMonth(2026, 3))
        assertEquals(120_000L, activeTotal(lastMonth))
        assertEquals(50_000L, activeTotal(thisMonth))

        assertEquals(VoidExpenseResult.Voided, world.void()(february, VoidReason.OTHER, null))

        assertEquals(0L, activeTotal(lastMonth))
        assertEquals(50_000L, activeTotal(thisMonth))
        // El anulado sigue en su mes, para la historia.
        assertEquals(1, world.expenses.observeIn(lastMonth).first().size)
    }

    @Test
    fun `an expense is voided only once`() = runTest {
        val id = registered(ExpenseRequest(Money(100), other))
        world.void()(id, VoidReason.UNDO, null)
        val changes = world.database.tables.value.changeSeq

        assertEquals(VoidExpenseResult.AlreadyVoided, world.void()(id, VoidReason.OTHER, null))
        assertEquals(
            VoidReason.UNDO,
            world.database.tables.value.expenses.getValue(id).voiding?.reason,
        )
        assertEquals(changes, world.database.tables.value.changeSeq)
    }

    @Test
    fun `a return is not a reason to void an expense`() = runTest {
        val id = registered(ExpenseRequest(Money(100), other))

        for (reason in listOf(VoidReason.CUSTOMER_RETURN, VoidReason.SUPPLIER_RETURN)) {
            assertEquals(VoidExpenseResult.ReasonNotAllowed, world.void()(id, reason, null))
        }
        assertNull(world.database.tables.value.expenses.getValue(id).voiding)
    }

    @Test
    fun `a long note or a missing expense voids nothing`() = runTest {
        val id = registered(ExpenseRequest(Money(100), other))

        assertEquals(
            VoidExpenseResult.NoteTooLong,
            world.void()(id, VoidReason.OTHER, "n".repeat(201)),
        )
        assertEquals(
            VoidExpenseResult.ExpenseNotFound,
            world.void()(999L, VoidReason.OTHER, null),
        )
        assertNull(world.database.tables.value.expenses.getValue(id).voiding)
    }

    @Test
    fun `a storage failure while voiding comes back as a result`() = runTest {
        val id = registered(ExpenseRequest(Money(100), other))
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.IO_ERROR) }

        assertEquals(
            VoidExpenseResult.StorageFailed(StorageFailure.IO_ERROR),
            world.void()(id, VoidReason.ENTRY_ERROR, null),
        )
        assertNull(world.database.tables.value.expenses.getValue(id).voiding)
    }
}
