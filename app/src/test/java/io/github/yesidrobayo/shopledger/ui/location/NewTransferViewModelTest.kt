package io.github.yesidrobayo.shopledger.ui.location

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

/**
 * El formulario de una transferencia con respuestas fijas: extremos propuestos, avisos de negativo,
 * errores que no borran lo escrito y el borrador que sobrevive. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewTransferViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val savedState = SavedStateHandle()
    private val requests = mutableListOf<StockTransferRequest>()
    private var result: RegisterStockTransferResult = RegisterStockTransferResult.Registered(1L)

    // En el origen: 8 de arroz y 1,5 kg de queso; el jabón lleva lotes y no se ofrece.
    private val rice = LocationSamples.product(7L, "Arroz", LocationSamples.UNIT.id, 8_000L)
    private val cheese = LocationSamples.product(8L, "Queso", LocationSamples.KILOGRAM.id, 1_500L)
    private val soap = LocationSamples.product(9L, "Jabón", LocationSamples.UNIT.id, 3_000L)
        .let { it.copy(details = it.details.copy(tracksLots = true)) }

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(
        initialFrom: Long? = null,
        state: SavedStateHandle = savedState,
    ): NewTransferViewModel {
        val model = NewTransferViewModel(
            initialFrom = initialFrom,
            locationsSource = { flowOf(listOf(LocationSamples.MAIN, LocationSamples.STALL)) },
            stockAt = {
                flowOf(
                    InventoryOverview(
                        hasProducts = true,
                        tracked = listOf(rice, cheese, soap),
                        lowStock = emptyList(),
                        negative = emptyList(),
                        value = Money.ZERO,
                        archivedValue = null,
                        hasStockWithoutCost = false,
                        units = listOf(LocationSamples.UNIT, LocationSamples.KILOGRAM)
                            .associateBy { it.id },
                    ),
                )
            },
            register = {
                requests += it
                result
            },
            savedState = state,
            quantitySymbols = symbols,
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun NewTransferViewModel.act(scope: TestScope, action: NewTransferAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val NewTransferViewModel.state: NewTransferUiState get() = uiState.value

    @Test
    fun `the origin is the one proposed and the destination the first other one`() =
        runTest(dispatcher) {
            val model = model(initialFrom = LocationSamples.STALL.id)

            assertFalse(model.state.loading)
            assertEquals(LocationSamples.STALL.id, model.state.fromId)
            assertEquals(LocationSamples.MAIN.id, model.state.toId)
            assertNull(model.state.endError)
            assertFalse(model.state.dirty)
        }

    @Test
    fun `the picker offers products with their own book and no lots, once each`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, NewTransferAction.OpenPicker)
            assertEquals(listOf(7L, 8L), model.state.picker?.options?.map { it.id })

            model.act(this, NewTransferAction.AddProduct(7L))
            model.act(this, NewTransferAction.OpenPicker)
            model.act(this, NewTransferAction.QueryChange("que"))

            assertEquals(listOf(8L), model.state.picker?.options?.map { it.id })
            assertEquals(listOf(7L), model.state.lines.map { it.productId })
            assertTrue(model.state.dirty)
        }

    @Test
    fun `moving more than the origin has warns what would be left, without blocking`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, NewTransferAction.AddProduct(8L))
            val key = model.state.lines.single().key

            model.act(this, NewTransferAction.QuantityChange(key, "2,35"))

            // 1,5 kg menos 2,35 kg dejan −0,85 kg en el origen.
            assertEquals(QuantityMilli(-850L), model.state.lines.single().leftAtOrigin)
            model.act(this, NewTransferAction.Register)
            assertEquals(
                listOf(
                    StockTransferRequest(
                        LocationSamples.MAIN.id,
                        LocationSamples.STALL.id,
                        listOf(StockTransferLineRequest(8L, QuantityMilli(2_350L))),
                        "",
                    ),
                ),
                requests,
            )
            assertTrue(model.state.saved)
        }

    @Test
    fun `registering without lines says what is missing and writes nothing`() =
        runTest(dispatcher) {
            val model = model()

            model.act(this, NewTransferAction.Register)

            assertTrue(model.state.showNoLines)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `the same location at both ends is marked on the destination`() = runTest(dispatcher) {
        val model = model()
        model.act(this, NewTransferAction.AddProduct(7L))
        model.act(this, NewTransferAction.QuantityChange(model.state.lines.single().key, "1"))

        model.act(this, NewTransferAction.OpenEnd(TransferEnd.TO))
        model.act(this, NewTransferAction.ChooseEnd(LocationSamples.MAIN.id))
        model.act(this, NewTransferAction.Register)

        assertEquals(TransferEnd.TO, model.state.endError)
        assertNull(model.state.choosing)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `quantities are checked in the field before registering`() = runTest(dispatcher) {
        val model = model()
        model.act(this, NewTransferAction.AddProduct(7L))
        val key = model.state.lines.single().key

        model.act(this, NewTransferAction.Register)
        assertEquals(TransferLineError.NOT_A_NUMBER, model.state.lines.single().error)
        model.act(this, NewTransferAction.QuantityChange(key, "1,5"))
        assertEquals(TransferLineError.DECIMALS, model.state.lines.single().error)
        model.act(this, NewTransferAction.QuantityChange(key, "0"))
        assertEquals(TransferLineError.ZERO, model.state.lines.single().error)
        model.act(this, NewTransferAction.Register)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a rejected line is marked and a failure keeps everything`() = runTest(dispatcher) {
        result = RegisterStockTransferResult.LineRejected(
            0,
            StockTransferLineRejection.PRODUCT_ARCHIVED,
        )
        val model = model()
        model.act(this, NewTransferAction.AddProduct(7L))
        val key = model.state.lines.single().key
        model.act(this, NewTransferAction.QuantityChange(key, "2"))

        model.act(this, NewTransferAction.Register)
        assertEquals(TransferLineError.UNAVAILABLE, model.state.lines.single().error)

        model.act(this, NewTransferAction.QuantityChange(key, "3"))
        result = RegisterStockTransferResult.StorageFailed(StorageFailure.DISK_FULL)
        model.act(this, NewTransferAction.Register)

        assertEquals(TransferNotice.SaveFailed(outOfSpace = true), model.state.notice)
        assertEquals("3", model.state.lines.single().quantityText)
        assertFalse(model.state.saving)
    }

    @Test
    fun `an unknown zone asks to choose it`() = runTest(dispatcher) {
        result = RegisterStockTransferResult.UnknownTimeZone
        val model = model()
        model.act(this, NewTransferAction.AddProduct(7L))
        model.act(this, NewTransferAction.QuantityChange(model.state.lines.single().key, "1"))

        model.act(this, NewTransferAction.Register)

        assertEquals(TransferNotice.UnknownZone, model.state.notice)
    }

    @Test
    fun `what was written survives the process and closing asks before discarding`() =
        runTest(dispatcher) {
            val first = model()
            first.act(this, NewTransferAction.AddProduct(7L))
            first.act(this, NewTransferAction.QuantityChange(first.state.lines.single().key, "4"))
            first.act(this, NewTransferAction.Note("Para la feria"))

            val again = model(state = savedState)

            assertEquals("4", again.state.lines.single().quantityText)
            assertEquals("Para la feria", again.state.note)
            again.act(this, NewTransferAction.Close)
            assertTrue(again.state.discardAsked)
            again.act(this, NewTransferAction.ConfirmLeave)
            assertTrue(again.closed.value)
        }

    @Test
    fun `closing an empty form leaves without asking`() = runTest(dispatcher) {
        val model = model()

        model.act(this, NewTransferAction.Close)

        assertFalse(model.state.discardAsked)
        assertTrue(model.closed.value)
    }
}
