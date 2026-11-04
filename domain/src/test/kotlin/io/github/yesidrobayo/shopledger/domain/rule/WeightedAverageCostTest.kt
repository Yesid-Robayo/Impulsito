package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Montos en unidad menor de una moneda sin decimales y cantidades en milésimas. Cada valor esperado
// está calculado a mano desde las reglas de costo, nunca con el código que se prueba.
class WeightedAverageCostTest {
    private val cost = WeightedAverageCost

    @Test
    fun `the moving average goes through 1200 1300 1300 and 1355`() {
        // Primera compra: 10 unidades por 12000.
        val first = cost.entry(state(0, 0), qty(10_000), Money(12_000)).accepted()
        assertEquals(state(10_000, 1200), first)

        // Compra de 5 por 7500: 12000 + 7500 = 19500 por 15 unidades.
        val second = cost.entry(first, qty(5000), Money(7500)).accepted()
        assertEquals(state(15_000, 1300), second)

        // Venta de 8: salen con 10400, y el promedio no cambia.
        assertEquals(Checked.Value(Money(10_400)), cost.exitCost(second, qty(8000)))
        val third = cost.exit(second, qty(8000))
        assertEquals(state(7000, 1300), third)

        // Compra de 4 por 5800: 9100 + 5800 = 14900 por 11 unidades, 1354,54.
        assertEquals(Checked.Value(state(11_000, 1355)), cost.entry(third, qty(4000), Money(5800)))
    }

    @Test
    fun `a purchase by weight weighs the stock value against the purchase total`() {
        // 2,5 kg a 30000 valen 75000. Entran 1,750 kg por 61250: 136250 por 4,250 kg, 32058,82.
        assertEquals(
            Checked.Value(state(4250, 32_059)),
            cost.entry(state(2500, 30_000), qty(1750), Money(61_250)),
        )
    }

    @Test
    fun `the average comes from the line total and not from its rounded unit cost`() {
        // 3 kg por 25000 son 8333,33 por kilogramo. Con el total, 16000 + 25000 = 41000 por 5 kg.
        assertEquals(
            Checked.Value(state(5000, 8200)),
            cost.entry(state(2000, 8000), qty(3000), Money(25_000)),
        )
    }

    @Test
    fun `an opening balance enters at the typed unit cost`() {
        // 24 unidades a 1200: el total es 28800, y el promedio queda en 1200.
        val total = Money(1200).timesQuantity(qty(24_000)).accepted()
        assertEquals(Money(28_800), total)

        assertEquals(
            Checked.Value(state(24_000, 1200)),
            cost.entry(state(0, 0), qty(24_000), total),
        )
    }

    @Test
    fun `the first purchase sets the average`() {
        assertEquals(
            Checked.Value(state(10_000, 1200)),
            cost.entry(state(0, 0), qty(10_000), Money(12_000)),
        )
    }

    @Test
    fun `with no stock the previous cost does not weigh`() {
        // 5 por 8000, aunque el costo anterior era 1300.
        assertEquals(
            Checked.Value(state(5000, 1600)),
            cost.entry(state(0, 1300), qty(5000), Money(8000)),
        )
    }

    @Test
    fun `with negative stock the previous cost does not weigh`() {
        // 10 por 15000 con 2 unidades vendidas sin stock. Ponderar daría
        // (−2600000 + 15000000) / 8000 = 1550, más de lo pagado por cada unidad que queda.
        val after = cost.entry(state(-2000, 1300), qty(10_000), Money(15_000)).accepted()
        assertEquals(state(8000, 1500), after)

        // Las 8 unidades que quedan valen 12000: venderlas todas cuesta exactamente eso.
        assertEquals(Checked.Value(Money(12_000)), cost.exitCost(after, qty(8000)))
    }

    @Test
    fun `a purchase that does not cover the negative stock keeps it negative`() {
        // 2 por 3000 con 5 unidades en negativo.
        assertEquals(
            Checked.Value(state(-3000, 1500)),
            cost.entry(state(-5000, 1300), qty(2000), Money(3000)),
        )
    }

    @Test
    fun `positive stock without value is treated as unknown cost`() {
        // 5 unidades sin costo y 5 por 5000. Ponderar daría 500 y haría pasar las primeras por
        // gratuitas.
        val after = cost.entry(state(5000, 0), qty(5000), Money(5000)).accepted()
        assertEquals(state(10_000, 1000), after)

        // Las 10 unidades valen 10000.
        assertEquals(Checked.Value(Money(10_000)), cost.exitCost(after, qty(10_000)))
    }

