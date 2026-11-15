package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RecurringExpenseLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SetRecurringExpenseArchivedUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SkipRecurringExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SkipRecurringExpenseUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los gastos recurrentes sobre la base real con su guarda: guardarlos no registra nada, y
 * confirmar uno crea un gasto normal con `recurring_expense_id` y adelanta `next_due_date` en la
 * misma transacción. Hoy, para estas pruebas, es el 31 de enero de 2027 en la zona del negocio,
 * la fecha del ejemplo del requisito. Montos y textos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class RecurringExpensesOnDatabaseTest : RepositoryTest() {
    private val recurring by lazy { RoomRecurringExpenseRepository(database) }
    private val expenses by lazy { RoomExpenseRepository(database) }
    private val categories by lazy { RoomExpenseCategoryRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val clock = Clock { TODAY_AT }

    private val save by lazy { SaveRecurringExpenseUseCase(runner, recurring, categories, clock) }
    private val skip by lazy { SkipRecurringExpenseUseCase(runner, recurring, clock) }
    private val archive by lazy { SetRecurringExpenseArchivedUseCase(runner, recurring, clock) }
    private val register by lazy {
        RegisterExpenseUseCase(
            transactions = runner,
            settings = settings,
            expenses = expenses,
            categories = categories,
            clock = clock,
            recurring = recurring,
        )
    }

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private suspend fun rent(): Long =
        count("SELECT id FROM expense_categories WHERE system_key = 'RENT'")

    private suspend fun saved(day: Int, next: String): Long {
        val draft = RecurringExpenseDraft(
            amount = Money(600_000),
            categoryId = rent(),
            description = "Alquiler inventado",
            recurrence = Recurrence.MONTHLY,
            dayOfPeriod = day,
            nextDueDate = date(next),
            reminderEnabled = true,
        )
        return (save(null, draft) as SaveRecurringExpenseResult.Saved).recurringExpenseId
    }

    @Test
    fun savingOneRecordsNoExpenseAndReadsBack() = runBlocking {
        val id = saved(day = 31, next = "2027-01-31")

        val stored = requireNotNull(recurring.findById(id))
        assertEquals(31, stored.details.dayOfPeriod)
        assertEquals(date("2027-01-31"), stored.details.nextDueDate)
        assertTrue(stored.details.reminderEnabled)
        assertEquals(TODAY_AT, stored.createdAt)
        assertEquals(0L, count("SELECT COUNT(*) FROM expenses"))
        assertEquals(listOf(id), recurring.observe(ArchiveFilter.ACTIVE).first().map { it.id })
        assertEquals(1L, changeSeq())
    }

    @Test
    fun confirmingTheThirtyFirstOfJanuaryRecordsItAndMovesToTheEndOfFebruary() = runBlocking {
        val id = saved(day = 31, next = "2027-01-31")
        val occurrence = RecurringOccurrence(id, date("2027-01-31"))
        val request = ExpenseRequest(Money(600_000), rent(), recurring = occurrence)

        val result = register(request)

        val expenseId = (result as RegisterExpenseResult.Registered).expenseId
        assertEquals(
            id,
            count("SELECT recurring_expense_id FROM expenses WHERE id = $expenseId"),
        )
        assertEquals(
            date("2027-02-28"),
            requireNotNull(recurring.findById(id)).details.nextDueDate,
        )
        // Guardar y confirmar: dos cambios, cada uno en su transacción.
        assertEquals(2L, changeSeq())
        assertEquals(RegisterExpenseResult.RecurringAlreadyHandled, register(request))
        assertEquals(1L, count("SELECT COUNT(*) FROM expenses"))
        assertInvariantsHold()
    }

    @Test
    fun skippingMovesTheDateWithoutAnExpense() = runBlocking {
        val id = saved(day = 31, next = "2027-01-31")

        val result = skip(RecurringOccurrence(id, date("2027-01-31")))

        assertEquals(SkipRecurringExpenseResult.Skipped(date("2027-02-28")), result)
        assertEquals(0L, count("SELECT COUNT(*) FROM expenses"))
    }

    @Test
    fun archivingStopsProposingItAndKeepsItsExpenses() = runBlocking {
        val id = saved(day = 5, next = "2027-01-05")
        val january = RecurringOccurrence(id, date("2027-01-05"))
        register(ExpenseRequest(Money(600_000), rent(), recurring = january))

        assertEquals(RecurringExpenseLifecycleResult.Done, archive(id, archived = true))

        assertTrue(recurring.observe(ArchiveFilter.ACTIVE).first().isEmpty())
        assertEquals(listOf(id), recurring.observe(ArchiveFilter.ARCHIVED).first().map { it.id })
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM expenses WHERE recurring_expense_id = $id"),
        )
        val february = RecurringOccurrence(id, date("2027-02-05"))
        assertEquals(
            RegisterExpenseResult.RecurringUnavailable,
            register(ExpenseRequest(Money(600_000), rent(), recurring = february)),
        )
        assertEquals(RecurringExpenseLifecycleResult.Done, archive(id, archived = false))
        assertNull(requireNotNull(recurring.findById(id)).archivedAt)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private companion object {
        /** El 31 de enero de 2027 a mediodía, tres horas al oeste de UTC. */
        val TODAY_AT: Instant = Instant.parse("2027-01-31T15:00:00Z")
    }
}
