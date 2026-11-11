package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo que dejó una venta, con el ejemplo de la venta de tres productos y un pago con comisión. */
class SaleResultsTest {
    private val at = Instant.fromEpochMilliseconds(0L)
    private val date = BusinessDate(LocalDate(2026, 11, 2))

    private fun sale(net: Long, cost: Long) = Sale(
        id = 1L,
        content = NewSale(
            netTotal = Money(net),
            taxTotal = Money.ZERO,
            total = Money(net),
            totalCost = Money(cost),
            totalReplacementCost = Money(cost),
            orderAdjustment = Money.ZERO,
            tipAmount = Money.ZERO,
            cashRoundingAmount = Money.ZERO,
            amountDue = Money(net),
            customerId = null,
            priceListId = 1L,
            locationId = 1L,
            cashSessionId = null,
            quoteId = null,
            orderId = null,
            note = null,
            occurredAt = at,
            businessDate = date,
            createdAt = at,
        ),
        voiding = null,
    )

    private fun payment(amount: Long, fee: Long) = SalePayment(
        id = amount,
        saleId = 1L,
        content = NewSalePayment(
            paymentMethodId = 1L,
            methodNameSnapshot = "Tarjeta",
            methodKind = PaymentMethodKind.CARD,
            amount = Money(amount),
            feeAmount = Money(fee),
            settlementDate = date,
            moneyAccountId = null,
            createdAt = at,
        ),
    )

    @Test
    fun `the three product sale leaves 13183 with a 42,53 percent margin`() {
        val results = SaleResults.of(sale(31_000L, 17_817L), listOf(payment(31_000L, 899L)))

        assertEquals(Money(17_817L), results.cost)
        assertEquals(Money(13_183L), results.profit)
        assertEquals(Margin.Defined(BasisPoints(4_253L)), results.margin)
        assertEquals(Money(899L), results.fees)
        assertEquals(Money(30_101L), results.netReceived)
    }

    @Test
    fun `a sale of zero has no margin and its loss is its cost`() {
        val results = SaleResults.of(sale(0L, 1_200L), listOf(payment(0L, 0L)))

        assertEquals(Money(-1_200L), results.profit)
        assertEquals(Margin.NoData, results.margin)
        assertEquals(Money.ZERO, results.netReceived)
    }
}
