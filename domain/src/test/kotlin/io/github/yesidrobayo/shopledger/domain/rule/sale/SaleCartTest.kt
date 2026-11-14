package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
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
 * La venta en edición: agregar suma a la línea del mismo producto, el menos quita la línea en su
 * última unidad, los topes de líneas y de cantidad, el aviso de stock y las cuentas, que son las de
 * la venta al confirmar. Productos y montos inventados.
 */
class SaleCartTest {
    private val one = QuantityMilli.ofUnits(1L)
    private val two = QuantityMilli.ofUnits(2L)
    private val kilogram = 2L

    private fun line(productId: Long, quantity: QuantityMilli = one) =
        SaleLineRequest(productId, quantity)

    private fun changed(change: CartChange): List<SaleLineRequest> =
        (change as CartChange.Changed).lines

    @Test
    fun `adding a new product appends a line with its quantity`() {
        val lines = changed(SaleCart.add(listOf(line(1L)), 2L, one))

        assertEquals(listOf(line(1L), line(2L)), lines)
    }

    @Test
    fun `adding the same product again adds to its line instead of repeating it`() {
        val lines = changed(SaleCart.add(listOf(line(1L), line(2L)), 1L, one))

        assertEquals(listOf(line(1L, two), line(2L)), lines)
    }

    @Test
    fun `a sale at the line limit does not take another product but still adds to a present one`() {
        val full = (1L..Limit.DOCUMENT_LINES.max).map { line(it) }

        assertEquals(CartChange.LinesFull, SaleCart.add(full, 9_999L, one))
        assertEquals(two, changed(SaleCart.add(full, 1L, one)).first().quantity)
    }

    @Test
    fun `a quantity that is not positive or passes the line limit is rejected`() {
        val atLimit = QuantityMilli(Limit.LINE_QUANTITY_MILLI.max)

        assertEquals(CartChange.InvalidQuantity, SaleCart.add(emptyList(), 1L, QuantityMilli.ZERO))
        assertEquals(CartChange.InvalidQuantity, SaleCart.add(listOf(line(1L, atLimit)), 1L, one))
        assertEquals(
            CartChange.InvalidQuantity,
            SaleCart.setQuantity(listOf(line(1L)), 1L, QuantityMilli(-1L)),
        )
        assertEquals(atLimit, changed(SaleCart.add(emptyList(), 1L, atLimit)).single().quantity)
    }

    @Test
    fun `setting the quantity changes only that line and leaves a missing one alone`() {
        val lines = listOf(line(1L), line(2L))

        assertEquals(
            listOf(line(1L), line(2L, QuantityMilli(1_250L))),
            changed(SaleCart.setQuantity(lines, 2L, QuantityMilli(1_250L))),
        )
        assertEquals(lines, changed(SaleCart.setQuantity(lines, 3L, two)))
    }

    @Test
    fun `minus takes one unit, and on the last unit removes the line`() {
        val lines = listOf(line(1L, two), line(2L))

        assertEquals(listOf(line(1L), line(2L)), SaleCart.decrement(lines, 1L))
        assertEquals(listOf(line(1L, two)), SaleCart.decrement(lines, 2L))
        assertTrue(SaleCart.decrementRemoves(one))
        assertFalse(SaleCart.decrementRemoves(two))
    }

    @Test
    fun `plus stops at the line quantity limit`() {
        val limit = QuantityMilli(Limit.LINE_QUANTITY_MILLI.max)

        assertTrue(SaleCart.canIncrement(limit - one))
        assertFalse(SaleCart.canIncrement(limit))
    }

    @Test
    fun `the quantity of a product is its line, or zero when it is not in the sale`() {
        val lines = listOf(line(1L, two))

        assertEquals(two, SaleCart.quantityOf(lines, 1L))
        assertEquals(QuantityMilli.ZERO, SaleCart.quantityOf(lines, 2L))
        assertEquals(emptyList<SaleLineRequest>(), SaleCart.remove(lines, 1L))
    }

