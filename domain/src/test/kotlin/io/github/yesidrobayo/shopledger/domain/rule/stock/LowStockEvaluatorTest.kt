package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LowStockEvaluatorTest {
    private fun units(units: Long) = QuantityMilli.ofUnits(units)

    /**
     * Un producto con stock propio y sin archivar. Por defecto su alerta está como quedaría con
     * datos sanos: activa si y solo si el saldo inicial ya era bajo.
     */
    private fun subject(
        before: QuantityMilli,
        min: QuantityMilli,
        productId: Long = 1L,
        hasActiveAlert: Boolean = before <= min,
        stockMode: ProductStockMode = ProductStockMode.TRACKED,
        isArchived: Boolean = false,
    ) = LowStockSubject(productId, stockMode, isArchived, min, before, hasActiveAlert)

    private fun evaluate(before: Long, after: Long, min: Long): LowStockTransition =
        LowStockEvaluator.evaluate(subject(units(before), units(min)), units(after))

    private fun line(
        productId: Long,
        type: StockMovementType,
        milli: Long,
        reverses: Long? = null,
    ) = StockMovementDraft(StockKey(productId, 1L, null), type, QuantityMilli(milli), reverses)

    @Test
    fun `reaching the minimum exactly is already low`() {
        val tracked = ProductStockMode.TRACKED

        assertTrue(LowStockEvaluator.isLow(tracked, false, units(5L), units(5L)))
        assertTrue(LowStockEvaluator.isLow(tracked, false, units(-3L), units(5L)))
        assertFalse(LowStockEvaluator.isLow(tracked, false, QuantityMilli(5001L), units(5L)))
    }

    @Test
    fun `a minimum of zero means low when the stock runs out`() {
        val tracked = ProductStockMode.TRACKED

        assertTrue(LowStockEvaluator.isLow(tracked, false, QuantityMilli.ZERO, QuantityMilli.ZERO))
        assertFalse(LowStockEvaluator.isLow(tracked, false, QuantityMilli(1L), QuantityMilli.ZERO))
    }

    @Test
    fun `a product without its own stock or archived is never low`() {
        val zero = QuantityMilli.ZERO

        assertFalse(LowStockEvaluator.isLow(ProductStockMode.UNTRACKED, false, zero, units(5L)))
        assertFalse(LowStockEvaluator.isLow(ProductStockMode.COMPONENTS, false, zero, units(5L)))
        assertFalse(LowStockEvaluator.isLow(ProductStockMode.TRACKED, true, zero, units(5L)))
    }

    @Test
    fun `reaching the minimum exactly notifies`() {
        assertEquals(LowStockTransition.Crossed(units(5L), units(5L)), evaluate(6L, 5L, min = 5L))
    }

    @Test
    fun `crossing far below the minimum notifies once`() {
        assertEquals(
            LowStockTransition.Crossed(units(-3L), units(5L)),
            evaluate(20L, -3L, min = 5L),
        )
    }

    @Test
    fun `a product that was already low does not notify again`() {
        assertEquals(LowStockTransition.Unchanged, evaluate(4L, 3L, min = 5L))
    }

    @Test
    fun `going up without reaching the minimum keeps the alert active`() {
        assertEquals(LowStockTransition.Unchanged, evaluate(2L, 4L, min = 5L))
    }

    @Test
    fun `going up exactly to the minimum does not rearm`() {
        assertEquals(LowStockTransition.Unchanged, evaluate(2L, 5L, min = 5L))
    }

    @Test
    fun `going above the minimum rearms`() {
        assertEquals(LowStockTransition.Rearmed, evaluate(2L, 6L, min = 5L))
    }

    @Test
    fun `falling again after a rearm notifies again`() {
        assertEquals(LowStockTransition.Crossed(units(5L), units(5L)), evaluate(6L, 5L, min = 5L))
    }

    @Test
    fun `with a minimum of zero running out notifies`() {
        assertEquals(
            LowStockTransition.Crossed(QuantityMilli.ZERO, QuantityMilli.ZERO),
            evaluate(1L, 0L, min = 0L),
        )
    }

    @Test
    fun `with a minimum of zero going negative after running out does not notify`() {
        assertEquals(LowStockTransition.Unchanged, evaluate(0L, -1L, min = 0L))
    }

    @Test
    fun `fractions cross the minimum like whole units`() {
        // 5,100 kg a 4,900 kg con mínimo de 5 kg.
        assertEquals(
            LowStockTransition.Crossed(QuantityMilli(4900L), units(5L)),
            LowStockEvaluator.evaluate(
                subject(QuantityMilli(5100L), units(5L)),
                QuantityMilli(4900L),
            ),
        )
    }

    @Test
    fun `voiding a sale that takes the stock from 3 to 8 rearms`() {
        val start = subject(units(3L), units(5L))

        assertEquals(
            mapOf(1L to LowStockTransition.Rearmed),
            LowStockEvaluator.evaluateTransaction(
                listOf(start),
                listOf(line(1L, StockMovementType.SALE_VOID, 5000L, reverses = 7L)),
            ),
        )
    }

    @Test
    fun `voiding a purchase that takes the stock from 8 to 3 notifies`() {
        val start = subject(units(8L), units(5L))

        assertEquals(
            mapOf(1L to LowStockTransition.Crossed(units(3L), units(5L))),
            LowStockEvaluator.evaluateTransaction(
                listOf(start),
                listOf(line(1L, StockMovementType.PURCHASE_VOID, -5000L, reverses = 4L)),
            ),
        )
    }

    @Test
    fun `an adjustment that leaves the stock at the minimum changes nothing`() {
        assertEquals(LowStockTransition.Unchanged, evaluate(5L, 5L, min = 5L))
    }

    @Test
    fun `an archived product is not evaluated`() {
        val archived = subject(units(8L), units(5L), isArchived = true)

        assertEquals(LowStockTransition.NotWatched, LowStockEvaluator.evaluate(archived, units(3L)))
    }

    @Test
    fun `a product without its own stock is not evaluated`() {
        for (mode in listOf(ProductStockMode.UNTRACKED, ProductStockMode.COMPONENTS)) {
            val untracked = subject(units(8L), units(5L), stockMode = mode)

            assertEquals(
                LowStockTransition.NotWatched,
                LowStockEvaluator.evaluate(untracked, units(3L)),
            )
        }
    }

    @Test
    fun `with a minimum of 5 the alert notifies once per fall and rearms only above`() {
        // De 7 a 6, 5, 4, 5, 6 y 5, una unidad por transacción, con la alerta tal como la deja
        // cada paso.
        val path = listOf(7L, 6L, 5L, 4L, 5L, 6L, 5L)
        var hasActiveAlert = false
        val transitions = mutableListOf<LowStockTransition>()
        for ((before, after) in path.zipWithNext()) {
            val start = subject(units(before), units(5L), hasActiveAlert = hasActiveAlert)
            val transition = LowStockEvaluator.evaluate(start, units(after))
            transitions += transition
            when (transition) {
                is LowStockTransition.Crossed -> hasActiveAlert = true
                LowStockTransition.Rearmed -> hasActiveAlert = false
                LowStockTransition.Unchanged, LowStockTransition.NotWatched -> Unit
            }
        }

        assertEquals(
            listOf(
                LowStockTransition.Unchanged,
                LowStockTransition.Crossed(units(5L), units(5L)),
                LowStockTransition.Unchanged,
                LowStockTransition.Unchanged,
                LowStockTransition.Rearmed,
                LowStockTransition.Crossed(units(5L), units(5L)),
            ),
            transitions,
        )
    }

    @Test
    fun `two lines of the same product count as one change`() {
        // Una venta con dos líneas del mismo producto baja de 8 a 6 y de 6 a 4: un solo aviso.
        val start = subject(units(8L), units(5L))
        val lines =
            listOf(
                line(1L, StockMovementType.SALE, -2000L),
                line(1L, StockMovementType.SALE, -2000L),
            )

        assertEquals(
            mapOf(1L to LowStockTransition.Crossed(units(4L), units(5L))),
            LowStockEvaluator.evaluateTransaction(listOf(start), lines),
        )
    }

    @Test
    fun `only the balance at commit counts and not the balance between lines`() {
        // Entre una línea y otra el producto pasa por 4, pero al confirmar queda en 7.
        val start = subject(units(6L), units(5L))
        val lines =
            listOf(
                line(1L, StockMovementType.ADJUSTMENT, -2000L),
                line(1L, StockMovementType.ADJUSTMENT, 3000L),
            )

        assertEquals(
            mapOf(1L to LowStockTransition.Unchanged),
            LowStockEvaluator.evaluateTransaction(listOf(start), lines),
        )
    }

    @Test
    fun `each product of a transaction is evaluated once and the rest are left out`() {
        val subjects =
            listOf(
                subject(units(6L), units(5L), productId = 1L),
                subject(units(2L), units(5L), productId = 2L),
                subject(units(9L), units(5L), productId = 3L),
            )
        val lines =
            listOf(
                line(1L, StockMovementType.SALE, -1000L),
                line(2L, StockMovementType.PURCHASE, 4000L),
                line(1L, StockMovementType.SALE, -1000L),
            )

        assertEquals(
            mapOf(
                1L to LowStockTransition.Crossed(units(4L), units(5L)),
                2L to LowStockTransition.Rearmed,
            ),
            LowStockEvaluator.evaluateTransaction(subjects, lines),
        )
    }

    @Test
    fun `a transaction that moves nothing evaluates nothing`() {
        val start = subject(units(3L), units(5L))

        assertTrue(LowStockEvaluator.evaluateTransaction(listOf(start), emptyList()).isEmpty())
    }

    @Test
    fun `a moved product without its initial state is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            LowStockEvaluator.evaluateTransaction(
                listOf(subject(units(3L), units(5L), productId = 1L)),
                listOf(line(2L, StockMovementType.SALE, -1000L)),
            )
        }
    }

    @Test
    fun `a product listed twice is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            LowStockEvaluator.evaluateTransaction(
                listOf(subject(units(3L), units(5L)), subject(units(3L), units(5L))),
                listOf(line(1L, StockMovementType.SALE, -1000L)),
            )
        }
    }

    @Test
    fun `an active alert is never doubled even if the start was not low`() {
        // Con datos sanos no ocurre: una alerta activa implica que el producto ya estaba bajo.
        val inconsistent = subject(units(6L), units(5L), hasActiveAlert = true)

        assertEquals(
            LowStockTransition.Unchanged,
            LowStockEvaluator.evaluate(inconsistent, units(4L)),
        )
    }

    @Test
    fun `an active alert on a product above the minimum is resolved`() {
        // Una alerta que quedó activa por encima del mínimo se resuelve al primer movimiento.
        val stale = subject(units(8L), units(5L), hasActiveAlert = true)

        assertEquals(LowStockTransition.Rearmed, LowStockEvaluator.evaluate(stale, units(7L)))
    }

    @Test
    fun `a product above the minimum without an alert stays as it was`() {
        assertEquals(LowStockTransition.Unchanged, evaluate(9L, 7L, min = 5L))
    }
}
