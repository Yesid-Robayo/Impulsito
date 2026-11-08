package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los montos van en unidad menor de una moneda cualquiera. La cuenta no conoce los decimales: el
 * comentario de cada caso dice cómo se lee, y el resultado es el mismo entero en cualquier moneda.
 */
class TaxCalculatorTest {
    @Test
    fun `examples of the line calculation table`() {
        // Sin decimales, 21,00 % incluido: 12100 son 10000 de neto y 2100 de impuesto.
        assertLine(TaxMode.INCLUDED, 12_100L, 2_100L, net = 10_000L, tax = 2_100L, total = 12_100L)
        // Sin decimales, 21,00 % añadido: el mismo neto llega al mismo total por el otro lado.
        assertLine(TaxMode.ADDED, 10_000L, 2_100L, net = 10_000L, tax = 2_100L, total = 12_100L)
        // Sin decimales, 19,00 % incluido en 999: el neto exacto, 839,496, baja a 839, y el
        // impuesto es lo que falta para 999.
        assertLine(TaxMode.INCLUDED, 999L, 1_900L, net = 839L, tax = 160L, total = 999L)
        // Sin decimales, 19,00 % añadido sobre 1999: exacto 379,81, que sube a 380.
        assertLine(TaxMode.ADDED, 1_999L, 1_900L, net = 1_999L, tax = 380L, total = 2_379L)
        // Dos decimales, 8,25 % añadido sobre 19,99: exacto 1,649, que sube a 1,65.
        assertLine(TaxMode.ADDED, 1_999L, 825L, net = 1_999L, tax = 165L, total = 2_164L)
        // Tres decimales, 5,00 % añadido sobre 1,999: exacto 0,09995, que sube a 0,100.
        assertLine(TaxMode.ADDED, 1_999L, 500L, net = 1_999L, tax = 100L, total = 2_099L)
    }

    @Test
    fun `examples of the tax test table`() {
        // 19 % incluido en 11900: neto 10000, impuesto 1900.
        assertLine(TaxMode.INCLUDED, 11_900L, 1_900L, net = 10_000L, tax = 1_900L, total = 11_900L)
        // 19 % incluido en 10001: el neto exacto es 8404,20 y baja a 8404; el total no cambia.
        assertLine(TaxMode.INCLUDED, 10_001L, 1_900L, net = 8_404L, tax = 1_597L, total = 10_001L)
        // 19 % añadido sobre 10000.
        assertLine(TaxMode.ADDED, 10_000L, 1_900L, net = 10_000L, tax = 1_900L, total = 11_900L)
        // 19 % añadido sobre 333: exacto 63,27, que baja a 63.
        assertLine(TaxMode.ADDED, 333L, 1_900L, net = 333L, tax = 63L, total = 396L)
    }

    @Test
    fun `without tax the whole line is net and the rate does not count`() {
        assertLine(TaxMode.NONE, 12_100L, 2_100L, net = 12_100L, tax = 0L, total = 12_100L)
        assertLine(TaxMode.NONE, 1_999L, 0L, net = 1_999L, tax = 0L, total = 1_999L)
    }

    @Test
    fun `a rate of zero leaves the line untouched in every mode`() {
        for (mode in TaxMode.entries) {
            assertLine(mode, 4_321L, 0L, net = 4_321L, tax = 0L, total = 4_321L)
        }
    }

    @Test
    fun `a rate of one hundred percent splits or doubles the line`() {
        assertLine(TaxMode.INCLUDED, 12_100L, 10_000L, net = 6_050L, tax = 6_050L, total = 12_100L)
        // 999 a la mitad es 499,5, que sube a 500: el impuesto se queda con 499.
        assertLine(TaxMode.INCLUDED, 999L, 10_000L, net = 500L, tax = 499L, total = 999L)
        assertLine(TaxMode.ADDED, 1_000L, 10_000L, net = 1_000L, tax = 1_000L, total = 2_000L)
    }

    @Test
    fun `a line worth zero has no tax`() {
        for (mode in TaxMode.entries) {
            assertLine(mode, 0L, 2_100L, net = 0L, tax = 0L, total = 0L)
        }
    }

