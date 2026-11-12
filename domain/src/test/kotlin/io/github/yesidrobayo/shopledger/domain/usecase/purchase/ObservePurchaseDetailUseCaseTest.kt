package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.VoidStockEffect
import io.github.yesidrobayo.shopledger.domain.port.PurchaseHistorySource
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El detalle de una compra: cabecera, líneas con sus productos, el proveedor de hoy, la fecha de la
 * anulación y lo que anular haría con el stock. También el lector del historial y el filtro.
 * Productos y proveedores inventados.
 */
class ObservePurchaseDetailUseCaseTest {
    private val world = SaleWorld()

    private fun detail(world: SaleWorld = this.world) = ObservePurchaseDetailUseCase(
        purchases = world.purchases,
        products = world.products,
        suppliers = world.supplierRepository,
        units = world.unitRepository,
        calendar = BusinessCalendarSource(world.settings),
    )

    @Test
    fun `the detail carries lines, supplier and what voiding would leave`() = runTest {
        world.addSupplier(7)
        world.addProduct(product(1, name = "Soap"))
        world.addProduct(product(2, name = "Oil", unitId = SaleWorld.KILOGRAM))
        val purchaseId = world.buy(
            atUnitCost(1, 3_000, cost = 100),
            atUnitCost(2, 1_500, cost = 900),
            atUnitCost(1, 2_000, cost = 100),
            supplierId = 7,
        )
        // Se venden 4 jabones: queda 1, y anular la compra le quitaría 5.
        world.sell(1, 4_000)

        val shown = requireNotNull(detail()(purchaseId).first())

        assertEquals("Supplier 7", shown.supplierName)
        assertEquals(listOf("Soap", "Oil", "Soap"), shown.lines.map { it.productName })
        assertEquals(SaleWorld.KILOGRAM, shown.lines[1].unit?.id)
        assertEquals(
            listOf(
                VoidStockEffect(
                    1,
                    "Soap",
                    world.units[SaleWorld.UNIT],
                    QuantityMilli(5_000),
                    QuantityMilli(-4_000),
                ),
                VoidStockEffect(
                    2,
                    "Oil",
                    world.units[SaleWorld.KILOGRAM],
                    QuantityMilli(1_500),
                    QuantityMilli.ZERO,
                ),
            ),
            shown.voidEffects,
        )
        assertEquals(listOf(true, false), shown.voidEffects.map { it.leavesNegative })
        assertNull(shown.voidedOn)
    }

    @Test
    fun `a voided purchase says the business date of its void`() = runTest {
        world.addProduct(product(1, archived = false))
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 100))
        world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)
        world.arrange {
            val archived = it.products.getValue(1).copy(archivedAt = SaleWorld.EPOCH)
            it.copy(products = it.products + (1L to archived))
        }

        val shown = requireNotNull(detail()(purchaseId).first())

        assertEquals(BusinessDate(LocalDate(2026, 11, 2)), shown.voidedOn)
        assertNull(shown.supplierName)
        assertTrue(shown.lines.single().productArchived)
    }

    @Test
    fun `without a known zone the void has no date, and a missing purchase is null`() = runTest {
        val lost = SaleWorld(zoneId = "Invented/Zone")
        val purchaseId = world.run {
            addProduct(product(1))
            buy(atUnitCost(1, 1_000, cost = 100)).also {
                voidPurchase()(it, VoidReason.ENTRY_ERROR, null)
            }
        }
        lost.arrange { world.tables }

        assertNull(requireNotNull(detail(lost)(purchaseId).first()).voidedOn)
        assertNull(detail()(99).first())
    }

    @Test
    fun `a purchase whose products are gone shows its lines without names`() = runTest {
        world.addProduct(product(1))
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 100))
        // Un caso que la base no permite, como una copia dañada: el producto falta.
        world.arrange { it.copy(products = emptyMap()) }

        val shown = requireNotNull(detail()(purchaseId).first())

        assertEquals("", shown.lines.single().productName)
        assertFalse(shown.lines.single().productArchived)
        assertTrue(shown.voidEffects.isEmpty())
    }

    @Test
    fun `the history reader asks its source and checks the page size`() = runTest {
        val row = PurchaseHistoryRow(
            id = 3,
            occurredAt = NOW,
            businessDate = TODAY,
            total = Money(500),
            voided = false,
            supplierName = null,
            lineCount = 1,
            invoiceReference = "F-1",
        )
        val asked = mutableListOf<Any?>()
        val source = object : PurchaseHistorySource {
            override suspend fun page(
                filter: PurchaseHistoryFilter,
                after: PurchaseHistoryCursor?,
                limit: Int,
            ): List<PurchaseHistoryRow> {
                asked.addAll(listOf(filter, after, limit))
                return listOf(row)
            }

            override suspend fun dayTotals(
                filter: PurchaseHistoryFilter,
                dates: Set<BusinessDate>,
            ) = dates.associateWith { Money(500) }

            override fun observeChanges(): Flow<String> = flowOf("1:0")
        }
        val reader = PurchaseHistoryReader(source, FixedClock(NOW))
        val filter = PurchaseHistoryFilter(supplier = SupplierFilter.None)

        assertEquals(listOf(row), reader.page(filter, row.cursor, 10))
        assertEquals(listOf(filter, PurchaseHistoryCursor(TODAY, 3), 10), asked)
        assertEquals(mapOf(TODAY to Money(500)), reader.dayTotals(filter, setOf(TODAY)))
        assertEquals("1:0", reader.changes().first())
        assertEquals(NOW, reader.now())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { reader.page(filter, null, 0) }
        }
    }

    @Test
    fun `a filter knows whether it filters anything`() {
        assertFalse(PurchaseHistoryFilter().isFiltered)
        assertTrue(PurchaseHistoryFilter(from = TODAY).isFiltered)
        assertTrue(PurchaseHistoryFilter(to = TODAY).isFiltered)
        assertTrue(PurchaseHistoryFilter(supplier = SupplierFilter.Of(1)).isFiltered)
        assertTrue(PurchaseHistoryFilter(onlyVoided = true).isFiltered)
    }
}
