package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Revisar un gasto recurrente y armar su registro. Los textos y montos son inventados. */
class RecurringExpenseRulesTest {
    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private val at = Instant.parse("2026-11-01T12:00:00Z")

    private fun draft(
        amount: Long = 600_000,
        description: String? = "Alquiler inventado",
        recurrence: Recurrence = Recurrence.MONTHLY,
        day: Int = 5,
        next: String = "2026-12-05",
    ) = RecurringExpenseDraft(
        amount = Money(amount),
        categoryId = 1L,
        description = description,
        recurrence = recurrence,
        dayOfPeriod = day,
        nextDueDate = date(next),
        moneyAccountId = 3L,
        reminderEnabled = true,
    )

    private fun errorsOf(draft: RecurringExpenseDraft): Set<RecurringExpenseInputError> =
        (RecurringExpenseRules.check(draft) as RecurringExpenseCheck.Invalid).errors

    @Test
    fun `a valid draft keeps its fields and trims the description`() {
        val check = RecurringExpenseRules.check(draft(description = "  Alquiler inventado  "))

        assertEquals(
            RecurringExpenseCheck.Valid(
                RecurringExpenseDetails(
                    expenseCategoryId = 1L,
                    amount = Money(600_000),
                    description = "Alquiler inventado",
                    recurrence = Recurrence.MONTHLY,
                    dayOfPeriod = 5,
                    nextDueDate = date("2026-12-05"),
                    moneyAccountId = 3L,
                    reminderEnabled = true,
                ),
            ),
            check,
        )
    }

    @Test
    fun `a blank description is not kept`() {
        val check = RecurringExpenseRules.check(draft(description = "   "))
        assertEquals(null, (check as RecurringExpenseCheck.Valid).details.description)
        val none = RecurringExpenseRules.check(draft(description = null))
        assertEquals(null, (none as RecurringExpenseCheck.Valid).details.description)
    }

    @Test
    fun `every wrong field comes back at once`() {
        assertEquals(
            setOf(
                RecurringExpenseInputError.AMOUNT_NOT_POSITIVE,
                RecurringExpenseInputError.DESCRIPTION_TOO_LONG,
                RecurringExpenseInputError.DAY_OUT_OF_RANGE,
            ),
            errorsOf(draft(amount = 0, description = "x".repeat(201), day = 32)),
        )
        assertEquals(
            setOf(RecurringExpenseInputError.AMOUNT_TOO_LARGE),
            errorsOf(draft(amount = 1_000_000_000_000_001)),
        )
    }

    @Test
    fun `two hundred characters fit, counted as the database counts them`() {
        val check = RecurringExpenseRules.check(draft(description = "x".repeat(200)))
        assertTrue(check is RecurringExpenseCheck.Valid)
    }

    @Test
    fun `the next date has to fall on the chosen day`() {
        assertEquals(
            setOf(RecurringExpenseInputError.NEXT_DATE_MISMATCH),
            errorsOf(draft(day = 5, next = "2026-12-06")),
        )
        // El día 31 cae el 28 en un febrero corto.
        val shortMonth = RecurringExpenseRules.check(draft(day = 31, next = "2027-02-28"))
        assertTrue(shortMonth is RecurringExpenseCheck.Valid)
    }

    private fun validDetails() =
        (RecurringExpenseRules.check(draft()) as RecurringExpenseCheck.Valid).details

    @Test
    fun `registering brings the data and the due date, without an archived category`() {
        val recurring = RecurringExpense(
            id = 7L,
            details = validDetails(),
            archivedAt = null,
            createdAt = at,
            updatedAt = at,
        )
        val active = category(archivedAt = null)

        val registration = RecurringExpenseRules.registrationOf(recurring, active)

        assertEquals(
            RecurringRegistration(
                occurrence = RecurringOccurrence(7L, date("2026-12-05")),
                amount = Money(600_000),
                categoryId = 1L,
                description = "Alquiler inventado",
                moneyAccountId = 3L,
                categoryLeftOut = false,
            ),
            registration,
        )
        val archived = RecurringExpenseRules.registrationOf(recurring, category(archivedAt = at))
        assertEquals(null, archived.categoryId)
        assertTrue(archived.categoryLeftOut)
        val missing = RecurringExpenseRules.registrationOf(recurring, null)
        assertTrue(missing.categoryLeftOut)
    }

    @Test
    fun `a recurring expense is due from its date on, while it is active`() {
        val active = RecurringExpense(7L, validDetails(), null, at, at)

        assertFalse(active.isArchived)
        assertFalse(active.isDue(date("2026-12-04")))
        assertTrue(active.isDue(date("2026-12-05")))
        assertTrue(active.isDue(date("2027-01-20")))
        val archived = active.copy(archivedAt = at)
        assertTrue(archived.isArchived)
        assertFalse(archived.isDue(date("2027-01-20")))
    }

    private fun category(archivedAt: Instant?) = ExpenseCategory(
        id = 1L,
        systemKey = "RENT",
        details = ExpenseCategoryDetails(null, null, isFixed = true),
        isSystem = false,
        archivedAt = archivedAt,
        createdAt = at,
        updatedAt = at,
    )
}