    @Test
    fun `a negative line is split with the same rounding as a positive one`() {
        assertLine(
            TaxMode.INCLUDED,
            -12_100L,
            2_100L,
            net = -10_000L,
            tax = -2_100L,
            total = -12_100L,
        )
        assertLine(TaxMode.INCLUDED, -999L, 1_900L, net = -839L, tax = -160L, total = -999L)
        assertLine(TaxMode.ADDED, -333L, 1_900L, net = -333L, tax = -63L, total = -396L)
        assertLine(TaxMode.NONE, -333L, 1_900L, net = -333L, tax = 0L, total = -333L)
    }

    @Test
    fun `the tax of each line is added up and never taken from the total`() {
        // Dos líneas de 999 con 19 % incluido. Línea por línea: netos 839 + 839 e impuestos
        // 160 + 160. Sobre el total de 1998 saldrían 1679 y 319, y el total de la venta dejaría de
        // ser la suma de sus netos y sus impuestos guardados.
        val lines = List(2) { line(TaxMode.INCLUDED, 999L, 1_900L) }
        assertEquals(1_678L, lines.sumOf { it.netAmount.minor })
        assertEquals(320L, lines.sumOf { it.taxAmount.minor })
        assertEquals(1_998L, lines.sumOf { it.lineTotal.minor })
        val wholeSale = line(TaxMode.INCLUDED, 1_998L, 1_900L)
        assertEquals(1_679L, wholeSale.netAmount.minor)
        assertEquals(319L, wholeSale.taxAmount.minor)
    }

    @Test
    fun `lines with different rates keep each its own tax`() {
        val lines =
            listOf(
                line(TaxMode.INCLUDED, 12_100L, 2_100L),
                line(TaxMode.INCLUDED, 10_500L, 500L),
                line(TaxMode.INCLUDED, 3_000L, 0L),
            )
        assertEquals(listOf(10_000L, 10_000L, 3_000L), lines.map { it.netAmount.minor })
        assertEquals(listOf(2_100L, 500L, 0L), lines.map { it.taxAmount.minor })
        // El total de la venta es la suma de netos más la de impuestos.
        assertEquals(
            lines.sumOf { it.lineTotal.minor },
            lines.sumOf { it.netAmount.minor } + lines.sumOf { it.taxAmount.minor },
        )
    }

    @Test
    fun `the margin of a sale line is measured on its net amount`() {
        // Precio 12100 con 21 % incluido y costo 8000: el margen es 20,0 %, sobre los 10000 sin
        // impuesto. Sobre el total, 33,9 %, la ganancia parecería mayor de lo que es.
        val included = line(TaxMode.INCLUDED, 12_100L, 2_100L)
        val cost = Money(8_000L)
        assertEquals(margin(2_000L), MarginCalculator.margin(included.netAmount, cost))
        assertEquals(margin(3_388L), MarginCalculator.margin(included.lineTotal, cost))
        // Con el impuesto añadido el neto es el mismo, y el margen también.
        val added = line(TaxMode.ADDED, 10_000L, 2_100L)
        assertEquals(margin(2_000L), MarginCalculator.margin(added.netAmount, cost))
    }

    @Test
    fun `the catalog price loses its tax only when the tax is included`() {
        assertEquals(Checked.Value(Money(10_000L)), catalogNet(TaxMode.INCLUDED, 12_100L, 2_100L))
        assertEquals(Checked.Value(Money(12_100L)), catalogNet(TaxMode.ADDED, 12_100L, 2_100L))
        assertEquals(Checked.Value(Money(12_100L)), catalogNet(TaxMode.NONE, 12_100L, 2_100L))
        // El precio sugerido 1730, con 21 % incluido, vuelve a 1429,75, que sube a 1430: su margen
        // contra un costo de 1000 alcanza el 30 % pedido.
        assertEquals(Checked.Value(Money(1_430L)), catalogNet(TaxMode.INCLUDED, 1_730L, 2_100L))
        assertEquals(margin(3_007L), MarginCalculator.margin(Money(1_430L), Money(1_000L)))
        // Sin precio no hay base.
        assertEquals(Checked.Value(Money.ZERO), catalogNet(TaxMode.INCLUDED, 0L, 2_100L))
    }

    @Test
    fun `the catalog margin with the tax included is measured without it`() {
        val net = value(catalogNet(TaxMode.INCLUDED, 12_100L, 2_100L))
        assertEquals(margin(2_000L), MarginCalculator.margin(net, Money(8_000L)))
    }

