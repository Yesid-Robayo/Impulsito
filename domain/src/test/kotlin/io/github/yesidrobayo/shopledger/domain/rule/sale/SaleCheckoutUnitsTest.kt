package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import org.junit.Assert.assertEquals
import org.junit.Test

/** El cobro de una venta por caja: cobra el precio de la caja, y una unidad que no se vende frena. */
class SaleCheckoutUnitsTest {
    private val box = 3L
    private val gram = 4L
    private val soap = product(1, salePrice = 500)
    private val cheese = product(2, salePrice = 20_000, unitId = 2)
    private val conversions = mapOf(
        soap.id to mapOf(box to ConversionDetails(box, 24_000, null, null, true, false)),
        cheese.id to mapOf(gram to ConversionDetails(gram, 1, null, null, true, false)),
    )

    private fun preview(line: SaleLineRequest, paid: Long) = SaleCheckout.preview(
        lines = listOf(line),
        products = mapOf(soap.id to soap, cheese.id to cheese),
        pricing = CartPricing(),
        adjustment = Money.ZERO,
        payments = listOf(CheckoutPaymentInput(1, PaymentMethodKind.CASH, Money(paid))),
        conversions = conversions,
    )

    @Test
    fun twoBoxesAreChargedAtTheBoxPrice() {
        val result = preview(SaleLineRequest(soap.id, QuantityMilli(2_000), unitId = box), 24_000)

        assertEquals(Money(24_000), result.amountDue)
        assertEquals(null, result.problem)
    }

    @Test
    fun aUnitNotSoldOrAQuantityThatRoundsToNothingIsALineIssue() {
        val notSold = preview(SaleLineRequest(soap.id, QuantityMilli(1_000), unitId = gram), 0)
        val nothing = preview(SaleLineRequest(cheese.id, QuantityMilli(400), unitId = gram), 0)

        assertEquals(CheckoutProblem.LineIssue, notSold.problem)
        assertEquals(CheckoutProblem.LineIssue, nothing.problem)
    }

    @Test
    fun aBoxWhoseComputedPriceDoesNotFitNeedsAWrittenPrice() {
        val dear = product(5, salePrice = 1_000_000_000_000)
        fun charge(price: Money?) = SaleCheckout.preview(
            lines = listOf(SaleLineRequest(dear.id, QuantityMilli(1_000), price, unitId = box)),
            products = mapOf(dear.id to dear),
            pricing = CartPricing(),
            adjustment = Money.ZERO,
            payments = listOf(CheckoutPaymentInput(1, PaymentMethodKind.CASH, Money(9_000))),
            conversions = mapOf(dear.id to conversions.getValue(soap.id)),
        )

        assertEquals(CheckoutProblem.LineIssue, charge(null).problem)
        assertEquals(Money(9_000), charge(Money(9_000)).amountDue)
    }
}
