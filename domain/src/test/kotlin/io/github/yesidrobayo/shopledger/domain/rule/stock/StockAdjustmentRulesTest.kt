package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El valor de un ajuste, con los ejemplos de las reglas de negocio, y sus casos borde. */
class StockAdjustmentRulesTest {
    private fun state(onHand: Long, average: Long, value: Long) =
        CostState(QuantityMilli(onHand), Money(average), Money(value))

    private fun valueOf(state: CostState, change: Long): AdjustmentValue =
        (StockAdjustmentRules.value(state, QuantityMilli(change)) as Checked.Value).value

    @Test
    fun `the shrinkage example of the rules, step by step`() {
        val start = state(11_000L, 1_355L, 14_905L)

        val expired = valueOf(start, -3_000L)
        assertEquals(Money(-4_065L), expired.totalCost)
        assertEquals(state(8_000L, 1_355L, 10_840L), expired.after)

        val missing = valueOf(expired.after, -2_000L)
        assertEquals(Money(-2_710L), missing.totalCost)

        val found = valueOf(missing.after, 1_000L)
        assertEquals(Money(1_355L), found.totalCost)
        assertEquals(state(7_000L, 1_355L, 9_485L), found.after)
    }

    @Test
    fun `the count example of the rules`() {
        val delta =
            StockAdjustmentRules.countDifference(QuantityMilli(12_000L), QuantityMilli(10_000L))

        assertEquals(QuantityMilli(-2_000L), delta)
        val value = valueOf(state(12_000L, 1_355L, 16_260L), delta.milli)
        assertEquals(Money(-2_710L), value.totalCost)
    }

    @Test
    fun `an exit beyond the stock takes its value plus the rest at the average`() {
        val value = valueOf(state(2_000L, 1_000L, 2_000L), -5_000L)

        assertEquals(Money(-5_000L), value.totalCost)
        assertEquals(state(-3_000L, 1_000L, 0L), value.after)
    }

    @Test
    fun `an exit over negative stock goes at the average`() {
        val value = valueOf(state(-1_000L, 800L, 0L), -2_000L)

        assertEquals(Money(-1_600L), value.totalCost)
        assertEquals(QuantityMilli(-3_000L), value.after.onHand)
    }

    @Test
    fun `an entry over no stock enters at the average`() {
        val value = valueOf(state(-1_000L, 800L, 0L), 3_000L)

        assertEquals(Money(2_400L), value.totalCost)
        assertEquals(state(2_000L, 800L, 1_600L), value.after)
    }

    @Test
    fun `a product with no cost adjusts for zero`() {
        val value = valueOf(state(5_000L, 0L, 0L), -2_000L)

        assertEquals(Money.ZERO, value.totalCost)
        assertEquals(Money.ZERO, valueOf(state(5_000L, 0L, 0L), 2_000L).totalCost)
    }

    @Test
    fun `figures that do not fit are rejected`() {
        val full = state(1_000L, 1_000L, Long.MAX_VALUE)

        // La salida de más suma al valor completo el resto al promedio, y no cabe.
        assertEquals(Checked.Overflow, StockAdjustmentRules.value(full, QuantityMilli(-2_000L)))
        // La parte proporcional de un valor enorme no cabe.
        assertEquals(Checked.Overflow, StockAdjustmentRules.value(full, QuantityMilli(2_000L)))
        // La parte cabe, pero sumarla al valor no.
        val half = state(1_000L, 1_000L, Long.MAX_VALUE / 2 + 1)
        assertEquals(Checked.Overflow, StockAdjustmentRules.value(half, QuantityMilli(1_000L)))
    }

    @Test
    fun `an entry cannot push the value above its limit`() {
        val top = state(1_000L, 1_000L, Limit.STOCK_VALUE.max)

        assertEquals(
            Checked.AboveLimit(Limit.STOCK_VALUE),
            StockAdjustmentRules.value(top, QuantityMilli(1L)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an adjustment always moves something`() {
        StockAdjustmentRules.value(state(1_000L, 1L, 1L), QuantityMilli.ZERO)
    }

    @Test
    fun `a count can be zero, never negative, and whole in a counting unit`() {
        assertEquals(
            UnitQuantityCheck.Valid,
            StockAdjustmentRules.checkCount(QuantityMilli.ZERO, allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.NotPositive,
            StockAdjustmentRules.checkCount(QuantityMilli(-1L), allowsFraction = true),
        )
        assertEquals(
            UnitQuantityCheck.FractionNotAllowed,
            StockAdjustmentRules.checkCount(QuantityMilli(1_500L), allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.Valid,
            StockAdjustmentRules.checkCount(QuantityMilli(1_500L), allowsFraction = true),
        )
    }

    @Test
    fun `only adjustments and opening balances are voided from the history`() {
        val voidable = StockMovementType.entries.filter { StockAdjustmentRules.isVoidable(it) }

        assertEquals(
            listOf(StockMovementType.OPENING_BALANCE, StockMovementType.ADJUSTMENT),
            voidable,
        )
        assertTrue(voidable.all { it.voidType == StockMovementType.ADJUSTMENT_VOID })
        assertFalse(StockAdjustmentRules.isVoidable(StockMovementType.ADJUSTMENT_VOID))
    }
}
