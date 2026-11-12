package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El detalle de un producto sobre la base real: corregir el costo pasa cada guarda del libro como un
 * solo cambio, y cada fila de una compra sabe de qué compra salió.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductDetailOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val correct by lazy {
        CorrectCostUseCase(
            runner,
            products,
            RoomSystemRowRepository(database),
            ledger,
            BusinessCalendarSource(settings),
            CurrencyLock(settings),
            Clock { LATER_AT },
        )
    }

    private val overview by lazy {
        ObserveProductOverviewUseCase(
            products,
            RoomUnitRepository(database),
            ledger,
            RoomPriceHistoryRepository(database),
            RoomMovementDocumentSource(database),
        )
    }

    @Test
    fun correctingTheCostWritesAZeroRowAndValuesTheStockAtTheNewCost() = runBlocking {
        commit { ledger.append(openingRow(productId, units = 10)) }
        val before = changeSeq()

        val result = correct(productId, Money(1_500L), "Factura perdida")

        val movementId = (result as CorrectCostResult.Corrected).movementId
        assertEquals(before + 1, changeSeq())
        val row = requireNotNull(ledger.findById(movementId))
        assertEquals(StockMovementType.COST_CORRECTION, row.movement.type)
        assertEquals(QuantityMilli.ZERO, row.movement.quantity)
        assertEquals(Money(1_500L), row.unitCost)
        assertEquals(Money.ZERO, row.totalCost)
        assertEquals(Money(15_000L), row.stockValueAfter)
        val product = requireNotNull(products.findById(productId))
        assertEquals(Money(1_500L), product.averageCost)
        assertEquals(Money(15_000L), product.stockValue)
        assertEquals(QuantityMilli(10_000L), product.onHand)
        assertInvariantsHold()

        val shown = requireNotNull(overview(productId).first())
        assertEquals(listOf(movementId, movementId - 1), shown.movements.map { it.id })
        assertEquals(Money(1_200L), shown.movements.first().averageCostBefore)
    }

    @Test
    fun eachPurchaseRowKnowsItsPurchase() = runBlocking {
        val register =
            RegisterPurchaseUseCase(
                transactions = runner,
                settings = settings,
                products = products,
                purchases = RoomPurchaseRepository(database),
                suppliers = RoomSupplierRepository(database),
                units = RoomUnitRepository(database),
                systemRows = RoomSystemRowRepository(database),
                ledger = ledger,
                taxRates = RoomTaxRateRepository(database),
                clock = Clock { AT },
            )
        val line =
            PurchaseLineRequest(
                productId,
                QuantityMilli(3_000L),
                PurchaseLineAmount.UnitCost(Money(900L)),
            )

        val result = register(PurchaseRequest(listOf(line), invoiceReference = "F-0042"))

        val purchaseId = (result as RegisterPurchaseResult.Registered).purchaseId
        val documents = RoomMovementDocumentSource(database).observeDocuments(productId).first()
        assertEquals(listOf(MovementDocument.Purchase(purchaseId)), documents.values.toList())
        val shown = requireNotNull(overview(productId).first())
        assertEquals(MovementDocument.Purchase(purchaseId), shown.movements.single().document)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
