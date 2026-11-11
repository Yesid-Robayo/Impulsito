package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Repartir lo que cobra una venta entre varios pagos, con el ejemplo de las reglas a mano. */
class PaymentSplitTest {
    private val limit = Limit.DOCUMENT_TOTAL.max

    @Test
    fun theWorkedExampleSplitsTheAmountDueBetweenCardAndCash() {
        // 5281 por cobrar: 3001 con tarjeta y 2280 en efectivo.
        val payments = listOf(payment(CARD, 3_001), payment(CASH, 2_280))

        assertNull(PaymentSplit.firstRejection(payments))
        assertEquals(
            PaymentSplitCheck.Matches,
            PaymentSplit.check(Money(5_281), payments.map { it.amount }),
        )
    }

    @Test
    fun paymentsThatDoNotAddUpToTheAmountDueAreAMismatch() {
        assertEquals(
            PaymentSplitCheck.Mismatch(Money(5_280)),
            PaymentSplit.check(Money(5_281), listOf(Money(3_000), Money(2_280))),
        )
        assertEquals(
            PaymentSplitCheck.Mismatch(Money(5_282)),
            PaymentSplit.check(Money(5_281), listOf(Money(3_002), Money(2_280))),
        )
    }

    @Test
    fun aSaleOfZeroIsCoveredByAPaymentOfZero() {
        assertEquals(PaymentSplitCheck.Matches, PaymentSplit.check(Money.ZERO, listOf(Money.ZERO)))
        assertEquals(
            PaymentSplitCheck.Mismatch(Money(1)),
            PaymentSplit.check(Money.ZERO, listOf(Money(1))),
        )
    }

    @Test
    fun noPaymentAtAllNeverCoversASale() {
        // Una venta siempre tiene un pago: sin pagos no hay nada que cuadre, ni siquiera con cero.
        assertEquals(
            PaymentSplitCheck.Mismatch(Money.ZERO),
            PaymentSplit.check(Money(100), emptyList()),
        )
    }

    @Test
    fun aSumAboveTheDocumentLimitStopsBeforeOverflowing() {
        val amounts = List(20) { Money(limit) }

        assertEquals(PaymentSplitCheck.AboveLimit, PaymentSplit.check(Money(limit), amounts))
        assertEquals(
            PaymentSplitCheck.Matches,
            PaymentSplit.check(Money(limit), listOf(Money(limit - 1), Money(1))),
        )
    }

    @Test
    fun aRepeatedMethodNamesTheSecondPayment() {
        val payments = listOf(payment(CARD, 100), payment(CASH, 200), payment(CARD, 300))

        assertEquals(
            IndexedValue(2, PaymentRejection.DUPLICATE_METHOD),
            PaymentSplit.firstRejection(payments),
        )
    }

    @Test
    fun anAmountOutOfRangeNamesItsPayment() {
        assertEquals(
            IndexedValue(1, PaymentRejection.INVALID_AMOUNT),
            PaymentSplit.firstRejection(listOf(payment(CASH, 0), payment(CARD, -1))),
        )
        assertEquals(
            IndexedValue(0, PaymentRejection.INVALID_AMOUNT),
            PaymentSplit.firstRejection(listOf(payment(CASH, limit + 1))),
        )
        assertNull(PaymentSplit.firstRejection(listOf(payment(CASH, limit))))
        assertNull(PaymentSplit.firstRejection(emptyList()))
    }

    @Test
    fun checkingAnAmountOutOfRangeIsAProgrammingError() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentSplit.check(Money(100), listOf(Money(-1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentSplit.check(Money(100), listOf(Money(limit + 1)))
        }
    }

    private fun payment(methodId: Long, amount: Long) = SalePaymentRequest(methodId, Money(amount))

    private companion object {
        const val CASH = 1L
        const val CARD = 2L
    }
}
