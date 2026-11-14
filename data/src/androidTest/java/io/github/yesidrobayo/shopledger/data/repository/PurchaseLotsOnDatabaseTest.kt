package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprar con lote sobre la base real: el lote nuevo nace en la transacción de la compra y anota
 * su línea, otra entrega suma al mismo lote, una línea sin código entra al lote sin asignar, y las
 * consultas de invariantes quedan en cero, también al anular. Productos y lotes inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PurchaseLotsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val lots by lazy { RoomStockLotRepository(database) }
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
            lots = lots,
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

    private fun line(productId: Long, milli: Long, lot: PurchaseLineLot?) = PurchaseLineRequest(
        productId,
        QuantityMilli(milli),
        PurchaseLineAmount.UnitCost(Money(1_000)),
        lot = lot,
    )

    private suspend fun buy(vararg lines: PurchaseLineRequest): RegisterPurchaseResult =
        registerPurchase(PurchaseRequest(lines.toList()))

    private suspend fun lotProduct(name: String): Long = commit {
        products.insert(newProduct(name, productDetails(name, tracksLots = true)))
    }

    @Test
    fun aPurchaseWithLotsPassesEveryGuardAndItsVoidReturnsEachLot() = runBlocking {
        val id = lotProduct("Queso inventado")
        val expiry = requireNotNull(BusinessDate.parse("2026-11-20"))
        val first = buy(line(id, 4_000, PurchaseLineLot.New("A-118", expiry)))
        assertTrue(first is RegisterPurchaseResult.Registered)
        val lotA = requireNotNull(lots.findByCode(id, "A-118"))
        val before = changeSeq()

        val second = buy(
            line(id, 2_000, PurchaseLineLot.Existing(lotA.id)),
            line(id, 1_000, lot = null),
        )

        assertTrue(second is RegisterPurchaseResult.Registered)
        assertEquals(before + 1, changeSeq())
        val firstId = (first as RegisterPurchaseResult.Registered).purchaseId
        val firstLine = purchases.findLines(firstId).single()
        assertEquals(lotA.id, firstLine.content.lotId)
        assertEquals(firstLine.id, lotA.purchaseLineId)
        assertEquals(expiry, lotA.expiryDate)
        val stock = lots.findStock(id, LOCATION_ID)
        assertEquals(listOf("A-118", null), stock.map { it.code })
        assertEquals(listOf(6_000L, 1_000L), stock.map { it.onHand.milli })
        assertInvariantsHold()

        val secondId = (second as RegisterPurchaseResult.Registered).purchaseId
        voidPurchase(secondId, VoidReason.ENTRY_ERROR, null)

        val afterVoid = lots.findStock(id, LOCATION_ID)
        assertEquals(listOf(4_000L, 0L), afterVoid.map { it.onHand.milli })
        assertInvariantsHold()
    }

    @Test
    fun aRepeatedCodeIsRejectedBeforeWriting() = runBlocking {
        val id = lotProduct("Leche inventada")
        buy(line(id, 1_000, PurchaseLineLot.New("SN-1", null)))
        val before = changeSeq()

        val repeated = buy(line(id, 1_000, PurchaseLineLot.New("SN-1", null)))

        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.LOT_CODE_REPEATED),
            repeated,
        )
        assertEquals(before, changeSeq())
        assertEquals(1, lots.findStock(id, LOCATION_ID).size)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
