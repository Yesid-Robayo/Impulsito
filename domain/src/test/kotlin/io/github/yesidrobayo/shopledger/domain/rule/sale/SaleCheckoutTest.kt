package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las cuentas del cobro: el ajuste escrito, varios pagos con recargo y comisión, lo que falta o
 * sobra, y cada motivo que impide confirmar. Los valores esperados están hechos a mano. Productos,
 * tasas y montos inventados.
 */
class SaleCheckoutTest {
    private val rate = 1L
    private val products = listOf(
        product(1L, salePrice = 1_190L),
        product(2L, salePrice = 2_000L),
        product(3L, salePrice = 10_000L),
        product(4L, salePrice = HUGE_PRICE),
    ).associateBy { it.id }

    private fun pricing(mode: TaxMode, bp: Long = 1_900L) =
        CartPricing(mode, mapOf(rate to BasisPoints(bp)), defaultRateId = rate)

    private fun units(n: Long) = QuantityMilli.ofUnits(n)

    private fun pay(
        amount: Long,
        kind: PaymentMethodKind = PaymentMethodKind.CASH,
        adjustmentBp: Long = 0L,
        feeBp: Long = 0L,
        feeFixed: Long = 0L,
        fee: Long? = null,
        methodId: Long = amount,
    ) = CheckoutPaymentInput(
        methodId = methodId,
        kind = kind,
        amount = Money(amount),
        priceAdjustment = BasisPoints(adjustmentBp),
        feeRate = BasisPoints(feeBp),
        feeFixed = Money(feeFixed),
        feeAmount = fee?.let(::Money),
    )

    private fun tenThousand(
        payments: List<CheckoutPaymentInput>,
        mode: TaxMode = TaxMode.NONE,
        adjustment: Long = 0L,
    ) = SaleCheckout.preview(
        listOf(SaleLineRequest(3L, units(1L))),
        products,
        pricing(mode),
        Money(adjustment),
        payments,
    )

    @Test
    fun `the complete example of the payment sum adds up with a card fee`() {
        // 19 % incluido, 2 und a 1190 y 1,500 kg a 2000, descuento de 100: total 5280, impuesto
        // 843. Tarjeta 3001 al 2,75 %: comisión 82,5 sube a 83. El efectivo cubre 2279.
        val preview = SaleCheckout.preview(
            listOf(SaleLineRequest(1L, units(2L)), SaleLineRequest(2L, QuantityMilli(1_500L))),
            products,
            pricing(TaxMode.INCLUDED),
            Money(-100L),
            listOf(
                pay(3_001L, PaymentMethodKind.CARD, feeBp = 275L),
                pay(2_279L),
            ),
        )

        assertEquals(Money(5_380L), preview.subtotal)
        assertEquals(Money(-100L), preview.writtenAdjustment)
        assertEquals(Money(5_280L), preview.total)
        assertEquals(Money(843L), preview.taxTotal)
        assertEquals(Money(5_280L), preview.due)
        assertEquals(Money(5_280L), preview.amountDue)
        assertEquals(listOf(3_001L, 2_279L), preview.payments.map { it.amount.minor })
        assertEquals(listOf(83L, 0L), preview.payments.map { it.fee.minor })
        assertEquals(Money(2_918L), preview.payments.first().netReceived)
        assertEquals(CheckoutBalance.Complete, preview.balance)
        assertTrue(preview.canRegister)
        assertNull(preview.problem)
    }

    @Test
    fun `each payment covers what its own surcharge or discount adds, tax included`() {
        // 19 % añadido sobre 10000: total 11900. 7000 con +5 % y 4900 con −2 %: recargos 350 y
        // −98. Con 350 el total es 12317, y con los dos, 12200: cubren 7417 y 4783.
        val preview = tenThousand(
            listOf(
                pay(7_000L, PaymentMethodKind.CARD, adjustmentBp = 500L),
                pay(4_900L, PaymentMethodKind.WALLET, adjustmentBp = -200L),
            ),
            TaxMode.ADDED,
        )

        assertEquals(Money(10_000L), preview.subtotal)
        assertEquals(Money(11_900L), preview.due)
        assertEquals(Money(12_200L), preview.total)
        assertEquals(Money(1_948L), preview.taxTotal)
        assertEquals(Money(12_200L), preview.amountDue)
        assertEquals(listOf(350L, -98L), preview.payments.map { it.surcharge.minor })
        assertEquals(listOf(7_417L, 4_783L), preview.payments.map { it.amount.minor })
        assertEquals(Money(252L), preview.methodAdjustment)
        assertEquals(listOf(7_000L, 4_900L), preview.payments.map { it.written.minor })
        assertTrue(preview.canRegister)
    }

