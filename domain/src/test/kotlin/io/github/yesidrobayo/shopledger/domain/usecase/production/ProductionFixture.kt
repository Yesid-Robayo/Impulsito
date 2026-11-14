package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductionBatchRepository
import io.github.yesidrobayo.shopledger.domain.testing.ProductStockHeader
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import org.junit.Assert.assertTrue

/**
 * El catálogo en memoria con la producción conectada y el ejemplo de la regla de producción
 * sembrado: velas que se hacen con cera y mecha. Todos los productos y montos son inventados.
 */
internal class ProductionFixture(zoneId: String = "UTC", currencyConfirmed: Boolean = true) {
    val catalog = CatalogFixture(zoneId, currencyConfirmed)
    val batches = FakeProductionBatchRepository(catalog.database)
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val currencyLock = CurrencyLock(catalog.settings)

    val register = with(catalog) {
        RegisterProductionUseCase(
            transactions, products, units, components, batches, systemRows, ledger, alerts,
            calendar, currencyLock, clock,
        )
    }

    /**
     * Siembra la vela, la cera, la mecha y una etiqueta sin stock, y la receta de una vela: 0,200
     * kg de cera y una mecha.
     */
    fun seedCandles() {
        catalog.seedProduct(CANDLE, "Vela inventada")
        catalog.seedProduct(WAX, "Cera inventada", baseUnitId = CatalogFixture.KILOGRAM_ID)
        catalog.seedProduct(WICK, "Mecha inventada")
        catalog.seedProduct(LABEL, "Etiqueta inventada", stockMode = ProductStockMode.UNTRACKED)
        seedRecipe(CANDLE, WAX to 200L, WICK to 1_000L)
    }

    /** El inventario inicial del ejemplo: 5 kg de cera por 40000 y 100 mechas por 15000. */
    suspend fun stockCandleInputs() {
        opening(WAX, 5_000L, 8_000L)
        opening(WICK, 100_000L, 150L)
    }

    /** Un inventario inicial de [milli] milésimas a [unitCost] por unidad entera. */
    suspend fun opening(productId: Long, milli: Long, unitCost: Long) {
        val result = catalog.opening(productId, QuantityMilli(milli), Money(unitCost))
        assertTrue("El inventario inicial debía entrar", result is OpeningBalanceResult.Registered)
    }

    /**
     * Deja a [productId] con [milli] milésimas que valen [value] y el promedio [average], sin
     * filas en el libro.
     */
    fun seedStock(productId: Long, milli: Long, value: Long, average: Long = 0L) {
        catalog.database.seed { tables ->
            val header = ProductStockHeader(QuantityMilli(milli), Money(average), Money(value))
            tables.copy(productStock = tables.productStock + (productId to header))
        }
    }

    fun seedRecipe(parentId: Long, vararg rows: Pair<Long, Long>) {
        catalog.database.seed { tables ->
            val first = (tables.lastIds["product_components"] ?: 0L) + 1L
            val added = rows.mapIndexed { index, (componentId, milli) ->
                val id = first + index
                id to RecipeComponent(id, parentId, componentId, QuantityMilli(milli))
            }
            tables.copy(
                productComponents = tables.productComponents + added,
                lastIds = tables.lastIds + ("product_components" to first + rows.size - 1),
            )
        }
    }

    fun movementsOf(productId: Long): List<StockMovementRecord> = catalog.movementsOf(productId)

    companion object {
        const val CANDLE = 1L
        const val WAX = 2L
        const val WICK = 3L
        const val LABEL = 4L
    }
}