    @Test
    fun `selling more than the stock only warns with what would be left, never for untracked`() {
        val tracked = product(1L, onHandMilli = 3_000L)
        val service = product(2L, stockMode = ProductStockMode.UNTRACKED)

        assertEquals(QuantityMilli(-2_000L), SaleCart.oversell(tracked, QuantityMilli(5_000L)))
        assertNull(SaleCart.oversell(tracked, QuantityMilli(3_000L)))
        assertNull(SaleCart.oversell(service, QuantityMilli(5_000L)))
    }

    @Test
    fun `a combo warns when the sale asks for more than its components are enough for`() {
        // Alcanzan 3 kits: pedir 5 avisa que faltan 2; pedir 3 no avisa; sin stock no limita.
        assertEquals(QuantityMilli(-2_000L), SaleCart.comboOversell(3L, QuantityMilli(5_000L)))
        assertNull(SaleCart.comboOversell(3L, QuantityMilli(3_000L)))
        assertNull(SaleCart.comboOversell(null, QuantityMilli(5_000L)))
    }

    /** El ejemplo de la venta de tres productos: 31000 en total, con descuento del 10 %. */
    @Test
    fun `the preview adds the lines like the sale will, discount included`() {
        val soap = product(1L, salePrice = 3_500L)
        val coffee = product(2L, salePrice = 18_500L, unitId = kilogram)
        val cream = product(3L, salePrice = 20_000L)
        val lines = listOf(
            line(1L, two),
            SaleLineRequest(2L, QuantityMilli(333L), discount = LineDiscount.Amount(Money(161L))),
            SaleLineRequest(3L, one, discount = LineDiscount.Percent(BasisPoints(1_000L))),
        )

        val preview = SaleCart.preview(
            lines,
            listOf(soap, coffee, cream).associateBy { it.id },
            fractionUnits = setOf(kilogram),
        )

        assertEquals(Money(31_000L), preview.total)
        assertEquals(
            listOf(Money(7_000L), Money(6_000L), Money(18_000L)),
            preview.lines.map { it.total },
        )
        assertEquals(Money(6_161L), preview.lines[1].gross)
        assertEquals(Money(2_000L), preview.lines[2].discount)
        // Dos jabones, el café por peso cuenta una vez y una crema.
        assertEquals(4L, preview.itemCount)
        assertTrue(preview.canRegister)
    }

    @Test
    fun `a price changed on the line wins over the product price`() {
        val preview = SaleCart.preview(
            listOf(SaleLineRequest(1L, one, unitPrice = Money(3_000L))),
            mapOf(1L to product(1L, salePrice = 3_500L)),
            emptySet(),
        )

        assertEquals(Money(3_000L), preview.lines.single().unitPrice)
        assertEquals(Money(3_000L), preview.total)
    }

    @Test
    fun `a discount over the line or a missing product leaves the sale without a total`() {
        val products = mapOf(1L to product(1L, salePrice = 1_000L))
        val overDiscount = SaleCart.preview(
            listOf(SaleLineRequest(1L, one, discount = LineDiscount.Amount(Money(1_001L)))),
            products,
            emptySet(),
        )
        val missing = SaleCart.preview(listOf(line(1L), line(2L)), products, emptySet())

        assertEquals(CartLineIssue.INVALID_DISCOUNT, overDiscount.lines.single().issue)
        assertNull(overDiscount.total)
        assertFalse(overDiscount.canRegister)
        assertEquals(CartLineIssue.PRODUCT_UNAVAILABLE, missing.lines[1].issue)
        assertEquals(Money.ZERO, missing.lines[1].unitPrice)
        assertNull(missing.total)
        assertEquals(2L, missing.itemCount)
    }

