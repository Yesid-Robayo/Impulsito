package io.github.yesidrobayo.shopledger.ui.count

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountReview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountSessionView
import io.github.yesidrobayo.shopledger.domain.usecase.stock.FinishCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * Una sesión de conteo con respuestas fijas del dominio: el arroz contado igual, el aceite sin
 * contar, el queso por peso con faltante y el jabón con sobrante. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CountSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeCountSessionOperations()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): CountSessionViewModel =
        CountSessionViewModel(CountSamples.OPEN.id, operations, symbols).also { advanceUntilIdle() }

    private fun CountSessionViewModel.act(scope: TestScope, action: CountSessionAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private fun CountSessionViewModel.row(lineId: Long): CountRowUi =
        uiState.value.rows.single { it.lineId == lineId }

    @Test
    fun `the session reads its lines, its progress and hides nothing it should not`() =
        runTest(dispatcher) {
            val model = model()

            val state = model.uiState.value
            assertFalse(state.loading)
            assertEquals(listOf(RICE, OIL, CHEESE, SOAP), state.rows.map { it.lineId })
            assertEquals(3, state.counted)
            assertEquals(4, state.total)
            assertTrue(state.blind)
            assertTrue(state.showsLocation)
            assertEquals("2,1", model.row(CHEESE).text)
            assertEquals("", model.row(OIL).text)
        }

    @Test
    fun `a session that is gone says so, and a failed read offers to retry`() =
        runTest(dispatcher) {
            operations.view.value = null
            assertTrue(model().uiState.value.missing)

            operations.view.value = CountSamples.view()
            operations.failingReads = 1
            val model = model()
            assertTrue(model.uiState.value.readFailed)
            model.act(this, CountSessionAction.Retry)
            assertFalse(model.uiState.value.readFailed)
            assertEquals(4, model.uiState.value.total)
        }

    @Test
    fun `a typed count is saved as it is written`() = runTest(dispatcher) {
        val model = model()

        model.act(this, CountSessionAction.Count(OIL, "11"))

        assertEquals(listOf(OIL to QuantityMilli(11_000L)), operations.records)
        assertEquals(QuantityMilli(11_000L), model.row(OIL).counted)
        assertEquals(4, model.uiState.value.counted)
        // Borrar lo escrito vuelve a dejarlo sin contar.
        model.act(this, CountSessionAction.Count(OIL, ""))
        assertNull(model.row(OIL).counted)
    }

    @Test
    fun `decimals in whole units, a negative or text are marked and not saved`() =
        runTest(dispatcher) {
            val model = model()

            model.act(this, CountSessionAction.Count(OIL, "1,5"))
            assertEquals(CountRowError.DECIMALS, model.row(OIL).error)
            assertEquals("1,5", model.row(OIL).text)
            model.act(this, CountSessionAction.Count(OIL, "-2"))
            assertEquals(CountRowError.NEGATIVE, model.row(OIL).error)
            model.act(this, CountSessionAction.Count(OIL, "abc"))
            assertEquals(CountRowError.NOT_A_NUMBER, model.row(OIL).error)

            assertTrue(operations.records.isEmpty())
            // Por peso, los decimales sí valen.
            model.act(this, CountSessionAction.Count(CHEESE, "2,35"))
            assertEquals(QuantityMilli(2_350L), model.row(CHEESE).counted)
            assertNull(model.row(CHEESE).error)
        }

    @Test
    fun `a rejection from the domain marks the row and a failed save keeps the text`() =
        runTest(dispatcher) {
            val model = model()
            operations.recordResult = RecordCountResult.AboveLimit
            model.act(this, CountSessionAction.Count(OIL, "7"))
            assertEquals(CountRowError.TOO_HIGH, model.row(OIL).error)

            operations.recordResult = RecordCountResult.StorageFailed(StorageFailure.DISK_FULL)
            model.act(this, CountSessionAction.Count(OIL, "8"))

            assertEquals(CountNotice.SaveFailed(outOfSpace = true), model.uiState.value.notice)
            assertEquals("8", model.row(OIL).text)
        }

    @Test
    fun `stepping starts at one, adds one and never goes below zero`() = runTest(dispatcher) {
        val model = model()

        model.act(this, CountSessionAction.Step(OIL, up = true))
        assertEquals(QuantityMilli(1_000L), model.row(OIL).counted)
        model.act(this, CountSessionAction.Step(OIL, up = true))
        assertEquals(QuantityMilli(2_000L), model.row(OIL).counted)
        model.act(this, CountSessionAction.Step(RICE, up = false))
        assertEquals(QuantityMilli(7_000L), model.row(RICE).counted)

        operations.view.value = operations.withCount(SOAP, QuantityMilli.ZERO)
        advanceUntilIdle()
        val before = operations.records.size
        model.act(this, CountSessionAction.Step(SOAP, up = false))
        assertEquals(before, operations.records.size)
    }

    @Test
    fun `each scan counts one unit, even of a row the filter hides`() = runTest(dispatcher) {
        val model = model()
        assertFalse(model.uiState.value.canScan)
        model.act(this, CountSessionAction.Filter(CountFilter.DONE))

        model.act(this, CountSessionAction.Scanned(CountSamples.OIL.id, times = 3))
        model.act(this, CountSessionAction.Scanned(CountSamples.RICE.id))

        assertEquals(
            listOf(OIL to QuantityMilli(3_000L), RICE to QuantityMilli(9_000L)),
            operations.records,
        )
    }

    @Test
    fun `a scan of a weighed product finds it to write, and an unknown one changes nothing`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, CountSessionAction.Filter(CountFilter.PENDING))

            model.act(this, CountSessionAction.Scanned(CountSamples.CHEESE.id))
            assertEquals(listOf(CHEESE), model.uiState.value.rows.map { it.lineId })
            assertNull(model.uiState.value.filter)
            model.act(this, CountSessionAction.Scanned(UNKNOWN_PRODUCT))

            assertTrue(operations.records.isEmpty())
        }

    @Test
    fun `the scanner is offered when a product of the session has a code`() = runTest(dispatcher) {
        operations.view.value = CountSamples.view().let { view ->
            view.copy(
                lines = view.lines.map { item ->
                    val product = requireNotNull(item.product)
                    item.copy(
                        product = product.copy(
                            details = product.details.copy(barcode = "2000000000015"),
                        ),
                    )
                },
            )
        }

        assertTrue(model().uiState.value.canScan)
    }

    @Test
    fun `the search and the chips narrow the rows`() = runTest(dispatcher) {
        val model = model()

        model.act(this, CountSessionAction.Filter(CountFilter.PENDING))
        assertEquals(listOf(OIL), model.uiState.value.rows.map { it.lineId })
        model.act(this, CountSessionAction.Filter(CountFilter.DONE))
        assertEquals(listOf(RICE, CHEESE, SOAP), model.uiState.value.rows.map { it.lineId })
        model.act(this, CountSessionAction.Query("JABON"))
        assertEquals(listOf(SOAP), model.uiState.value.rows.map { it.lineId })
        // El progreso cuenta toda la sesión, no solo lo que se ve.
        assertEquals(3, model.uiState.value.counted)
    }

    @Test
    fun `the review shows every line, marks what moved and back keeps the filter`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, CountSessionAction.Filter(CountFilter.PENDING))

            model.act(this, CountSessionAction.Review)

            val state = model.uiState.value
            assertTrue(state.reviewing)
            assertEquals(4, state.rows.size)
            assertEquals(CountReviewUi(2, Money(11_400L), Money(950L), 1), state.review)
            assertTrue(model.row(SOAP).moved)
            assertFalse(model.row(RICE).moved)

            model.act(this, CountSessionAction.Back)
            assertFalse(model.uiState.value.reviewing)
            assertEquals(listOf(OIL), model.uiState.value.rows.map { it.lineId })
        }

    @Test
    fun `applying asks first and goes back with the adjustments`() = runTest(dispatcher) {
        val model = model()
        model.act(this, CountSessionAction.Review)

        model.act(this, CountSessionAction.AskApply)
        assertEquals(CountDialog.APPLY, model.uiState.value.dialog)
        assertEquals(0, operations.applied)
        model.act(this, CountSessionAction.Confirm)

        assertEquals(1, operations.applied)
        assertEquals(CountFinished.Applied(2), model.uiState.value.finished)
        model.act(this, CountSessionAction.FinishedShown)
        assertNull(model.uiState.value.finished)
    }

    @Test
    fun `without differences the count closes without asking`() = runTest(dispatcher) {
        operations.view.value = CountSamples.view().let { view ->
            view.copy(lines = view.lines.filter { it.line.id == RICE || it.line.id == OIL })
        }
        operations.applyResult = FinishCountResult.Applied(0)
        val model = model()
        model.act(this, CountSessionAction.Review)

        model.act(this, CountSessionAction.AskApply)

        assertNull(model.uiState.value.dialog)
        assertEquals(1, operations.applied)
        assertEquals(CountFinished.Applied(0), model.uiState.value.finished)
    }

    @Test
    fun `a failed apply keeps the session open with its reason`() = runTest(dispatcher) {
        val expected = mapOf(
            FinishCountResult.StorageFailed(StorageFailure.DISK_FULL) to
                CountNotice.ApplyFailed(outOfSpace = true),
            FinishCountResult.UnknownTimeZone to CountNotice.UnknownZone,
            FinishCountResult.CurrencyUnconfirmed to CountNotice.CurrencyUnconfirmed,
            FinishCountResult.AlreadyFinished to CountNotice.ApplyFailed(outOfSpace = false),
        )
        val model = model()
        model.act(this, CountSessionAction.Review)
        for ((result, notice) in expected) {
            operations.applyResult = result

            model.act(this, CountSessionAction.AskApply)
            model.act(this, CountSessionAction.Confirm)

            val state = model.uiState.value
            assertEquals(notice, state.notice)
            assertFalse(state.working)
            assertNull(state.finished)
            assertTrue(state.reviewing)
        }
    }

    @Test
    fun `cancelling asks and goes back without applying`() = runTest(dispatcher) {
        val model = model()

        model.act(this, CountSessionAction.AskCancel)
        assertEquals(CountDialog.CANCEL, model.uiState.value.dialog)
        model.act(this, CountSessionAction.DismissDialog)
        assertNull(model.uiState.value.dialog)
        model.act(this, CountSessionAction.AskCancel)
        model.act(this, CountSessionAction.Confirm)

        assertEquals(1, operations.cancelled)
        assertEquals(0, operations.applied)
        assertEquals(CountFinished.Cancelled, model.uiState.value.finished)
    }

    @Test
    fun `a finished session is only read`() = runTest(dispatcher) {
        operations.view.value = CountSamples.view(CountSamples.APPLIED)
        val model = model()
        assertTrue(model.uiState.value.readOnly)
        assertEquals(CountSessionStatus.APPLIED, model.uiState.value.session?.status)

        model.act(this, CountSessionAction.Count(OIL, "3"))
        model.act(this, CountSessionAction.Step(OIL, up = true))
        model.act(this, CountSessionAction.AskApply)

        assertTrue(operations.records.isEmpty())
        assertEquals(0, operations.applied)
    }

    private class FakeCountSessionOperations : CountSessionOperations {
        val view = MutableStateFlow<CountSessionView?>(CountSamples.view())
        var failingReads = 0
        var recordResult: RecordCountResult = RecordCountResult.Recorded
        var applyResult: FinishCountResult = FinishCountResult.Applied(2)
        val records = mutableListOf<Pair<Long, QuantityMilli?>>()
        var applied = 0
        var cancelled = 0

        fun withCount(lineId: Long, counted: QuantityMilli?): CountSessionView? =
            view.value?.let { current ->
                current.copy(
                    lines = current.lines.map { item ->
                        if (item.line.id == lineId) {
                            item.copy(line = item.line.copy(counted = counted))
                        } else {
                            item
                        }
                    },
                )
            }

        override fun session(sessionId: Long): Flow<CountSessionView?> = flow {
            if (failingReads > 0) {
                failingReads--
                error("Lectura fallida a propósito")
            }
            view.collect { emit(it) }
        }

        override fun showsLocations(): Flow<Boolean> = flowOf(true)

        override suspend fun record(
            sessionId: Long,
            lineId: Long,
            counted: QuantityMilli?,
        ): RecordCountResult {
            records += lineId to counted
            if (recordResult == RecordCountResult.Recorded) view.value = withCount(lineId, counted)
            return recordResult
        }

        override suspend fun review(view: CountSessionView): CountReview = CountReview(
            adjustments = 2,
            surplus = Money(11_400L),
            shortage = Money(950L),
            moved = setOf(CountSamples.SOAP.id),
        )

        override suspend fun apply(sessionId: Long): FinishCountResult {
            applied++
            return applyResult
        }

        override suspend fun cancel(sessionId: Long): FinishCountResult {
            cancelled++
            return FinishCountResult.Cancelled
        }
    }

    private companion object {
        const val RICE = 101L
        const val OIL = 102L
        const val CHEESE = 103L
        const val SOAP = 104L
        const val UNKNOWN_PRODUCT = 999L
    }
}
