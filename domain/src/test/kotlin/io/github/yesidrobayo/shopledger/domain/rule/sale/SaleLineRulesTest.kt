package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las cuentas de una línea de venta, con los ejemplos de las reglas escritos a mano. */
class SaleLineRulesTest {
    @Test
    fun theGrossRoundsToTheNearestMinorUnit() {
        // 0,333 kg a 18500 son 6160,5, que sube a 6161.
        assertEquals(
            Checked.Value(Money(6_161)),
            SaleLineRules.gross(QuantityMilli(333), Money(18_500)),
        )
        assertEquals(
            Checked.Value(Money(7_000)),
            SaleLineRules.gross(QuantityMilli(2_000), Money(3_500)),
        )
    }

    @Test
    fun theGrossStopsAtTheLineLimit() {
        val max = QuantityMilli(Limit.LINE_QUANTITY_MILLI.max)
        // Un millón de unidades a mil millones cada una llega justo al tope de una línea.
        assertEquals(
            Checked.Value(Money(Limit.DOCUMENT_TOTAL.max)),
            SaleLineRules.gross(max, Money(1_000_000_000)),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            SaleLineRules.gross(max, Money(1_000_000_001)),
        )
    }

    @Test
    fun noDiscountIsZero() {
        assertEquals(Money.ZERO, SaleLineRules.discountOf(Money(500), LineDiscount.None))
    }

    @Test
    fun anAmountGoesFromZeroToTheGross() {
        val gross = Money(20_000)
        assertEquals(Money.ZERO, SaleLineRules.discountOf(gross, LineDiscount.Amount(Money.ZERO)))
        assertEquals(gross, SaleLineRules.discountOf(gross, LineDiscount.Amount(gross)))
        assertNull(SaleLineRules.discountOf(gross, LineDiscount.Amount(Money(20_001))))
        assertNull(SaleLineRules.discountOf(gross, LineDiscount.Amount(Money(-1))))
    }

    @Test
    fun aPercentIsConvertedOnTheGrossAndNotKept() {
        // El 10 % de 20000, y el 12,50 % de 12345 redondeado al más cercano.
        assertEquals(Money(2_000), SaleLineRules.discountOf(Money(20_000), percent(1_000)))
        assertEquals(Money(1_543), SaleLineRules.discountOf(Money(12_345), percent(1_250)))
        assertEquals(Money.ZERO, SaleLineRules.discountOf(Money(12_345), percent(0)))
        assertEquals(Money(12_345), SaleLineRules.discountOf(Money(12_345), percent(10_000)))
        assertNull(SaleLineRules.discountOf(Money(12_345), percent(10_001)))
        assertNull(SaleLineRules.discountOf(Money(12_345), percent(-1)))
    }

    @Test
    fun theReplacementCostFallsBackToTheFrozenCost() {
        assertEquals(Money(1_500), SaleLineRules.unitReplacementCost(Money(1_500), Money(1_000)))
        assertEquals(Money(1_000), SaleLineRules.unitReplacementCost(Money.ZERO, Money(1_000)))
        assertEquals(
            Checked.Value(Money(3_000)),
            SaleLineRules.lineReplacementCost(QuantityMilli(2_000), Money(1_500)),
        )
    }

    private fun percent(bp: Long) = LineDiscount.Percent(BasisPoints(bp))
}
