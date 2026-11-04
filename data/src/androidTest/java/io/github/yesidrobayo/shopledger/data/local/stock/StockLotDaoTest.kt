package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.openingBalance
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.purchaseLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los lotes: cada columna en su lugar, código único dentro de su producto, un solo lote sin asignar
 * por producto, orden por entrada, cada edición en sus columnas, y un lote con filas en el libro o
 * con una línea de compra, que no se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class StockLotDaoTest : LedgerDatabaseTest() {
    private val lots get() = database.stockLotDao()
    private var cheese = 0L

    @Before
    fun createProductWithLots() = runBlocking {
        cheese = database.productDao().insert(product("Queso").copy(tracksLots = 1))
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val lineId = insertPurchaseWithLine().id
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val received =
            StockLotEntity(
                productId = cheese,
                lotCode = "Q-2026-118",
                isUnassigned = 0,
                expiryDate = "2026-11-20",
                purchaseLineId = lineId,
                expiryNotifiedAt = LATER,
                createdAt = CREATED_AT,
                closedAt = MUCH_LATER,
            )

        val id = lots.insert(received)

        assertEquals(received.copy(id = id), lots.findById(id))
        assertEquals(id, lots.findByCode(cheese, "Q-2026-118")?.id)
        assertNull(lots.findById(MISSING_ID))
        assertNull(lots.findByCode(cheese, "Q-2026-119"))
        assertNull(lots.findUnassigned(cheese))
    }

    @Test
    fun aCodeIsUniqueWithinItsProductOnly() = runBlocking {
        lots.insert(lot(cheese, "L-01"))
        constraintFailure(UNIQUE) { lots.insert(lot(cheese, "L-01", expiryDate = "2026-12-01")) }

        // Otro producto puede repetir el código, y una serie es un lote de su propio código.
        val yogurt = database.productDao().insert(product("Yogur").copy(tracksLots = 1))
        val other = lots.insert(lot(yogurt, "L-01"))
        val second = lots.insert(lot(cheese, "L-02"))
        constraintFailure(UNIQUE) { lots.updateCodeAndExpiry(second, "L-01", null) }
        assertEquals(other, lots.findByCode(yogurt, "L-01")?.id)
        assertEquals("L-02", lots.findById(second)?.lotCode)
    }

    @Test
    fun aProductHasAtMostOneUnassignedLot() = runBlocking {
        val unassigned = lots.insert(unassignedLot(cheese))
        lots.insert(lot(cheese, "L-01"))

        // Sin código, el índice único no lo detiene: lo detiene la guarda.
        guardFailure(GUARD) { lots.insert(unassignedLot(cheese)) }
        val yogurt = database.productDao().insert(product("Yogur").copy(tracksLots = 1))
        val otherUnassigned = lots.insert(unassignedLot(yogurt))

        assertEquals(unassigned, lots.findUnassigned(cheese)?.id)
        assertEquals(otherUnassigned, lots.findUnassigned(yogurt)?.id)
        // El lote sin asignar no lleva código ni vencimiento.
        guardFailure(GUARD) { lots.updateCodeAndExpiry(unassigned, "L-09", null) }
        guardFailure(GUARD) { lots.updateCodeAndExpiry(unassigned, null, "2026-12-01") }
    }

    @Test
    fun lotsFollowEntryOrderAndEmitAgainOnEachChange() = runBlocking {
        val emissions = Emissions(this, lots.observeByProduct(cheese).ids { it.id })
        try {
            emissions.awaitValue(emptyList())
            // El orden es el de entrada, no el del código ni el del vencimiento.
            val late = lots.insert(lot(cheese, "Z-01", expiryDate = "2027-01-01"))
            val early = lots.insert(lot(cheese, "A-01", expiryDate = "2026-06-01"))
            emissions.awaitValue(listOf(late, early))

            assertEquals(1, lots.delete(late))
            emissions.awaitValue(listOf(early))
        } finally {
            emissions.stop()
        }
        assertEquals(1, lots.findByProduct(cheese).size)
    }

    @Test
    fun eachWriteChangesOnlyItsColumns() = runBlocking {
        val id = lots.insert(lot(cheese, "L-01", expiryDate = "2026-11-20"))
        val before = lots.findById(id)

        assertEquals(1, lots.updateCodeAndExpiry(id, "L-01B", "2026-12-20"))
        assertEquals(1, lots.setExpiryNotifiedAt(id, LATER))
        assertEquals(1, lots.setClosedAt(id, MUCH_LATER))
        assertEquals(
            before?.copy(
                lotCode = "L-01B",
                expiryDate = "2026-12-20",
                expiryNotifiedAt = LATER,
                closedAt = MUCH_LATER,
            ),
            lots.findById(id),
        )

        // Reabrir y quitar el aviso vuelven a nulo, y un vencimiento que no es fecha se rechaza.
        lots.setClosedAt(id, null)
        lots.setExpiryNotifiedAt(id, null)
        guardFailure(GUARD) { lots.updateCodeAndExpiry(id, "L-01B", "2026-02-30") }
        assertEquals(before?.copy(lotCode = "L-01B", expiryDate = "2026-12-20"), lots.findById(id))
        assertEquals(0, lots.updateCodeAndExpiry(MISSING_ID, "X", null))
        assertEquals(0, lots.setClosedAt(MISSING_ID, LATER))
        assertEquals(0, lots.setExpiryNotifiedAt(MISSING_ID, LATER))
    }

    @Test
    fun aLotNeedsItsProductAndALotInUseIsNotDeleted() = runBlocking {
        constraintFailure(FOREIGN_KEY) { lots.insert(lot(MISSING_ID, "L-01")) }
        constraintFailure(FOREIGN_KEY) {
            lots.insert(lot(cheese, "L-01").copy(purchaseLineId = MISSING_ID))
        }

        // Con una fila del libro, o con una línea de compra que lo nombra, no se borra.
        val stocked = lots.insert(lot(cheese, "L-01"))
        database.stockMovementDao().insert(openingBalance(cheese).copy(lotId = stocked))
        val bought = lots.insert(lot(cheese, "L-02"))
        val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
        database.purchaseLineDao().insert(purchaseLine(purchaseId, productId).copy(lotId = bought))
        for (used in listOf(stocked, bought)) {
            assertTrue("$used está en uso", lots.isInUse(used))
            constraintFailure(FOREIGN_KEY) { lots.delete(used) }
        }
        // Con lotes, el producto tampoco se borra.
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(cheese) }

        val unused = lots.insert(lot(cheese, "L-03"))
        assertFalse(lots.isInUse(unused))
        assertEquals(1, lots.delete(unused))
        assertEquals(0, lots.delete(MISSING_ID))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val GUARD = "guard:stock_lots"
    }
}
