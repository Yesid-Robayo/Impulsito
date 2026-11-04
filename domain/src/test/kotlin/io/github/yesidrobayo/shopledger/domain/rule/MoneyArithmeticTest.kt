package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyArithmeticTest {
    @Test
    fun `a line by weight gives the same integers with zero two or three currency decimals`() {
        val third = QuantityMilli(333L)

        // Sin decimales: 18500 por kilogramo da 6161, exacto 6160,5.
        assertEquals(Checked.Value(Money(6161L)), Money(18_500L).timesQuantity(third))
        // Con dos decimales: 18,50 por kilogramo es 1850 y da 616, que se lee 6,16. Exacto 616,05.
        assertEquals(Checked.Value(Money(616L)), Money(1850L).timesQuantity(third))
        // Con tres decimales: 18,500 es 18500 y da 6161, que se lee 6,161. Es el mismo entero que
        // sin decimales: la moneda y sus decimales son datos, y ninguna cuenta los supone.
        assertEquals(Checked.Value(Money(6161L)), Money(18_500L).timesQuantity(third))
    }

    @Test
    fun `a price is per whole unit and never per thousandth`() {
        // Dos unidades a 3500 cada una.
        assertEquals(
            Checked.Value(Money(7000L)),
            Money(3500L).timesQuantity(QuantityMilli.ofUnits(2L)),
        )
        // 0,250 kg a 12000 por kilogramo.
        assertEquals(Checked.Value(Money(3000L)), Money(12_000L).timesQuantity(QuantityMilli(250L)))
    }

    @Test
    fun `a cheap product by weight is better kept in kilograms than in grams`() {
        // 0,250 kg a 12500 por kilogramo da 3125 exacto.
        assertEquals(Checked.Value(Money(3125L)), Money(12_500L).timesQuantity(QuantityMilli(250L)))
        // En gramos, la unidad menor no tiene fracciones: no existe 12,5 por gramo, solo 12 o 13.
        val quarterKilogramInGrams = QuantityMilli.ofUnits(250L)
        assertEquals(Checked.Value(Money(3000L)), Money(12L).timesQuantity(quarterKilogramInGrams))
        assertEquals(Checked.Value(Money(3250L)), Money(13L).timesQuantity(quarterKilogramInGrams))
    }

    @Test
    fun `a negative quantity gives the opposite amount`() {
        assertEquals(
            Checked.Value(Money(-6161L)),
            Money(18_500L).timesQuantity(QuantityMilli(-333L)),
        )
    }

    @Test
    fun `a line at the document cap is accepted and one above the cap is rejected`() {
        val oneMillionUnits = QuantityMilli(1_000_000_000L)

        assertEquals(
            Checked.Value(Money(1_000_000_000_000_000L)),
            Money(1_000_000_000L).timesQuantity(oneMillionUnits, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            Money(1_000_000_000_000L).timesQuantity(oneMillionUnits, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            Checked.Value(Money(1_000_000_000_000_000_000L)),
            Money(1_000_000_000_000L).timesQuantity(oneMillionUnits),
        )
    }

    @Test
    fun `a line that does not fit in a long is an overflow`() {
        assertEquals(Checked.Overflow, Money(Long.MAX_VALUE).timesQuantity(QuantityMilli(3000L)))
        assertEquals(
            Checked.Overflow,
            Money(Long.MAX_VALUE).timesQuantity(QuantityMilli(3000L), Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `a percentage discount is rounded to the nearest minor unit`() {
        // 12,50 % de 12345: exacto 1543,125.
        assertEquals(Checked.Value(Money(1543L)), Money(12_345L).timesRate(BasisPoints(1250L)))
        // 10 % de 20000.
        assertEquals(Checked.Value(Money(2000L)), Money(20_000L).timesRate(BasisPoints(1000L)))
    }

    @Test
    fun `twelve and a half percent of ten thousand is 1250`() {
        assertEquals(Checked.Value(Money(1250L)), Money(10_000L).timesRate(BasisPoints(1250L)))
        assertEquals(
            Checked.Value(Money(10_000L)),
            Money(10_000L).timesRate(BasisPoints.ONE_HUNDRED_PERCENT),
        )
    }

    @Test
    fun `a percentage of a negative amount is symmetric`() {
        assertEquals(Checked.Value(Money(-1543L)), Money(-12_345L).timesRate(BasisPoints(1250L)))
        assertEquals(Checked.Value(Money(-1543L)), Money(12_345L).timesRate(BasisPoints(-1250L)))
    }

    @Test
    fun `a percentage of the highest document needs 128 bits and fits`() {
        assertEquals(
            Checked.Value(Money(125_000_000_000_000L)),
            Money(1_000_000_000_000_000L).timesRate(BasisPoints(1250L)),
        )
    }

    @Test
    fun `a percentage is checked against its cap`() {
        assertEquals(
            Checked.Value(Money(1_000_000_000_000_000L)),
            Money(
                1_000_000_000_000_000L,
            ).timesRate(BasisPoints.ONE_HUNDRED_PERCENT, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            Money(1_000_000_000_000_000L).timesRate(BasisPoints(10_001L), Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `a percentage that does not fit in a long is an overflow`() {
        assertEquals(Checked.Overflow, Money(Long.MAX_VALUE).timesRate(BasisPoints(20_000L)))
        assertEquals(
            Checked.Overflow,
            Money(Long.MAX_VALUE).timesRate(BasisPoints(20_000L), Limit.DOCUMENT_TOTAL),
        )
    }
}
