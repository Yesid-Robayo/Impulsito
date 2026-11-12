package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los topes de un gasto, de su anulación y de una categoría, con los valores escritos a mano. */
class ExpenseRulesTest {
    private fun check(amount: Long, description: String? = null) =
        ExpenseRules.check(ExpenseRequest(Money(amount), categoryId = 1L, description))

    @Test
    fun `the rent of the example is a valid expense`() {
        assertEquals(ExpenseCheck.Valid(Money(600_000), null), check(600_000))
    }

    @Test
    fun `a zero or negative amount is rejected`() {
        val zero = ExpenseCheck.Invalid(setOf(ExpenseInputError.AMOUNT_NOT_POSITIVE))
        assertEquals(zero, check(0))
        assertEquals(zero, check(-1))
    }

    @Test
    fun `the amount goes from one to the document cap, both included`() {
        assertEquals(ExpenseCheck.Valid(Money(1), null), check(1))
        val cap = 1_000_000_000_000_000L
        assertEquals(ExpenseCheck.Valid(Money(cap), null), check(cap))
        assertEquals(
            ExpenseCheck.Invalid(setOf(ExpenseInputError.AMOUNT_TOO_LARGE)),
            check(cap + 1),
        )
    }

    @Test
    fun `the description is trimmed, and a blank one is not stored`() {
        assertEquals(ExpenseCheck.Valid(Money(5), "Luz del local"), check(5, "  Luz del local "))
        assertEquals(ExpenseCheck.Valid(Money(5), null), check(5, "   "))
        assertEquals(ExpenseCheck.Valid(Money(5), null), check(5, ""))
    }

    @Test
    fun `the description takes up to 200 characters, counting an emoji as one`() {
        val longest = "a".repeat(199) + "🚀"
        assertEquals(ExpenseCheck.Valid(Money(5), longest), check(5, longest))
        assertEquals(
            ExpenseCheck.Invalid(setOf(ExpenseInputError.DESCRIPTION_TOO_LONG)),
            check(5, "a".repeat(201)),
        )
    }

    @Test
    fun `every error comes back at once`() {
        assertEquals(
            ExpenseCheck.Invalid(
                setOf(
                    ExpenseInputError.AMOUNT_NOT_POSITIVE,
                    ExpenseInputError.DESCRIPTION_TOO_LONG,
                ),
            ),
            check(0, "b".repeat(201)),
        )
    }

    @Test
    fun `a void note is trimmed, blank means none, and it takes up to 200 characters`() {
        assertEquals(VoidNoteCheck.Valid(null), ExpenseRules.voidNote(null))
        assertEquals(VoidNoteCheck.Valid(null), ExpenseRules.voidNote("  "))
        assertEquals(
            VoidNoteCheck.Valid("Se anotó dos veces"),
            ExpenseRules.voidNote(" Se anotó dos veces "),
        )
        val longest = "n".repeat(200)
        assertEquals(VoidNoteCheck.Valid(longest), ExpenseRules.voidNote(longest))
        assertEquals(VoidNoteCheck.TooLong, ExpenseRules.voidNote("n".repeat(201)))
    }

    @Test
    fun `a category name is trimmed and normalized, and carries the fixed flag`() {
        assertEquals(
            ExpenseCategoryCheck.Valid(
                ExpenseCategoryDetails("Fumigación", "fumigacion", isFixed = true),
            ),
            ExpenseRules.checkCategory(ExpenseCategoryDraft(" Fumigación ", isFixed = true), false),
        )
    }

    @Test
    fun `a blank name keeps the translated one only in an initial category`() {
        assertEquals(
            ExpenseCategoryCheck.Valid(ExpenseCategoryDetails(null, null, isFixed = false)),
            ExpenseRules.checkCategory(ExpenseCategoryDraft("  "), isInitial = true),
        )
        assertEquals(
            ExpenseCategoryCheck.Invalid(setOf(ExpenseCategoryInputError.NAME_EMPTY)),
            ExpenseRules.checkCategory(ExpenseCategoryDraft("  "), isInitial = false),
        )
    }

    @Test
    fun `a category name takes up to 40 characters`() {
        val longest = "c".repeat(40)
        assertEquals(
            ExpenseCategoryCheck.Valid(ExpenseCategoryDetails(longest, longest, isFixed = false)),
            ExpenseRules.checkCategory(ExpenseCategoryDraft(longest), isInitial = false),
        )
        val tooLong = ExpenseCategoryCheck.Invalid(setOf(ExpenseCategoryInputError.NAME_TOO_LONG))
        assertEquals(
            tooLong,
            ExpenseRules.checkCategory(ExpenseCategoryDraft("c".repeat(41)), isInitial = false),
        )
        assertEquals(
            tooLong,
            ExpenseRules.checkCategory(ExpenseCategoryDraft("c".repeat(41)), isInitial = true),
        )
    }
}
