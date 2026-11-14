package io.github.yesidrobayo.shopledger.data.local.stock

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
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.movement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las transferencias: cada columna en su lugar, llaves hacia las dos ubicaciones, el producto y el
 * lote, la lista por período, una anulación una sola vez, y líneas que no cambian. Sus filas del
 * libro apuntan a la línea, que con ellas ya no se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class StockTransferDaoTest : LedgerDatabaseTest() {
    private val transfers get() = database.stockTransferDao()
    private var warehouse = 0L

    @Before
    fun createSecondLocation() = runBlocking {
        warehouse = database.locationDao().insert(location("Bodega"))
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val cheese = database.productDao().insert(product("Queso").copy(tracksLots = 1))
        val lotId = database.stockLotDao().insert(lot(cheese, "Q-01"))
        val header = transfer(warehouse, MAIN_LOCATION_ID).copy(note = "Reponer el local")

        val id = transfers.insert(header)
        val line =
            StockTransferLineEntity(
                transferId = id,
                productId = cheese,
                lotId = lotId,
                quantityMilli = 4_500,
            )
        val lineId = transfers.insertLine(line)

        assertEquals(header.copy(id = id), transfers.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), transfers.findLines(id))
        assertNull(transfers.findById(MISSING_ID))
        assertEquals(emptyList<StockTransferLineEntity>(), transfers.findLines(MISSING_ID))
    }

    @Test
    fun aTransferNeedsItsLocationsProductAndLot() = runBlocking {
        constraintFailure(FOREIGN_KEY) { transfers.insert(transfer(MISSING_ID, warehouse)) }
        constraintFailure(FOREIGN_KEY) { transfers.insert(transfer(MAIN_LOCATION_ID, MISSING_ID)) }
        val id = transfers.insert(transfer(MAIN_LOCATION_ID, warehouse))
        constraintFailure(FOREIGN_KEY) { transfers.insertLine(transferLine(MISSING_ID, productId)) }
        constraintFailure(FOREIGN_KEY) { transfers.insertLine(transferLine(id, MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            transfers.insertLine(transferLine(id, productId).copy(lotId = MISSING_ID))
        }
        transfers.insertLine(transferLine(id, productId))

        // Con una transferencia, ni sus ubicaciones ni el producto se borran.
        constraintFailure(FOREIGN_KEY) { database.locationDao().delete(warehouse) }
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }
        assertEquals(1, transfers.findLines(id).size)
    }

    @Test
    fun theLedgerRowsOfALinePointToIt() = runBlocking {
        val id = transfers.insert(transfer(MAIN_LOCATION_ID, warehouse))
        val lineId = transfers.insertLine(transferLine(id, productId))
        val ledger = database.stockMovementDao()
        val out =
            movement(productId, "TRANSFER_OUT", -4_000, 1_200, 0, -4_000, 1_200, 0)
                .copy(transferLineId = lineId)

        ledger.insert(out)
        ledger.insert(
            movement(productId, "TRANSFER_IN", 4_000, 1_200, 0, 0, 1_200, 0)
                .copy(transferLineId = lineId, locationId = warehouse),
        )
        constraintFailure(FOREIGN_KEY) { ledger.insert(out.copy(transferLineId = MISSING_ID)) }

        assertEquals(
            2L,
            count("SELECT COUNT(*) FROM stock_movements WHERE transfer_line_id = $lineId"),
        )
        assertEquals(0L, count("SELECT SUM(quantity_milli) FROM stock_movements"))
    }

    @Test
    fun theListByPeriodGoesFromNewestAndEmitsAgainOnVoid() = runBlocking {
        val january = transfers.insert(transfer(MAIN_LOCATION_ID, warehouse, "2026-01-10"))
        val first = transfers.insert(transfer(MAIN_LOCATION_ID, warehouse, "2026-01-20"))
        val second = transfers.insert(transfer(warehouse, MAIN_LOCATION_ID, "2026-01-20"))
        transfers.insert(transfer(MAIN_LOCATION_ID, warehouse, "2026-02-01"))

        val emissions =
            Emissions(this, transfers.observeIn("2026-01-15", "2026-01-31").ids { it.id })
        try {
            // Del más nuevo al más antiguo, y en la misma fecha el registrado después primero.
            emissions.awaitValue(listOf(second, first))
            assertEquals(1, transfers.markVoided(first, LATER, "ENTRY_ERROR", "Se repitió"))
            emissions.awaitValue(listOf(second, first))
        } finally {
            emissions.stop()
        }
        assertEquals("VOIDED", transfers.findById(first)?.status)
        assertEquals("ACTIVE", transfers.findById(january)?.status)
    }

    @Test
    fun aTransferIsVoidedOnceAndOnlyItsVoidColumnsChange() = runBlocking {
        val id = transfers.insert(transfer(MAIN_LOCATION_ID, warehouse))
        val before = transfers.findById(id)

        assertEquals(1, transfers.markVoided(id, LATER, "UNDO", null))
        assertEquals(0, transfers.markVoided(id, MUCH_LATER, "OTHER", "Otra vez"))
        assertEquals(0, transfers.markVoided(MISSING_ID, LATER, "UNDO", null))
        // Un motivo que una transferencia no admite lo rechaza la guarda.
        val other = transfers.insert(transfer(MAIN_LOCATION_ID, warehouse))
        guardFailure("guard:stock_transfers_void_only") {
            transfers.markVoided(other, LATER, "CUSTOMER_RETURN", null)
        }

        assertEquals(
            before?.copy(status = "VOIDED", voidedAt = LATER, voidReason = "UNDO"),
            transfers.findById(id),
        )
    }

    @Test
    fun theDaoOnlyAddsReadsAndVoids() {
        // Una función nueva que edite o borre una transferencia o una línea rompe esta prueba a
        // propósito: lo registrado solo se anula.
        assertEquals(
            setOf(
                "observeIn",
                "findById",
                "findLines",
                // Lee las filas del libro de sus líneas: solo lee.
                "findMovements",
                "insert",
                "insertLine",
                "markVoided",
            ),
            functionsOf(StockTransferDao::class.java),
        )
    }

    private fun transfer(
        fromLocationId: Long,
        toLocationId: Long,
        businessDate: String = "2026-01-15",
    ): StockTransferEntity = StockTransferEntity(
        fromLocationId = fromLocationId,
        toLocationId = toLocationId,
        note = null,
        occurredAt = CREATED_AT,
        businessDate = businessDate,
        createdAt = CREATED_AT,
        status = "ACTIVE",
        voidedAt = null,
        voidReason = null,
        voidNote = null,
    )

    private fun transferLine(transferId: Long, productId: Long): StockTransferLineEntity =
        StockTransferLineEntity(
            transferId = transferId,
            productId = productId,
            lotId = null,
            quantityMilli = 4_000,
        )

    private companion object {
        const val MISSING_ID = 999L
    }
}
