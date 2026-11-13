package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** El programa de puntos: cuándo se puede guardar, cuántos puntos gana una venta y cuánto valen. */
class LoyaltyProgramRulesTest {
    @Test
    fun `an active program needs both amounts, an inactive one does not`() {
        assertEquals(
            LoyaltyProgramCheck.Invalid(
                setOf(
                    LoyaltyProgramIssue.AMOUNT_PER_POINT_REQUIRED,
                    LoyaltyProgramIssue.POINT_VALUE_REQUIRED,
                ),
            ),
            LoyaltyProgramRules.check(LoyaltySettings(enabled = true, null, null)),
        )
        val off = LoyaltySettings(enabled = false, null, null)
        assertEquals(LoyaltyProgramCheck.Valid(off), LoyaltyProgramRules.check(off))
        val kept = LoyaltySettings(enabled = false, Money(1_000), Money(5))
        assertEquals(LoyaltyProgramCheck.Valid(kept), LoyaltyProgramRules.check(kept))
    }

    @Test
    fun `each amount is above zero and at most the price limit`() {
        assertEquals(
            LoyaltyProgramCheck.Invalid(
                setOf(
                    LoyaltyProgramIssue.AMOUNT_PER_POINT_OUT_OF_RANGE,
                    LoyaltyProgramIssue.POINT_VALUE_OUT_OF_RANGE,
                ),
            ),
            LoyaltyProgramRules.check(LoyaltySettings(true, Money.ZERO, Money(-1))),
        )
        val tooBig = Money(Limit.UNIT_MONEY.max + 1)
        assertEquals(
            LoyaltyProgramCheck.Invalid(setOf(LoyaltyProgramIssue.AMOUNT_PER_POINT_OUT_OF_RANGE)),
            LoyaltyProgramRules.check(LoyaltySettings(false, tooBig, null)),
        )
        val top = LoyaltySettings(true, Money(Limit.UNIT_MONEY.max), Money(1))
        assertEquals(LoyaltyProgramCheck.Valid(top), LoyaltyProgramRules.check(top))
    }

    @Test
    fun `the example of the rule earns 12 points and 100 points take off 500`() {
        assertEquals(12L, LoyaltyProgramRules.pointsEarned(Money(12_750), Money(1_000)))
        assertEquals(Checked.Value(Money(500)), LoyaltyProgramRules.discountFor(100, Money(5)))
    }

    @Test
    fun `points are truncated and nothing is earned without a positive total`() {
        assertEquals(0L, LoyaltyProgramRules.pointsEarned(Money(999), Money(1_000)))
        assertEquals(0L, LoyaltyProgramRules.pointsEarned(Money.ZERO, Money(1_000)))
        assertEquals(0L, LoyaltyProgramRules.pointsEarned(Money(-5_000), Money(1_000)))
    }

    @Test
    fun `a discount that does not fit is refused`() {
        assertEquals(
            Checked.Overflow,
            LoyaltyProgramRules.discountFor(Long.MAX_VALUE, Money(2)),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            LoyaltyProgramRules.discountFor(Limit.DOCUMENT_TOTAL.max, Money(2)),
        )
    }

    @Test
    fun `no points is the empty summary`() {
        assertEquals(PointsSummary(0L, 0), PointsSummary.NONE)
    }
}