    @Test
    fun `a line or a total above the document limit is a typing error and cannot be confirmed`() {
        val top = Limit.UNIT_MONEY.max
        val pricey = product(1L, salePrice = top)
        val second = product(2L, salePrice = top)
        // Un millón de unidades al precio más alto llega justo al tope; al doble de precio, lo pasa.
        val lineTooLarge = SaleCart.preview(
            listOf(
                SaleLineRequest(
                    1L,
                    QuantityMilli(Limit.LINE_QUANTITY_MILLI.max),
                    unitPrice = Money(top * 2),
                ),
            ),
            mapOf(1L to pricey),
            emptySet(),
        )
        val half = QuantityMilli.ofUnits(Limit.DOCUMENT_TOTAL.max / top / 2 + 1)
        val totalTooLarge = SaleCart.preview(
            listOf(line(1L, half), line(2L, half)),
            mapOf(1L to pricey, 2L to second),
            emptySet(),
        )

        assertEquals(CartLineIssue.AMOUNT_TOO_LARGE, lineTooLarge.lines.single().issue)
        assertNull(lineTooLarge.total)
        assertNull(totalTooLarge.lines.firstNotNullOfOrNull { it.issue })
        assertNull(totalTooLarge.total)
    }

    @Test
    fun `after a line with a problem the sale stays without a total, and an overflow is one`() {
        val products = mapOf(
            1L to product(1L, salePrice = 1_000L),
            2L to product(2L, salePrice = Long.MAX_VALUE),
        )
        val preview = SaleCart.preview(
            listOf(
                SaleLineRequest(1L, one, discount = LineDiscount.Amount(Money(2_000L))),
                line(1L),
                line(2L, two),
            ),
            products,
            emptySet(),
        )

        // Una línea con problema deja toda la venta sin cuentas, aunque las demás valgan.
        assertNull(preview.lines[1].issue)
        assertEquals(Money(1_000L), preview.lines[1].gross)
        assertNull(preview.lines[1].total)
        // Un precio así desborda `Long` al multiplicar: la línea pasa del tope.
        assertEquals(CartLineIssue.AMOUNT_TOO_LARGE, preview.lines[2].issue)
        assertNull(preview.total)
    }

    @Test
    fun `each product pays its own rate, or the default, or none`() {
        val own = product(1L, taxRateId = 5L)
        val plain = product(2L)
        val unknown = product(3L, taxRateId = 9L)
        val added = CartPricing(
            TaxMode.ADDED,
            mapOf(
                5L to BasisPoints(1_000L),
                6L to BasisPoints(500L),
            ),
            6L,
        )

        assertEquals(BasisPoints(1_000L), added.rateOf(own))
        assertEquals(BasisPoints(500L), added.rateOf(plain))
        assertEquals(BasisPoints.ZERO, added.rateOf(unknown))
        assertEquals(BasisPoints.ZERO, added.copy(defaultRateId = null).rateOf(plain))
        assertEquals(BasisPoints.ZERO, added.copy(taxMode = TaxMode.NONE).rateOf(own))
    }

    @Test
    fun `with tax added the line pays its tax, and a line at the top no longer fits`() {
        val pricing = CartPricing(TaxMode.ADDED, mapOf(5L to BasisPoints(1_900L)), 5L)
        val soap = product(1L, salePrice = 10_000L)
        // Novecientos mil millones caben en un documento; con el 19 % sumado, ya no.
        val top = product(2L, salePrice = 900_000_000L)

        val preview = SaleCart.preview(listOf(line(1L)), mapOf(1L to soap), emptySet(), pricing)
        val tooLarge = SaleCart.preview(
            listOf(line(2L, QuantityMilli(Limit.LINE_QUANTITY_MILLI.max))),
            mapOf(2L to top),
            emptySet(),
            pricing,
        )

        assertEquals(Money(1_900L), preview.tax)
        assertEquals(Money(11_900L), preview.total)
        assertEquals(Money(11_900L), preview.lines.single().total)
        assertEquals(CartLineIssue.AMOUNT_TOO_LARGE, tooLarge.lines.single().issue)
    }