    @Test
    fun `a line total above the document cap is rejected`() {
        val cap = Limit.DOCUMENT_TOTAL.max
        val aboveCap = Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
        // Al tope, una línea sin impuesto o con el impuesto dentro cabe.
        assertLine(TaxMode.NONE, cap, 2_100L, net = cap, tax = 0L, total = cap)
        assertEquals(cap, line(TaxMode.INCLUDED, cap, 2_100L).lineTotal.minor)
        // Un monto más ya no.
        assertEquals(aboveCap, checkedLine(TaxMode.NONE, cap + 1L, 2_100L))
        assertEquals(aboveCap, checkedLine(TaxMode.INCLUDED, cap + 1L, 2_100L))
        // Con el impuesto añadido, una línea al tope lo supera al sumar el impuesto.
        assertEquals(aboveCap, checkedLine(TaxMode.ADDED, cap, 2_100L))
        // El neto que, con el impuesto, queda justo bajo el tope: 10^15 / 1,21, al entero de abajo.
        val netAtCap = 826_446_280_991_735L
        assertTrue(line(TaxMode.ADDED, netAtCap, 2_100L).lineTotal.minor <= cap)
        assertEquals(aboveCap, checkedLine(TaxMode.ADDED, netAtCap + 1L, 2_100L))
    }

    @Test
    fun `a total that does not fit in a long is an overflow`() {
        assertEquals(Checked.Overflow, checkedLine(TaxMode.ADDED, Long.MAX_VALUE, 1L))
        // El neto de un monto enorme con el impuesto dentro sí cabe, porque nunca lo supera.
        assertEquals(
            Checked.Value(Money(Long.MAX_VALUE / 2L)),
            catalogNet(TaxMode.INCLUDED, Long.MAX_VALUE - 1L, 10_000L),
        )
    }

    @Test
    fun `a rate outside zero to one hundred percent is a programming error`() {
        for (rate in listOf(-1L, 10_001L)) {
            for (mode in TaxMode.entries) {
                assertThrows(IllegalArgumentException::class.java) {
                    checkedLine(mode, 1_000L, rate)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    catalogNet(mode, 1_000L, rate)
                }
            }
        }
    }

    @Test
    fun `random lines follow the documented formulas exactly`() {
        // Semilla fija: el mismo resultado en cada corrida. Compara con las fórmulas tal como están
        // escritas, incluido el impuesto añadido, que aquí sale de otra cuenta equivalente.
        val random = Random(1_201L)
        val bound = Limit.DOCUMENT_TOTAL.max / 2L
        repeat(10_000) {
            val after = random.nextLong(-bound, bound)
            val rate = random.nextLong(0L, 10_001L)
            val case = "$after $rate"

            val included = line(TaxMode.INCLUDED, after, rate)
            val expectedNet = value(mulDivRound(after, 10_000L, 10_000L + rate))
            assertEquals(case, expectedNet, included.netAmount.minor)
            assertEquals(case, after - expectedNet, included.taxAmount.minor)
            assertEquals(case, after, included.lineTotal.minor)

            val added = line(TaxMode.ADDED, after, rate)
            val expectedTax = value(mulDivRound(after, rate, 10_000L))
            assertEquals(case, after, added.netAmount.minor)
            assertEquals(case, expectedTax, added.taxAmount.minor)
            assertEquals(case, after + expectedTax, added.lineTotal.minor)
        }
    }

    private fun assertLine(
        mode: TaxMode,
        lineAfter: Long,
        rateBp: Long,
        net: Long,
        tax: Long,
        total: Long,
    ) {
        assertEquals(
            "$mode $lineAfter $rateBp",
            Checked.Value(LineTax(Money(net), Money(tax), Money(total))),
            checkedLine(mode, lineAfter, rateBp),
        )
    }

    private fun checkedLine(mode: TaxMode, lineAfter: Long, rateBp: Long): Checked<LineTax> =
        TaxCalculator.line(Money(lineAfter), mode, BasisPoints(rateBp))

    private fun line(mode: TaxMode, lineAfter: Long, rateBp: Long): LineTax =
        value(checkedLine(mode, lineAfter, rateBp))

    private fun catalogNet(mode: TaxMode, price: Long, rateBp: Long): Checked<Money> =
        TaxCalculator.catalogNetPrice(Money(price), mode, BasisPoints(rateBp))

    private fun margin(bp: Long): Margin = Margin.Defined(BasisPoints(bp))

    private fun <T> value(checked: Checked<T>): T {
        assertTrue("$checked", checked is Checked.Value)
        return (checked as Checked.Value).value
    }
}
