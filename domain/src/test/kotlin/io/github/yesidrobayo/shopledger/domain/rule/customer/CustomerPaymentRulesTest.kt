package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las reglas del abono, con los ejemplos de la regla del libro y del vencimiento, a mano. */
class CustomerPaymentRulesTest {
    @Test
    fun `an amount must be above zero and within a document total`() {
        assertEquals(
            CustomerPaymentIssue.AMOUNT_NOT_POSITIVE,
            CustomerPaymentRules.check(Money.ZERO),
        )
        assertEquals(
            CustomerPaymentIssue.AMOUNT_NOT_POSITIVE,
            CustomerPaymentRules.check(Money(-1)),
        )
        assertNull(CustomerPaymentRules.check(Money(1)))
        assertNull(CustomerPaymentRules.check(Money(1_000_000_000_000_000L)))
        assertEquals(
            CustomerPaymentIssue.AMOUNT_TOO_LARGE,
            CustomerPaymentRules.check(Money(Limit.DOCUMENT_TOTAL.max + 1)),
        )
    }

    @Test
    fun `a payment of 2000 on 5000 leaves 3000, and paying all leaves it settled`() {
        assertEquals(
            PaymentPreview.RemainingDebt(Money(3_000)),
            CustomerPaymentRules.preview(Money(5_000), Money(2_000)),
        )
        assertEquals(
            PaymentPreview.RemainingDebt(Money.ZERO),
            CustomerPaymentRules.preview(Money(5_000), Money(5_000)),
        )
    }

    @Test
    fun `a payment of 6000 on 4500 leaves 1500 in favour`() {
        assertEquals(
            PaymentPreview.CreditLeft(Money(1_500)),
            CustomerPaymentRules.preview(Money(4_500), Money(6_000)),
        )
        // Un anticipo sin deuda queda entero a favor.
        assertEquals(
            PaymentPreview.CreditLeft(Money(800)),
            CustomerPaymentRules.preview(Money.ZERO, Money(800)),
        )
    }

    @Test
    fun `a balance that would not fit in 64 bits is refused`() {
        assertEquals(
            PaymentPreview.Overflow,
            CustomerPaymentRules.preview(Money(Long.MIN_VALUE + 1), Money(2)),
        )
    }

    @Test
    fun `6000 covers the charge of 5000 and leaves 2000 of the one of 3000`() {
        val dues = CustomerDues(
            listOf(OpenCharge(1L, Money(5_000), NOV_10), OpenCharge(2L, Money(3_000), NOV_20)),
        )

        val allocation = CustomerPaymentRules.allocate(dues, Money(6_000))

        assertEquals(
            PaymentAllocation(
                listOf(
                    CoveredCharge(1L, Money(5_000), NOV_10),
                    CoveredCharge(2L, Money(1_000), NOV_20),
                ),
                creditLeft = Money.ZERO,
            ),
            allocation,
        )
    }

    @Test
    fun `an exact payment stops at the first charge, and a larger one leaves credit`() {
        val dues = CustomerDues(
            listOf(OpenCharge(1L, Money(5_000), NOV_10), OpenCharge(2L, Money(3_000), null)),
        )

        assertEquals(
            PaymentAllocation(listOf(CoveredCharge(1L, Money(5_000), NOV_10)), Money.ZERO),
            CustomerPaymentRules.allocate(dues, Money(5_000)),
        )
        assertEquals(
            PaymentAllocation(
                listOf(
                    CoveredCharge(1L, Money(5_000), NOV_10),
                    CoveredCharge(2L, Money(3_000), null),
                ),
                creditLeft = Money(2_000),
            ),
            CustomerPaymentRules.allocate(dues, Money(10_000)),
        )
        assertEquals(
            PaymentAllocation(emptyList(), Money(700)),
            CustomerPaymentRules.allocate(CustomerDues(emptyList()), Money(700)),
        )
    }

    private companion object {
        val NOV_10: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-10"))
        val NOV_20: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-20"))
    }
}
