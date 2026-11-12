package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El inventario de un vistazo, leído del catálogo en memoria, sin mirar las alertas. */
class ObserveInventoryUseCaseTest {
    private val fixture = StockFixture()
    private val observe = ObserveInventoryUseCase(fixture.catalog.products, fixture.catalog.units)

    @Test
    fun `an empty catalog has no products`() = runTest {
        val overview = observe().first()

        assertFalse(overview.hasProducts)
        assertEquals(Money.ZERO, overview.value)
        assertNull(overview.archivedValue)
    }

    @Test
    fun `a product that crosses its minimum is listed even with no alert at all`() = runTest {
        fixture.seedStock(id = 1L, units = 6_000L, unitCost = 100L, minStock = 5_000L)
        fixture.catalog.seedProduct(
            id = 2L,
            name = "Servicio",
            stockMode = ProductStockMode.UNTRACKED,
        )
        assertTrue(observe().first().lowStock.isEmpty())

        fixture.adjust(
            StockAdjustmentRequest.Change(1L, StockAdjustmentReason.DAMAGED, QuantityMilli(1_000L)),
        )
        // La lista no depende de las alertas: aunque se borraran, el producto sigue ahí.
        fixture.catalog.database.seed { it.copy(alerts = emptyMap()) }

        val overview = observe().first()
        assertEquals(listOf(1L), overview.lowStock.map { it.id })
        assertEquals(listOf(1L), overview.tracked.map { it.id })
        assertTrue(overview.hasProducts)
        assertEquals(Money(500L), overview.value)
        assertEquals(CatalogFixture.UNIT_ID, overview.units[CatalogFixture.UNIT_ID]?.id)
    }

    @Test
    fun `archived stock counts in the value and is told apart`() = runTest {
        fixture.seedStock(id = 1L, units = 2_000L, unitCost = 100L)
        fixture.seedStock(id = 2L, units = 3_000L, unitCost = 1_000L)
        fixture.catalog.database.seed { tables ->
            val product = requireNotNull(tables.products[2L])
            tables.copy(
                products = tables.products + (2L to product.copy(archivedAt = CatalogFixture.NOW)),
            )
        }

        val overview = observe().first()

        assertEquals(Money(3_200L), overview.value)
        assertEquals(Money(3_000L), overview.archivedValue)
        assertEquals(listOf(1L), overview.tracked.map { it.id })
    }

    @Test
    fun `negative stock and stock without cost are flagged`() = runTest {
        fixture.seedStock(id = 1L, units = 1_000L, unitCost = 100L)
        fixture.adjust(
            StockAdjustmentRequest.Change(1L, StockAdjustmentReason.DAMAGED, QuantityMilli(3_000L)),
        )
        fixture.catalog.seedProduct(id = 2L, name = "Sin costo", onHand = QuantityMilli(4_000L))

        val overview = observe().first()

        assertEquals(listOf(1L), overview.negative.map { it.id })
        assertTrue(overview.hasStockWithoutCost)
        assertNull(overview.archivedValue)
    }
}
