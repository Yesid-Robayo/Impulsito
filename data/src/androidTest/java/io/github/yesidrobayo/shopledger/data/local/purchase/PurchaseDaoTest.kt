package io.github.yesidrobayo.shopledger.data.local.purchase

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.catalog.supplier
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.purchaseLine
import io.github.yesidrobayo.shopledger.data.local.stock.location
import io.github.yesidrobayo.shopledger.data.local.stock.lot
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las compras y sus líneas: cada columna en su lugar y entera, la lista de un período de la más
 * nueva a la más antigua, una anulación que toca solo sus columnas y una sola vez, líneas que solo
 * se agregan y en su orden, y llaves que protegen al proveedor y al producto con compras.
 */
@RunWith(AndroidJUnit4::class)
internal class PurchaseDaoTest : LedgerDatabaseTest() {
    private val purchases get() = database.purchaseDao()
    private val lines get() = database.purchaseLineDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val supplierId = database.supplierDao().insert(supplier("Distribuidora Andina"))
        // La ubicación y el lote existen, con el id que espera cada columna.
        database.locationDao().insert(location("Bodega").copy(id = 4))
        database.stockLotDao().insert(lot(productId, "L-2026-01").copy(id = 17))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen. Se pagó una
        // parte, con deuda al proveedor, en otra moneda, desde una cuenta que existe.
        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco").copy(id = 3))
        val header =
            PurchaseEntity(
                supplierId = supplierId,
                status = "ACTIVE",
                invoiceReference = "FAC-2026-118",
                netTotal = 84_000,
                taxTotal = 15_960,
                total = 99_960,
                costTotal = 84_001,
                amountPaid = 40_000,
                moneyAccountId = accountId,
                dueDate = "2026-02-15",
                locationId = 4,
                fxCurrencyCode = "XTS",
                fxRateMicro = 4_150_000_000,
                occurredAt = CREATED_AT,
                createdAt = LATER,
                businessDate = "2026-01-15",
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            )
        val purchaseId = purchases.insert(header)
        val line =
            PurchaseLineEntity(
                purchaseId = purchaseId,
                productId = productId,
                unitId = 2,
                unitFactorMilli = 24_000,
                enteredQuantityMilli = 3_500,
                quantityMilli = 84_000,
                unitCost = 24_001,
                lineTotal = 99_960,
                taxRateBp = 1_900,
                taxNameSnapshot = "Tasa general",
                taxAmount = 15_960,
                costAmount = 84_002,
                lotId = 17,
                referenceUnitCost = 5_800,
            )
        val lineId = lines.insert(line)

