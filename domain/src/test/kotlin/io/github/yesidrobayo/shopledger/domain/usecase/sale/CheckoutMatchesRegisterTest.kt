package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutPaymentInput
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCheckout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que muestra la pantalla de cobro es lo que guarda la venta: con impuesto añadido, incluido o
 * sin impuesto, con un ajuste escrito para toda la venta, varios pagos con recargo o descuento de
 * su método y comisiones calculadas o escritas. Tasas, precios y recargos inventados.
 */
class CheckoutMatchesRegisterTest {
    private val general = 1L
    private val reduced = 2L
    private val card = 7L
    private val promo = 8L

    private val cardMethod =
        method(card, PaymentMethodKind.CARD, feeBp = 299L, feeFixed = 15L, priceAdjustmentBp = 350L)
    private val promoMethod = method(promo, PaymentMethodKind.WALLET, priceAdjustmentBp = -1_000L)

    private fun world(mode: TaxMode) = SaleWorld().apply {
        taxRates = mapOf(
            general to taxRate(general, "Tasa general", 1_900L),
            reduced to taxRate(reduced, "Tasa reducida", 500L),
        )
        setTaxes(mode, defaultTaxRateId = general)
        addMethod(method())
        addMethod(cardMethod)
        addMethod(promoMethod)
        addProduct(product(1L, salePrice = 3_333L, onHandMilli = 50_000L, stockValue = 50_000L))
        addProduct(
            product(2L, salePrice = 18_517L, unitId = SaleWorld.KILOGRAM, taxRateId = reduced),
        )
        addProduct(product(3L, salePrice = 999L, onHandMilli = 1_000L, stockValue = 500L))
    }

    private val lines = listOf(
        SaleLineRequest(
            1L,
            QuantityMilli(3_000L),
            discount = LineDiscount.Percent(BasisPoints(750L)),
        ),
        SaleLineRequest(2L, QuantityMilli(1_337L)),
        SaleLineRequest(3L, QuantityMilli(1_000L), discount = LineDiscount.Amount(Money(99L))),
    )

    private fun input(method: SalePaymentMethod, amount: Long, fee: Long? = null) =
        CheckoutPaymentInput(
            methodId = method.id,
            kind = method.kind,
            amount = Money(amount),
            priceAdjustment = method.priceAdjustment,
            feeRate = method.feeRate,
            feeFixed = method.feeFixed,
            feeAmount = fee?.let(::Money),
        )

    /**
     * Calcula el cobro, completa el último pago con lo que falta, como `Usar {amount}`, y registra
     * la venta con esos mismos pagos.
     */
    private suspend fun compare(
        mode: TaxMode,
        adjustment: Long,
        split: List<Pair<SalePaymentMethod, Long?>>,
    ) {
        val world = world(mode)
        val pricing = CartPricing(
            taxMode = mode,
            rates = world.taxRates.mapValues { it.value.details.rate },
            defaultRateId = general,
        )
        val opened = SaleCheckout.preview(
            lines,
            world.tables.products,
            pricing,
            Money(adjustment),
            emptyList(),
        )
        val due = requireNotNull(opened.due) { "La venta se puede cobrar" }.minor
        val first = split.dropLast(1).map { (method, amount) ->
            input(method, requireNotNull(amount))
        }
        val last = input(split.last().first, due - first.sumOf { it.amount.minor }, fee = 120L)
        val inputs = first + last
        val preview = SaleCheckout.preview(
            lines,
            world.tables.products,
            pricing,
            Money(adjustment),
            inputs,
        )
        assertTrue("el cobro cuadra con $mode", preview.canRegister)

        val result = world.registerSale()(
            SaleRequest(
                lines = lines,
                paymentMethodId = SaleWorld.CASH,
                payments = inputs.map { SalePaymentRequest(it.methodId, it.amount, it.feeAmount) },
                orderAdjustment = Money(adjustment),
            ),
        )

        val sale = world.tables.sales.getValue((result as RegisterSaleResult.Registered).saleId)
        assertEquals("total con $mode", sale.content.total, preview.total)
        assertEquals("impuesto con $mode", sale.content.taxTotal, preview.taxTotal)
        assertEquals("cobrado con $mode", sale.content.amountDue, preview.amountDue)
        val stored = world.tables.payments.values.filter { it.saleId == sale.id }.sortedBy { it.id }
        assertEquals(stored.map { it.content.amount }, preview.payments.map { it.amount })
        assertEquals(stored.map { it.content.feeAmount }, preview.payments.map { it.fee })
    }

    @Test
    fun `with tax added, a discount and a surcharged card, the checkout is the registered sale`() =
        runTest {
            compare(TaxMode.ADDED, -1_234L, listOf(cardMethod to 20_001L, method() to null))
        }

    @Test
    fun `with tax included, a surcharge and a method discount, it is the registered sale`() =
        runTest {
            compare(
                TaxMode.INCLUDED,
                777L,
                listOf(
                    promoMethod to 9_999L,
                    cardMethod to 12_345L,
                    method() to null,
                ),
            )
        }

    @Test
    fun `without tax and a single payment, the checkout is the registered sale`() = runTest {
        compare(TaxMode.NONE, 0L, listOf(cardMethod to null))
    }
}
