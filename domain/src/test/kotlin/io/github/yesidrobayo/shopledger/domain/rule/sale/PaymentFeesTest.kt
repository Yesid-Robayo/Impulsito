package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** La comisión y la acreditación de un pago, con las tablas de las reglas escritas a mano. */
class PaymentFeesTest {
    @Test
    fun theFeeTableGivesItsExpectedFees() {
        // Las tres filas valen igual con 2, 0 y 3 decimales: la cuenta no mira la moneda.
        assertEquals(fee(314), PaymentFees.feeOf(Money(10_000), BasisPoints(299), Money(15)))
        assertEquals(fee(339), PaymentFees.feeOf(Money(12_345), BasisPoints(275), Money.ZERO))
        assertEquals(fee(70), PaymentFees.feeOf(Money(1_999), BasisPoints(350), Money.ZERO))
    }

    @Test
    fun aMethodWithoutFeeCostsNothing() {
        assertEquals(fee(0), PaymentFees.feeOf(Money(10_000), BasisPoints.ZERO, Money.ZERO))
        assertEquals(fee(0), PaymentFees.feeOf(Money.ZERO, BasisPoints(299), Money.ZERO))
        // Una comisión fija se cobra aunque el pago sea cero.
        assertEquals(fee(15), PaymentFees.feeOf(Money.ZERO, BasisPoints(299), Money(15)))
    }

    @Test
    fun aFeeAboveTheDocumentLimitIsRejected() {
        val limit = Money(Limit.DOCUMENT_TOTAL.max)
        assertEquals(
            fee(Limit.DOCUMENT_TOTAL.max),
            PaymentFees.feeOf(Money.ZERO, BasisPoints.ZERO, limit),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            PaymentFees.feeOf(Money(1), BasisPoints(10_000), limit),
        )
        // Una comisión fija absurda no desborda: se compara antes de sumar.
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            PaymentFees.feeOf(Money(1), BasisPoints(10_000), Money(Long.MAX_VALUE)),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            PaymentFees.feeOf(limit, BasisPoints(10_001), Money.ZERO),
        )
        assertEquals(
            Checked.Overflow,
            PaymentFees.feeOf(Money(Long.MAX_VALUE), BasisPoints(Long.MAX_VALUE), Money.ZERO),
        )
    }

    @Test
    fun negativeAmountsAreAProgrammingError() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentFees.feeOf(Money(-1), BasisPoints.ZERO, Money.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentFees.feeOf(Money.ZERO, BasisPoints.ZERO, Money(-1))
        }
    }

    @Test
    fun theMoneyArrivesSettlementDaysAfterTheSale() {
        val date = BusinessDate(LocalDate(2026, 11, 1))
        assertEquals(BusinessDate(LocalDate(2026, 11, 3)), PaymentFees.settlementDateOf(date, 2))
        assertEquals(date, PaymentFees.settlementDateOf(date, 0))
        assertEquals(
            BusinessDate(LocalDate(2027, 1, 1)),
            PaymentFees.settlementDateOf(BusinessDate(LocalDate(2026, 12, 31)), 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PaymentFees.settlementDateOf(date, -1)
        }
    }

    private fun fee(minor: Long) = Checked.Value(Money(minor))
}
