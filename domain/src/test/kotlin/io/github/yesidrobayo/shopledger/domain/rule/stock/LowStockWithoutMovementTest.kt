package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los cambios del estado bajo que no mueven stock: nunca avisan, solo crean callada o resuelven. */
class LowStockWithoutMovementTest {
    private fun evaluate(
        onHandUnits: Long,
        minUnits: Long,
        hasActiveAlert: Boolean,
        stockMode: ProductStockMode = ProductStockMode.TRACKED,
        isArchived: Boolean = false,
    ): LowStockTransition = LowStockEvaluator.evaluateWithoutMovement(
        stockMode = stockMode,
        isArchived = isArchived,
        onHand = QuantityMilli.ofUnits(onHandUnits),
        minStock = QuantityMilli.ofUnits(minUnits),
        hasActiveAlert = hasActiveAlert,
    )

    @Test
    fun `raising the minimum over the stock creates the alert`() {
        assertEquals(
            LowStockTransition.Crossed(QuantityMilli.ofUnits(4L), QuantityMilli.ofUnits(5L)),
            evaluate(onHandUnits = 4L, minUnits = 5L, hasActiveAlert = false),
        )
    }

    @Test
    fun `a new product with no stock and a minimum of zero is already low`() {
        assertEquals(
            LowStockTransition.Crossed(QuantityMilli.ZERO, QuantityMilli.ZERO),
            evaluate(onHandUnits = 0L, minUnits = 0L, hasActiveAlert = false),
        )
    }

    @Test
    fun `lowering the minimum under the stock resolves the alert`() {
        assertEquals(
            LowStockTransition.Rearmed,
            evaluate(onHandUnits = 4L, minUnits = 3L, hasActiveAlert = true),
        )
    }

    @Test
    fun `a product that stops being watched resolves its alert`() {
        assertEquals(
            LowStockTransition.Rearmed,
            evaluate(0L, 5L, hasActiveAlert = true, stockMode = ProductStockMode.UNTRACKED),
        )
        assertEquals(
            LowStockTransition.Rearmed,
            evaluate(0L, 5L, hasActiveAlert = true, isArchived = true),
        )
    }

    @Test
    fun `nothing changes when the alert already matches`() {
        assertEquals(
            LowStockTransition.Unchanged,
            evaluate(onHandUnits = 3L, minUnits = 5L, hasActiveAlert = true),
        )
        assertEquals(
            LowStockTransition.Unchanged,
            evaluate(onHandUnits = 6L, minUnits = 5L, hasActiveAlert = false),
        )
    }
}
