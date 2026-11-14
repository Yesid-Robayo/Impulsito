package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotHolding
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetExpiryNoticeDaysResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetExpiryNoticeDaysUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture.Companion.PRODUCT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La pantalla de lotes: la lista con lo de cada ubicación, los lotes para elegir al sacar,
 * corregir un lote y los días de aviso. El reloj de prueba marca el 2023-11-14 en la zona del
 * negocio, y los ajustes avisan 30 días antes. Productos, lotes y fechas inventados.
 */
class LotScreenUseCasesTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val observe =
        ObserveLotsUseCase(
            catalog.lots,
            catalog.products,
            calendar,
            catalog.settings,
            catalog.clock,
        )
    private val choices = LoadLotChoicesUseCase(
        catalog.lots,
        catalog.products,
        catalog.systemRows,
        calendar,
        catalog.settings,
        catalog.clock,
    )
    private val correct = CorrectLotUseCase(catalog.transactions, catalog.lots, catalog.products)
    private val movements = ObserveLotMovementsUseCase(catalog.ledger)

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    /**
     * Un lote del producto con [units] en [locationId], o en la inicial, que vence en [expiry].
     */
    private suspend fun lot(
        code: String,
        expiry: String?,
        units: Long = 2_000L,
        locationId: Long? = null,
    ): Long {
        val lot = fixture.seedLot(PRODUCT_ID, code, expiry)
        if (units > 0L) fixture.receiveIntoLot(PRODUCT_ID, lot, units, 1_000L, locationId)
        return lot
    }

    @Test
    fun `lists each lot with its stock per location and when it expires`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val split = lot("A-1", "2023-11-20")
        fixture.receiveIntoLot(PRODUCT_ID, split, 3_000L, 1_000L, SECOND_LOCATION)
        val plain = lot("A-2", null, units = 0L)

        val overview = observe().first()

        assertEquals(30, overview.noticeDays)
        assertEquals(listOf(split, plain), overview.entries.map { it.lot.id })
        val first = overview.entries.first()
        assertEquals(QuantityMilli(5_000L), first.onHand)
        assertEquals(
            listOf(
                LotHolding(split, CatalogFixture.MAIN_LOCATION_ID, QuantityMilli(2_000L)),
                LotHolding(split, SECOND_LOCATION, QuantityMilli(3_000L)),
            ),
            first.byLocation,
        )
        assertEquals(6, first.due?.daysLeft)
        assertTrue(requireNotNull(first.due).soon)
        assertNull(overview.entries.last().due)
        assertEquals(QuantityMilli.ZERO, overview.entries.last().onHand)
    }

    @Test
    fun `filters by product, and an archived product or an unknown zone hide or unmark`() =
        runTest {
            fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
            lot("B-1", "2023-11-10")
            assertEquals(1, observe(PRODUCT_ID).first().entries.size)
            assertTrue(observe(PRODUCT_ID + 1).first().entries.isEmpty())

            catalog.settings.settings.value = catalog.settings.settings.value.let {
                it.copy(regional = it.regional.copy(businessTimeZoneId = "Mars/Olympus_Mons"))
            }
            val unmarked = observe().first().entries.single()
            assertNull(unmarked.due?.daysLeft)

            catalog.database.seed { tables ->
                val product = requireNotNull(tables.products[PRODUCT_ID])
                val archived = product.copy(archivedAt = CatalogFixture.NOW)
                tables.copy(products = tables.products + (PRODUCT_ID to archived))
            }
            assertTrue(observe().first().entries.isEmpty())
        }

    @Test
    fun `choices come in exit order with the unassigned lot apart`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val later = lot("C-2", "2023-12-20")
        val expired = lot("C-1", "2023-11-01")
        lot("C-3", "2023-11-02", units = 0L)
        val elsewhere = lot("C-4", "2023-11-03", locationId = SECOND_LOCATION)
        fixture.adjust(
            StockAdjustmentRequest.Change(
                PRODUCT_ID,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(1_000L),
            ),
        )

        val here = requireNotNull(choices(PRODUCT_ID, null))

        assertEquals(listOf(expired, later), here.lots.map { it.stock.lotId })
        assertTrue(requireNotNull(here.proposed?.due).isExpired)
        assertEquals(QuantityMilli(-1_000L), here.unassigned?.onHand)
        val there = requireNotNull(choices(PRODUCT_ID, SECOND_LOCATION))
        assertEquals(listOf(elsewhere), there.lots.map { it.stock.lotId })
        assertNull(choices(PRODUCT_ID + 1, null))
    }

    @Test
    fun `corrects the code and the date, and a new date notices again`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = lot("K-1", "2023-11-20")
        catalog.lots.markExpiryNotified(listOf(lot), CatalogFixture.NOW)

        assertEquals(CorrectLotResult.Saved, correct(lot, "  K-1b ", date("2023-11-20")))
        val renamed = catalog.tables.lots.getValue(lot)
        assertEquals("K-1b", renamed.code)
        assertEquals(CatalogFixture.NOW, renamed.expiryNotifiedAt)

        assertEquals(CorrectLotResult.Saved, correct(lot, "K-1b", date("2023-11-28")))
        val moved = catalog.tables.lots.getValue(lot)
        assertEquals(date("2023-11-28"), moved.expiryDate)
        assertNull(moved.expiryNotifiedAt)

        // Lo mismo no escribe nada.
        val before = catalog.tables
        assertEquals(CorrectLotResult.Saved, correct(lot, "K-1b", date("2023-11-28")))
        assertEquals(before, catalog.tables)
    }

    @Test
    fun `rejects a blank or repeated code, the unassigned lot and a failed write`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = lot("E-1", null)
        lot("E-2", null)

        assertEquals(CorrectLotResult.InvalidCode(LotCodeCheck.Blank), correct(lot, " ", null))
        assertEquals(
            CorrectLotResult.InvalidCode(LotCodeCheck.TooLong(60)),
            correct(lot, "x".repeat(61), null),
        )
        val repeated = correct(lot, "E-2", null)
        assertEquals(
            CorrectLotResult.CodeRepeated(fixture.product(PRODUCT_ID).details.name),
            repeated,
        )
        assertEquals(CorrectLotResult.NotFound, correct(999L, "E-9", null))

        fixture.adjust(
            StockAdjustmentRequest.Change(
                PRODUCT_ID,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(1_000L),
            ),
        )
        val unassigned = catalog.tables.lots.values.single { it.isUnassigned }.id
        assertEquals(CorrectLotResult.NotFound, correct(unassigned, "E-3", null))

        catalog.transactions.restoreInProgress = true
        assertTrue(correct(lot, "E-4", null) is CorrectLotResult.Failed)
        assertEquals("E-1", catalog.tables.lots.getValue(lot).code)
    }

    @Test
    fun `the movements of a lot come newest first and only from that lot`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = lot("F-1", null)
        lot("F-2", null)
        fixture.adjust(
            StockAdjustmentRequest.Change(
                PRODUCT_ID,
                StockAdjustmentReason.EXPIRED,
                QuantityMilli(1_000L),
                lotId = lot,
            ),
        )

        val rows = movements(PRODUCT_ID, lot).first()

        assertEquals(listOf(-1_000L, 2_000L), rows.map { it.movement.quantity.milli })
    }

    @Test
    fun `sets the notice days once, and rejects them out of range or on a failed write`() =
        runTest {
            val set = SetExpiryNoticeDaysUseCase(
                catalog.transactions,
                catalog.settings,
                catalog.clock,
            )

            assertEquals(SetExpiryNoticeDaysResult.Saved, set(10))
            assertEquals(10, catalog.settings.get().notices.expiryNoticeDays)
            assertEquals(SetExpiryNoticeDaysResult.Saved, set(10))
            assertEquals(SetExpiryNoticeDaysResult.OutOfRange, set(-1))
            assertEquals(SetExpiryNoticeDaysResult.OutOfRange, set(36_501))

            catalog.transactions.restoreInProgress = true
            assertTrue(set(5) is SetExpiryNoticeDaysResult.Failed)
            assertEquals(10, catalog.settings.get().notices.expiryNoticeDays)
        }

    private companion object {
        /** Una segunda ubicación inventada: el libro falso no revisa que exista. */
        const val SECOND_LOCATION = 77L
    }
}
