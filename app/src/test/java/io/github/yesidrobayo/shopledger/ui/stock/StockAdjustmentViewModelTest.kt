package io.github.yesidrobayo.shopledger.ui.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentSubject
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** El formulario del ajuste: lo que muestra en vivo, lo que pide y cómo termina. */
@OptIn(ExperimentalCoroutinesApi::class)
class StockAdjustmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeOperations()
    private val numbers =
        ProductNumberFormat(SYMBOLS, currencyDecimals = 0, quantitySymbols = SYMBOLS)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun viewModel() = StockAdjustmentViewModel(operations, numbers, productId = 1L)

    @Test
    fun `a product with movements opens on the count, and shows the result of the example`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            assertEquals(AdjustmentMode.COUNT, model.uiState.value.mode)

            model.onAction(StockAdjustmentAction.QuantityChanged("10"))

            assertEquals(
                AdjustmentPreview(QuantityMilli(10_000L), QuantityMilli(-2_000L), Money(-2_710L)),
                model.uiState.value.preview,
            )
        }

    @Test
    fun `a failed read says so, keeps what was written, and retry reads again`() =
        runTest(dispatcher) {
            operations.failingReads = 1
            val model = viewModel()
            model.onAction(StockAdjustmentAction.QuantityChanged("10"))
            advanceUntilIdle()

            assertEquals(StockAdjustmentContent.ReadFailed, model.uiState.value.content)

            model.onAction(StockAdjustmentAction.Retry)
            advanceUntilIdle()

            assertTrue(model.uiState.value.content is StockAdjustmentContent.Ready)
            assertEquals("10", model.uiState.value.quantityText)
        }

    @Test
    fun `a product without movements opens on the opening balance`() = runTest(dispatcher) {
        operations.subject.value = previewSubject(onHand = 0L, value = 0L, acceptsOpening = true)
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(AdjustmentMode.OPENING, model.uiState.value.mode)

        model.onAction(StockAdjustmentAction.QuantityChanged("24"))
        model.onAction(StockAdjustmentAction.UnitCostChanged("1.200"))
        assertEquals(Money(28_800L), model.uiState.value.preview?.value)

        model.onAction(StockAdjustmentAction.Save)
        advanceUntilIdle()

        assertEquals(Triple(1L, QuantityMilli(24_000L), Money(1_200L)), operations.opening)
        assertEquals(AdjustmentOutcome.Saved(QuantityMilli(24_000L)), model.uiState.value.outcome)
    }

    @Test
    fun `once movements exist the opening tab falls back to the count`() = runTest(dispatcher) {
        operations.subject.value = previewSubject(acceptsOpening = true)
        val model = viewModel()
        advanceUntilIdle()

        operations.subject.value = previewSubject(acceptsOpening = false)
        advanceUntilIdle()

        assertEquals(AdjustmentMode.COUNT, model.uiState.value.mode)
    }

    @Test
    fun `an exit with its reason goes out with the written quantity`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.ModeChanged(AdjustmentMode.CHANGE))
        model.onAction(StockAdjustmentAction.ReasonChanged(StockAdjustmentReason.EXPIRED))
        model.onAction(StockAdjustmentAction.QuantityChanged("3"))
        assertEquals(QuantityMilli(-3_000L), model.uiState.value.preview?.difference)

        model.onAction(StockAdjustmentAction.Save)
        advanceUntilIdle()

        assertEquals(
            StockAdjustmentRequest.Change(
                1L,
                StockAdjustmentReason.EXPIRED,
                QuantityMilli(3_000L),
                increase = false,
                note = "",
            ),
            operations.requests.single(),
        )
        assertEquals(AdjustmentOutcome.Saved(QuantityMilli(9_000L)), model.uiState.value.outcome)
    }

    @Test
    fun `another reason asks for a note before calling the domain`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.ModeChanged(AdjustmentMode.CHANGE))
        model.onAction(StockAdjustmentAction.ReasonChanged(StockAdjustmentReason.OTHER))
        assertTrue(model.uiState.value.showsDirection)
        model.onAction(StockAdjustmentAction.DirectionChanged(increase = true))
        model.onAction(StockAdjustmentAction.QuantityChanged("1"))
        assertEquals(QuantityMilli(13_000L), model.uiState.value.preview?.left)

        model.onAction(StockAdjustmentAction.Save)
        advanceUntilIdle()

        assertEquals(
            AdjustmentProblem.NoteRequired,
            model.uiState.value.errors[AdjustmentField.NOTE],
        )
        assertTrue(operations.requests.isEmpty())
        // Lo escrito sigue ahí.
        assertEquals("1", model.uiState.value.quantityText)
    }

    @Test
    fun `a reason that only removes forgets the entry direction`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.ModeChanged(AdjustmentMode.CHANGE))
        model.onAction(StockAdjustmentAction.ReasonChanged(StockAdjustmentReason.OTHER))
        model.onAction(StockAdjustmentAction.DirectionChanged(increase = true))

        model.onAction(StockAdjustmentAction.ReasonChanged(StockAdjustmentReason.DAMAGED))

        assertFalse(model.uiState.value.increase)
        assertFalse(model.uiState.value.showsDirection)
    }

    @Test
    fun `quantities are checked against the unit`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onAction(StockAdjustmentAction.Save)
        assertEquals(
            AdjustmentProblem.NotANumber,
            model.uiState.value.errors[AdjustmentField.QUANTITY],
        )

        model.onAction(StockAdjustmentAction.QuantityChanged("1,5"))
        model.onAction(StockAdjustmentAction.Save)
        assertEquals(
            AdjustmentProblem.WholeUnitsOnly,
            model.uiState.value.errors[AdjustmentField.QUANTITY],
        )

        model.onAction(StockAdjustmentAction.ModeChanged(AdjustmentMode.CHANGE))
        model.onAction(StockAdjustmentAction.QuantityChanged("0"))
        model.onAction(StockAdjustmentAction.Save)
        assertEquals(AdjustmentProblem.Zero, model.uiState.value.errors[AdjustmentField.QUANTITY])
        advanceUntilIdle()
        assertTrue(operations.requests.isEmpty())
    }

    @Test
    fun `a count equal to the ledger closes with no differences`() = runTest(dispatcher) {
        operations.result = StockAdjustmentResult.NoDifference
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.QuantityChanged("12"))
        assertEquals(QuantityMilli.ZERO, model.uiState.value.preview?.difference)

        model.onAction(StockAdjustmentAction.Save)
        advanceUntilIdle()

        assertEquals(AdjustmentOutcome.NoDifference, model.uiState.value.outcome)
    }

    @Test
    fun `a storage failure keeps what was written and says so`() = runTest(dispatcher) {
        operations.result = StockAdjustmentResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.QuantityChanged("8"))

        model.onAction(StockAdjustmentAction.Save)
        advanceUntilIdle()

        val state = model.uiState.value
        assertTrue(state.saveFailed)
        assertNull(state.outcome)
        assertFalse(state.saving)
        assertEquals("8", state.quantityText)
    }

    @Test
    fun `a missing product shows that nothing can be adjusted`() = runTest(dispatcher) {
        operations.subject.value = null
        val model = viewModel()
        advanceUntilIdle()

        assertEquals(StockAdjustmentContent.Unavailable, model.uiState.value.content)
    }

    @Test
    fun `fixing the cost leaves what was written for when it comes back`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.QuantityChanged("8"))
        val before = model.uiState.value

        model.onAction(StockAdjustmentAction.FixCost)
        advanceUntilIdle()

        assertEquals(before, model.uiState.value)
    }

    @Test
    fun `closing with something written asks first`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onAction(StockAdjustmentAction.QuantityChanged("8"))

        model.onAction(StockAdjustmentAction.Close)
        assertTrue(model.uiState.value.confirmingDiscard)
        model.onAction(StockAdjustmentAction.KeepEditing)
        assertFalse(model.uiState.value.closed)
        model.onAction(StockAdjustmentAction.Close)
        model.onAction(StockAdjustmentAction.Discard)

        assertTrue(model.uiState.value.closed)
    }

    @Test
    fun `with two locations the count is compared with the chosen one and saved there`() =
        runTest(dispatcher) {
            operations.locations.value = listOf(location(1L, "MAIN"), location(2L, null))
            operations.here = mapOf(2L to QuantityMilli(3_000L))
            val model = viewModel()
            advanceUntilIdle()
            // Sin elegir, se ajusta la inicial: el conteo se compara con lo que hay en ella.
            assertEquals(1L, model.uiState.value.locationId)
            assertEquals(listOf(1L, 2L), model.uiState.value.locations.map { it.id })

            model.onAction(StockAdjustmentAction.LocationChanged(2L))
            model.onAction(StockAdjustmentAction.QuantityChanged("10"))
            advanceUntilIdle()

            assertEquals(QuantityMilli(7_000L), model.uiState.value.preview?.difference)
            model.onAction(StockAdjustmentAction.Save)
            advanceUntilIdle()
            assertEquals(2L, operations.requests.single().locationId)
        }

    @Test
    fun `with a single location nothing is offered and the request names none`() =
        runTest(dispatcher) {
            operations.locations.value = listOf(location(1L, "MAIN"))
            val model = viewModel()
            advanceUntilIdle()
            model.onAction(StockAdjustmentAction.QuantityChanged("10"))
            model.onAction(StockAdjustmentAction.Save)
            advanceUntilIdle()

            assertTrue(model.uiState.value.locations.isEmpty())
            assertEquals(null, operations.requests.single().locationId)
        }

    private fun location(id: Long, systemKey: String?) = Location(
        id = id,
        systemKey = systemKey,
        details = LocationDetails(
            if (systemKey == null) "Bodega" else null,
            if (systemKey == null) "bodega" else null,
            LocationKind.WAREHOUSE,
        ),
        archivedAt = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private class FakeOperations : StockAdjustmentOperations {
        val subject = MutableStateFlow<StockAdjustmentSubject?>(previewSubject())
        val locations = MutableStateFlow<List<Location>>(emptyList())

        /** Lo que hay en cada ubicación; sin dato, el stock del producto. */
        var here: Map<Long, QuantityMilli> = emptyMap()
        val requests = mutableListOf<StockAdjustmentRequest>()
        var result: StockAdjustmentResult =
            StockAdjustmentResult.Adjusted(movementId = 7L, onHandAfter = QuantityMilli(9_000L))
        var opening: Triple<Long, QuantityMilli, Money>? = null

        /** Cuántas lecturas del producto fallan antes de responder. */
        var failingReads = 0

        override fun subject(productId: Long): Flow<StockAdjustmentSubject?> =
            if (failingReads > 0) {
                failingReads--
                flow { throw IllegalStateException("lectura fallida de prueba") }
            } else {
                subject
            }

        override fun subject(productId: Long, locationId: Long?): Flow<StockAdjustmentSubject?> =
            subject(productId).map { read ->
                val at = locationId?.let { here[it] }
                if (read == null || at == null) read else read.copy(here = at)
            }

        override fun locations(): Flow<List<Location>> = locations

        override suspend fun adjust(request: StockAdjustmentRequest): StockAdjustmentResult {
            requests += request
            return result
        }

        override suspend fun registerOpening(
            productId: Long,
            quantity: QuantityMilli,
            unitCost: Money,
        ): OpeningBalanceResult {
            opening = Triple(productId, quantity, unitCost)
            return OpeningBalanceResult.Registered(8L)
        }
    }

    private companion object {
        val SYMBOLS = NumberSymbols(',', '-', DigitGrouping('.', 3, 3))
    }
}
