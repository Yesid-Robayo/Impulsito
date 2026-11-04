package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.COST_CORRECTION
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.OPENING_BALANCE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_VOID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StockMovementRulesTest {
    private val shelf = StockKey(productId = 1L, locationId = 1L, lotId = null)
    private val backRoom = StockKey(productId = 1L, locationId = 2L, lotId = null)

    private fun draft(type: StockMovementType, milli: Long, reverses: Long? = null) =
        StockMovementDraft(shelf, type, QuantityMilli(milli), reverses)

    private fun movement(id: Long, type: StockMovementType, milli: Long, key: StockKey = shelf) =
        StockMovement(id, key, type, QuantityMilli(milli), reversesMovementId = null)

    private fun check(
        draft: StockMovementDraft,
        onHandBefore: Long = 0L,
        compensated: StockMovement? = null,
        isAlreadyCompensated: Boolean = false,
    ) = StockMovementRules.check(
        draft,
        QuantityMilli(onHandBefore),
        compensated,
        isAlreadyCompensated,
    )

    @Test
    fun `a sale without stock is accepted and nothing asks for confirmation`() {
        // Con 0 en el libro se venden 2: el saldo queda en −2 y la venta no se bloquea.
        assertEquals(StockMovementCheck.Valid, check(draft(SALE, -2000L), onHandBefore = 0L))
        assertEquals(StockMovementCheck.Valid, check(draft(SALE, -5000L), onHandBefore = -3000L))
    }

    @Test
    fun `a sale is never capped however large it is`() {
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(SALE, -1_000_000_000L), onHandBefore = -7_000_000_000L),
        )
    }

    @Test
    fun `a quantity with the wrong sign is rejected`() {
        assertEquals(StockMovementCheck.WrongQuantitySign, check(draft(SALE, 1000L)))
        assertEquals(StockMovementCheck.WrongQuantitySign, check(draft(PURCHASE, -1000L)))
        assertEquals(StockMovementCheck.WrongQuantitySign, check(draft(PURCHASE, 0L)))
        assertEquals(StockMovementCheck.WrongQuantitySign, check(draft(ADJUSTMENT, 0L)))
        assertEquals(StockMovementCheck.WrongQuantitySign, check(draft(COST_CORRECTION, 1L)))
    }

    @Test
    fun `the sign is checked before the compensation`() {
        assertEquals(StockMovementCheck.WrongQuantitySign, check(draft(SALE_VOID, -1000L)))
    }

    @Test
    fun `a cost correction moves no units and is valid`() {
        assertEquals(StockMovementCheck.Valid, check(draft(COST_CORRECTION, 0L)))
    }

    @Test
    fun `an adjustment goes up or down`() {
        assertEquals(StockMovementCheck.Valid, check(draft(ADJUSTMENT, -3000L), 11_000L))
        assertEquals(StockMovementCheck.Valid, check(draft(ADJUSTMENT, 1000L), 6000L))
    }

    @Test
    fun `a row that is not compensating cannot point to another`() {
        assertEquals(
            StockMovementCheck.UnexpectedCompensation,
            check(draft(PURCHASE, 1000L, reverses = 5L), compensated = movement(5L, SALE, -1000L)),
        )
    }

    @Test
    fun `a compensating row must say which row it voids and that row must exist`() {
        assertEquals(StockMovementCheck.MissingCompensatedMovement, check(draft(SALE_VOID, 1000L)))
        assertEquals(
            StockMovementCheck.MissingCompensatedMovement,
            check(draft(SALE_VOID, 1000L, reverses = 7L), compensated = null),
        )
    }

    @Test
    fun `passing a row other than the one the draft points to is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            check(draft(SALE_VOID, 1000L, reverses = 7L), compensated = movement(8L, SALE, -1000L))
        }
    }

    @Test
    fun `a void of one kind cannot compensate a row of another kind`() {
        assertEquals(
            StockMovementCheck.CrossCompensation,
            check(
                draft(SALE_VOID, 2000L, reverses = 1L),
                compensated = movement(1L, PURCHASE, 2000L),
            ),
        )
        assertEquals(
            StockMovementCheck.CrossCompensation,
            check(
                draft(PURCHASE_VOID, -2000L, reverses = 1L),
                compensated = movement(1L, SALE_RETURN, 2000L),
            ),
        )
    }

    @Test
    fun `a cost correction cannot be voided`() {
        assertEquals(
            StockMovementCheck.CrossCompensation,
            check(
                draft(ADJUSTMENT_VOID, 1000L, reverses = 4L),
                compensated = movement(4L, COST_CORRECTION, 0L),
            ),
        )
    }

    @Test
    fun `a compensating row cannot be voided`() {
        // Misma parte y cantidad opuesta: lo único que falla es que la fila ya es un compensatorio.
        assertEquals(
            StockMovementCheck.CrossCompensation,
            check(
                draft(ADJUSTMENT_VOID, -1000L, reverses = 9L),
                compensated = movement(9L, SALE_VOID, 1000L),
            ),
        )
    }

    @Test
    fun `a compensating row falls in the same product location and lot`() {
        val sale = movement(3L, SALE, -2000L, key = backRoom)
        val otherLot = StockKey(productId = 1L, locationId = 2L, lotId = 12L)
        val otherProduct = StockKey(productId = 2L, locationId = 2L, lotId = null)

        assertEquals(
            StockMovementCheck.DifferentStockKey,
            check(draft(SALE_VOID, 2000L, reverses = 3L), compensated = sale),
        )
        assertEquals(
            StockMovementCheck.DifferentStockKey,
            check(
                StockMovementDraft(otherLot, SALE_VOID, QuantityMilli(2000L), 3L),
                compensated = sale,
            ),
        )
        assertEquals(
            StockMovementCheck.DifferentStockKey,
            check(
                StockMovementDraft(otherProduct, SALE_VOID, QuantityMilli(2000L), 3L),
                compensated = sale,
            ),
        )
    }

    @Test
    fun `a compensating row carries exactly the opposite quantity`() {
        assertEquals(
            StockMovementCheck.QuantityNotOpposite,
            check(draft(SALE_VOID, 2000L, reverses = 3L), compensated = movement(3L, SALE, -3000L)),
        )
    }

    @Test
    fun `a row is compensated at most once`() {
        assertEquals(
            StockMovementCheck.AlreadyCompensated,
            check(
                draft(SALE_VOID, 3000L, reverses = 3L),
                compensated = movement(3L, SALE, -3000L),
                isAlreadyCompensated = true,
            ),
        )
    }

    @Test
    fun `voiding a sale puts back exactly what it took`() {
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(SALE_VOID, 3000L, reverses = 3L), compensated = movement(3L, SALE, -3000L)),
        )
    }

    @Test
    fun `an opening balance and an adjustment are voided with an adjustment void`() {
        // Cien unidades iniciales digitadas por error se anulan, y no quedan como merma.
        assertEquals(
            StockMovementCheck.Valid,
            check(
                draft(ADJUSTMENT_VOID, -100_000L, reverses = 1L),
                onHandBefore = 100_000L,
                compensated = movement(1L, OPENING_BALANCE, 100_000L),
            ),
        )
        assertEquals(
            StockMovementCheck.Valid,
            check(
                draft(ADJUSTMENT_VOID, 3000L, reverses = 2L),
                compensated = movement(2L, ADJUSTMENT, -3000L),
            ),
        )
    }

    @Test
    fun `a purchase that reaches the cap exactly is accepted`() {
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(PURCHASE, 1_000_000_000L), onHandBefore = 7_000_000_000L),
        )
    }

    @Test
    fun `a purchase one thousandth above the cap is rejected`() {
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(PURCHASE, 1_000_000_001L), onHandBefore = 7_000_000_000L),
        )
    }

    @Test
    fun `an opening balance and a production entry check the cap`() {
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(OPENING_BALANCE, 8_000_000_001L), onHandBefore = 0L),
        )
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(OPENING_BALANCE, 8_000_000_000L), onHandBefore = 0L),
        )
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(PRODUCTION_IN, 2L), onHandBefore = 7_999_999_999L),
        )
    }

    @Test
    fun `an upward adjustment checks the cap and a downward one does not`() {
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(ADJUSTMENT, 1L), onHandBefore = 8_000_000_000L),
        )
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(ADJUSTMENT, -1L), onHandBefore = 9_000_000_000L),
        )
    }

    @Test
    fun `a void a return and a transfer are not capped`() {
        val sale = movement(5L, SALE, -1_000_000_000L)
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(SALE_VOID, 1_000_000_000L, reverses = 5L), 7_500_000_000L, sale),
        )
        assertEquals(StockMovementCheck.Valid, check(draft(SALE_RETURN, 1000L), 8_000_000_000L))
        assertEquals(StockMovementCheck.Valid, check(draft(TRANSFER_IN, 1000L), 8_000_000_000L))
    }

    @Test
    fun `an entry over a negative balance counts what it covers`() {
        // Con −5 en el libro, una compra de 8.000.005 unidades deja exactamente el tope.
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(PURCHASE, 8_000_005_000L), onHandBefore = -5000L),
        )
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(PURCHASE, 8_000_005_001L), onHandBefore = -5000L),
        )
    }

    @Test
    fun `an entry over a balance already above the cap is rejected`() {
        // Una anulación pudo dejar el saldo encima del tope; una compra después no lo sube más.
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(PURCHASE, 1L), onHandBefore = 8_000_000_001L),
        )
    }

    @Test
    fun `the cap comparison never overflows at the extremes of a long`() {
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(PURCHASE, Long.MAX_VALUE), onHandBefore = Long.MAX_VALUE),
        )
        assertEquals(
            StockMovementCheck.Valid,
            check(draft(PURCHASE, Long.MAX_VALUE), onHandBefore = Long.MIN_VALUE),
        )
        assertEquals(
            StockMovementCheck.AboveOnHandLimit,
            check(draft(PURCHASE, Long.MAX_VALUE), onHandBefore = 0L),
        )
    }

    @Test
    fun `the compensation of a sale returns its units to the same place`() {
        val sale = movement(3L, SALE, -4000L, key = backRoom)

        assertEquals(
            StockCompensation.Ready(
                StockMovementDraft(backRoom, SALE_VOID, QuantityMilli(4000L), 3L),
            ),
            StockMovementRules.compensationOf(sale, isAlreadyCompensated = false),
        )
    }

    @Test
    fun `the compensation of each kind uses its own void type`() {
        val cases =
            listOf(
                movement(1L, PURCHASE, 1000L) to draft(PURCHASE_VOID, -1000L, reverses = 1L),
                movement(2L, OPENING_BALANCE, 24_000L) to
                    draft(ADJUSTMENT_VOID, -24_000L, reverses = 2L),
                movement(3L, ADJUSTMENT, -2000L) to draft(ADJUSTMENT_VOID, 2000L, reverses = 3L),
                movement(4L, PURCHASE_RETURN, -1500L) to
                    draft(PURCHASE_RETURN_VOID, 1500L, reverses = 4L),
                movement(5L, TRANSFER_IN, 250L) to draft(TRANSFER_VOID, -250L, reverses = 5L),
            )

        for ((original, expected) in cases) {
            assertEquals(
                StockCompensation.Ready(expected),
                StockMovementRules.compensationOf(original, isAlreadyCompensated = false),
            )
        }
    }

    @Test
    fun `every compensation passes the check of the row it voids`() {
        for (type in StockMovementType.entries) {
            val milli = if (type.quantitySign.allows(QuantityMilli(1000L))) 1000L else -1000L
            val original = movement(1L, type, if (type == COST_CORRECTION) 0L else milli)
            val compensation = StockMovementRules.compensationOf(original, false)
            if (compensation is StockCompensation.Ready) {
                assertEquals(
                    "$type",
                    StockMovementCheck.Valid,
                    check(compensation.draft, compensated = original),
                )
            }
        }
    }

    @Test
    fun `a cost correction and a compensating row are not compensable`() {
        assertEquals(
            StockCompensation.NotCompensable,
            StockMovementRules.compensationOf(movement(1L, COST_CORRECTION, 0L), false),
        )
        assertEquals(
            StockCompensation.NotCompensable,
            StockMovementRules.compensationOf(movement(2L, SALE_VOID, 1000L), false),
        )
    }

    @Test
    fun `voiding the same row twice does not compensate twice`() {
        assertEquals(
            StockCompensation.AlreadyCompensated,
            StockMovementRules.compensationOf(movement(3L, SALE, -1000L), true),
        )
    }
}
