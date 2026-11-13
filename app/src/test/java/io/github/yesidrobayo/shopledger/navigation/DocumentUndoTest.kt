package io.github.yesidrobayo.shopledger.navigation

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deshacer una compra o un gasto: qué cuenta como hecho y qué como falla, sin rama por defecto. */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentUndoTest {
    @Test
    fun `voided or already voided is undone, and every rejection is a failure`() {
        assertTrue(undoneExpense(VoidExpenseResult.Voided))
        assertTrue(undoneExpense(VoidExpenseResult.AlreadyVoided))
        for (result in listOf(
            null,
            VoidExpenseResult.ExpenseNotFound,
            VoidExpenseResult.ReasonNotAllowed,
            VoidExpenseResult.NoteTooLong,
            VoidExpenseResult.StorageFailed(StorageFailure.IO_ERROR),
        )) {
            assertFalse(result.toString(), undoneExpense(result))
        }
        assertTrue(undonePurchase(VoidPurchaseResult.Voided))
        assertTrue(undonePurchase(VoidPurchaseResult.AlreadyVoided))
        for (result in listOf(
            null,
            VoidPurchaseResult.PurchaseNotFound,
            VoidPurchaseResult.HasActiveReturns,
            VoidPurchaseResult.ReasonNotAllowed,
            VoidPurchaseResult.NoteTooLong,
            VoidPurchaseResult.UnknownTimeZone("Nowhere/Unknown"),
            VoidPurchaseResult.LedgerDamaged,
            VoidPurchaseResult.StorageFailed(StorageFailure.DISK_FULL),
        )) {
            assertFalse(result.toString(), undonePurchase(result))
        }
    }

    @Test
    fun `undo runs outside the screen and reports how it ended`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val asked = mutableListOf<Long>()
        val outcomes = mutableListOf<Boolean>()
        val undo = DocumentUndo(scope) { id ->
            asked += id
            id == 7L
        }

        undo.undo(7L) { outcomes += it }
        undo.undo(8L) { outcomes += it }
        scope.testScheduler.advanceUntilIdle()

        assertEquals(listOf(7L, 8L), asked)
        assertEquals(listOf(true, false), outcomes)
    }
}
