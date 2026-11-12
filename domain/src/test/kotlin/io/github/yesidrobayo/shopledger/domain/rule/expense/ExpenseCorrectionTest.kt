package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo que trae corregir un gasto: valor, categoría si sigue vigente, descripción y fecha. */
class ExpenseCorrectionTest {
    private val at = Instant.fromEpochMilliseconds(0)
    private val date = BusinessDate(LocalDate(2026, 10, 30))

    private val expense = NewExpense(
        expenseCategoryId = 4,
        amount = Money(45_000),
        description = "Luz del local",
        moneyAccountId = null,
        recurringExpenseId = null,
        occurredAt = at,
        businessDate = date,
        createdAt = at,
    )

    private fun category(archived: Boolean) = ExpenseCategory(
        id = 4,
        systemKey = "UTILITIES",
        details = ExpenseCategoryDetails(null, null, isFixed = false),
        isSystem = false,
        archivedAt = if (archived) at else null,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `an active category travels with the amount, the description and the date`() {
        assertEquals(
            ExpenseCorrection(Money(45_000), 4, "Luz del local", date, categoryLeftOut = false),
            ExpenseCorrections.of(expense, category(archived = false)),
        )
    }

    @Test
    fun `an archived or missing category is left out and said`() {
        val leftOut = ExpenseCorrection(Money(45_000), null, "Luz del local", date, true)
        assertEquals(leftOut, ExpenseCorrections.of(expense, category(archived = true)))
        assertEquals(leftOut, ExpenseCorrections.of(expense, null))
    }
}
