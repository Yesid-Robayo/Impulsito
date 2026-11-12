package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Registrar y anular una compra sobre la base real, con sus guardas: la cabecera, las líneas, las
 * entradas del libro y sus compensatorios pasan cada guarda, cada caso de uso es un solo cambio
 * para el respaldo, y cada consulta de invariantes queda en cero. Los escenarios de anular son los
 * de la regla, con los valores escritos a mano.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PurchaseOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val registerPurchase by lazy {
        RegisterPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            suppliers = RoomSupplierRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private val voidPurchase by lazy {
        VoidPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            ledger = ledger,
            clock = Clock { LATER_AT },
        )
    }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private suspend fun buy(vararg lines: PurchaseLineRequest): Long {
        val result = registerPurchase(PurchaseRequest(lines.toList(), invoiceReference = "F-0042"))
        return (result as RegisterPurchaseResult.Registered).purchaseId
    }

    private fun atUnitCost(productId: Long, milli: Long, cost: Long) = PurchaseLineRequest(
        productId,
        QuantityMilli(milli),
        PurchaseLineAmount.UnitCost(Money(cost)),
    )

    /** Un producto con 10 unidades de inventario inicial a 1.000: el punto de partida de la regla. */
    private suspend fun stockedProduct(name: String): Long = commit {
        val id = products.insert(newProduct(name))
        ledger.append(
            ledgerRow(
                id,
                StockMovementType.OPENING_BALANCE,
                quantity = 10_000,
                balanceAfter = 10_000,
                unitCost = 1_000,
                totalCost = 10_000,
                averageCostAfter = 1_000,
                stockValueAfter = 10_000,
            ),
        )
        id
    }

    @Test
    fun aPurchasePassesEveryGuardAsOneChange() = runBlocking {
        val first = stockedProduct("Jabón inventado")
        val second = commit { products.insert(newProduct("Cepillo inventado")) }
        val before = changeSeq()

        val purchaseId = buy(
            atUnitCost(first, 10_000, cost = 2_000),
            atUnitCost(second, 3_000, cost = 500),
            atUnitCost(first, 20_000, cost = 3_000),
        )

        assertEquals(before + 1, changeSeq())
        val purchase = requireNotNull(purchases.findById(purchaseId))
        assertEquals(DocumentStatus.ACTIVE, purchase.status)
        assertEquals(Money(81_500), purchase.content.total)
        assertEquals(Money(81_500), purchase.content.costTotal)
        assertEquals("F-0042", purchase.content.invoiceReference)
        assertEquals(3, purchases.findLines(purchaseId).size)
        val rows = ledger.findByPurchase(purchaseId)
        assertEquals(List(3) { StockMovementType.PURCHASE }, rows.map { it.movement.type })
        // 10000 + 20000 + 60000 = 90000 por 40 unidades.
        val after = requireNotNull(products.findById(first))
        assertEquals(QuantityMilli(40_000), after.onHand)
        assertEquals(Money(2_250), after.averageCost)
        assertEquals(Money(90_000), after.stockValue)
        assertInvariantsHold()
    }

    @Test
    fun scenarioAVoidGoesBackToTheExactCost() = runBlocking {
        val id = stockedProduct("Jabón inventado")
        val purchaseId = buy(atUnitCost(id, 1_000, cost = 50_000))
        assertEquals(Money(5_455), requireNotNull(products.findById(id)).averageCost)
        val before = changeSeq()

        assertEquals(
            VoidPurchaseResult.Voided,
            voidPurchase(purchaseId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(
            VoidPurchaseResult.AlreadyVoided,
            voidPurchase(purchaseId, VoidReason.ENTRY_ERROR, null),
        )

        assertEquals(before + 1, changeSeq())
        assertEquals(DocumentStatus.VOIDED, requireNotNull(purchases.findById(purchaseId)).status)
        val rows = ledger.findByPurchase(purchaseId)
        assertEquals(
            listOf(StockMovementType.PURCHASE, StockMovementType.PURCHASE_VOID),
            rows.map { it.movement.type },
        )
        assertEquals(rows[0].id, rows[1].movement.reversesMovementId)
        val after = requireNotNull(products.findById(id))
        assertEquals(QuantityMilli(10_000), after.onHand)
        assertEquals(Money(1_000), after.averageCost)
        assertEquals(Money(10_000), after.stockValue)
        assertInvariantsHold()
    }

    @Test
    fun scenarioBVoidKeepsTheSaleCost() = runBlocking {
        val id = stockedProduct("Jabón inventado")
        val purchaseId = buy(atUnitCost(id, 1_000, cost = 50_000))
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val sale =
            registerSale(SaleRequest(listOf(SaleLineRequest(id, QuantityMilli(9_000))), method))
        val saleId = (sale as RegisterSaleResult.Registered).saleId

        assertEquals(VoidPurchaseResult.Voided, voidPurchase(purchaseId, VoidReason.OTHER, "Nota"))

        val line = RoomSaleRepository(database).findLines(saleId).single()
        assertEquals(Money(5_455), line.content.unitCost)
        assertEquals(Money(49_091), line.content.lineCost)
        val after = requireNotNull(products.findById(id))
        assertEquals(QuantityMilli(1_000), after.onHand)
        assertEquals(Money(1_000), after.averageCost)
        assertEquals(Money.ZERO, after.stockValue)
        assertInvariantsHold()
    }

    @Test
    fun scenarioCVoidLeavesNegativeStockAtTheInitialCost() = runBlocking {
        // El producto de las pruebas nace con costo inicial de 1.200.
        val id = commit { products.insert(newProduct("Jabón inventado")) }
        val purchaseId = buy(atUnitCost(id, 10_000, cost = 1_000))
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        registerSale(SaleRequest(listOf(SaleLineRequest(id, QuantityMilli(10_000))), method))

        assertEquals(VoidPurchaseResult.Voided, voidPurchase(purchaseId, VoidReason.UNDO, null))

        val after = requireNotNull(products.findById(id))
        assertEquals(QuantityMilli(-10_000), after.onHand)
        assertEquals(Money(1_200), after.averageCost)
        assertEquals(Money.ZERO, after.stockValue)
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
