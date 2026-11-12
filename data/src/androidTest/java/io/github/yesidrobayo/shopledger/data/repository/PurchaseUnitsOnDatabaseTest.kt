package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprar por caja y vender por unidad sobre la base real, con sus guardas: la línea de compra
 * guarda la unidad, el factor y la cantidad escrita, el libro suma la cantidad en la unidad base, y
 * vender las 24 unidades de una caja por 10000 se lleva exactamente 10000. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PurchaseUnitsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val productUnits by lazy { RoomProductUnitRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val buy by lazy {
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
            productUnits = productUnits,
        )
    }

    private val sell by lazy {
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
            productUnits = productUnits,
        )
    }

    @Test
    fun aBoxOfTwentyFourSellsOutAtExactlyWhatWasPaid() = runBlocking {
        val box = count("SELECT id FROM units WHERE system_key = 'BOX'")
        val id = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            productUnits.insert(id, ConversionDetails(box, 24_000, null, null, false, true), AT)
            id
        }
        val line = PurchaseLineRequest(
            id,
            QuantityMilli(1_000),
            PurchaseLineAmount.LineTotal(Money(10_000)),
            unitId = box,
        )

        val result = buy(PurchaseRequest(listOf(line)))

        val purchaseId = (result as RegisterPurchaseResult.Registered).purchaseId
        val stored = purchases.findLines(purchaseId).single().content
        assertEquals(box, stored.unitId)
        assertEquals(QuantityMilli(24_000), stored.unitFactor)
        assertEquals(QuantityMilli(1_000), stored.enteredQuantity)
        assertEquals(QuantityMilli(24_000), stored.quantity)
        assertEquals(Money(10_000), stored.unitCost)
        val bought = requireNotNull(products.findById(id))
        assertEquals(QuantityMilli(24_000), bought.onHand)
        assertEquals(Money(417), bought.averageCost)
        assertEquals(Money(10_000), bought.stockValue)

        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        repeat(24) {
            val sale = sell(SaleRequest(listOf(SaleLineRequest(id, QuantityMilli(1_000))), cash))
            assertEquals(true, sale is RegisterSaleResult.Registered)
        }

        assertEquals(10_000L, count("SELECT SUM(line_cost) FROM sale_lines WHERE product_id = $id"))
        val soldOut = requireNotNull(products.findById(id))
        assertEquals(QuantityMilli.ZERO, soldOut.onHand)
        assertEquals(Money.ZERO, soldOut.stockValue)
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
