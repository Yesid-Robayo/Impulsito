package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vender por vencimiento sobre la base real: una fila de venta por lote, cada una con su lote y su
 * costo, la línea que cuesta la suma, el lote sin asignar en negativo cuando falta, y las
 * consultas de invariantes en cero, también al anular. Productos y lotes inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleLotsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val lots by lazy { RoomStockLotRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val registerSale by lazy {
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
            lots = lots,
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = ledger,
            clock = Clock { LATER_AT },
        )
    }

    /** Un producto con lotes: A vence el 10 y tiene 3; B vence el 20 y tiene 5, todo a 1.200. */
    private suspend fun lotProduct(): Triple<Long, Long, Long> = commit {
        val name = "Queso inventado"
        val id = products.insert(newProduct(name, productDetails(name, tracksLots = true)))
        fun lot(code: String, expiry: String) = NewStockLot(
            productId = id,
            code = code,
            expiryDate = BusinessDate.parse(expiry),
            purchaseLineId = null,
            createdAt = AT,
        )
        val later = lots.insert(lot("B", "2026-11-20"))
        val sooner = lots.insert(lot("A", "2026-11-10"))
        ledger.append(openingRow(id, units = 5, lotId = later))
        ledger.append(
            ledgerRow(
                id,
                StockMovementType.OPENING_BALANCE,
                quantity = 3_000,
                balanceAfter = 8_000,
                stockValueAfter = 9_600,
                lotId = sooner,
            ),
        )
        Triple(id, sooner, later)
    }

    private suspend fun sell(productId: Long, milli: Long): Long {
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val request = SaleRequest(listOf(SaleLineRequest(productId, QuantityMilli(milli))), method)
        return (registerSale(request) as RegisterSaleResult.Registered).saleId
    }

    @Test
    fun aSaleByExpiryWritesOneRowPerLotAndItsVoidReturnsEachOne() = runBlocking {
        val (id, sooner, later) = lotProduct()

        val saleId = sell(id, 9_000)

        val unassigned = requireNotNull(database.stockLotDao().findUnassigned(id)).id
        val rows = ledger.findBySale(saleId)
        assertEquals(listOf(sooner, later, unassigned), rows.map { it.movement.key.lotId })
        assertEquals(listOf(-3_000L, -5_000L, -1_000L), rows.map { it.movement.quantity.milli })
        val line = sales.findLines(saleId).single()
        assertEquals(-rows.sumOf { it.totalCost.minor }, line.content.lineCost.minor)
        assertEquals(
            listOf(0L, 0L, -1_000L),
            lots.findStock(id, LOCATION_ID).sortedBy { it.lotId }.map { it.onHand.milli },
        )
        assertInvariantsHold()

        assertEquals(VoidSaleResult.Voided, voidSale(saleId, VoidReason.ENTRY_ERROR, null))

        val back = lots.findStock(id, LOCATION_ID).associate { it.lotId to it.onHand.milli }
        assertEquals(mapOf(sooner to 3_000L, later to 5_000L, unassigned to 0L), back)
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
