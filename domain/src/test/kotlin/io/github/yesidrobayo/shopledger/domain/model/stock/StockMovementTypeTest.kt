package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.COST_CORRECTION
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.OPENING_BALANCE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_OUT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_OUT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_VOID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockMovementTypeTest {
    @Test
    fun `the stored names never change`() {
        assertEquals(
            listOf(
                "OPENING_BALANCE", "PURCHASE", "SALE", "ADJUSTMENT", "COST_CORRECTION",
                "PURCHASE_VOID", "SALE_VOID", "ADJUSTMENT_VOID", "SALE_RETURN", "SALE_RETURN_VOID",
                "PURCHASE_RETURN", "PURCHASE_RETURN_VOID", "PRODUCTION_OUT", "PRODUCTION_IN",
                "PRODUCTION_VOID", "TRANSFER_OUT", "TRANSFER_IN", "TRANSFER_VOID",
            ),
            StockMovementType.entries.map { it.name },
        )
    }

    @Test
    fun `each type has the sign of the table of movement types`() {
        // Escrita a mano desde la tabla de tipos de movimiento, sin leer el código.
        val expected =
            mapOf(
                OPENING_BALANCE to QuantitySign.POSITIVE,
                PURCHASE to QuantitySign.POSITIVE,
                SALE to QuantitySign.NEGATIVE,
                ADJUSTMENT to QuantitySign.NON_ZERO,
                COST_CORRECTION to QuantitySign.ZERO,
                PURCHASE_VOID to QuantitySign.NEGATIVE,
                SALE_VOID to QuantitySign.POSITIVE,
                ADJUSTMENT_VOID to QuantitySign.NON_ZERO,
                SALE_RETURN to QuantitySign.POSITIVE,
                SALE_RETURN_VOID to QuantitySign.NEGATIVE,
                PURCHASE_RETURN to QuantitySign.NEGATIVE,
                PURCHASE_RETURN_VOID to QuantitySign.POSITIVE,
                PRODUCTION_OUT to QuantitySign.NEGATIVE,
                PRODUCTION_IN to QuantitySign.POSITIVE,
                PRODUCTION_VOID to QuantitySign.NON_ZERO,
                TRANSFER_OUT to QuantitySign.NEGATIVE,
                TRANSFER_IN to QuantitySign.POSITIVE,
                TRANSFER_VOID to QuantitySign.NON_ZERO,
            )

        assertEquals(expected, StockMovementType.entries.associateWith { it.quantitySign })
    }

    @Test
    fun `each original is voided by its own compensating type`() {
        assertEquals(ADJUSTMENT_VOID, OPENING_BALANCE.voidType)
        assertEquals(ADJUSTMENT_VOID, ADJUSTMENT.voidType)
        assertEquals(PURCHASE_VOID, PURCHASE.voidType)
        assertEquals(SALE_VOID, SALE.voidType)
        assertEquals(SALE_RETURN_VOID, SALE_RETURN.voidType)
        assertEquals(PURCHASE_RETURN_VOID, PURCHASE_RETURN.voidType)
        assertEquals(PRODUCTION_VOID, PRODUCTION_OUT.voidType)
        assertEquals(PRODUCTION_VOID, PRODUCTION_IN.voidType)
        assertEquals(TRANSFER_VOID, TRANSFER_OUT.voidType)
        assertEquals(TRANSFER_VOID, TRANSFER_IN.voidType)
    }

    @Test
    fun `a cost correction and a compensating row are never voided`() {
        val notVoided =
            listOf(
                COST_CORRECTION,
                PURCHASE_VOID,
                SALE_VOID,
                ADJUSTMENT_VOID,
                SALE_RETURN_VOID,
                PURCHASE_RETURN_VOID,
                PRODUCTION_VOID,
                TRANSFER_VOID,
            )

        notVoided.forEach { assertNull(it.name, it.voidType) }
    }

    @Test
    fun `only the void types are compensating rows`() {
        val compensating =
            setOf(
                PURCHASE_VOID,
                SALE_VOID,
                ADJUSTMENT_VOID,
                SALE_RETURN_VOID,
                PURCHASE_RETURN_VOID,
                PRODUCTION_VOID,
                TRANSFER_VOID,
            )

        assertEquals(compensating, StockMovementType.entries.filter { it.isCompensating }.toSet())
    }

    @Test
    fun `a compensating type voids only its originals`() {
        assertTrue(SALE_VOID.compensates(SALE))
        assertTrue(ADJUSTMENT_VOID.compensates(OPENING_BALANCE))
        assertTrue(ADJUSTMENT_VOID.compensates(ADJUSTMENT))
        assertTrue(PRODUCTION_VOID.compensates(PRODUCTION_OUT))
        assertTrue(PRODUCTION_VOID.compensates(PRODUCTION_IN))
        assertTrue(TRANSFER_VOID.compensates(TRANSFER_IN))
        assertFalse(SALE_VOID.compensates(PURCHASE))
        assertFalse(PURCHASE_VOID.compensates(SALE))
        assertFalse(ADJUSTMENT_VOID.compensates(COST_CORRECTION))
        assertFalse(SALE_VOID.compensates(SALE_VOID))
        assertFalse(SALE.compensates(SALE))
    }

    @Test
    fun `a compensating row can always carry the opposite quantity of what it voids`() {
        // Si el signo del compensatorio no admitiera la cantidad opuesta, esa fila no se podría
        // anular nunca.
        val samples = listOf(QuantityMilli(-1000L), QuantityMilli(1000L))
        for (original in StockMovementType.entries) {
            val voidType = original.voidType ?: continue
            for (quantity in samples.filter(original.quantitySign::allows)) {
                assertTrue("$original", voidType.quantitySign.allows(-quantity))
            }
        }
    }

    @Test
    fun `only the entries the person writes check the stock cap`() {
        val capped = setOf(OPENING_BALANCE, PURCHASE, ADJUSTMENT, PRODUCTION_IN)

        assertEquals(capped, StockMovementType.entries.filter { it.checksOnHandLimit }.toSet())
    }
}