    @Test
    fun `a free purchase lowers the average`() {
        // Bonificación de 2 unidades por 0: 10000 por 12 unidades, 833,33.
        assertEquals(
            Checked.Value(state(12_000, 833)),
            cost.entry(state(10_000, 1000), qty(2000), Money(0)),
        )
    }

    @Test
    fun `a free purchase with no stock leaves the product without cost`() {
        // 3 por 0 sin stock: el costo anterior no pesa y el producto queda sin costo.
        assertEquals(
            Checked.Value(state(3000, 0)),
            cost.entry(state(0, 1300), qty(3000), Money(0)),
        )
    }

    @Test
    fun `with negative stock part of what was paid reaches no report`() {
        // Se venden 2 sin stock, a 1300 cada una.
        val empty = state(0, 1300)
        assertEquals(Checked.Value(Money(2600)), cost.exitCost(empty, qty(2000)))
        val negative = cost.exit(empty, qty(2000))
        assertEquals(state(-2000, 1300), negative)

        // Llegan 10 por 15000. Lo vendido costó 2600 y las 8 que quedan valen 12000: los 400
        // restantes no aparecen en ningún lado, porque reescribir la venta cambiaría el pasado.
        val after = cost.entry(negative, qty(10_000), Money(15_000)).accepted()
        assertEquals(state(8000, 1500), after)
        assertEquals(Checked.Value(Money(12_000)), cost.exitCost(after, qty(8000)))
    }

    @Test
    fun `adjustments take their value from the moving cost without changing it`() {
        // 11 unidades a 1355 valen 14905.
        val start = state(11_000, 1355)

        // Tres vencidas: 14905 × 3 / 11 = 4065.
        assertEquals(Checked.Value(Money(4065)), cost.exitCost(start, qty(3000)))
        val afterExpired = cost.exit(start, qty(3000))
        assertEquals(state(8000, 1355), afterExpired)

        // El conteo dice que faltan 2: 10840 × 2 / 8 = 2710. Merma acumulada: 6775.
        assertEquals(Checked.Value(Money(2710)), cost.exitCost(afterExpired, qty(2000)))
        val afterMissing = cost.exit(afterExpired, qty(2000))
        assertEquals(state(6000, 1355), afterMissing)

        // Aparece 1: 8130 / 6 = 1355. Merma acumulada: 5420.
        assertEquals(
            Checked.Value(Money(1355)),
            cost.positiveAdjustmentCost(afterMissing, qty(1000)),
        )
        assertEquals(state(7000, 1355), cost.positiveAdjustment(afterMissing, qty(1000)))
    }

    @Test
    fun `a physical count with two missing units costs their share of the stock value`() {
        // El libro dice 12 a 1355, que valen 16260, y hay 10.
        val start = state(12_000, 1355)
        assertEquals(Checked.Value(Money(2710)), cost.exitCost(start, qty(2000)))
        assertEquals(state(10_000, 1355), cost.exit(start, qty(2000)))
    }

    @Test
    fun `selling the whole stock takes its whole value`() {
        assertEquals(Checked.Value(Money(19_500)), cost.exitCost(state(15_000, 1300), qty(15_000)))
    }

    @Test
    fun `selling more than the stock values the excess at the average cost`() {
        // Hay 2 a 1300, que valen 2600, y se venden 5: las 3 que faltan salen a 1300, 3900 más.
        val start = state(2000, 1300)
        assertEquals(Checked.Value(Money(6500)), cost.exitCost(start, qty(5000)))
        assertEquals(state(-3000, 1300), cost.exit(start, qty(5000)))
    }

    @Test
    fun `selling with negative stock uses the average cost`() {
        assertEquals(Checked.Value(Money(1300)), cost.exitCost(state(-2000, 1300), qty(1000)))
    }

    @Test
    fun `a positive adjustment without stock enters at the average cost`() {
        val start = state(-1000, 1300)
        assertEquals(Checked.Value(Money(1300)), cost.positiveAdjustmentCost(start, qty(1000)))
        assertEquals(state(0, 1300), cost.positiveAdjustment(start, qty(1000)))
    }

    @Test
    fun `a positive adjustment of stock without value is worth nothing`() {
        assertEquals(
            Checked.Value(Money(0)),
            cost.positiveAdjustmentCost(state(5000, 0), qty(1000)),
        )
    }

    @Test
    fun `a return to the supplier leaves the rest at its remaining value`() {
        // 12 a 1200 valen 14400. Vuelven 2 de una línea a 1400, que costaron 2800: quedan 11600 por
        // 10 unidades.
        assertEquals(
            Checked.Value(state(10_000, 1160)),
            cost.supplierReturn(state(12_000, 1200), qty(2000), Money(2800)),
        )
    }

