package io.github.yesidrobayo.shopledger.data.catalog

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Producir sobre la base real, con sus guardas: el ejemplo de la regla de producción, diez velas
 * con 0,200 kg de cera y una mecha cada una, deja los saldos, el costo y el libro como dice la
 * regla, y todas las consultas de invariantes en cero. Productos y montos inventados.
 */
internal class ProductionOnDatabaseTest : CatalogDatabaseTest() {
    private lateinit var ports: DataPorts
    private lateinit var opening: RegisterOpeningBalanceUseCase
    private lateinit var register: RegisterProductionUseCase

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val calendar = BusinessCalendarSource(ports.settings)
        val currencyLock = CurrencyLock(ports.settings)
        opening = RegisterOpeningBalanceUseCase(
            ports.transactions, ports.products, ports.units, ports.systemRows, ports.stockLedger,
            ports.lowStockAlerts, calendar, currencyLock, FIXED_CLOCK,
        )
        register = RegisterProductionUseCase(
            ports.transactions, ports.products, ports.units, ports.productComponents,
            ports.productionBatches, ports.systemRows, ports.stockLedger, ports.lowStockAlerts,
            calendar, currencyLock, FIXED_CLOCK,
        )
    }

    @Test
    fun tenCandlesMoveTheInputsOutAndEnterAtTheirSum() = runBlocking {
        val candles = arrangeCandles()

        val result = register(
            ProductionRequest(
                candles.candle,
                QuantityMilli(10_000),
                listOf(
                    ProductionInput(candles.wax, QuantityMilli(2_000)),
                    ProductionInput(candles.wick, QuantityMilli(10_000)),
                ),
                note = null,
            ),
        )

        val batchId = (result as RegisterProductionResult.Registered).batchId
        assertEquals(Money(17_500), result.totalCost)
        assertEquals(
            listOf("${candles.candle}|10000|17500|ACTIVE"),
            texts(
                "SELECT product_id || '|' || quantity_milli || '|' || total_cost || '|' || " +
                    "status FROM production_batches",
            ),
        )
        assertEquals(
            listOf("${candles.wax}|2000|8000|16000", "${candles.wick}|10000|150|1500"),
            texts(
                "SELECT component_product_id || '|' || quantity_milli || '|' || unit_cost || " +
                    "'|' || line_cost FROM production_batch_lines ORDER BY id",
            ),
        )
        assertEquals(
            listOf(
                "PRODUCTION_OUT|-2000|-16000",
                "PRODUCTION_OUT|-10000|-1500",
                "PRODUCTION_IN|10000|17500",
            ),
            texts(
                "SELECT type || '|' || quantity_milli || '|' || total_cost FROM stock_movements " +
                    "WHERE production_batch_id = $batchId ORDER BY id",
            ),
        )
        assertEquals(
            listOf("3000|8000|24000", "90000|150|13500", "10000|1750|17500"),
            listOf(candles.wax, candles.wick, candles.candle).map { id ->
                texts(
                    "SELECT on_hand_milli || '|' || average_cost || '|' || stock_value " +
                        "FROM products WHERE id = $id",
                ).single()
            },
        )
        assertEquals(
            listOf("${candles.wax}|3000", "${candles.wick}|90000", "${candles.candle}|10000"),
            texts(
                "SELECT b.product_id || '|' || b.on_hand_milli FROM stock_balances b " +
                    "JOIN locations l ON l.id = b.location_id " +
                    "WHERE l.system_key = 'MAIN' ORDER BY b.product_id",
            ),
        )
        assertInvariantsHold()
    }

    private class Candles(val wax: Long, val wick: Long, val candle: Long)

    /** La vela con su receta, y el inventario inicial del ejemplo de cera y mecha. */
    private suspend fun arrangeCandles(): Candles {
        val products = database.productDao()
        val wax = products.insert(product("Cera inventada").copy(baseUnitId = KILOGRAM_ID))
        val wick = products.insert(product("Mecha inventada"))
        val candle = products.insert(product("Vela inventada"))
        val save = SaveRecipeUseCase(
            ports.transactions,
            ports.products,
            ports.productComponents,
            FIXED_CLOCK,
        )
        val recipe = listOf(
            RecipeLine(wax, QuantityMilli(200)),
            RecipeLine(wick, QuantityMilli(1_000)),
        )
        assertEquals(SaveRecipeResult.Saved, save(candle, recipe))
        assertTrue(
            opening(wax, QuantityMilli(5_000), Money(8_000)) is OpeningBalanceResult.Registered,
        )
        assertTrue(
            opening(wick, QuantityMilli(100_000), Money(150)) is OpeningBalanceResult.Registered,
        )
        return Candles(wax, wick, candle)
    }

    private suspend fun texts(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
