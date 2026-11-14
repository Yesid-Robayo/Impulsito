package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnUseCase
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
 * Un combo cuya hoja se controla por lotes, sobre la base real: la hoja sale por vencimiento con
 * una fila por lote ligada a la hoja, la devolución vuelve a los lotes de los que salió, anular la
 * devolución la saca otra vez de esos lotes y anular la venta devuelve cada fila a su lote. Las
 * consultas de invariantes quedan en cero en cada paso. Productos y lotes inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ComboLotsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val recipes by lazy { RoomProductComponentRepository(database) }
    private val leaves by lazy { RoomSaleLineComponentRepository(database) }
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
            recipes = recipes,
            lineComponents = leaves,
            lots = lots,
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

    private val voidReturn by lazy {
        VoidSaleReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            returns = RoomSaleReturnRepository(database),
            ledger = ledger,
            clock = Clock { LATER_AT },
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

    /** El queso tiene lotes: A vence el 10 y tiene 3; B vence el 20 y tiene 5, todo a 1200. */
    private class Kit(val cheese: Long, val sooner: Long, val later: Long, val combo: Long)

    private suspend fun arrangeKit(): Kit = commit {
        val name = "Queso inventado"
        val cheese = products.insert(newProduct(name, productDetails(name, tracksLots = true)))
        fun lot(code: String, expiry: String) = NewStockLot(
            productId = cheese,
            code = code,
            expiryDate = BusinessDate.parse(expiry),
            purchaseLineId = null,
            createdAt = AT,
        )
        val later = lots.insert(lot("B", "2026-11-20"))
        val sooner = lots.insert(lot("A", "2026-11-10"))
        ledger.append(openingRow(cheese, units = 5, lotId = later))
        ledger.append(
            ledgerRow(
                cheese,
                StockMovementType.OPENING_BALANCE,
                quantity = 3_000,
                balanceAfter = 8_000,
                stockValueAfter = 9_600,
                lotId = sooner,
            ),
        )
        val combo = products.insert(
            newProduct(
                "Tabla inventada",
                productDetails("Tabla inventada", stockMode = ProductStockMode.COMPONENTS),
            ).copy(initialCost = Money.ZERO, salePrice = Money(9_000)),
        )
        recipes.insert(combo, cheese, QuantityMilli(2_000), AT)
        Kit(cheese, sooner, later, combo)
    }

    private suspend fun onHandByLot(productId: Long): Map<Long, Long> =
        lots.findStock(productId, LOCATION_ID).associate { it.lotId to it.onHand.milli }

    @Test
    fun aComboLeafLeavesByExpiryAndComesBackToItsLotsOnReturnAndVoid() = runBlocking {
        val kit = arrangeKit()
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

        // Tres tablas sacan 6 de queso: los 3 de A, que vence antes, y 3 de B.
        val sold = registerSale(
            SaleRequest(listOf(SaleLineRequest(kit.combo, QuantityMilli(3_000))), cash),
        )
        val saleId = (sold as RegisterSaleResult.Registered).saleId
        val rows = ledger.findBySale(saleId)
        assertEquals(listOf(kit.sooner, kit.later), rows.map { it.movement.key.lotId })
        assertEquals(listOf(-3_000L, -3_000L), rows.map { it.movement.quantity.milli })
        assertTrue(rows.all { it.source.saleLineComponentId != null })
        assertEquals(mapOf(kit.sooner to 0L, kit.later to 2_000L), onHandByLot(kit.cheese))
        assertInvariantsHold()

        // Dos tablas devuelven 4: los 3 de A y 1 de B, en el orden de salida.
        val lineId = sales.findLines(saleId).single().id
        val returned = giveBack(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(lineId, QuantityMilli(2_000))),
                ReturnSettlement.Refund(null),
            ),
        )
        val returnId = (returned as RegisterSaleReturnResult.Registered).saleReturnId
        assertEquals(mapOf(kit.sooner to 3_000L, kit.later to 3_000L), onHandByLot(kit.cheese))
        assertInvariantsHold()

        assertEquals(
            VoidSaleReturnResult.Voided,
            voidReturn(returnId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(mapOf(kit.sooner to 0L, kit.later to 2_000L), onHandByLot(kit.cheese))
        assertInvariantsHold()

        assertEquals(VoidSaleResult.Voided, voidSale(saleId, VoidReason.ENTRY_ERROR, null))
        assertEquals(mapOf(kit.sooner to 3_000L, kit.later to 5_000L), onHandByLot(kit.cheese))
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
