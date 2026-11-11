package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.LineRejection
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vender por caja sobre Room, con sus guardas: la línea guarda la unidad, el factor y la cantidad
 * escrita, el libro sale en la unidad base, y cada consulta de invariantes queda en cero, así que
 * el saldo del producto sigue siendo la suma de su libro. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleUnitsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val productUnits by lazy { RoomProductUnitRepository(database) }

    private val register by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = RoomAppSettingsRepository(database, RestoreLock { false }),
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

    private suspend fun unitId(key: String): Long =
        count("SELECT id FROM units WHERE system_key = '$key'")

    private suspend fun sell(vararg lines: SaleLineRequest): RegisterSaleResult {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        return register(SaleRequest(lines.toList(), cash))
    }

    @Test
    fun threeBoxesPassEveryGuardAndLeaveTheLedgerInUnits() = runBlocking {
        val box = unitId("BOX")
        val id = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            ledger.append(openingRow(id, units = 100))
            productUnits.insert(id, ConversionDetails(box, 24_000, null, null, true, true), AT)
            id
        }

        val result = sell(SaleLineRequest(id, QuantityMilli(3_000), unitId = box))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val stored = RoomSaleRepository(database).findLines(saleId).single().content
        assertEquals(box, stored.unitId)
        assertEquals(QuantityMilli(24_000), stored.unitFactor)
        assertEquals(QuantityMilli(3_000), stored.enteredQuantity)
        assertEquals(QuantityMilli(72_000), stored.quantity)
        // La caja sin precio propio vale 24 veces el precio del producto.
        assertEquals(Money(24 * 2_500L), stored.unitPrice)
        val rows = ledger.findBySale(saleId)
        assertEquals(listOf(StockMovementType.SALE), rows.map { it.movement.type })
        assertEquals(QuantityMilli(-72_000), rows.single().movement.quantity)
        assertEquals(QuantityMilli(28_000), requireNotNull(products.findById(id)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun aUnitTheProductDoesNotSellIsRejected() = runBlocking {
        val id = commit { products.insert(newProduct("Jabón inventado")) }

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.UNIT_UNAVAILABLE),
            sell(SaleLineRequest(id, QuantityMilli(1_000), unitId = unitId("BOX"))),
        )
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
