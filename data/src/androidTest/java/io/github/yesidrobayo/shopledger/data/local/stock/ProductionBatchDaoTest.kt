package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
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
 * Los lotes de producción: cada columna en su lugar, llaves hacia lo producido, la ubicación, los
 * insumos y sus lotes, la lista por período, una anulación una sola vez, e insumos que no cambian.
 * Son las velas de la regla de producción: 10 velas con 2 kg de cera y 10 mechas.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductionBatchDaoTest : LedgerDatabaseTest() {
    private val batches get() = database.productionBatchDao()
    private var candle = 0L
    private var wax = 0L
    private var wick = 0L

    @Before
    fun createProducts() = runBlocking {
        candle = database.productDao().insert(product("Vela"))
        wax = database.productDao().insert(product("Cera").copy(tracksLots = 1))
        wick = database.productDao().insert(product("Mecha"))
    }

    @Test
    fun aBatchAndItsInputsReadBackWithEveryColumn() = runBlocking {
        val waxLot = database.stockLotDao().insert(lot(wax, "C-01"))
        val header = batch(candle).copy(note = "Tanda del lunes")

        val id = batches.insert(header)
        val inputs =
            listOf(
                ProductionBatchLineEntity(
                    productionBatchId = id,
                    componentProductId = wax,
                    lotId = waxLot,
                    quantityMilli = 2_000,
                    unitCost = 8_000,
                    lineCost = 16_000,
                ),
                ProductionBatchLineEntity(
                    productionBatchId = id,
                    componentProductId = wick,
                    lotId = null,
                    quantityMilli = 10_000,
                    unitCost = 150,
                    lineCost = 1_500,
                ),
            )
        val stored = inputs.map { it.copy(id = batches.insertLine(it)) }

        assertEquals(header.copy(id = id), batches.findById(id))
        assertEquals(stored, batches.findLines(id))
        // El costo del lote es la suma de sus insumos.
        assertEquals(header.totalCost, stored.sumOf { it.lineCost })
        assertNull(batches.findById(MISSING_ID))
    }

    @Test
    fun aBatchNeedsItsProductLocationInputsAndLots() = runBlocking {
        constraintFailure(FOREIGN_KEY) { batches.insert(batch(MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            batches.insert(batch(candle).copy(locationId = MISSING_ID))
        }
        val id = batches.insert(batch(candle))
        constraintFailure(FOREIGN_KEY) { batches.insertLine(input(MISSING_ID, wax)) }
        constraintFailure(FOREIGN_KEY) { batches.insertLine(input(id, MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            batches.insertLine(input(id, wax).copy(lotId = MISSING_ID))
        }
        batches.insertLine(input(id, wick))

        // Con producción, ni lo producido ni sus insumos se borran.
        for (used in listOf(candle, wick)) {
            constraintFailure(FOREIGN_KEY) { database.productDao().delete(used) }
        }
        assertEquals(1, batches.findLines(id).size)
    }

    @Test
    fun theLedgerRowsOfABatchPointToIt() = runBlocking {
        val id = batches.insert(batch(candle))
        val ledger = database.stockMovementDao()
        val made =
            movement(candle, "PRODUCTION_IN", 10_000, 1_750, 17_500, 10_000, 1_750, 17_500)
                .copy(productionBatchId = id)

        ledger.insert(made)
        constraintFailure(FOREIGN_KEY) { ledger.insert(made.copy(productionBatchId = MISSING_ID)) }

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM stock_movements WHERE production_batch_id = $id"),
        )
    }

    @Test
    fun theListByPeriodGoesFromNewestAndEmitsAgainOnVoid() = runBlocking {
        val first = batches.insert(batch(candle, "2026-01-20"))
        val second = batches.insert(batch(candle, "2026-01-20"))
        val earlier = batches.insert(batch(candle, "2026-01-16"))
        batches.insert(batch(candle, "2026-01-14"))

        val emissions =
            Emissions(this, batches.observeIn("2026-01-15", "2026-01-31").ids { it.id })
        try {
            emissions.awaitValue(listOf(second, first, earlier))
            assertEquals(1, batches.markVoided(second, LATER, "ENTRY_ERROR", null))
            emissions.awaitValue(listOf(second, first, earlier))
        } finally {
            emissions.stop()
        }
        assertEquals("VOIDED", batches.findById(second)?.status)
    }

    @Test
    fun aBatchIsVoidedOnceAndOnlyItsVoidColumnsChange() = runBlocking {
        val id = batches.insert(batch(candle))
        val before = batches.findById(id)

        assertEquals(1, batches.markVoided(id, LATER, "OTHER", "Se quemó la tanda"))
        assertEquals(0, batches.markVoided(id, MUCH_LATER, "UNDO", null))
        assertEquals(0, batches.markVoided(MISSING_ID, LATER, "UNDO", null))
        assertEquals(
            before?.copy(
                status = "VOIDED",
                voidedAt = LATER,
                voidReason = "OTHER",
                voidNote = "Se quemó la tanda",
            ),
            batches.findById(id),
        )
    }

    @Test
    fun theDaoOnlyAddsReadsAndVoids() {
        assertEquals(
            setOf("observeIn", "findById", "findLines", "insert", "insertLine", "markVoided"),
            functionsOf(ProductionBatchDao::class.java),
        )
    }

    private fun batch(productId: Long, businessDate: String = "2026-01-15"): ProductionBatchEntity =
        ProductionBatchEntity(
            productId = productId,
            locationId = MAIN_LOCATION_ID,
            quantityMilli = 10_000,
            totalCost = 17_500,
            note = null,
            occurredAt = CREATED_AT,
            businessDate = businessDate,
            createdAt = CREATED_AT,
            status = "ACTIVE",
            voidedAt = null,
            voidReason = null,
            voidNote = null,
        )

    private fun input(batchId: Long, componentId: Long): ProductionBatchLineEntity =
        ProductionBatchLineEntity(
            productionBatchId = batchId,
            componentProductId = componentId,
            lotId = null,
            quantityMilli = 10_000,
            unitCost = 150,
            lineCost = 1_500,
        )

    private companion object {
        const val MISSING_ID = 999L
    }
}
