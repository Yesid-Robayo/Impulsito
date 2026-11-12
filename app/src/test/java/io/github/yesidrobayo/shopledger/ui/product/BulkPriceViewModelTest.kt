package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceResult
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** La pantalla de actualización masiva, con un catálogo inventado y respuestas fijas al aplicar. */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkPriceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeBulkOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): BulkPriceViewModel {
        val model = BulkPriceViewModel(operations, NUMBERS, dispatcher)
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `without a percentage the preview asks for one`() = runTest(dispatcher) {
        val model = model()

        assertEquals(BulkPreviewContent.NeedsInput, model.uiState.value.content)
        assertEquals(0, model.uiState.value.changing)
    }

    @Test
    fun `a percentage shows the preview, and removing a row leaves it out`() = runTest(dispatcher) {
        val model = model()

        model.onAction(BulkPriceAction.PercentText("8"))
        advanceUntilIdle()
        val preview = (model.uiState.value.content as BulkPreviewContent.Ready).preview
        assertEquals(listOf(1L, 2L), preview.changes.map { it.productId })

        model.onAction(BulkPriceAction.RemoveRow(2L))
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.changing)
    }

    @Test
    fun `shortcuts, rounding and a high percentage`() = runTest(dispatcher) {
        val model = model()

        model.onAction(BulkPriceAction.PercentShortcut(BasisPoints(-1_000L)))
        advanceUntilIdle()
        assertEquals("-10", model.uiState.value.input.percentText)
        assertFalse(model.uiState.value.highPercent)

        model.onAction(BulkPriceAction.PercentText("80"))
        model.onAction(BulkPriceAction.SelectRounding(BulkRounding.SAVED_STEP))
        advanceUntilIdle()
        assertTrue(model.uiState.value.highPercent)
        val ready = model.uiState.value.content as BulkPreviewContent.Ready
        // 12350 con +80 % y el paso guardado de 100 es 22300.
        assertEquals(
            Money(22_300L),
            ready.preview.changes.first {
                it.productId == 1L
            }.change.newPrice,
        )

        model.onAction(BulkPriceAction.SelectRounding(BulkRounding.OTHER))
        advanceUntilIdle()
        assertEquals(BulkPreviewContent.NeedsInput, model.uiState.value.content)
        model.onAction(BulkPriceAction.StepText("500"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is BulkPreviewContent.Ready)
    }

    @Test
    fun `a scope opens its sheet and narrows the preview`() = runTest(dispatcher) {
        val model = model()
        model.onAction(BulkPriceAction.PercentText("8"))

        model.onAction(BulkPriceAction.SelectScope(BulkScopeKind.PICK))
        advanceUntilIdle()
        assertEquals(BulkSheet.PRODUCTS, model.uiState.value.sheet)
        assertEquals(BulkPreviewContent.NeedsInput, model.uiState.value.content)

        model.onAction(BulkPriceAction.TogglePicked(2L))
        model.onAction(BulkPriceAction.CloseSheet)
        advanceUntilIdle()
        assertNull(model.uiState.value.sheet)
        val ready = model.uiState.value.content as BulkPreviewContent.Ready
        assertEquals(listOf(2L), ready.preview.changes.map { it.productId })
    }

    @Test
    fun `applying sends the shown rows once and says so`() = runTest(dispatcher) {
        val model = model()
        model.onAction(BulkPriceAction.PercentText("8"))
        advanceUntilIdle()

        model.onAction(BulkPriceAction.Apply)
        model.onAction(BulkPriceAction.Apply)
        advanceUntilIdle()

        assertEquals(listOf(listOf(1L, 2L)), operations.applied)
        assertEquals(BulkMessage.Applied(BATCH, 2), model.uiState.value.message)
        assertEquals("", model.uiState.value.input.percentText)
    }

    @Test
    fun `a failure keeps everything as written`() = runTest(dispatcher) {
        operations.result = ApplyBulkPriceResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = model()
        model.onAction(BulkPriceAction.PercentText("8"))
        advanceUntilIdle()

        model.onAction(BulkPriceAction.Apply)
        advanceUntilIdle()

        assertEquals(BulkMessage.Failed(outOfSpace = true), model.uiState.value.message)
        assertEquals("8", model.uiState.value.input.percentText)
        assertFalse(model.uiState.value.applying)
    }

    @Test
    fun `undo is offered only with a price to bring back, and asks first`() = runTest(dispatcher) {
        val model = model()
        assertFalse(model.uiState.value.canUndo)

        operations.offer.value = BulkUndoPlan(BATCH, emptyList(), kept = 2)
        advanceUntilIdle()
        assertFalse(model.uiState.value.canUndo)
        // Sin precios que vuelvan, el diálogo no se abre aunque se pida.
        model.onAction(BulkPriceAction.OpenUndo)
        advanceUntilIdle()
        assertFalse(model.uiState.value.confirmingUndo)

        operations.offer.value = BulkUndoPlan(BATCH, listOf(REVERT), kept = 1)
        advanceUntilIdle()
        assertTrue(model.uiState.value.canUndo)
        assertFalse(model.uiState.value.confirmingUndo)
        model.onAction(BulkPriceAction.OpenUndo)
        advanceUntilIdle()
        assertTrue(model.uiState.value.confirmingUndo)
        model.onAction(BulkPriceAction.DismissUndo)
        advanceUntilIdle()
        assertFalse(model.uiState.value.confirmingUndo)
        assertTrue(operations.undone.isEmpty())
    }

    @Test
    fun `confirming undoes once and says what stayed`() = runTest(dispatcher) {
        operations.offer.value = BulkUndoPlan(BATCH, listOf(REVERT), kept = 1)
        val model = model()

        model.onAction(BulkPriceAction.OpenUndo)
        model.onAction(BulkPriceAction.ConfirmUndo)
        model.onAction(BulkPriceAction.ConfirmUndo)
        advanceUntilIdle()

        assertEquals(listOf(BATCH), operations.undone)
        assertEquals(BulkMessage.Undone(reverted = 1, kept = 1), model.uiState.value.message)
        assertFalse(model.uiState.value.confirmingUndo)
        assertFalse(model.uiState.value.undoing)
    }

    @Test
    fun `each undo result has its message`() {
        assertEquals(
            BulkMessage.Undone(reverted = 0, kept = 3),
            BulkMessage.ofUndo(UndoBulkPriceResult.NothingToRevert(3)),
        )
        assertEquals(BulkMessage.UndoGone, BulkMessage.ofUndo(UndoBulkPriceResult.NotUndoable))
        assertEquals(
            BulkMessage.Failed(outOfSpace = true),
            BulkMessage.ofUndo(UndoBulkPriceResult.StorageFailed(StorageFailure.DISK_FULL)),
        )
        assertEquals(BulkMessage.Failed(outOfSpace = false), BulkMessage.ofUndo(null))
        assertEquals(
            BulkMessage.Failed(outOfSpace = false),
            BulkMessage.ofUndo(UndoBulkPriceResult.UnknownTimeZone),
        )
    }

    @Test
    fun `no product with a price is the empty state`() = runTest(dispatcher) {
        operations.catalog.value = listOf(ProductDetailSamples.product(price = 0L))
        val model = model()

        assertEquals(BulkPreviewContent.NoPrices, model.uiState.value.content)
    }

    private companion object {
        val BATCH: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val REVERT = BulkUndoRevert(1L, from = Money(13_400L), to = Money(12_350L))
        val NUMBERS =
            BulkNumberFormat(
                percentSymbols = NumberSymbols(',', '-', DigitGrouping('.', 3, 3)),
                moneySymbols = NumberSymbols(',', '-', null),
                currencyDecimals = 0,
            )
    }
}

