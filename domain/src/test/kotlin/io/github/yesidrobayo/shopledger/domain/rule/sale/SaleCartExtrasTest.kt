package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los extras en la venta en edición: cada uno va justo después de su línea, sigue su cantidad y se
 * va con ella; un mismo producto puede ser extra de varias líneas y venderse suelto, y lo que busca
 * por producto mira solo las líneas principales. Productos y montos inventados.
 */
class SaleCartExtrasTest {
    private val one = QuantityMilli.ofUnits(1L)
    private val two = QuantityMilli.ofUnits(2L)
    private val three = QuantityMilli.ofUnits(3L)

    private fun line(productId: Long, quantity: QuantityMilli = one, parentIndex: Int? = null) =
        SaleLineRequest(productId, quantity, parentIndex = parentIndex)

    private fun changed(change: CartChange): List<SaleLineRequest> =
        (change as CartChange.Changed).lines

    @Test
    fun `an extra goes right after its line, with its quantity, and toggling again removes it`() {
        val lines = listOf(line(AREPA, two), line(TEA))

        val withCheese = changed(SaleCart.toggleExtra(lines, AREPA, CHEESE))

        assertEquals(
            listOf(line(AREPA, two), line(CHEESE, two, parentIndex = 0), line(TEA)),
            withCheese,
        )
        assertEquals(listOf(CHEESE), SaleCart.extrasOf(withCheese, AREPA))
        assertEquals(lines, changed(SaleCart.toggleExtra(withCheese, AREPA, CHEESE)))
    }

    @Test
    fun `an extra follows the quantity of its line, and is one on a line with decimals`() {
        val lines = changed(SaleCart.toggleExtra(listOf(line(AREPA)), AREPA, CHEESE))

        val more = changed(SaleCart.add(lines, AREPA, two))
        val weighed = changed(SaleCart.setQuantity(lines, AREPA, QuantityMilli(1_250L)))

        assertEquals(three, more[1].quantity)
        assertEquals(one, weighed[1].quantity)
        assertEquals(one, SaleCart.extraQuantityFor(QuantityMilli(500L)))
    }

    @Test
    fun `the same product is an extra of two lines and also its own line`() {
        var lines = listOf(line(AREPA), line(TEA))
        lines = changed(SaleCart.toggleExtra(lines, AREPA, CHEESE))
        lines = changed(SaleCart.toggleExtra(lines, TEA, CHEESE))
        lines = changed(SaleCart.add(lines, CHEESE, one))

        assertEquals(
            listOf(
                line(AREPA),
                line(CHEESE, parentIndex = 0),
                line(TEA),
                line(CHEESE, parentIndex = 2),
                line(CHEESE),
            ),
            lines,
        )
        // Lo que busca por producto mira la línea principal, no los extras.
        assertEquals(one, SaleCart.quantityOf(lines, CHEESE))
        assertEquals(line(CHEESE), SaleCart.mainOf(lines, CHEESE))
        assertEquals(two, SaleCart.quantityOf(changed(SaleCart.add(lines, CHEESE, one)), CHEESE))
    }

    @Test
    fun `a product that is only an extra is not in the sale as its own line`() {
        val lines = changed(SaleCart.toggleExtra(listOf(line(AREPA)), AREPA, CHEESE))

        assertEquals(QuantityMilli.ZERO, SaleCart.quantityOf(lines, CHEESE))
        // Agregarlo suelto crea su propia línea al final, sin tocar el extra.
        assertEquals(
            listOf(line(AREPA), line(CHEESE, parentIndex = 0), line(CHEESE)),
            changed(SaleCart.add(lines, CHEESE, one)),
        )
    }

    @Test
    fun `removing a line takes its extras and moves the links of the others`() {
        var lines = listOf(line(AREPA), line(TEA))
        lines = changed(SaleCart.toggleExtra(lines, AREPA, CHEESE))
        lines = changed(SaleCart.toggleExtra(lines, TEA, SAUCE))

        assertEquals(
            listOf(line(TEA), line(SAUCE, parentIndex = 0)),
            SaleCart.remove(lines, AREPA),
        )
        // El menos en su última unidad también quita la línea con sus extras.
        assertEquals(
            listOf(line(AREPA), line(CHEESE, parentIndex = 0)),
            SaleCart.decrement(lines, TEA),
        )
    }

    @Test
    fun `changing the price of a line keeps its extras`() {
        val lines = changed(SaleCart.toggleExtra(listOf(line(AREPA)), AREPA, CHEESE))

        val priced = SaleCart.update(lines, AREPA) { it.copy(unitPrice = Money(3_500L)) }

        assertEquals(Money(3_500L), priced[0].unitPrice)
        assertEquals(line(CHEESE, parentIndex = 0), priced[1])
    }

    @Test
    fun `a full sale does not take another extra but still removes one`() {
        val full = (1L..Limit.DOCUMENT_LINES.max).map { line(it + 100L) }
        val withExtra = full.dropLast(1).let {
            changed(SaleCart.toggleExtra(it, it.first().productId, CHEESE))
        }

        assertEquals(
            CartChange.LinesFull,
            SaleCart.toggleExtra(full, full.first().productId, CHEESE),
        )
        assertEquals(
            full.dropLast(1),
            changed(SaleCart.toggleExtra(withExtra, full.first().productId, CHEESE)),
        )
    }

    @Test
    fun `toggling on a line that is not there changes nothing`() {
        val lines = listOf(line(AREPA))

        assertEquals(lines, changed(SaleCart.toggleExtra(lines, TEA, CHEESE)))
        assertEquals(emptyList<Long>(), SaleCart.extrasOf(lines, TEA))
    }

    @Test
    fun `normalize puts each extra after its line and drops a broken link`() {
        val lines = listOf(
            line(AREPA, two),
            line(TEA),
            line(CHEESE, one, parentIndex = 0),
            line(SAUCE, one, parentIndex = 2),
            line(SAUCE, one, parentIndex = 9),
        )

        assertEquals(
            listOf(line(AREPA, two), line(CHEESE, two, parentIndex = 0), line(TEA)),
            SaleCart.normalize(lines),
        )
    }

    @Test
    fun `an extra charges like a line but does not count as a product`() {
        val products = listOf(
            product(AREPA, salePrice = 4_000),
            product(CHEESE, salePrice = 1_000),
        ).associateBy { it.id }
        val lines = listOf(
            line(AREPA, two),
            SaleLineRequest(
                CHEESE,
                two,
                discount = LineDiscount.None,
                parentIndex = 0,
            ),
        )

        val preview = SaleCart.preview(lines, products, fractionUnits = emptySet())

        assertEquals(2L, preview.itemCount)
        assertEquals(Money(10_000L), preview.total)
        assertEquals(Money(2_000L), preview.lines[1].total)
    }

    private companion object {
        const val AREPA = 1L
        const val TEA = 2L
        const val CHEESE = 3L
        const val SAUCE = 4L
    }
}
