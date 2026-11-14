package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Devolver un combo sobre Room, con sus guardas: cada hoja con stock vuelve en proporción con su
 * propio `SALE_RETURN` ligado a la hoja, y cada consulta de invariantes queda en cero, también la
 * de no devolver más de lo vendido. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ComboReturnOnDatabaseTest : RepositoryTest() {
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

    private val giveBack by lazy {
        RegisterSaleReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            returns = RoomSaleReturnRepository(database),
            units = RoomUnitRepository(database),
            ledger = ledger,
            clock = Clock { AT },
            lineComponents = leaves,
        )
    }

    @Test
    fun eachTrackedLeafComesBackInProportionAndEveryInvariantHolds() = runBlocking {
        val (a, b, combo) = arrangeKit()
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val sold = register(
            SaleRequest(listOf(SaleLineRequest(combo, QuantityMilli(3_000), null)), cash),
        )
        val saleId = (sold as RegisterSaleResult.Registered).saleId
        val lineId = sales.findLines(saleId).single().id

        repeat(3) {
            val result = giveBack(
                SaleReturnRequest(
                    saleId,
                    listOf(ReturnLineRequest(lineId, QuantityMilli(1_000))),
                    ReturnSettlement.Refund(null),
                ),
            )
            assertTrue(result is RegisterSaleReturnResult.Registered)
        }

        val returned = ledger.findByProduct(a)
            .filter { it.movement.type == StockMovementType.SALE_RETURN }
        assertEquals(listOf(2_000L, 2_000L, 2_000L), returned.map { it.movement.quantity.milli })
        // A salió a 1200: 7200 por los 3 kits, que vuelven en tres partes iguales de 2400.
        assertEquals(listOf(2_400L, 2_400L, 2_400L), returned.map { it.totalCost.minor })
        assertTrue(returned.all { it.source.saleLineComponentId != null })
        assertEquals(QuantityMilli(10_000), requireNotNull(products.findById(a)).onHand)
        assertEquals(QuantityMilli(10_000), requireNotNull(products.findById(b)).onHand)
        assertInvariantsHold()
    }

    /** El kit de 2 de A, 1 de B y un servicio de costo fijo 300, con 10 de A y 10 de B. */
    private suspend fun arrangeKit(): Triple<Long, Long, Long> = commit {
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
        Triple(a, b, combo)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
