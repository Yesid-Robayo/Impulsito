package io.github.yesidrobayo.shopledger.data.catalog

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.LineRejection
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los insumos sobre la base real, con sus guardas: la venta no los ofrece ni los registra, pero se
 * compran y entran en la producción como cualquier producto, y todas las consultas de invariantes
 * quedan en cero. Productos y montos inventados.
 */
internal class ProductUsageOnDatabaseTest : CatalogDatabaseTest() {
    private lateinit var ports: DataPorts

    @Before
    fun connectPorts() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
    }

    private class Bakery(
        val flour: Long,
        val sugar: Long,
        val bread: Long,
        val oldYeast: Long,
        val oldSalt: Long,
    )

    /** Harina como insumo, azúcar que también se vende, pan que se vende y dos archivados. */
    private suspend fun arrangeBakery(): Bakery {
        val products = database.productDao()
        return Bakery(
            flour = products.insert(
                product("Harina inventada").copy(baseUnitId = KILOGRAM_ID, usage = "INPUT"),
            ),
            sugar = products.insert(product("Azúcar inventada").copy(usage = "BOTH")),
            bread = products.insert(product("Pan inventado")),
            oldYeast = products.insert(
                product("Levadura inventada", archivedAt = CREATED_AT).copy(usage = "INPUT"),
            ),
            oldSalt = products.insert(product("Sal inventada", archivedAt = CREATED_AT)),
        )
    }

    @Test
    fun theSaleCatalogLeavesTheInputsOut() = runBlocking {
        val bakery = arrangeBakery()

        val catalog = ObserveSaleCatalogUseCase(
            ports.products,
            ports.units,
            ports.saleFrequency,
            ports.paymentMethods,
            BusinessCalendarSource(ports.settings),
            ports.taxSettings,
            ports.taxRates,
            FIXED_CLOCK,
        )().first()

        val offered = catalog.products.map { it.id }.toSet()
        assertTrue(bakery.sugar in offered)
        assertTrue(bakery.bread in offered)
        assertTrue(bakery.flour !in offered)
        assertTrue(catalog.frequent.none { it.id == bakery.flour })
        assertEquals(listOf(bakery.oldSalt), catalog.archived.map { it.id })
        assertTrue(catalog.archived.none { it.id == bakery.oldYeast })
    }

    @Test
    fun anInputIsBoughtAndProducedButNotSold() = runBlocking {
        val bakery = arrangeBakery()
        val buy = RegisterPurchaseUseCase(
            ports.transactions,
            ports.settings,
            ports.products,
            ports.purchases,
            ports.suppliers,
            ports.units,
            ports.systemRows,
            ports.stockLedger,
            ports.taxRates,
            FIXED_CLOCK,
        )
        val bought = buy(
            PurchaseRequest(
                listOf(
                    PurchaseLineRequest(
                        bakery.flour,
                        QuantityMilli(10_000),
                        PurchaseLineAmount.UnitCost(Money(3_000)),
                    ),
                ),
            ),
        )
        assertTrue(bought is RegisterPurchaseResult.Registered)
        assertEquals(10_000L, onHand(bakery.flour))

        val recipe = SaveRecipeUseCase(
            ports.transactions,
            ports.products,
            ports.productComponents,
            FIXED_CLOCK,
        )
        assertEquals(
            SaveRecipeResult.Saved,
            recipe(bakery.bread, listOf(RecipeLine(bakery.flour, QuantityMilli(500)))),
        )
        val produce = RegisterProductionUseCase(
            ports.transactions, ports.products, ports.units, ports.productComponents,
            ports.productionBatches, ports.systemRows, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
        )
        val produced = produce(
            ProductionRequest(
                bakery.bread,
                QuantityMilli(4_000),
                listOf(ProductionInput(bakery.flour, QuantityMilli(2_000))),
                note = null,
            ),
        )
        assertTrue(produced is RegisterProductionResult.Registered)
        assertEquals(8_000L, onHand(bakery.flour))
        assertEquals(4_000L, onHand(bakery.bread))

        val sell = RegisterSaleUseCase(
            transactions = ports.transactions,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = ports.taxRates,
            clock = FIXED_CLOCK,
        )
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val rejected = sell(
            SaleRequest(listOf(SaleLineRequest(bakery.flour, QuantityMilli(1_000))), method),
        )
        assertEquals(RegisterSaleResult.LineRejected(0, LineRejection.PRODUCT_NOT_SOLD), rejected)
        assertEquals(0L, count("SELECT COUNT(*) FROM sales"))
        assertEquals(8_000L, onHand(bakery.flour))
        // Un insumo no espera revisión de precio por subir su costo: no se vende.
        assertEquals(
            0L,
            count(
                "SELECT COUNT(*) FROM products WHERE id = ${bakery.flour} " +
                    "AND price_review_since IS NOT NULL",
            ),
        )
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private suspend fun onHand(productId: Long): Long =
        count("SELECT on_hand_milli FROM products WHERE id = $productId")
}
