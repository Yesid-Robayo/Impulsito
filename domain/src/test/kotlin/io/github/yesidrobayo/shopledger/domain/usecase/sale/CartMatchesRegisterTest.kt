package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El total que muestra la venta en edición es el que guarda la venta al confirmar: con el impuesto
 * añadido o incluido línea por línea, con descuentos y con el recargo o el descuento del método.
 * Tasas, precios y recargos inventados.
 */
class CartMatchesRegisterTest {
    private val general = 1L
    private val reduced = 2L
    private val card = 7L
    private val promo = 8L

    private fun world(mode: TaxMode) = SaleWorld().apply {
        taxRates = mapOf(
            general to taxRate(general, "Tasa general", 1_900L),
            reduced to taxRate(reduced, "Tasa reducida", 500L),
        )
        setTaxes(mode, defaultTaxRateId = general)
        addMethod(method())
        addMethod(method(card, PaymentMethodKind.CARD, priceAdjustmentBp = 350L))
        addMethod(method(promo, PaymentMethodKind.WALLET, priceAdjustmentBp = -1_000L))
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

    private suspend fun compare(mode: TaxMode, methodId: Long, adjustmentBp: Long) {
        val world = world(mode)
        val pricing = CartPricing(
            taxMode = mode,
            rates = world.taxRates.mapValues { it.value.details.rate },
            defaultRateId = general,
            methodAdjustment = BasisPoints(adjustmentBp),
        )
        val preview = SaleCart.preview(lines, world.tables.products, emptySet(), pricing)

        val result = world.registerSale()(SaleRequest(lines, methodId))

        val sale = world.tables.sales.getValue((result as RegisterSaleResult.Registered).saleId)
        assertEquals("total con $mode y ajuste $adjustmentBp", sale.content.total, preview.total)
        assertEquals(
            "impuesto con $mode y ajuste $adjustmentBp",
            sale.content.taxTotal,
            preview.tax,
        )
        val stored = world.tables.lines.values.filter { it.saleId == sale.id }.sortedBy { it.id }
        assertEquals(stored.map { it.content.lineTotal }, preview.lines.map { it.total })
    }

    @Test
    fun `with tax added, the cart total is the registered total`() = runTest {
        compare(TaxMode.ADDED, SaleWorld.CASH, 0L)
    }

    @Test
    fun `with tax added and a card surcharge, the cart total is the registered total`() = runTest {
        compare(TaxMode.ADDED, card, 350L)
    }

    @Test
    fun `with tax added and a method discount, the cart total is the registered total`() = runTest {
        compare(TaxMode.ADDED, promo, -1_000L)
    }

    @Test
    fun `with tax included or none, the cart total is the registered total`() = runTest {
        compare(TaxMode.INCLUDED, card, 350L)
        compare(TaxMode.NONE, SaleWorld.CASH, 0L)
    }
}
