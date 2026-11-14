package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.CANDLE
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.LABEL
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WAX
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WICK
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que leen las pantallas de producción: qué se puede producir, la lista más nueva primero y el
 * detalle con sus insumos, también anulado. Productos inventados.
 */
class ProductionReadUseCasesTest {
    private val fixture = ProductionFixture().apply { seedCandles() }
    private val catalog = fixture.catalog
    private val observeCatalog = ObserveProductionCatalogUseCase(
        catalog.products,
        catalog.components,
        catalog.units,
    )
    private val observeDetail = ObserveProductionDetailUseCase(fixture.batches)

    @Test
    fun `only a current product with stock and with a recipe can be produced, also with lots`() =
        runTest {
            catalog.seedProduct(10L, "Combo inventado", stockMode = ProductStockMode.COMPONENTS)
            catalog.seedProduct(11L, "Archivado inventado", archived = true)
            catalog.seedProduct(12L, "Velón con lotes inventado", tracksLots = true)
            fixture.seedRecipe(10L, WAX to 1_000L)
            fixture.seedRecipe(11L, WAX to 1_000L)
            fixture.seedRecipe(12L, WAX to 1_000L)

            val read = observeCatalog().first()

            assertEquals(listOf(CANDLE, 12L), read.producible.map { it.id })
            assertTrue(read.hasRecipes)
            assertEquals(ProductStockMode.UNTRACKED, read.modes[LABEL])
        }

    @Test
    fun `without recipes nothing can be produced`() = runTest {
        val empty = ProductionFixture()
        empty.catalog.seedProduct(CANDLE, "Vela inventada")
        val read = ObserveProductionCatalogUseCase(
            empty.catalog.products,
            empty.catalog.components,
            empty.catalog.units,
        )().first()

        assertTrue(read.producible.isEmpty())
        assertFalse(read.hasRecipes)
    }

    @Test
    fun `the detail brings the inputs, and the void shows on it`() = runTest {
        fixture.stockCandleInputs()
        fixture.register(
            ProductionRequest(
                CANDLE,
                QuantityMilli(10_000),
                listOf(
                    ProductionInput(WAX, QuantityMilli(2_000)),
                    ProductionInput(WICK, QuantityMilli(10_000)),
                ),
                note = null,
            ),
        )

        val active = requireNotNull(observeDetail(1L).first())
        assertEquals(
            listOf(Money(16_000L), Money(1_500L)),
            active.lines.map { it.content.lineCost },
        )
        assertEquals(DocumentStatus.ACTIVE, active.batch.status)
        assertEquals(1, fixture.batches.observeRecent(10).first().size)

        fixture.void(1L, VoidReason.ENTRY_ERROR, null)

        assertEquals(DocumentStatus.VOIDED, observeDetail(1L).first()?.batch?.status)
        assertNull(observeDetail(99L).first())
    }
}
