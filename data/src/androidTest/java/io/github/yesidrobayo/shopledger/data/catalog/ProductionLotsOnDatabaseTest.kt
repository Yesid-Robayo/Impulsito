package io.github.yesidrobayo.shopledger.data.catalog

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionLot
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Producir con lotes sobre la base real, con sus guardas: el queso se produce en sus lotes A y B,
 * el pan lo consume por vencimiento con una fila por lote y entra a su propio lote, y anular
 * devuelve cada fila a su lote. Anular una producción cuyo lote ya se consumió se rechaza con lo
 * que falta. Las consultas de invariantes quedan en cero en cada paso, también la de los lotes.
 * Productos y lotes inventados.
 */
internal class ProductionLotsOnDatabaseTest : CatalogDatabaseTest() {
    private lateinit var ports: DataPorts
    private lateinit var opening: RegisterOpeningBalanceUseCase
    private lateinit var register: RegisterProductionUseCase
    private lateinit var void: VoidProductionUseCase

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
            calendar, currencyLock, FIXED_CLOCK, ports.stockLots,
        )
        void = VoidProductionUseCase(
            ports.transactions,
            ports.products,
            ports.productionBatches,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            FIXED_CLOCK,
            ports.stockLots,
        )
    }

    @Test
    fun inputsLeaveByLotTheProductEntersItsLotAndVoidingGivesEachRowBack() = runBlocking {
        val bakery = arrangeBakery()
        // El queso entra en dos producciones, cada una a su lote con su vencimiento.
        val cheeseA = produce(
            bakery.cheese,
            3_000,
            ProductionInput(bakery.milk, QuantityMilli(3_000)),
            ProductionLot("A", date("2026-11-10")),
        )
        produce(
            bakery.cheese,
            5_000,
            ProductionInput(bakery.milk, QuantityMilli(5_000)),
            ProductionLot("B", date("2026-11-20")),
        )
        assertEquals(
            listOf("A|2026-11-10", "B|2026-11-20"),
            texts(
                "SELECT lot_code || '|' || expiry_date FROM stock_lots " +
                    "WHERE product_id = ${bakery.cheese} AND is_unassigned = 0 ORDER BY id",
            ),
        )
        assertInvariantsHold()

        // Dieciocho panes llevan 9 de queso: 3 de A, 5 de B y 1 del lote sin asignar.
        val bread = produce(
            bakery.bread,
            18_000,
            ProductionInput(bakery.cheese, QuantityMilli(9_000)),
            ProductionLot("P-1", null),
        )
        assertEquals(
            listOf("A|-3000", "B|-5000", "-|-1000", "P-1|18000"),
            texts(
                "SELECT IFNULL(l.lot_code, '-') || '|' || m.quantity_milli " +
                    "FROM stock_movements m JOIN stock_lots l ON l.id = m.lot_id " +
                    "WHERE m.production_batch_id = $bread ORDER BY m.id",
            ),
        )
        assertEquals(listOf("A|0", "B|0", "-|-1000"), lotsOf(bakery.cheese))
        assertInvariantsHold()

        // El lote A ya se consumió: anular su producción lo dejaría en negativo.
        val lotA = requireNotNull(ports.stockLots.findByCode(bakery.cheese, "A")).id
        assertEquals(
            VoidProductionResult.LotAlreadyUsed(bakery.cheese, lotA, QuantityMilli(3_000)),
            void(cheeseA, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(
            listOf("ACTIVE", "ACTIVE", "ACTIVE"),
            texts("SELECT status FROM production_batches ORDER BY id"),
        )

        assertEquals(VoidProductionResult.Voided, void(bread, VoidReason.ENTRY_ERROR, null))
        assertEquals(listOf("A|3000", "B|5000", "-|0"), lotsOf(bakery.cheese))
        assertEquals(listOf("P-1|0"), lotsOf(bakery.bread))
        assertInvariantsHold()

        assertEquals(VoidProductionResult.Voided, void(cheeseA, VoidReason.ENTRY_ERROR, null))
        assertEquals(listOf("A|0", "B|5000", "-|0"), lotsOf(bakery.cheese))
        assertInvariantsHold()
    }

    private class Bakery(val milk: Long, val cheese: Long, val bread: Long)

    /**
     * La leche, sin lotes, con 100 unidades a 100; el queso, con lotes, lleva una de leche; el
     * pan, con lotes, lleva medio queso.
     */
    private suspend fun arrangeBakery(): Bakery {
        val products = database.productDao()
        val milk = products.insert(product("Leche inventada"))
        val cheese = products.insert(product("Queso inventado").copy(tracksLots = 1L))
        val bread = products.insert(product("Pan de queso inventado").copy(tracksLots = 1L))
        val save = SaveRecipeUseCase(
            ports.transactions,
            ports.products,
            ports.productComponents,
            FIXED_CLOCK,
        )
        assertEquals(
            SaveRecipeResult.Saved,
            save(cheese, listOf(RecipeLine(milk, QuantityMilli(1_000)))),
        )
        assertEquals(
            SaveRecipeResult.Saved,
            save(bread, listOf(RecipeLine(cheese, QuantityMilli(500)))),
        )
        assertTrue(
            opening(milk, QuantityMilli(100_000), Money(100)) is OpeningBalanceResult.Registered,
        )
        return Bakery(milk, cheese, bread)
    }

    /** Produce [milli] de [productId] con un solo insumo, al lote [lot], y devuelve su id. */
    private suspend fun produce(
        productId: Long,
        milli: Long,
        input: ProductionInput,
        lot: ProductionLot,
    ): Long {
        val result = register(
            ProductionRequest(productId, QuantityMilli(milli), listOf(input), null, lot),
        )
        return (result as RegisterProductionResult.Registered).batchId
    }

    /** Lo que suma el libro de [productId] en cada lote, en el orden en que se crearon. */
    private suspend fun lotsOf(productId: Long): List<String> = texts(
        "SELECT IFNULL(l.lot_code, '-') || '|' || SUM(m.quantity_milli) " +
            "FROM stock_movements m JOIN stock_lots l ON l.id = m.lot_id " +
            "WHERE m.product_id = $productId GROUP BY l.id ORDER BY l.id",
    )

    private fun date(text: String): BusinessDate = requireNotNull(BusinessDate.parse(text))

    private suspend fun texts(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