/** Dos productos con precio y respuestas fijas al aplicar. */
private class FakeBulkOperations : BulkPriceOperations {
    val catalog = MutableStateFlow(
        listOf(
            ProductDetailSamples.product(price = 12_350L, cost = 8_000L).copy(id = 1L),
            ProductDetailSamples.product(price = 10_000L, cost = 9_000L).copy(id = 2L),
        ),
    )
    var result: ApplyBulkPriceResult =
        ApplyBulkPriceResult.Applied(Uuid.parse("00000000-0000-4000-8000-000000000001"), 2)
    val applied = mutableListOf<List<Long>>()
    val offer = MutableStateFlow<BulkUndoPlan?>(null)
    val undone = mutableListOf<Uuid>()

    override fun catalog(): Flow<List<Product>> = catalog

    override fun categories(): Flow<List<Category>> = MutableStateFlow(emptyList())

    override fun suppliers(): Flow<List<Supplier>> = MutableStateFlow(emptyList())

    override fun defaultStep(): Flow<Money> = MutableStateFlow(Money(100L))

    override suspend fun apply(
        productIds: Collection<Long>,
        percent: BasisPoints,
        step: Money,
    ): ApplyBulkPriceResult {
        applied += productIds.toList()
        return result
    }

    override fun undoOffer(): Flow<BulkUndoPlan?> = offer

    override suspend fun undo(batchId: Uuid): UndoBulkPriceResult {
        undone += batchId
        return UndoBulkPriceResult.Undone(reverted = 1, kept = 1)
    }
}
