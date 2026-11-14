package io.github.yesidrobayo.shopledger.ui.lot

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.LotsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetExpiryNoticeDaysResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectLotResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** La pantalla de lotes con respuestas fijas del dominio. Lotes y fechas inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class LotsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(productId: Long? = null, openLotId: Long? = null): LotsViewModel {
        val model = LotsViewModel(operations, dispatcher, productId, openLotId)
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun LotsViewModel.act(scope: TestScope, action: LotsAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private fun LotsViewModel.ready(): LotsReady =
        (uiState.value.content as LotsContent.Ready).ready

    @Test
    fun `opens on what expires soon, with its count, value and the lot places`() =
        runTest(dispatcher) {
            val model = model()

            assertEquals(LotFilter.EXPIRING, model.uiState.value.filter)
            val ready = model.ready()
            assertEquals(2, ready.expiringCount)
            // Queso: 9,5 kg valen 91.200; 5,5 kg y 2 kg salen con 52.800 y 19.200.
            assertEquals(Money(72_000L), ready.expiringValue)
            assertEquals(listOf(listOf(1L, 2L)), ready.groups.map { g -> g.lots.map { it.lotId } })
            assertEquals(2, ready.groups.single().lots.first().places.size)
            assertTrue(ready.showsLocations)
            assertEquals(LotSamples.TODAY, ready.today)
        }

    @Test
    fun `each filter shows its lots, and from a product it opens on all`() = runTest(dispatcher) {
        val model = model()
        model.act(this, LotsAction.SelectFilter(LotFilter.EXPIRED))
        assertEquals(listOf(3L), model.ready().groups.flatMap { g -> g.lots.map { it.lotId } })
        model.act(this, LotsAction.SelectFilter(LotFilter.UNASSIGNED))
        assertEquals(listOf(5L), model.ready().groups.flatMap { g -> g.lots.map { it.lotId } })

        val fromProduct = model(productId = LotSamples.OIL.id)
        assertEquals(LotFilter.ALL, fromProduct.uiState.value.filter)
        assertEquals(LotSamples.OIL.id, operations.askedProduct)
    }

    @Test
    fun `without any coded lot it says how to start, and a failed read can be retried`() =
        runTest(dispatcher) {
            operations.overview.value = LotSamples.EMPTY
            val model = model()
            assertTrue(!model.ready().hasLots)

            operations.failingReads = 1
            model.act(this, LotsAction.Retry)
            assertEquals(LotsContent.ReadError, model.uiState.value.content)
            model.act(this, LotsAction.Retry)
            assertTrue(model.uiState.value.content is LotsContent.Ready)
        }

    @Test
    fun `a lot opened on arrival shows its latest movements`() = runTest(dispatcher) {
        val model = model(openLotId = 1L)

        val sheet = model.uiState.value.sheet as LotSheet.Detail
        assertEquals(1L, sheet.lotId)
        assertEquals(listOf(LotSamples.CHEESE.id to 1L), operations.movementReads)
        assertEquals(LotSamples.MOVEMENTS.size, sheet.movements.size)
    }

    @Test
    fun `a new expiry is saved with the same code, and a failure keeps the date`() =
        runTest(dispatcher) {
            val model = model()
            val date = requireNotNull(BusinessDate.parse("2026-12-01"))
            model.act(this, LotsAction.StartFixExpiry(1L))
            model.act(this, LotsAction.ExpiryChange(date))

            operations.correction = CorrectLotResult.Failed(StorageFailure.DISK_FULL)
            model.act(this, LotsAction.SaveSheet)
            val failed = model.uiState.value.sheet as LotSheet.FixExpiry
            assertTrue(failed.failed)
            assertEquals(date, failed.date)

            operations.correction = CorrectLotResult.Saved
            model.act(this, LotsAction.SaveSheet)
            assertEquals(Triple(1L, "A-118", date), operations.corrections.last())
            assertNull(model.uiState.value.sheet)
            assertEquals(LotsMessage.LOT_SAVED, model.uiState.value.message)
        }

    @Test
    fun `a repeated, blank or long code keeps what was written and says why`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, LotsAction.StartFixCode(1L))
            model.act(this, LotsAction.CodeChange("A-121"))

            operations.correction = CorrectLotResult.CodeRepeated("Queso")
            model.act(this, LotsAction.SaveSheet)
            val repeated = model.uiState.value.sheet as LotSheet.FixCode
            assertEquals(LotCodeError.REPEATED, repeated.error)
            assertEquals("Queso", repeated.repeatedIn)
            assertEquals("A-121", repeated.text)

            operations.correction = CorrectLotResult.InvalidCode(LotCodeCheck.Blank)
            model.act(this, LotsAction.SaveSheet)
            assertEquals(LotCodeError.BLANK, (model.uiState.value.sheet as LotSheet.FixCode).error)
            operations.correction = CorrectLotResult.InvalidCode(LotCodeCheck.TooLong(60))
            model.act(this, LotsAction.SaveSheet)
            val long = model.uiState.value.sheet as LotSheet.FixCode
            assertEquals(LotCodeError.TOO_LONG, long.error)
        }

    @Test
    fun `the notice days are saved, and an empty value is rejected before saving`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, LotsAction.OpenNotice)
            assertEquals("30", (model.uiState.value.sheet as LotSheet.Notice).text)

            model.act(this, LotsAction.NoticeChange(""))
            model.act(this, LotsAction.SaveSheet)
            assertTrue((model.uiState.value.sheet as LotSheet.Notice).invalid)
            assertTrue(operations.noticeDays.isEmpty())

            model.act(this, LotsAction.NoticeChange("1a5"))
            model.act(this, LotsAction.SaveSheet)
            assertEquals(listOf(15), operations.noticeDays)
            assertEquals(LotsMessage.NOTICE_SAVED, model.uiState.value.message)
            model.act(this, LotsAction.MessageShown)
            assertNull(model.uiState.value.message)
        }

    private companion object {
        /** Una venta de 0,5 kg del lote 1, como la guarda el libro. */
        val RECORD = StockMovementRecord(
            movement = StockMovement(
                id = 12L,
                key = StockKey(LotSamples.CHEESE.id, 1L, 1L),
                type = StockMovementType.SALE,
                quantity = QuantityMilli(-500L),
                reversesMovementId = null,
            ),
            reason = null,
            unitCost = Money(3_800L),
            totalCost = Money(1_900L),
            balanceAfter = QuantityMilli(9_000L),
            averageCostAfter = Money(3_800L),
            stockValueAfter = Money(34_200L),
            source = StockMovementSource.NONE,
            note = null,
            occurredAt = Instant.parse("2026-11-14T12:00:00Z"),
            businessDate = LotSamples.TODAY,
            createdAt = Instant.parse("2026-11-14T12:00:00Z"),
        )
    }

    private class FakeOperations : LotsOperations {
        val overview = MutableStateFlow(LotSamples.OVERVIEW)
        var failingReads = 0
        var askedProduct: Long? = null
        val movementReads = mutableListOf<Pair<Long, Long>>()
        var correction: CorrectLotResult = CorrectLotResult.Saved
        val corrections = mutableListOf<Triple<Long, String, BusinessDate?>>()
        val noticeDays = mutableListOf<Int>()

        override fun lots(productId: Long?): Flow<LotsOverview> {
            askedProduct = productId
            if (failingReads > 0) {
                failingReads--
                return flow { throw IllegalStateException("lectura fallida de prueba") }
            }
            return overview
        }

        override fun units(): Flow<List<MeasureUnit>> = flowOf(LotSamples.UNITS)

        override fun locations(): Flow<List<Location>> = flowOf(LotSamples.LOCATIONS)

        override fun calendar(): Flow<BusinessCalendarResult> =
            flowOf(BusinessCalendar.of("UTC", DayOfWeek.MONDAY))

        // El 14 de noviembre de 2026 a mediodía, en la zona del negocio.
        override fun now(): Instant = Instant.parse("2026-11-14T12:00:00Z")

        override fun movements(productId: Long, lotId: Long): Flow<List<StockMovementRecord>> {
            movementReads += productId to lotId
            return flowOf(List(LotSamples.MOVEMENTS.size) { RECORD })
        }

        override suspend fun correct(
            lotId: Long,
            code: String,
            expiryDate: BusinessDate?,
        ): CorrectLotResult {
            corrections += Triple(lotId, code, expiryDate)
            return correction
        }

        override suspend fun setNoticeDays(days: Int): SetExpiryNoticeDaysResult {
            noticeDays += days
            return SetExpiryNoticeDaysResult.Saved
        }
    }
}