    @Test
    fun `a method discount of everything with tax added leaves the sale without a total`() {
        val pricing = CartPricing(
            TaxMode.ADDED,
            mapOf(5L to BasisPoints(1_900L)),
            5L,
            methodAdjustment = BasisPoints(-10_000L),
        )

        val preview = SaleCart.preview(
            listOf(line(1L)),
            mapOf(1L to product(1L, salePrice = 10_000L)),
            emptySet(),
            pricing,
        )

        // El descuento sale del total con impuesto y pasa de lo que se cobra antes de él.
        assertNull(preview.total)
        assertNull(preview.lines.single().total)
    }

    @Test
    fun `a card surcharge that pushes a line over the top leaves no total`() {
        val pricing = CartPricing(
            TaxMode.INCLUDED,
            mapOf(5L to BasisPoints(1_900L)),
            5L,
            methodAdjustment = BasisPoints(10_000L),
        )
        // Seiscientos millones por un millón de unidades todavía cabe; con un recargo del
        // 100 %, la línea pasa del tope de un documento.
        val pricey = product(1L, salePrice = 600_000_000L)

        val preview = SaleCart.preview(
            listOf(line(1L, QuantityMilli(Limit.LINE_QUANTITY_MILLI.max))),
            mapOf(1L to pricey),
            emptySet(),
            pricing,
        )

        assertNull(preview.lines.single().issue)
        assertNull(preview.total)
    }

    @Test
    fun `an empty sale has nothing to confirm`() {
        val preview = SaleCart.preview(emptyList(), emptyMap(), emptySet())

        assertEquals(Money.ZERO, preview.total)
        assertEquals(0L, preview.itemCount)
        assertFalse(preview.canRegister)
    }

    @Test
    fun `update changes only the line of that product`() {
        val lines = listOf(line(1L), line(2L))

        val updated = SaleCart.update(lines, 2L) { it.copy(unitPrice = Money(5L)) }

        assertEquals(listOf(line(1L), SaleLineRequest(2L, one, unitPrice = Money(5L))), updated)
    }

    @Test
    fun `in cash, what is charged is rounded to the step and the card is not`() {
        val products = mapOf(1L to product(1L, salePrice = 1_234L))
        val lines = listOf(line(1L))
        val cash = SaleCart.preview(
            lines,
            products,
            emptySet(),
            CartPricing(cashMethod = true, cashStep = Money(50L)),
        )
        val card = SaleCart.preview(lines, products, emptySet(), CartPricing(cashStep = Money(50L)))

        assertEquals(Money(1_234L), cash.total)
        assertEquals(Money(16L), cash.cashRounding)
        assertEquals(Money(1_250L), cash.amountDue)
        assertEquals(Money(1_234L), card.amountDue)
        assertEquals(Money.ZERO, card.cashRounding)
    }

    @Test
    fun `a rounding past the document limit leaves nothing to charge`() {
        val products = mapOf(
            1L to product(1L, salePrice = 1_000_000_000_000L),
            2L to product(2L, salePrice = 999_999_999_999L),
        )
        val preview = SaleCart.preview(
            listOf(line(1L, QuantityMilli.ofUnits(999L)), line(2L)),
            products,
            emptySet(),
            CartPricing(cashMethod = true, cashStep = Money(600_000_000_000L)),
        )

        assertEquals(Money(999_999_999_999_999L), preview.total)
        assertNull(preview.amountDue)
        assertFalse(preview.canRegister)
    }

    @Test
    fun `in cash, a sale that cannot be charged has no rounding and nothing to charge`() {
        // El producto ya no está: la línea tiene un problema y no hay total que redondear.
        val preview = SaleCart.preview(
            listOf(line(1L)),
            emptyMap(),
            emptySet(),
            CartPricing(cashMethod = true, cashStep = Money(50L)),
        )

        assertNull(preview.total)
        assertNull(preview.amountDue)
        assertEquals(Money.ZERO, preview.cashRounding)
    }
}
