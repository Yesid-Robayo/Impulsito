package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.catalog.supplier
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.purchaseReturn
import io.github.yesidrobayo.shopledger.data.local.purchaseReturnLine
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.local.saleReturnLine
import io.github.yesidrobayo.shopledger.data.local.stock.lot
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las devoluciones de venta y a proveedor: cada columna en su lugar y entera en sus topes,
 * anulación una sola vez y solo de sus columnas, lo ya devuelto de una línea sin las anuladas,
 * llaves que impiden huérfanos y protegen lo devuelto, y DAO de líneas que solo agregan y leen.
 */
@RunWith(AndroidJUnit4::class)
internal class ReturnDaoTest : LedgerDatabaseTest() {
    private val saleReturns get() = database.saleReturnDao()
    private val saleReturnLines get() = database.saleReturnLineDao()
    private val purchaseReturns get() = database.purchaseReturnDao()
    private val purchaseReturnLines get() = database.purchaseReturnLineDao()

    @Test
    fun aSaleReturnReadsBackWithEveryColumnAtItsLimits() = runBlocking {
        val saleLine = insertSaleWithLine()
        val replacement = database.saleDao().insert(sale(priceListId))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val header =
            saleReturn(saleLine.saleId).copy(
                resolution = "EXCHANGE",
                netRefund = Limit.DOCUMENT_TOTAL.max - 1,
                taxRefund = 1,
                totalRefund = Limit.DOCUMENT_TOTAL.max,
                costReturned = Limit.DOCUMENT_TOTAL.max - 2,
                moneyAccountId = cashAccountId(),
                replacementSaleId = replacement,
                note = "Cambio de talla",
                occurredAt = LATER,
                businessDate = "2026-01-02",
            )
        val id = saleReturns.insert(header)
        val line =
            saleReturnLine(id, saleLine.id).copy(
                quantityMilli = 2_000,
                netAmount = 9_000,
                taxAmount = 1_710,
                lineTotal = 10_710,
                lineCost = 6_000,
                restock = 0,
            )
        val lineId = saleReturnLines.insert(line)

        assertEquals(header.copy(id = id), saleReturns.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), saleReturnLines.findByReturn(id))
        assertNull(saleReturns.findById(MISSING_ID))
    }

    @Test
    fun voidingASaleReturnChangesOnlyItsColumnsAndOnlyOnce() = runBlocking {
        val saleLine = insertSaleWithLine()
        val id = saleReturns.insert(saleReturn(saleLine.saleId))
        val before = requireNotNull(saleReturns.findById(id))
        assertTrue(saleReturns.hasActiveReturn(saleLine.saleId))

        assertEquals(
            1,
            saleReturns.markVoided(id, MUCH_LATER, "ENTRY_ERROR", "Se registró dos veces"),
        )
        assertEquals(0, saleReturns.markVoided(id, MUCH_LATER, "UNDO", null))
        assertEquals(0, saleReturns.markVoided(MISSING_ID, MUCH_LATER, "UNDO", null))

        assertEquals(
            before.copy(
                status = "VOIDED",
                voidedAt = MUCH_LATER,
                voidReason = "ENTRY_ERROR",
                voidNote = "Se registró dos veces",
            ),
            saleReturns.findById(id),
        )
        assertFalse(saleReturns.hasActiveReturn(saleLine.saleId))
    }

    @Test
    fun returnsOfASaleEmitInOrderAndOnlyActiveOnesCountAsReturned() = runBlocking {
        val saleLine = insertSaleWithLine()
        val first = saleReturns.insert(saleReturn(saleLine.saleId))
        saleReturnLines.insert(saleReturnLine(first, saleLine.id).copy(quantityMilli = 500))
        assertEquals(0L, saleReturnLines.returnedQuantityMilli(MISSING_ID))

        val emissions = Emissions(this, saleReturns.observeBySale(saleLine.saleId).ids { it.id })
        try {
            emissions.awaitValue(listOf(first))
            val second = saleReturns.insert(saleReturn(saleLine.saleId))
            saleReturnLines.insert(saleReturnLine(second, saleLine.id).copy(quantityMilli = 700))
            emissions.awaitValue(listOf(first, second))
            assertEquals(1_200L, saleReturnLines.returnedQuantityMilli(saleLine.id))

            // Una devolución anulada sigue en la lista, pero lo suyo ya no cuenta como devuelto.
            saleReturns.markVoided(first, LATER, "UNDO", null)
            assertEquals(700L, saleReturnLines.returnedQuantityMilli(saleLine.id))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun whatASaleAlreadyReturnedAddsUpPerLineWithoutVoidedReturns() = runBlocking {
        val first = insertSaleWithLine()
        val second = saleLine(first.saleId, productId, priceListId)
        val secondId = database.saleLineDao().insert(second)
        val other = insertSaleWithLine()
        insertSaleReturnLine(other)
        assertEquals(emptyList<ReturnedLineTotals>(), saleReturnLines.takenBySale(first.saleId))

        val emissions = Emissions(this, saleReturnLines.observeTakenBySale(first.saleId))
        try {
            emissions.awaitValue(emptyList())
            val one = saleReturns.insert(saleReturn(first.saleId))
            saleReturnLines.insert(saleReturnLine(one, secondId).copy(quantityMilli = 500))
            saleReturnLines.insert(saleReturnLine(one, first.id))
            val two = saleReturns.insert(saleReturn(first.saleId))
            saleReturnLines.insert(
                saleReturnLine(two, first.id).copy(
                    quantityMilli = 250,
                    netAmount = 1_250,
                    taxAmount = 238,
                    lineTotal = 1_488,
                    lineCost = 750,
                    restock = 0,
                ),
            )
            val both = listOf(
                ReturnedLineTotals(first.id, 1_250, 6_250, 1_188, 3_750),
                ReturnedLineTotals(secondId, 500, 5_000, 950, 3_000),
            )
            emissions.awaitValue(both)
            assertEquals(both, saleReturnLines.takenBySale(first.saleId))

            // Lo que volvió sin reingreso cuenta igual: ya no se puede devolver. Lo anulado, no.
            saleReturns.markVoided(one, LATER, "ENTRY_ERROR", null)
            val onlyTwo = listOf(ReturnedLineTotals(first.id, 250, 1_250, 238, 750))
            emissions.awaitValue(onlyTwo)
            assertEquals(onlyTwo, saleReturnLines.takenBySale(first.saleId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun aPurchaseReturnReadsBackWithEveryColumnAndIsVoidedOnce() = runBlocking {
        val supplierId = database.supplierDao().insert(supplier("Distribuidora Andina"))
        val purchaseLine = insertPurchaseWithLine()
        val header =
            purchaseReturn().copy(
                purchaseId = purchaseLine.purchaseId,
                supplierId = supplierId,
                resolution = "SUPPLIER_CREDIT",
                total = Limit.DOCUMENT_TOTAL.max,
                moneyAccountId = cashAccountId(),
                note = "Caja golpeada",
                occurredAt = LATER,
                businessDate = "2026-01-03",
            )
        val id = purchaseReturns.insert(header)
        val line =
            purchaseReturnLine(id, productId).copy(
                purchaseLineId = purchaseLine.id,
                lotId = null,
                quantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                unitCost = Limit.UNIT_MONEY.max,
                lineTotal = Limit.DOCUMENT_TOTAL.max - 1,
            )
        val lineId = purchaseReturnLines.insert(line)

        assertEquals(header.copy(id = id), purchaseReturns.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), purchaseReturnLines.findByReturn(id))

        val emissions = Emissions(this, purchaseReturns.observeBySupplier(supplierId))
        try {
            emissions.awaitValue(listOf(header.copy(id = id)))
            assertEquals(1, purchaseReturns.markVoided(id, MUCH_LATER, "OTHER", null))
            assertEquals(0, purchaseReturns.markVoided(id, MUCH_LATER, "UNDO", null))
            emissions.awaitValue(
                listOf(
                    header.copy(
                        id = id,
                        status = "VOIDED",
                        voidedAt = MUCH_LATER,
                        voidReason = "OTHER",
                    ),
                ),
            )
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun keysRejectOrphansAndProtectWhatAReturnUses() = runBlocking {
        val saleLine = insertSaleWithLine()
        val saleReturnId = saleReturns.insert(saleReturn(saleLine.saleId))
        constraintFailure(FOREIGN_KEY) {
            saleReturns.insert(saleReturn(saleLine.saleId).copy(replacementSaleId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            saleReturnLines.insert(saleReturnLine(MISSING_ID, saleLine.id))
        }
        constraintFailure(FOREIGN_KEY) {
            saleReturns.insert(saleReturn(saleLine.saleId).copy(moneyAccountId = MISSING_ID))
        }

        // Un proveedor y un producto sin otra historia quedan protegidos por la devolución.
        val supplierId = database.supplierDao().insert(supplier("Lácteos del Valle"))
        val cheese = database.productDao().insert(product("Queso"))
        val purchaseReturnId =
            purchaseReturns.insert(purchaseReturn().copy(supplierId = supplierId))
        purchaseReturnLines.insert(purchaseReturnLine(purchaseReturnId, cheese))
        constraintFailure(FOREIGN_KEY) { database.supplierDao().delete(supplierId) }
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(cheese) }
        constraintFailure(FOREIGN_KEY) {
            purchaseReturns.insert(purchaseReturn().copy(purchaseId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            purchaseReturns.insert(purchaseReturn().copy(moneyAccountId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            purchaseReturnLines.insert(
                purchaseReturnLine(purchaseReturnId, cheese).copy(purchaseLineId = MISSING_ID),
            )
        }
        constraintFailure(FOREIGN_KEY) {
            purchaseReturnLines.insert(purchaseReturnLine(MISSING_ID, cheese))
        }

        // El lote de una línea devuelta existe y queda protegido por ella.
        constraintFailure(FOREIGN_KEY) {
            purchaseReturnLines.insert(
                purchaseReturnLine(purchaseReturnId, cheese).copy(lotId = MISSING_ID),
            )
        }
        val lotId = database.stockLotDao().insert(lot(cheese, "L-0425"))
        assertFalse(database.stockLotDao().isInUse(lotId))
        purchaseReturnLines.insert(purchaseReturnLine(purchaseReturnId, cheese).copy(lotId = lotId))
        assertTrue(database.stockLotDao().isInUse(lotId))
        constraintFailure(FOREIGN_KEY) { database.stockLotDao().delete(lotId) }

        assertEquals(1L, count("SELECT COUNT(*) FROM sale_returns WHERE id = $saleReturnId"))
        assertEquals(2L, count("SELECT COUNT(*) FROM purchase_return_lines"))
    }

    @Test
    fun theDaosOnlyVoidTheHeadersAndOnlyAppendLines() {
        // Sin funciones de editar ni de borrar, a propósito: una devolución confirmada solo se
        // anula, y su línea congela lo que volvió y a qué costo.
        assertEquals(
            setOf("observeBySale", "findById", "hasActiveReturn", "insert", "markVoided"),
            functionsOf(SaleReturnDao::class.java),
        )
        assertEquals(
            setOf(
                "findByReturn",
                "returnedQuantityMilli",
                "takenBySale",
                "observeTakenBySale",
                "insert",
            ),
            functionsOf(SaleReturnLineDao::class.java),
        )
        assertEquals(
            setOf(
                "observeBySupplier",
                "observeByPurchase",
                "observeById",
                "findById",
                "hasActiveReturn",
                "insert",
                "markVoided",
            ),
            functionsOf(PurchaseReturnDao::class.java),
        )
        assertEquals(
            setOf("findByReturn", "observeByReturn", "takenByPurchase", "insert"),
            functionsOf(PurchaseReturnLineDao::class.java),
        )
    }

    /** La cuenta de efectivo, que nace con la base. */
    private suspend fun cashAccountId(): Long =
        count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")

    private companion object {
        const val MISSING_ID = 999L
    }
}
