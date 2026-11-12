package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Deshacer un gasto: la anulación de siempre con el motivo de deshacer, sin borrar nada. */
class UndoExpenseUseCaseTest {
    private val world = ExpenseWorld()

    @Test
    fun `undo voids the expense with its reason and keeps it`() = runTest {
        val registered =
            world.register()(ExpenseRequest(Money(8_000), world.seeded.getValue("OTHER")))
        val id = (registered as RegisterExpenseResult.Registered).expenseId

        assertEquals(VoidExpenseResult.Voided, UndoExpenseUseCase(world.void())(id))
        assertEquals(VoidExpenseResult.AlreadyVoided, UndoExpenseUseCase(world.void())(id))

        val stored = world.database.tables.value.expenses.getValue(id)
        assertEquals(VoidReason.UNDO, stored.voiding?.reason)
        assertNull(stored.voiding?.note)
        assertEquals(1, world.database.tables.value.expenses.size)
    }
}
