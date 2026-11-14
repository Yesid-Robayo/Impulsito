package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture.Companion.PRODUCT_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El ajuste de un producto que se controla por lotes: cada fila lleva lote, y solo el lote sin
 * asignar puede quedar negativo. Productos, lotes y montos inventados.
 */
class AdjustStockLotsTest {
    private val fixture = StockFixture()
    private val ledger = fixture.catalog.ledger

    private fun takeOut(units: Long, lotId: Long? = null) = StockAdjustmentRequest.Change(
        PRODUCT_ID,
        StockAdjustmentReason.EXPIRED,
        QuantityMilli(units),
        lotId = lotId,
    )

    private fun unassignedOf(productId: Long): Long = fixture.catalog.tables.lots.values
        .single { it.productId == productId && it.isUnassigned }.id

    private suspend fun onHand(lotId: Long?): QuantityMilli =
        ledger.onHandAt(StockKey(PRODUCT_ID, CatalogFixture.MAIN_LOCATION_ID, lotId))

    @Test
    fun `the opening balance of a product with lots goes to its unassigned lot`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 1_000L, tracksLots = true)

        val unassigned = unassignedOf(PRODUCT_ID)
        assertEquals(unassigned, fixture.movementsOf(PRODUCT_ID).single().movement.key.lotId)
        assertEquals(QuantityMilli(4_000L), onHand(unassigned))
    }

    @Test
    fun `a count and a take out without lot use the same unassigned lot`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)

        fixture.adjust(StockAdjustmentRequest.Count(PRODUCT_ID, QuantityMilli(2_000L)))
        val result = fixture.adjust(takeOut(5_000L))

        // El sin asignar es uno solo, y es el único que puede quedar negativo.
        val unassigned = unassignedOf(PRODUCT_ID)
        val rows = fixture.movementsOf(PRODUCT_ID)
        assertEquals(listOf(unassigned, unassigned), rows.map { it.movement.key.lotId })
        assertEquals(QuantityMilli(-3_000L), onHand(unassigned))
        val adjusted = StockAdjustmentResult.Adjusted(rows.last().id, QuantityMilli(-3_000L))
        assertEquals(adjusted, result)
    }

    @Test
    fun `a take out from a chosen lot leaves that lot`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = fixture.seedLot(PRODUCT_ID, "A-118", expiry = "2026-11-04")
        fixture.receiveIntoLot(PRODUCT_ID, lot, units = 4_000L, unitCost = 1_000L)

        val result = fixture.adjust(takeOut(4_000L, lotId = lot))

        assertTrue(result is StockAdjustmentResult.Adjusted)
        assertEquals(lot, fixture.movementsOf(PRODUCT_ID).last().movement.key.lotId)
        assertEquals(QuantityMilli.ZERO, onHand(lot))
        // Nada pasó por el lote sin asignar, que ni siquiera se creó.
        assertTrue(fixture.catalog.tables.lots.values.none { it.isUnassigned })
    }

    @Test
    fun `a chosen lot never gives more than it has`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = fixture.seedLot(PRODUCT_ID, "A-121")
        fixture.receiveIntoLot(PRODUCT_ID, lot, units = 2_000L, unitCost = 1_000L)
        val before = fixture.movementsOf(PRODUCT_ID)

        val result = fixture.adjust(takeOut(3_000L, lotId = lot))

        assertEquals(StockAdjustmentResult.AboveLotStock(QuantityMilli(2_000L)), result)
        assertEquals(before, fixture.movementsOf(PRODUCT_ID))
    }

    @Test
    fun `the unassigned lot can be chosen and may go negative`() = runTest {
        fixture.seedStock(units = 1_000L, unitCost = 1_000L, tracksLots = true)
        val unassigned = unassignedOf(PRODUCT_ID)

        val result = fixture.adjust(takeOut(3_000L, lotId = unassigned))

        assertTrue(result is StockAdjustmentResult.Adjusted)
        assertEquals(QuantityMilli(-2_000L), onHand(unassigned))
    }

    @Test
    fun `a lot of another product or of a product without lots is rejected`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        fixture.seedStock(id = 2L, units = 0L, unitCost = 1_000L, tracksLots = true)
        val foreign = fixture.seedLot(2L, "B-1")

        assertEquals(
            StockAdjustmentResult.LotNotFound,
            fixture.adjust(takeOut(1_000L, lotId = foreign)),
        )
        val missing = fixture.adjust(takeOut(1_000L, lotId = 99L))
        assertEquals(StockAdjustmentResult.LotNotFound, missing)

        fixture.seedStock(id = 3L, units = 5_000L, unitCost = 1_000L)
        val ownLot = fixture.seedLot(3L, "C-1")
        val plain = StockAdjustmentRequest.Change(
            3L,
            StockAdjustmentReason.EXPIRED,
            QuantityMilli(1_000L),
            lotId = ownLot,
        )
        assertEquals(StockAdjustmentResult.LotNotFound, fixture.adjust(plain))
    }

    @Test
    fun `a product without lots keeps writing rows without lot`() = runTest {
        fixture.seedStock(units = 5_000L, unitCost = 1_000L)

        fixture.adjust(takeOut(1_000L))

        assertTrue(fixture.movementsOf(PRODUCT_ID).all { it.movement.key.lotId == null })
        assertTrue(fixture.catalog.tables.lots.isEmpty())
    }

    @Test
    fun `voiding a take out from a lot gives the units back to that lot`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 1_000L, tracksLots = true)
        val lot = fixture.seedLot(PRODUCT_ID, "A-130")
        fixture.receiveIntoLot(PRODUCT_ID, lot, units = 3_000L, unitCost = 1_000L)
        val taken = fixture.adjust(takeOut(2_000L, lotId = lot)) as StockAdjustmentResult.Adjusted

        fixture.void(taken.movementId)

        val compensation = fixture.movementsOf(PRODUCT_ID).last()
        assertEquals(taken.movementId, compensation.movement.reversesMovementId)
        assertEquals(lot, compensation.movement.key.lotId)
        assertEquals(QuantityMilli(3_000L), onHand(lot))
    }

    @Test
    fun `an increase by adjustment never chooses a lot`() {
        assertThrows(IllegalArgumentException::class.java) {
            StockAdjustmentRequest.Change(
                PRODUCT_ID,
                StockAdjustmentReason.OTHER,
                QuantityMilli(1_000L),
                increase = true,
                lotId = 1L,
            )
        }
        val plain = StockAdjustmentRequest.Change(
            PRODUCT_ID,
            StockAdjustmentReason.OTHER,
            QuantityMilli(1_000L),
            increase = true,
        )
        assertNull(plain.lotId)
    }
}
