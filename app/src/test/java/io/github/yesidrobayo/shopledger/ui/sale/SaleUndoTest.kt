package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.material3.SnackbarDuration
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deshacer la última venta sin base: corre fuera de la pantalla de venta, dice cómo terminó y
 * ofrece reintentar si falló. Los textos son inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleUndoTest {
    private val texts = SaleUndoTexts(undone = "undone", failed = "failed", retry = "retry")

    @Test
    fun theUndoneSaleIsReported() = runTest {
        val asked = mutableListOf<Long>()
        val undo = SaleUndo(this) { saleId ->
            asked += saleId
            VoidSaleResult.Voided
        }
        var outcome: SaleUndoOutcome? = null

        undo.undo(7) { outcome = it }
        advanceUntilIdle()

        assertEquals(listOf(7L), asked)
        assertEquals(SaleUndoOutcome.UNDONE, outcome)
    }

    @Test
    fun theUndoOutlivesTheScreenThatAskedForIt() {
        // La pantalla de venta ya se cerró: el trabajo sigue en el alcance de la app.
        val app = TestScope()
        val written = CompletableDeferred<VoidSaleResult>()
        val undo = SaleUndo(app) { written.await() }
        var outcome: SaleUndoOutcome? = null

        undo.undo(3) { outcome = it }
        app.advanceUntilIdle()
        assertNull(outcome)
        written.complete(VoidSaleResult.Voided)
        app.advanceUntilIdle()

        assertEquals(SaleUndoOutcome.UNDONE, outcome)
    }

    @Test
    fun eachResultIsShownAsUndoneOrFailed() {
        val undone = listOf(VoidSaleResult.Voided, VoidSaleResult.AlreadyVoided)
        val failed = listOf(
            null,
            VoidSaleResult.SaleNotFound,
            VoidSaleResult.HasActiveReturns,
            VoidSaleResult.ReasonNotAllowed,
            VoidSaleResult.NoteTooLong,
            VoidSaleResult.UnknownTimeZone("Invented/Zone"),
            VoidSaleResult.LedgerDamaged,
            VoidSaleResult.StorageFailed(StorageFailure.DISK_FULL),
        )

        undone.forEach { assertEquals(SaleUndoOutcome.UNDONE, SaleUndo.outcomeOf(it)) }
        failed.forEach { assertEquals(SaleUndoOutcome.FAILED, SaleUndo.outcomeOf(it)) }
    }

    @Test
    fun theRegisteredMessageOffersUndoForTenSeconds() {
        val message = SaleUndo.registeredMessage("Sale recorded", "Undo")

        assertEquals("Undo", message.actionLabel)
        assertEquals(SnackbarVariant.SUCCESS, message.variant)
        // Material da 10 segundos a un mensaje largo; con TalkBack, el sistema lo alarga.
        assertEquals(SnackbarDuration.Long, message.duration)
    }

    @Test
    fun aFailedUndoStaysWithRetry() {
        val failed = SaleUndo.outcomeMessage(SaleUndoOutcome.FAILED, texts)
        val undone = SaleUndo.outcomeMessage(SaleUndoOutcome.UNDONE, texts)

        assertEquals("failed", failed.message)
        assertEquals("retry", failed.actionLabel)
        assertEquals(SnackbarDuration.Indefinite, failed.duration)
        assertEquals("undone", undone.message)
        assertNull(undone.actionLabel)
        assertEquals(SnackbarDuration.Short, undone.duration)
    }
}
