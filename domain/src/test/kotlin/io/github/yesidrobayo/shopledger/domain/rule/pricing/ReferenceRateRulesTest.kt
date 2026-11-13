package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La moneda de referencia y su cotización manual, con el ejemplo de la regla. Códigos de prueba. */
class ReferenceRateRulesTest {
    private val business = requireNotNull(CurrencyCode.of("XTS"))
    private val other = requireNotNull(CurrencyCode.of("XXX"))

    @Test
    fun `a reference price of 1250 at 4150,50 is 51881 in a book without decimals`() {
        assertEquals(
            Checked.Value(Money(51_881)),
            ReferenceRateRules.toBook(Money(1_250), ReferenceCurrency(other, 2), 0, 4_150_500_000),
        )
    }

    @Test
    fun `the other currency must differ from the business and keep 0 to 4 decimals`() {
        assertEquals(
            ReferenceRateRules.ReferenceIssue.SAME_AS_BUSINESS,
            ReferenceRateRules.referenceIssue(ReferenceCurrency(business, 2), business),
        )
        assertEquals(
            ReferenceRateRules.ReferenceIssue.DECIMALS_OUT_OF_RANGE,
            ReferenceRateRules.referenceIssue(ReferenceCurrency(other, 5), business),
        )
        assertEquals(
            ReferenceRateRules.ReferenceIssue.DECIMALS_OUT_OF_RANGE,
            ReferenceRateRules.referenceIssue(ReferenceCurrency(other, -1), business),
        )
        assertNull(ReferenceRateRules.referenceIssue(ReferenceCurrency(other, 0), business))
        assertNull(ReferenceRateRules.referenceIssue(ReferenceCurrency(other, 4), business))
    }

    @Test
    fun `a rate is above zero and within the document limit`() {
        assertFalse(ReferenceRateRules.isValidRate(0))
        assertFalse(ReferenceRateRules.isValidRate(-1))
        assertTrue(ReferenceRateRules.isValidRate(1))
        assertTrue(ReferenceRateRules.isValidRate(Limit.DOCUMENT_TOTAL.max))
        assertFalse(ReferenceRateRules.isValidRate(Limit.DOCUMENT_TOTAL.max + 1))
    }

    @Test
    fun `a note is trimmed, and blank is none`() {
        assertEquals("Casa de cambio", ReferenceRateRules.note("  Casa de cambio "))
        assertNull(ReferenceRateRules.note("   "))
        assertNull(ReferenceRateRules.note(null))
    }

    @Test
    fun `the variation against the previous rate is signed, in basis points`() {
        assertEquals(BasisPoints(375), ReferenceRateRules.variation(4_000_000_000, 4_150_000_000))
        assertEquals(BasisPoints(-250), ReferenceRateRules.variation(4_000_000_000, 3_900_000_000))
        assertNull(ReferenceRateRules.variation(null, 4_150_000_000))
        assertNull(ReferenceRateRules.variation(0, 4_150_000_000))
        assertNull(ReferenceRateRules.variation(1, Limit.DOCUMENT_TOTAL.max))
    }

    @Test
    fun `a jump of more than half the previous rate warns, and never without one`() {
        assertTrue(ReferenceRateRules.isLargeJump(4_000_000_000, 40_000_000_000))
        assertTrue(ReferenceRateRules.isLargeJump(4_000_000_000, 400_000_000))
        assertFalse(ReferenceRateRules.isLargeJump(4_000_000_000, 6_000_000_000))
        assertFalse(ReferenceRateRules.isLargeJump(4_000_000_000, 2_000_000_000))
        assertFalse(ReferenceRateRules.isLargeJump(null, 4_000_000_000))
        assertFalse(ReferenceRateRules.isLargeJump(0, 4_000_000_000))
    }
}