    @Test
    fun `payments that do not add up say what is missing or over, and do not confirm`() {
        val missing = tenThousand(listOf(pay(9_000L)))
        val over = tenThousand(listOf(pay(10_500L)))

        assertEquals(CheckoutBalance.Missing(Money(1_000L)), missing.balance)
        assertEquals(CheckoutBalance.Excess(Money(500L)), over.balance)
        assertFalse(missing.canRegister)
        assertFalse(over.canRegister)
        assertEquals(Money(9_000L), missing.paid)
        // Lo que se cobra sigue siendo el total, no lo que se lleva escrito.
        assertEquals(Money(10_000L), missing.amountDue)
        assertEquals(Money(10_000L), over.amountDue)
    }

    @Test
    fun `without payments, all of the total is still missing`() {
        val preview = tenThousand(emptyList())

        assertEquals(Money(10_000L), preview.amountDue)
        assertEquals(CheckoutBalance.Missing(Money(10_000L)), preview.balance)
        assertEquals(Money.ZERO, preview.methodAdjustment)
    }

    @Test
    fun `a written fee is kept, a zero payment pays none, and a fee above it is flagged`() {
        val preview = tenThousand(
            listOf(
                pay(9_000L, PaymentMethodKind.CARD, feeBp = 299L, feeFixed = 15L, fee = 400L),
                pay(0L, PaymentMethodKind.WALLET, feeBp = 299L, feeFixed = 15L),
                pay(1_000L, PaymentMethodKind.OTHER, feeFixed = 1_500L),
            ),
        )

        assertEquals(listOf(400L, 0L, 1_500L), preview.payments.map { it.fee.minor })
        assertEquals(listOf(false, false, true), preview.payments.map { it.feeAboveAmount })
        assertEquals(Money(-500L), preview.payments.last().netReceived)
        assertTrue(preview.canRegister)
    }

    @Test
    fun `a line that cannot be charged stops the checkout`() {
        val cases = listOf(
            // Un producto que ya no se vende.
            SaleLineRequest(99L, units(1L)),
            // Un bruto sobre el tope de una línea.
            SaleLineRequest(4L, units(2_000L)),
            // Un descuento mayor que la línea.
            SaleLineRequest(3L, units(1L), discount = LineDiscount.Amount(Money(10_001L))),
        )
        for (line in cases) {
            val preview = SaleCheckout.preview(
                listOf(line),
                products,
                pricing(TaxMode.NONE),
                Money.ZERO,
                listOf(pay(1L)),
            )

            assertEquals(CheckoutProblem.LineIssue, preview.problem)
            assertNull(preview.balance)
            assertNull(preview.subtotal)
            assertFalse(preview.canRegister)
        }
    }

    @Test
    fun `a written discount above the sale is rejected, also past the document limit`() {
        assertEquals(
            CheckoutProblem.DiscountAboveSale,
            tenThousand(listOf(pay(0L)), adjustment = -10_001L).problem,
        )
        // Dos líneas de 900 und a 10^12 suman más que el tope, y el descuento también lo pasa.
        val big = SaleCheckout.preview(
            listOf(SaleLineRequest(4L, units(900L)), SaleLineRequest(4L, units(900L))),
            products,
            pricing(TaxMode.NONE),
            Money(-1_000_000_000_000_001L),
            emptyList(),
        )
        assertEquals(CheckoutProblem.DiscountAboveSale, big.problem)
        assertEquals(Money(1_800_000_000_000_000L), big.subtotal)
    }

