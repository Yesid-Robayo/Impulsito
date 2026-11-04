package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitQuantityRuleTest {
    @Test
    fun `a counting unit accepts whole units from one`() {
        assertEquals(
            UnitQuantityCheck.Valid,
            UnitQuantityRule.check(QuantityMilli(1000L), allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.Valid,
            UnitQuantityRule.check(QuantityMilli(24_000L), allowsFraction = false),
        )
    }

    @Test
    fun `a counting unit rejects a fraction`() {
        assertEquals(
            UnitQuantityCheck.FractionNotAllowed,
            UnitQuantityRule.check(QuantityMilli(500L), allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.FractionNotAllowed,
            UnitQuantityRule.check(QuantityMilli(1500L), allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.FractionNotAllowed,
            UnitQuantityRule.check(QuantityMilli(1L), allowsFraction = false),
        )
    }

    @Test
    fun `a fractional unit accepts up to three decimals from one thousandth`() {
        assertEquals(
            UnitQuantityCheck.Valid,
            UnitQuantityRule.check(QuantityMilli(1L), allowsFraction = true),
        )
        assertEquals(
            UnitQuantityCheck.Valid,
            UnitQuantityRule.check(QuantityMilli(250L), allowsFraction = true),
        )
        assertEquals(
            UnitQuantityCheck.Valid,
            UnitQuantityRule.check(QuantityMilli(1500L), allowsFraction = true),
        )
    }

    @Test
    fun `zero or less is rejected for any unit`() {
        assertEquals(
            UnitQuantityCheck.NotPositive,
            UnitQuantityRule.check(QuantityMilli.ZERO, allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.NotPositive,
            UnitQuantityRule.check(QuantityMilli.ZERO, allowsFraction = true),
        )
        assertEquals(
            UnitQuantityCheck.NotPositive,
            UnitQuantityRule.check(QuantityMilli(-1000L), allowsFraction = false),
        )
        assertEquals(
            UnitQuantityCheck.NotPositive,
            UnitQuantityRule.check(QuantityMilli(-1L), allowsFraction = true),
        )
    }
}
