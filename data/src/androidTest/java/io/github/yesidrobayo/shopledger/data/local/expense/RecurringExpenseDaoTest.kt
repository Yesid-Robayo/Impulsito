package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los gastos recurrentes: cada columna en su lugar, las vigentes por `id` y las que vencen por
 * fecha, escrituras que tocan solo sus columnas, y llaves que impiden borrar una categoría con
 * plantillas o una plantilla con gastos generados.
 */
@RunWith(AndroidJUnit4::class)
internal class RecurringExpenseDaoTest : CatalogDatabaseTest() {
    private val recurring get() = database.recurringExpenseDao()
    private var categoryId = 0L

    @Before
    fun createCategory() = runBlocking {
        categoryId = database.expenseCategoryDao().insert(expenseCategory("Arriendo"))
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco"))
        val full =
            recurringExpense(categoryId, nextDueDate = "2026-12-31").copy(
                amount = 123_456,
                recurrence = "YEARLY",
                dayOfPeriod = 366,
                moneyAccountId = accountId,
                reminderEnabled = 0,
                updatedAt = MUCH_LATER,
            )
        val bare = recurringExpense(categoryId).copy(description = null)

        val fullId = recurring.insert(full)
        val bareId = recurring.insert(bare)

        assertEquals(full.copy(id = fullId), recurring.findById(fullId))
        assertEquals(bare.copy(id = bareId), recurring.findById(bareId))
        assertNull(recurring.findById(MISSING_ID))
    }

    @Test
    fun activeOnesComeByIdAndDueOnesByDateThenId() = runBlocking {
        val later = recurring.insert(recurringExpense(categoryId, nextDueDate = "2026-03-01"))
        val today = recurring.insert(recurringExpense(categoryId, nextDueDate = "2026-02-05"))
        val late = recurring.insert(recurringExpense(categoryId, nextDueDate = "2026-01-20"))
        val alsoToday = recurring.insert(recurringExpense(categoryId, nextDueDate = "2026-02-05"))
        recurring.insert(
            recurringExpense(categoryId, nextDueDate = "2026-01-01", archivedAt = LATER),
        )

        // La más atrasada primero, y entre las del mismo día, la más vieja. La archivada no vence.
        assertEquals(listOf(late, today, alsoToday), recurring.findDue("2026-02-05").map { it.id })

        val emissions = Emissions(this, recurring.observeActive().ids { it.id })
        try {
            emissions.awaitValue(listOf(later, today, late, alsoToday))

            assertEquals(1, recurring.setArchivedAt(today, archivedAt = LATER, updatedAt = LATER))
            emissions.awaitValue(listOf(later, late, alsoToday))

            assertEquals(1, recurring.setArchivedAt(today, archivedAt = null, updatedAt = LATER))
            emissions.awaitValue(listOf(later, today, late, alsoToday))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun writesChangeOnlyTheirColumns() = runBlocking {
        val otherCategory = database.expenseCategoryDao().insert(expenseCategory("Servicios"))
        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco"))
        val original = recurringExpense(categoryId, archivedAt = LATER)
        val id = recurring.insert(original)

        assertEquals(
            1,
            recurring.update(
                id,
                expenseCategoryId = otherCategory,
                amount = 80_000,
                description = null,
                recurrence = "WEEKLY",
                dayOfPeriod = 7,
                nextDueDate = "2026-02-08",
                moneyAccountId = accountId,
                reminderEnabled = 0,
                updatedAt = MUCH_LATER,
            ),
        )
        val edited =
            original.copy(
                id = id,
                expenseCategoryId = otherCategory,
                amount = 80_000,
                description = null,
                recurrence = "WEEKLY",
                dayOfPeriod = 7,
                nextDueDate = "2026-02-08",
                moneyAccountId = accountId,
                reminderEnabled = 0,
                updatedAt = MUCH_LATER,
            )
        // El archivo y la fecha de creación no cambian al editar.
        assertEquals(edited, recurring.findById(id))

        assertEquals(1, recurring.updateNextDueDate(id, "2026-02-15", updatedAt = LATER))
        assertEquals(
            edited.copy(nextDueDate = "2026-02-15", updatedAt = LATER),
            recurring.findById(id),
        )
        assertEquals(0, recurring.updateNextDueDate(MISSING_ID, "2026-02-15", updatedAt = LATER))
        assertEquals(0, recurring.setArchivedAt(MISSING_ID, archivedAt = null, updatedAt = LATER))
    }

    @Test
    fun itsCategoryAndAccountMustExistAndCannotBeDeletedWhileInUse() = runBlocking {
        constraintFailure(FOREIGN_KEY) { recurring.insert(recurringExpense(MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            recurring.insert(recurringExpense(categoryId).copy(moneyAccountId = MISSING_ID))
        }

        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco"))
        recurring.insert(recurringExpense(categoryId).copy(moneyAccountId = accountId))
        constraintFailure(FOREIGN_KEY) { database.expenseCategoryDao().delete(categoryId) }
        constraintFailure(FOREIGN_KEY) { database.moneyAccountDao().delete(accountId) }
        assertEquals(1L, count("SELECT COUNT(*) FROM expense_categories WHERE id = $categoryId"))
    }

    @Test
    fun aTemplateWithGeneratedExpensesIsNeverDeleted() = runBlocking {
        val unused = recurring.insert(recurringExpense(categoryId))
        val used = recurring.insert(recurringExpense(categoryId))
        database.expenseDao().insert(expense(categoryId).copy(recurringExpenseId = used))

        assertFalse(recurring.hasExpenses(unused))
        assertTrue(recurring.hasExpenses(used))
        assertEquals(1, recurring.delete(unused))
        assertEquals(0, recurring.delete(MISSING_ID))
        constraintFailure(FOREIGN_KEY) { recurring.delete(used) }
        assertEquals(1L, count("SELECT COUNT(*) FROM recurring_expenses"))

        // Un gasto tampoco apunta a una plantilla que no existe.
        constraintFailure(FOREIGN_KEY) {
            database.expenseDao().insert(expense(categoryId).copy(recurringExpenseId = unused))
        }
    }

    @Test
    fun keysAndIndexesAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "expense_category_id -> expense_categories RESTRICT",
                "money_account_id -> money_accounts RESTRICT",
            ),
            database.foreignKeysOf("recurring_expenses"),
        )
        assertEquals(
            listOf(
                "index_recurring_expenses_expense_category_id (expense_category_id)",
                "index_recurring_expenses_next_due_date (next_due_date)",
            ),
            database.indexesOf("recurring_expenses"),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