    @Test
    fun `a total past the document limit is too large`() {
        val twoBig = SaleCheckout.preview(
            listOf(SaleLineRequest(4L, units(600L)), SaleLineRequest(4L, units(600L))),
            products,
            pricing(TaxMode.NONE),
            Money.ZERO,
            emptyList(),
        )
        val taxPushesOver = SaleCheckout.preview(
            listOf(SaleLineRequest(4L, units(900L))),
            products,
            pricing(TaxMode.ADDED),
            Money.ZERO,
            emptyList(),
        )
        val hugeSurcharge = tenThousand(emptyList(), adjustment = 1_000_000_000_000_001L)

        assertEquals(CheckoutProblem.TooLarge, twoBig.problem)
        assertEquals(CheckoutProblem.TooLarge, taxPushesOver.problem)
        assertEquals(CheckoutProblem.TooLarge, hugeSurcharge.problem)
    }

    @Test
    fun `a payment out of range is too large`() {
        assertEquals(CheckoutProblem.TooLarge, tenThousand(listOf(pay(-1L))).problem)
        assertEquals(
            CheckoutProblem.TooLarge,
            tenThousand(listOf(pay(1_000_000_000_000_001L))).problem,
        )
    }

    @Test
    fun `a method adjustment the sale cannot take is too large`() {
        // +100 % sobre 9 × 10^14 deja la venta sobre el tope.
        val over = SaleCheckout.preview(
            listOf(SaleLineRequest(4L, units(900L))),
            products,
            pricing(TaxMode.NONE),
            Money.ZERO,
            listOf(pay(900_000_000_000_000L, PaymentMethodKind.CARD, adjustmentBp = 10_000L)),
        )
        // −100 % sobre 6000, con un descuento escrito de 5000: el descuento pasa de 10000.
        val below = tenThousand(
            listOf(pay(6_000L, PaymentMethodKind.WALLET, adjustmentBp = -10_000L)),
            adjustment = -5_000L,
        )

        // Dos líneas de 9 × 10^14 con 9 × 10^14 de descuento escrito: el pago a −100 % lleva el
        // ajuste a −1,8 × 10^15, más allá del tope.
        val pastLimit = SaleCheckout.preview(
            listOf(SaleLineRequest(4L, units(900L)), SaleLineRequest(4L, units(900L))),
            products,
            pricing(TaxMode.NONE),
            Money(-900_000_000_000_000L),
            listOf(pay(900_000_000_000_000L, PaymentMethodKind.WALLET, adjustmentBp = -10_000L)),
        )

        assertEquals(CheckoutProblem.TooLarge, over.problem)
        assertEquals(CheckoutProblem.TooLarge, below.problem)
        assertEquals(Money(5_000L), below.due)
        assertEquals(CheckoutProblem.TooLarge, pastLimit.problem)
        assertEquals(Money(900_000_000_000_000L), pastLimit.due)
    }

    @Test
    fun `a price written for the line replaces the product price`() {
        val preview = SaleCheckout.preview(
            listOf(SaleLineRequest(3L, units(2L), unitPrice = Money(4_500L))),
            products,
            pricing(TaxMode.NONE),
            Money.ZERO,
            listOf(pay(9_000L)),
        )

        assertEquals(Money(9_000L), preview.subtotal)
        assertTrue(preview.canRegister)
    }

    @Test
    fun `a method discount that leaves its payment below zero names it`() {
        // 19 % añadido: 100 a −100 % baja el neto a 9900, y el total a 11781. El pago cubriría
        // 100 + 11781 − 11900 = −19.
        val preview = tenThousand(
            listOf(pay(100L, PaymentMethodKind.WALLET, adjustmentBp = -10_000L)),
            TaxMode.ADDED,
        )

        assertEquals(CheckoutProblem.PaymentBelowZero(0), preview.problem)
    }

    @Test
    fun `a method fee past the document limit is too large`() {
        val preview = tenThousand(
            listOf(pay(10_000L, PaymentMethodKind.CARD, feeFixed = 1_000_000_000_000_001L)),
        )

        assertEquals(CheckoutProblem.TooLarge, preview.problem)
    }