        assertEquals(header.copy(id = purchaseId), purchases.findById(purchaseId))
        assertEquals(listOf(line.copy(id = lineId)), lines.findByPurchase(purchaseId))
        assertNull(purchases.findById(MISSING_ID))
    }

    @Test
    fun periodListIsNewestFirstWithBothEndsIncluded() = runBlocking {
        val before = purchases.insert(purchase(null, businessDate = "2025-12-31"))
        val firstDay = purchases.insert(purchase(null, businessDate = "2026-01-01"))
        val lastDay = purchases.insert(purchase(null, businessDate = "2026-01-31"))
        // Con un reloj que dice antes, pero registrada después en la misma fecha de negocio.
        val sameLastDay =
            purchases.insert(
                purchase(null, businessDate = "2026-01-31", occurredAt = CREATED_AT - 1),
            )
        // Registrada después, pero con una fecha anterior: el orden es por fecha de negocio.
        val middle = purchases.insert(purchase(null, businessDate = "2026-01-10"))
        purchases.insert(purchase(null, businessDate = "2026-02-01"))

        val emissions =
            Emissions(this, purchases.observeIn("2026-01-01", "2026-01-31").ids { it.id })
        try {
            // En la misma fecha, la registrada después va primero.
            emissions.awaitValue(listOf(sameLastDay, lastDay, middle, firstDay))

            // Una anulada sigue en la lista, y la lista vuelve a emitir con una compra nueva.
            purchases.markVoided(
                middle,
                voidedAt = LATER,
                voidReason = "ENTRY_ERROR",
                voidNote = null,
            )
            val newest = purchases.insert(purchase(null, businessDate = "2026-01-20"))
            emissions.awaitValue(listOf(sameLastDay, lastDay, newest, middle, firstDay))
        } finally {
            emissions.stop()
        }
        assertEquals(
            listOf(before),
            purchases.observeIn("2025-12-31", "2025-12-31").first().map {
                it.id
            },
        )
    }

    @Test
    fun voidingChangesOnlyItsColumnsAndOnlyOnce() = runBlocking {
        val id = purchases.insert(purchase(supplierId = null))
        val line = purchaseLine(id, productId)
        val lineId = lines.insert(line)
        val active = requireNotNull(purchases.findById(id))

        val emissions = Emissions(this, purchases.observeById(id))
        try {
            emissions.awaitValue(active)
            assertEquals(1, purchases.markVoided(id, LATER, "SUPPLIER_RETURN", "Llegó dañada"))
            val voided =
                active.copy(
                    status = "VOIDED",
                    voidedAt = LATER,
                    voidReason = "SUPPLIER_RETURN",
                    voidNote = "Llegó dañada",
                )
            emissions.awaitValue(voided)

            // Un documento se anula una sola vez: la segunda anulación no cambia nada.
            assertEquals(0, purchases.markVoided(id, MUCH_LATER, "OTHER", null))
            assertEquals(voided, purchases.findById(id))
        } finally {
            emissions.stop()
        }
        assertEquals(0, purchases.markVoided(MISSING_ID, LATER, "ENTRY_ERROR", null))
        // Las líneas no cambian al anular la compra.
        assertEquals(listOf(line.copy(id = lineId)), lines.findByPurchase(id))
    }

    @Test
    fun linesKeepTheirSavedOrderAndTheSameProductCanRepeat() = runBlocking {
        val id = purchases.insert(purchase(supplierId = null))
        val otherId = purchases.insert(purchase(supplierId = null))
        val tea = database.productDao().insert(product("Té"))
        val box = purchaseLine(id, productId)
        val firstId = lines.insert(box)
        lines.insert(purchaseLine(otherId, productId))
        val teaId = lines.insert(purchaseLine(id, tea))

        val emissions = Emissions(this, lines.observeByPurchase(id).ids { it.id })
        try {
            emissions.awaitValue(listOf(firstId, teaId))

            // El mismo producto otra vez en la misma compra: con otro costo, por ejemplo.
            val againId = lines.insert(
                box.copy(unitCost = 19_500, lineTotal = 19_500, costAmount = 19_500),
            )
            emissions.awaitValue(listOf(firstId, teaId, againId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun keysProtectTheSupplierAndTheProductWithPurchases() = runBlocking {
        constraintFailure(FOREIGN_KEY) { purchases.insert(purchase(supplierId = MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) { lines.insert(purchaseLine(MISSING_ID, productId)) }
        val id = purchases.insert(purchase(supplierId = null))
        constraintFailure(FOREIGN_KEY) { lines.insert(purchaseLine(id, MISSING_ID)) }

        val supplierId = database.supplierDao().insert(supplier("Molino del Valle"))
        purchases.insert(purchase(supplierId))
        lines.insert(purchaseLine(id, productId))

        // Con compras, el proveedor y el producto se archivan: borrarlos dejaría compras huérfanas.
        constraintFailure(FOREIGN_KEY) { database.supplierDao().delete(supplierId) }
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }
        assertEquals(1L, count("SELECT COUNT(*) FROM purchase_lines"))
    }

    @Test
    fun moneyAndQuantitiesAreExactIntegersAtTheirLimits() = runBlocking {
        val header =
            purchase(supplierId = null).copy(
                netTotal = Limit.DOCUMENT_TOTAL.max - 1,
                taxTotal = 1,
                total = Limit.DOCUMENT_TOTAL.max,
                costTotal = Limit.DOCUMENT_TOTAL.max,
                amountPaid = Limit.DOCUMENT_TOTAL.max,
                fxCurrencyCode = "XTS",
                fxRateMicro = Limit.DOCUMENT_TOTAL.max,
            )
        val id = purchases.insert(header)
        val line =
            purchaseLine(id, productId).copy(
                unitFactorMilli = Limit.LINE_QUANTITY_MILLI.max,
                enteredQuantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                quantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                unitCost = Limit.UNIT_MONEY.max,
                lineTotal = Limit.DOCUMENT_TOTAL.max,
                taxRateBp = 10_000,
                taxAmount = Limit.DOCUMENT_TOTAL.max,
                costAmount = Limit.DOCUMENT_TOTAL.max,
                referenceUnitCost = Limit.UNIT_MONEY.max,
            )
        val lineId = lines.insert(line)

        assertEquals(header.copy(id = id), purchases.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), lines.findByPurchase(id))
        val integers =
            count(
                "SELECT COUNT(*) FROM purchases p JOIN purchase_lines l ON l.purchase_id = p.id " +
                    "WHERE typeof(p.total) = 'integer' AND typeof(p.cost_total) = 'integer' " +
                    "AND typeof(p.fx_rate_micro) = 'integer' " +
                    "AND typeof(l.quantity_milli) = 'integer' " +
                    "AND typeof(l.unit_cost) = 'integer' AND typeof(l.cost_amount) = 'integer'",
            )
        assertEquals(1L, integers)
    }

    @Test
    fun insertingALineWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val id = purchases.insert(purchase(supplierId = null))
        val original = purchaseLine(id, productId)
        val lineId = lines.insert(original)

        // Un INSERT OR REPLACE borraría la línea y escribiría otra en su lugar. La guarda rechaza
        // el id repetido antes que la llave primaria, también con REPLACE.
        guardFailure("guard:purchase_lines") {
            lines.insert(original.copy(id = lineId, unitCost = 1))
        }
        guardFailure("guard:purchases") {
            purchases.insert(purchase(null).copy(id = id, total = 1))
        }

        assertEquals(listOf(original.copy(id = lineId)), lines.findByPurchase(id))
        assertEquals(20_000L, purchases.findById(id)?.total)
    }

    @Test
    fun theDaosOnlyVoidTheHeaderAndOnlyAppendLines() {
        // Una función nueva que edite una compra, o que edite o borre una línea, rompe esta prueba
        // a propósito: una compra confirmada solo se anula.
        assertEquals(
            setOf(
                "observeIn",
                "observeById",
                "findById",
                "insert",
                "markVoided",
                // Las lecturas del historial: solo leen.
                "historyPage",
                "dayTotals",
                "observeHistoryMark",
                "observeLastCosts",
            ),
            functionsOf(PurchaseDao::class.java),
        )
        assertEquals(
            setOf("observeByPurchase", "findByPurchase", "insert"),
            functionsOf(PurchaseLineDao::class.java),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