    @Test
    fun `a return to the supplier that empties the stock keeps the cost`() {
        assertEquals(
            Checked.Value(state(0, 1200)),
            cost.supplierReturn(state(2000, 1200), qty(2000), Money(2800)),
        )
        assertEquals(
            Checked.Value(state(-1000, 1200)),
            cost.supplierReturn(state(1000, 1200), qty(2000), Money(2800)),
        )
    }

    @Test
    fun `a return worth more than the stock leaves the rest without value`() {
        // 2 a 1000 valen 2000, y la unidad devuelta costó 5000: lo que queda vale cero.
        assertEquals(
            Checked.Value(state(1000, 0)),
            cost.supplierReturn(state(2000, 1000), qty(1000), Money(5000)),
        )
    }

    @Test
    fun `correcting the cost gives value to stock that had none`() {
        // 5 unidades sin costo, corregido a 1000: dejan de estar sin costo y valen 5000.
        val corrected = cost.correctCost(state(5000, 0), Money(1000))
        assertEquals(state(5000, 1000), corrected)
        assertEquals(Checked.Value(Money(5000)), cost.exitCost(corrected, qty(5000)))
    }

    @Test
    fun `correcting the cost with negative stock only changes the cost`() {
        assertEquals(state(-2000, 1500), cost.correctCost(state(-2000, 1300), Money(1500)))
    }

    @Test
    fun `the quantity of a movement is always positive`() {
        val start = state(10_000, 1000)
        val rejected =
            listOf<() -> Any>(
                { cost.entry(start, qty(0), Money(1000)) },
                { cost.entry(start, qty(-1000), Money(1000)) },
                { cost.exitCost(start, qty(0)) },
                { cost.exit(start, qty(0)) },
                { cost.positiveAdjustmentCost(start, qty(0)) },
                { cost.positiveAdjustment(start, qty(0)) },
                { cost.supplierReturn(start, qty(0), Money(1000)) },
            )
        rejected.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `a cost is never negative`() {
        val start = state(10_000, 1000)
        val rejected =
            listOf<() -> Any>(
                { cost.entry(start, qty(1000), Money(-1)) },
                { cost.supplierReturn(start, qty(1000), Money(-1)) },
                { cost.correctCost(start, Money(-1)) },
            )
        rejected.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `an entry whose figures do not fit in a long is rejected`() {
        // El total por mil no cabe: el promedio de la primera compra.
        assertEquals(Checked.Overflow, cost.entry(state(0, 0), qty(1), Money(Long.MAX_VALUE)))
        // El valor del stock no cabe: diez millones de unidades al costo máximo.
        assertEquals(
            Checked.Overflow,
            cost.entry(state(10_000_000_000, 1_000_000_000_000), qty(1000), Money(0)),
        )
        // El promedio ponderado no cabe.
        assertEquals(
            Checked.Overflow,
            cost.entry(state(1, 1000), qty(1), Money(9_000_000_000_000_000_000)),
        )
        // El stock no cabe.
        assertEquals(Checked.Overflow, cost.entry(state(Long.MAX_VALUE, 0), qty(1), Money(0)))
    }

    @Test
    fun `an exit or adjustment cost that does not fit in a long is rejected`() {
        val absurd = state(0, Long.MAX_VALUE)
        assertEquals(Checked.Overflow, cost.exitCost(absurd, qty(2000)))
        assertEquals(Checked.Overflow, cost.positiveAdjustmentCost(absurd, qty(2000)))
        assertEquals(
            Checked.Overflow,
            cost.supplierReturn(state(10_000_000_000, 1_000_000_000_000), qty(1000), Money(0)),
        )
    }

    @Test
    fun `moving a stock that does not fit in a long fails instead of wrapping around`() {
        assertThrows(ArithmeticException::class.java) {
            cost.exit(state(Long.MIN_VALUE, 0), qty(1))
        }
        assertThrows(ArithmeticException::class.java) {
            cost.positiveAdjustment(state(Long.MAX_VALUE, 0), qty(1))
        }
    }

    private fun state(onHand: Long, averageCost: Long) = CostState(qty(onHand), Money(averageCost))

    private fun qty(milli: Long) = QuantityMilli(milli)

    private fun <T> Checked<T>.accepted(): T = when (this) {
        is Checked.Value -> value
        Checked.Overflow -> throw AssertionError("La cuenta no cupo en Long")
        is Checked.AboveLimit -> throw AssertionError("La cuenta pasó el tope $limit")
    }
}