    @Test
    fun `the cash part is rounded after the card, as in the payment sum example`() {
        // 5280 con 3001 en tarjeta: el efectivo cubre 2279, que con paso 5 sube a 2280. Se cobran
        // 5281, y con 5000 recibidos el cambio es 2720.
        val preview = SaleCheckout.preview(
            listOf(SaleLineRequest(1L, units(2L)), SaleLineRequest(2L, QuantityMilli(1_500L))),
            products,
            pricing(TaxMode.INCLUDED),
            Money(-100L),
            listOf(
                pay(3_001L, PaymentMethodKind.CARD, feeBp = 275L),
                pay(2_279L).copy(tenderedAmount = Money(5_000L)),
            ),
            cashStep = Money(5L),
        )

        assertEquals(Money(5_280L), preview.total)
        assertEquals(Money(1L), preview.cashRounding)
        assertEquals(Money(5_281L), preview.amountDue)
        assertEquals(listOf(0L, 1L), preview.payments.map { it.rounding.minor })
        assertEquals(listOf(3_001L, 2_280L), preview.payments.map { it.amount.minor })
        assertEquals(listOf(null, Money(2_720L)), preview.payments.map { it.change })
        assertEquals(CheckoutBalance.Complete, preview.balance)
        assertTrue(preview.canRegister)
    }

    @Test
    fun `what was received below the rounded cash part stops the checkout`() {
        // Con paso 50, 10000 ya es múltiplo; 9999 recibidos no alcanzan.
        val preview = tenThousand(listOf(pay(10_000L).copy(tenderedAmount = Money(9_999L))))
        val short = SaleCheckout.preview(
            listOf(SaleLineRequest(3L, units(1L))),
            products,
            pricing(TaxMode.NONE),
            Money.ZERO,
            listOf(pay(10_000L).copy(tenderedAmount = Money(9_999L))),
            cashStep = Money(50L),
        )

        assertEquals(CheckoutProblem.TenderedBelowAmount(0), preview.problem)
        assertEquals(CheckoutProblem.TenderedBelowAmount(0), short.problem)
        assertTrue(preview.payments.single().tenderedBelowAmount)
        assertEquals(Money(-1L), preview.payments.single().change)
        assertFalse(preview.canRegister)
    }

    @Test
    fun `a rounding that passes the document limit is too large`() {
        // 999 und a 10^12 y una de 999999999999: 999999999999999, que con un paso de 6 × 10^11
        // sube a 1667 pasos, sobre el tope.
        val almost = product(5L, salePrice = 999_999_999_999L)
        val preview = SaleCheckout.preview(
            listOf(SaleLineRequest(4L, units(999L)), SaleLineRequest(5L, units(1L))),
            products + (5L to almost),
            pricing(TaxMode.NONE),
            Money.ZERO,
            listOf(pay(999_999_999_999_999L)),
            cashStep = Money(600_000_000_000L),
        )

        assertEquals(CheckoutProblem.TooLarge, preview.problem)
    }

    @Test
    fun `the tip is covered by the payments but pays no surcharge`() {
        // 10000 + propina de 1000 con tarjeta a +5 %: recargo 500 sobre 10000, se cobran 11500.
        val preview = SaleCheckout.preview(
            listOf(SaleLineRequest(3L, units(1L))),
            products,
            pricing(TaxMode.NONE),
            Money.ZERO,
            listOf(pay(11_000L, PaymentMethodKind.CARD, adjustmentBp = 500L, feeBp = 300L)),
            tip = Money(1_000L),
        )

        assertEquals(Money(11_000L), preview.due)
        assertEquals(Money(10_500L), preview.total)
        assertEquals(Money(1_000L), preview.tip)
        assertEquals(Money(11_500L), preview.amountDue)
        assertEquals(Money(500L), preview.payments.single().surcharge)
        // La comisión sí se cobra sobre todo el pago, propina incluida: 3 % de 11500.
        assertEquals(Money(345L), preview.payments.single().fee)
        assertTrue(preview.canRegister)
    }

    @Test
    fun `a tip past the document limit with the sale is too large`() {
        val preview = SaleCheckout.preview(
            listOf(SaleLineRequest(3L, units(1L))),
            products,
            pricing(TaxMode.NONE),
            Money.ZERO,
            emptyList(),
            tip = Money(999_999_999_999_999L),
        )

        assertEquals(CheckoutProblem.TooLarge, preview.problem)
    }

    private companion object {
        /** El precio por unidad más alto que admite un producto. */
        const val HUGE_PRICE = 1_000_000_000_000L
    }
}
