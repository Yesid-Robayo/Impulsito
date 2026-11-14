package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotice
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotifier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture.Companion.PRODUCT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista `Por vencer` y su aviso, una vez por lote. El reloj de prueba marca el 2023-11-14 en la
 * zona del negocio, y los ajustes avisan 30 días antes. Productos, lotes y fechas inventados.
 */
class ExpiringLotsUseCasesTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val observe = ObserveExpiringLotsUseCase(
        catalog.lots,
        BusinessCalendarSource(catalog.settings),
        catalog.settings,
        catalog.clock,
    )
    private val shown = mutableListOf<List<ExpiryNotice>>()
    private var allowed = true
    private val notifier = ExpiryNotifier { notices ->
        if (allowed) shown += notices
        allowed
    }
    private val deliver =
        DeliverExpiryNoticesUseCase(observe, catalog.lots, catalog.units, notifier, catalog.clock)

    /** Un lote del producto con [units] en la inicial, que vence en [expiry]. */
    private suspend fun lot(code: String, expiry: String?, units: Long = 2_000L): Long {
        val lot = fixture.seedLot(PRODUCT_ID, code, expiry)
        if (units > 0L) fixture.receiveIntoLot(PRODUCT_ID, lot, units, unitCost = 1_000L)
        return lot
    }

    @Test
    fun `lists the lots within their days, soonest first, with an expired one marked`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val later = lot("A-2", "2023-12-01")
        val expired = lot("A-1", "2023-11-10")
        lot("A-3", "2024-03-01")
        lot("A-4", null)

        val listed = observe().first()

        assertEquals(listOf(expired, later), listed.map { it.lot.lotId })
        assertEquals(listOf(-4, 17), listed.map { it.daysLeft })
        assertTrue(listed.first().isExpired)
        assertEquals(QuantityMilli(2_000L), listed.first().lot.onHand)
    }

    @Test
    fun `a lot without stock, the unassigned lot and an archived product are not listed`() =
        runTest {
            fixture.seedStock(units = 3_000L, unitCost = 1_000L, tracksLots = true)
            lot("B-1", "2023-11-20", units = 0L)
            val sold = lot("B-2", "2023-11-21")
            fixture.adjust(
                StockAdjustmentRequest.Change(
                    PRODUCT_ID,
                    StockAdjustmentReason.EXPIRED,
                    QuantityMilli(2_000L),
                    lotId = sold,
                ),
            )
            assertTrue(observe().first().isEmpty())

            val kept = lot("B-3", "2023-11-22")
            assertEquals(listOf(kept), observe().first().map { it.lot.lotId })
            catalog.database.seed { tables ->
                val product = requireNotNull(tables.products[PRODUCT_ID])
                val archived = product.copy(archivedAt = CatalogFixture.NOW)
                tables.copy(products = tables.products + (PRODUCT_ID to archived))
            }
            assertTrue(observe().first().isEmpty())
        }

    @Test
    fun `the product's own days and the settings days decide when it enters`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        lot("C-1", "2023-11-24")
        assertEquals(1, observe().first().size)

        catalog.database.seed { tables ->
            val product = requireNotNull(tables.products[PRODUCT_ID])
            val own = product.copy(details = product.details.copy(expiryNoticeDays = 5))
            tables.copy(products = tables.products + (PRODUCT_ID to own))
        }
        // A 10 días, con 5 propios ya no avisa.
        assertTrue(observe().first().isEmpty())

        catalog.database.seed { tables ->
            val product = requireNotNull(tables.products[PRODUCT_ID])
            val none = product.copy(details = product.details.copy(expiryNoticeDays = null))
            tables.copy(products = tables.products + (PRODUCT_ID to none))
        }
        catalog.settings.settings.value = catalog.settings.settings.value.let {
            it.copy(notices = it.notices.copy(expiryNoticeDays = 9))
        }
        assertTrue(observe().first().isEmpty())
    }

    @Test
    fun `notifies each lot once and marks it`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val first = lot("F-1", "2023-11-20")
        val second = lot("F-2", "2023-11-21")

        assertEquals(2, deliver())
        assertEquals(listOf(first, second), shown.single().map { it.lot.lot.lotId })
        assertEquals(CatalogFixture.UNIT_ID, shown.single().first().unit?.id)
        assertEquals(CatalogFixture.NOW, catalog.tables.lots.getValue(first).expiryNotifiedAt)

        // La siguiente vez no repite: solo avisa el lote nuevo.
        val third = lot("F-3", "2023-11-22")
        assertEquals(1, deliver())
        assertEquals(listOf(third), shown.last().map { it.lot.lot.lotId })
        assertEquals(0, deliver())
        assertEquals(2, shown.size)
    }

    @Test
    fun `without permission nothing is marked, and it notifies once allowed`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = lot("G-1", "2023-11-20")
        allowed = false

        assertEquals(0, deliver())
        assertNull(catalog.tables.lots.getValue(lot).expiryNotifiedAt)
        // La lista no depende del permiso.
        assertEquals(1, observe().first().size)

        allowed = true
        assertEquals(1, deliver())
        assertEquals(CatalogFixture.NOW, catalog.tables.lots.getValue(lot).expiryNotifiedAt)
    }

    @Test
    fun `nothing pending shows nothing`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)

        assertEquals(0, deliver())
        assertTrue(shown.isEmpty())
    }
}
