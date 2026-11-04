package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLevel
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.COST_CORRECTION
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.OPENING_BALANCE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_OUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StockLedgerTest {
    private val shelf = StockKey(productId = 1L, locationId = 1L, lotId = null)

    private fun row(
        id: Long,
        type: StockMovementType,
        milli: Long,
        reverses: Long? = null,
        key: StockKey = shelf,
    ) = StockMovement(id, key, type, QuantityMilli(milli), reverses)

    private fun balancesAfter(vararg milli: Long): Map<Long, QuantityMilli> =
        milli.withIndex().associate { (index, value) -> index + 1L to QuantityMilli(value) }

    private fun onHand(movements: List<StockMovement>): QuantityMilli =
        StockLedger.onHandByProduct(movements).getValue(shelf.productId)

    @Test
    fun `the balance follows the weighted average example step by step`() {
        // Compra 10, compra 5, venta 8 y compra 4, en unidades.
        val ledger =
            listOf(
                row(1L, PURCHASE, 10_000L),
                row(2L, PURCHASE, 5000L),
                row(3L, SALE, -8000L),
                row(4L, PURCHASE, 4000L),
            )

        assertEquals(
            balancesAfter(10_000L, 15_000L, 7000L, 11_000L),
            StockLedger.balancesAfter(ledger),
        )
        assertEquals(QuantityMilli(11_000L), onHand(ledger))
    }

    @Test
    fun `voiding a sale adds a row and the balance is still the sum`() {
        // Compra 10, venta 4, compra 6 y la anulación de la venta.
        val ledger =
            listOf(
                row(1L, PURCHASE, 10_000L),
                row(2L, SALE, -4000L),
                row(3L, PURCHASE, 6000L),
                row(4L, SALE_VOID, 4000L, reverses = 2L),
            )

        assertEquals(
            balancesAfter(10_000L, 6000L, 12_000L, 16_000L),
            StockLedger.balancesAfter(ledger),
        )
        assertEquals(QuantityMilli(16_000L), onHand(ledger))
        assertEquals(listOf(1L, 3L), StockLedger.effective(ledger).map { it.id })
    }

    @Test
    fun `a purchase typed by mistake and voided at once leaves only the opening balance`() {
        val ledger =
            listOf(
                row(1L, OPENING_BALANCE, 10_000L),
                row(2L, PURCHASE, 1000L),
                row(3L, PURCHASE_VOID, -1000L, reverses = 2L),
            )

        assertEquals(balancesAfter(10_000L, 11_000L, 10_000L), StockLedger.balancesAfter(ledger))
        assertEquals(listOf(1L), StockLedger.effective(ledger).map { it.id })
    }

    @Test
    fun `a purchase voided after selling part of it keeps the sale in the effective ledger`() {
        val ledger =
            listOf(
                row(1L, OPENING_BALANCE, 10_000L),
                row(2L, PURCHASE, 1000L),
                row(3L, SALE, -9000L),
                row(4L, PURCHASE_VOID, -1000L, reverses = 2L),
            )

        assertEquals(
            balancesAfter(10_000L, 11_000L, 2000L, 1000L),
            StockLedger.balancesAfter(ledger),
        )
        assertEquals(listOf(1L, 3L), StockLedger.effective(ledger).map { it.id })
    }

    @Test
    fun `voiding a purchase that was already sold leaves the stock negative`() {
        val ledger =
            listOf(
                row(1L, PURCHASE, 10_000L),
                row(2L, SALE, -10_000L),
                row(3L, PURCHASE_VOID, -10_000L, reverses = 1L),
            )

        assertEquals(QuantityMilli(-10_000L), onHand(ledger))
        assertEquals(StockLevel.NEGATIVE, StockLevel.of(onHand(ledger)))
        assertEquals(listOf(2L), StockLedger.effective(ledger).map { it.id })
    }

    @Test
    fun `an opening balance of 24 units leaves 24 in stock`() {
        assertEquals(QuantityMilli(24_000L), onHand(listOf(row(1L, OPENING_BALANCE, 24_000L))))
    }

    @Test
    fun `shrinkage adjustments move the balance with their sign`() {
        // Con 11 en stock: 3 vencidos, faltan 2 en un conteo y aparece 1.
        val ledger =
            listOf(
                row(1L, PURCHASE, 11_000L),
                row(2L, ADJUSTMENT, -3000L),
                row(3L, ADJUSTMENT, -2000L),
                row(4L, ADJUSTMENT, 1000L),
            )

        assertEquals(balancesAfter(11_000L, 8000L, 6000L, 7000L), StockLedger.balancesAfter(ledger))
    }

    @Test
    fun `a count that finds 10 where the ledger says 12 adjusts by minus 2`() {
        val ledger = listOf(row(1L, PURCHASE, 12_000L), row(2L, ADJUSTMENT, -2000L))

        assertEquals(QuantityMilli(10_000L), onHand(ledger))
    }

    @Test
    fun `a mistaken opening balance voided leaves an empty effective ledger`() {
        // Se digitaron 100 unidades iniciales en vez de 10: se anulan y el libro efectivo queda
        // vacío, así que se puede registrar el inventario inicial correcto.
        val ledger =
            listOf(
                row(1L, OPENING_BALANCE, 100_000L),
                row(2L, ADJUSTMENT_VOID, -100_000L, reverses = 1L),
            )

        assertEquals(QuantityMilli.ZERO, onHand(ledger))
        assertTrue(StockLedger.effective(ledger).isEmpty())
    }

    @Test
    fun `a cost correction moves no units but stays in the effective ledger`() {
        val ledger = listOf(row(1L, PURCHASE, 5000L), row(2L, COST_CORRECTION, 0L))

        assertEquals(QuantityMilli(5000L), onHand(ledger))
        assertEquals(listOf(1L, 2L), StockLedger.effective(ledger).map { it.id })
    }

    @Test
    fun `the ledger order is the id and not the order in which rows arrive`() {
        // La fila 3 llega primero, como si tuviera una fecha anterior: igual va después de la 2.
        val ledger =
            listOf(
                row(3L, SALE, -8000L),
                row(1L, PURCHASE, 10_000L),
                row(2L, PURCHASE, 5000L),
            )

        assertEquals(listOf(1L, 2L, 3L), StockLedger.inOrder(ledger).map { it.id })
        assertEquals(balancesAfter(10_000L, 15_000L, 7000L), StockLedger.balancesAfter(ledger))
        assertEquals(listOf(1L, 2L, 3L), StockLedger.balancesAfter(ledger).keys.toList())
    }

    @Test
    fun `two rows with the same id are a broken ledger`() {
        val ledger = listOf(row(1L, PURCHASE, 1000L), row(1L, SALE, -1000L))

        assertThrows(IllegalArgumentException::class.java) { StockLedger.inOrder(ledger) }
        assertThrows(IllegalArgumentException::class.java) { StockLedger.onHandByProduct(ledger) }
    }

    @Test
    fun `each product keeps its own running balance`() {
        val other = StockKey(productId = 2L, locationId = 1L, lotId = null)
        val ledger =
            listOf(
                row(1L, PURCHASE, 5000L),
                row(2L, PURCHASE, 3000L, key = other),
                row(3L, SALE, -1000L),
                row(4L, SALE, -4000L, key = other),
            )

        assertEquals(balancesAfter(5000L, 3000L, 4000L, -1000L), StockLedger.balancesAfter(ledger))
        assertEquals(
            mapOf(1L to QuantityMilli(4000L), 2L to QuantityMilli(-1000L)),
            StockLedger.onHandByProduct(ledger),
        )
    }

    @Test
    fun `a transfer moves stock between places without changing the product balance`() {
        val backRoom = StockKey(productId = 1L, locationId = 2L, lotId = null)
        val ledger =
            listOf(
                row(1L, PURCHASE, 10_000L),
                row(2L, TRANSFER_OUT, -3000L),
                row(3L, TRANSFER_IN, 3000L, key = backRoom),
            )

        assertEquals(
            mapOf(shelf to QuantityMilli(7000L), backRoom to QuantityMilli(3000L)),
            StockLedger.onHandByKey(ledger),
        )
        assertEquals(QuantityMilli(10_000L), onHand(ledger))
        assertEquals(balancesAfter(10_000L, 7000L, 10_000L), StockLedger.balancesAfter(ledger))
    }

    @Test
    fun `lots of the same place are separate parts of the stock`() {
        val firstLot = StockKey(productId = 1L, locationId = 1L, lotId = 11L)
        val secondLot = StockKey(productId = 1L, locationId = 1L, lotId = 12L)
        val ledger =
            listOf(
                row(1L, PURCHASE, 2000L, key = firstLot),
                row(2L, PURCHASE, 3000L, key = secondLot),
                row(3L, SALE, -2500L, key = firstLot),
            )

        assertEquals(
            mapOf(firstLot to QuantityMilli(-500L), secondLot to QuantityMilli(3000L)),
            StockLedger.onHandByKey(ledger),
        )
        assertEquals(QuantityMilli(2500L), onHand(ledger))
    }

    @Test
    fun `an empty ledger has no balances and no effective rows`() {
        assertTrue(StockLedger.onHandByProduct(emptyList()).isEmpty())
        assertTrue(StockLedger.onHandByKey(emptyList()).isEmpty())
        assertTrue(StockLedger.balancesAfter(emptyList()).isEmpty())
        assertTrue(StockLedger.effective(emptyList()).isEmpty())
    }

    @Test
    fun `a sum that overflows fails instead of wrapping around`() {
        val ledger = listOf(row(1L, PURCHASE, Long.MAX_VALUE), row(2L, PURCHASE, 1L))

        assertThrows(ArithmeticException::class.java) { StockLedger.onHandByProduct(ledger) }
    }
}
