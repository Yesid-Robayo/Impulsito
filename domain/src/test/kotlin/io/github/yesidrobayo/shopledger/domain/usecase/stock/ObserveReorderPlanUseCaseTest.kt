package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.SaleOutflowSource
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderQuery
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de reposición leída del catálogo y de las salidas por ventas: la ventana termina hoy,
 * en la fecha de negocio, y la ubicación cambia lo que se mira. Nombres y cantidades inventados.
 */
class ObserveReorderPlanUseCaseTest {
    private val asked = mutableListOf<Pair<BusinessDate, Long?>>()
    private val outflow = SaleOutflowSource { since, locationId ->
        asked += since to locationId
        flowOf(mapOf(1L to QuantityMilli(45_000)))
    }

    private fun observe(catalog: CatalogFixture) = ObserveReorderPlanUseCase(
        products = catalog.products,
        suppliers = catalog.suppliers,
        categories = catalog.categories,
        units = catalog.units,
        productUnits = FakeProductUnitRepository(catalog.database),
        outflow = outflow,
        locationStock = FakeLocationStockSource(catalog.database),
        calendar = BusinessCalendarSource(catalog.settings),
        clock = catalog.clock,
    )

    @Test
    fun `the window ends today and counts thirty days with today included`() = runTest {
        val catalog = CatalogFixture()
        catalog.seedProduct(
            id = 1L,
            name = "Café molido",
            onHand = QuantityMilli(12_000),
            minStock = QuantityMilli(5_000),
        )

        val overview = observe(catalog)(ReorderQuery()).first()

        // El reloj de la prueba marca el 2023-11-14 en UTC: la ventana empieza el 2023-10-16.
        assertEquals(listOf(BusinessDate.parse("2023-10-16") to null), asked)
        val line = overview.plan.groups.single().lines.single()
        assertEquals(QuantityMilli(38_000), line.suggestion.suggested)
        assertTrue(overview.plan.hasRecentSales)
        assertTrue(overview.units.containsKey(CatalogFixture.UNIT_ID))
    }

    @Test
    fun `a location asks for its own sales and reads its own stock`() = runTest {
        val catalog = CatalogFixture()
        catalog.seedProduct(id = 1L, name = "Café molido", onHand = QuantityMilli(12_000))

        val overview = observe(catalog)(ReorderQuery(coverDays = 10), locationId = 77L).first()

        assertEquals(77L, asked.single().second)
        // En esa ubicación no cayó ninguna fila: hay cero, y se sugieren 10 días a 1.500.
        val line = overview.plan.groups.single().lines.single()
        assertEquals(QuantityMilli.ZERO, line.onHand)
        assertEquals(QuantityMilli(15_000), line.suggestion.suggested)
    }

    @Test
    fun `without a known business zone nothing is read from sales`() = runTest {
        val catalog = CatalogFixture(zoneId = "Not/AZone")
        catalog.seedProduct(id = 1L, name = "Café molido", minStock = QuantityMilli(2_000))

        val overview = observe(catalog)(ReorderQuery()).first()

        assertTrue(asked.isEmpty())
        assertFalse(overview.plan.hasRecentSales)
        val line = overview.plan.groups.single().lines.single()
        assertEquals(QuantityMilli.ZERO, line.suggestion.suggested)
        assertNull(line.suggestion.daysLeft)
    }
}
