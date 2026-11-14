package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vender y anular un combo sobre Room, con sus guardas: el kit de la regla de costo por
 * componentes guarda sus hojas y una fila de venta por hoja con stock, y cada consulta de
 * invariantes queda en cero, también la de la venta por componentes y la de solo hojas.
 * Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ComboSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val recipes by lazy { RoomProductComponentRepository(database) }
    private val leaves by lazy { RoomSaleLineComponentRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val register by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            recipes = recipes,
            lineComponents = leaves,
        )
    }

    private val void by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = ledger,
            clock = Clock { AT },
        )
    }

    @Test
    fun twoKitsLeaveTheirLeavesAndOneSalePerTrackedLeaf() = runBlocking {
        val kit = arrangeKit()

        val result = register(SaleRequest(listOf(line(kit.combo, 2_000)), cash()))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val stored = sales.findLines(saleId).single()
        // A a 1200: 4 unidades son 4800; B a 1200: 2 son 2400; el servicio a 300: 600.
        assertEquals(Money(7_800), stored.content.lineCost)
        assertEquals(Money(3_900), stored.content.unitCost)
        val saved = leaves.findBySaleLine(stored.id).map { it.content }
        assertEquals(listOf(kit.a, kit.b, kit.service), saved.map { it.componentProductId })
        assertEquals(listOf(4_000L, 2_000L, 2_000L), saved.map { it.quantity.milli })
        assertEquals(listOf(true, true, false), saved.map { it.tracked })
        val rows = ledger.findBySale(saleId)
        assertEquals(
            listOf(StockMovementType.SALE, StockMovementType.SALE),
            rows.map {
                it.movement.type
            },
        )
        assertTrue(rows.all { it.source.saleLineComponentId != null })
        assertEquals(QuantityMilli(6_000), requireNotNull(products.findById(kit.a)).onHand)
        assertInvariantsHold()

        assertTrue(void(saleId, VoidReason.ENTRY_ERROR, null) is VoidSaleResult.Voided)

        assertEquals(4, ledger.findBySale(saleId).size)
        assertEquals(QuantityMilli(10_000), requireNotNull(products.findById(kit.a)).onHand)
        assertEquals(3, leaves.findBySale(saleId).size)
        assertInvariantsHold()
    }

    private class Kit(val a: Long, val b: Long, val service: Long, val combo: Long)

    /** El kit de 2 de A, 1 de B y un servicio de costo fijo 300, con 10 de A y 10 de B. */
    private suspend fun arrangeKit(): Kit = commit {
        val a = products.insert(newProduct("Té inventado"))
        ledger.append(openingRow(a, units = 10))
        val b = products.insert(newProduct("Taza inventada"))
        ledger.append(openingRow(b, units = 10))
        val service = products.insert(
            newProduct(
                "Envoltura inventada",
                productDetails("Envoltura inventada", stockMode = ProductStockMode.UNTRACKED)
                    .copy(fixedUnitCost = Money(300)),
            ).copy(initialCost = Money.ZERO),
        )
        val combo = products.insert(
            newProduct(
                "Kit inventado",
                productDetails("Kit inventado", stockMode = ProductStockMode.COMPONENTS),
            ).copy(initialCost = Money.ZERO, salePrice = Money(9_000)),
        )
        recipes.insert(combo, a, QuantityMilli(2_000), AT)
        recipes.insert(combo, b, QuantityMilli(1_000), AT)
        recipes.insert(combo, service, QuantityMilli(1_000), AT)
        Kit(a, b, service, combo)
    }

    private suspend fun cash(): Long =
        count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli), unitPrice = null)

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
