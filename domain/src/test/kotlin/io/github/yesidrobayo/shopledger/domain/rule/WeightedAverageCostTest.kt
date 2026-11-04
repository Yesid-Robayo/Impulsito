package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Montos en unidad menor de una moneda sin decimales y cantidades en milésimas. Cada estado se
// escribe como (stock, costo promedio, valor del stock). Cada valor esperado está calculado a mano
// desde las reglas de costo, nunca con el código que se prueba.
class WeightedAverageCostTest {
    private val cost = WeightedAverageCost

    @Test
    fun `the moving average goes through 1200 1300 1300 and 1355`() {
        // Primera compra: 10 unidades por 12000.
        val first = cost.entry(state(0, 0, 0), qty(10_000), Money(12_000)).accepted()
        assertEquals(state(10_000, 1200, 12_000), first)

        // Compra de 5 por 7500: 12000 + 7500 = 19500 por 15 unidades.
        val second = cost.entry(first, qty(5000), Money(7500)).accepted()
        assertEquals(state(15_000, 1300, 19_500), second)

        // Venta de 8: salen con 19500 × 8 / 15 = 10400, y el promedio no cambia.
        assertEquals(Checked.Value(Money(10_400)), cost.exitCost(second, qty(8000)))
        val third = cost.exit(second, qty(8000), Money(10_400))
        assertEquals(state(7000, 1300, 9100), third)

        // Compra de 4 por 5800: 9100 + 5800 = 14900 por 11 unidades, 1354,54.
        assertEquals(
            Checked.Value(state(11_000, 1355, 14_900)),
            cost.entry(third, qty(4000), Money(5800)),
        )
    }

    @Test
    fun `a purchase by weight weighs the stock value against the purchase total`() {
        // 2,5 kg que valen 75000. Entran 1,750 kg por 61250: 136250 por 4,250 kg, 32058,82.
        assertEquals(
            Checked.Value(state(4250, 32_059, 136_250)),
            cost.entry(state(2500, 30_000, 75_000), qty(1750), Money(61_250)),
        )
    }

    @Test
    fun `the average comes from the line total and not from its rounded unit cost`() {
        // 3 kg por 25000 son 8333,33 por kilogramo. Con el total, 16000 + 25000 = 41000 por 5 kg.
        assertEquals(
            Checked.Value(state(5000, 8200, 41_000)),
            cost.entry(state(2000, 8000, 16_000), qty(3000), Money(25_000)),
        )
    }

    @Test
    fun `an opening balance enters at the typed unit cost`() {
        // 24 unidades a 1200: el total es 28800, y el promedio queda en 1200.
        val total = Money(1200).timesQuantity(qty(24_000)).accepted()
        assertEquals(Money(28_800), total)

        assertEquals(
            Checked.Value(state(24_000, 1200, 28_800)),
            cost.entry(state(0, 0, 0), qty(24_000), total),
        )
    }

    @Test
    fun `with no stock the previous cost does not weigh`() {
        // 5 por 8000, aunque el costo anterior era 1300.
        assertEquals(
            Checked.Value(state(5000, 1600, 8000)),
            cost.entry(state(0, 1300, 0), qty(5000), Money(8000)),
        )
    }

    @Test
    fun `with negative stock the previous cost does not weigh`() {
        // 10 por 15000 con 2 unidades vendidas sin stock. Ponderar daría
        // (−2600000 + 15000000) / 8000 = 1550, más de lo pagado por cada unidad que queda. Las 8
        // que quedan valen 15000 × 8 / 10 = 12000.
        val after = cost.entry(state(-2000, 1300, 0), qty(10_000), Money(15_000)).accepted()
        assertEquals(state(8000, 1500, 12_000), after)

        // Venderlas todas cuesta exactamente eso.
        assertEquals(Checked.Value(Money(12_000)), cost.exitCost(after, qty(8000)))
    }

    @Test
    fun `a purchase that does not cover the negative stock keeps it negative and without value`() {
        // 2 por 3000 con 5 unidades en negativo.
        assertEquals(
            Checked.Value(state(-3000, 1500, 0)),
            cost.entry(state(-5000, 1300, 0), qty(2000), Money(3000)),
        )
    }

    @Test
    fun `a purchase that leaves the stock in zero leaves it without value`() {
        // 2 por 3000 con 2 unidades en negativo: no queda nada que valorar.
        assertEquals(
            Checked.Value(state(0, 1500, 0)),
            cost.entry(state(-2000, 1300, 0), qty(2000), Money(3000)),
        )
    }

    @Test
    fun `positive stock without value is treated as unknown cost`() {
        // 5 unidades sin costo y 5 por 5000. Ponderar daría 500 y haría pasar las primeras por
        // gratuitas. Las 10 se valoran al costo de la compra: 5000 × 10 / 5 = 10000.
        val after = cost.entry(state(5000, 0, 0), qty(5000), Money(5000)).accepted()
        assertEquals(state(10_000, 1000, 10_000), after)
        assertEquals(Checked.Value(Money(10_000)), cost.exitCost(after, qty(10_000)))
    }

    @Test
    fun `a free purchase lowers the average`() {
        // Bonificación de 2 unidades por 0: 10000 por 12 unidades, 833,33.
        assertEquals(
            Checked.Value(state(12_000, 833, 10_000)),
            cost.entry(state(10_000, 1000, 10_000), qty(2000), Money(0)),
        )
    }

    @Test
    fun `a free purchase with no stock leaves the product without cost`() {
        // 3 por 0 sin stock: el costo anterior no pesa y el producto queda sin costo.
        assertEquals(
            Checked.Value(state(3000, 0, 0)),
            cost.entry(state(0, 1300, 0), qty(3000), Money(0)),
        )
    }

    @Test
    fun `with negative stock part of what was paid reaches no report`() {
        // Se venden 2 sin stock, a 1300 cada una.
        val empty = state(0, 1300, 0)
        assertEquals(Checked.Value(Money(2600)), cost.exitCost(empty, qty(2000)))
        val negative = cost.exit(empty, qty(2000), Money(2600))
        assertEquals(state(-2000, 1300, 0), negative)

        // Llegan 10 por 15000. Lo vendido costó 2600 y las 8 que quedan valen 12000: los 400
        // restantes no aparecen en ningún lado, porque reescribir la venta cambiaría el pasado.
        val after = cost.entry(negative, qty(10_000), Money(15_000)).accepted()
        assertEquals(state(8000, 1500, 12_000), after)
        assertEquals(Checked.Value(Money(12_000)), cost.exitCost(after, qty(8000)))
    }

    @Test
    fun `a box of 24 sold one by one adds up to exactly what it cost`() {
        // 24 unidades por 10000: 416,67 por unidad, que se guarda como 417.
        val box = cost.entry(state(0, 0, 0), qty(24_000), Money(10_000)).accepted()
        assertEquals(state(24_000, 417, 10_000), box)

        // La primera sale con 10000 / 24 = 416,67, que redondea a 417.
        assertEquals(Checked.Value(Money(417)), cost.exitCost(box, qty(1000)))
        val afterOne = cost.exit(box, qty(1000), Money(417))
        assertEquals(state(23_000, 417, 9583), afterOne)

        // Las 23 restantes se llevan lo que queda, aunque 23 × 417 sean 9591.
        assertEquals(Checked.Value(Money(9583)), cost.exitCost(afterOne, qty(23_000)))
        assertEquals(state(0, 417, 0), cost.exit(afterOne, qty(23_000), Money(9583)))

        // Vendidas de a una, también suman 10000.
        var current = box
        var sold = Money.ZERO
        repeat(24) {
            val unitCost = cost.exitCost(current, qty(1000)).accepted()
            sold += unitCost
            current = cost.exit(current, qty(1000), unitCost)
        }
        assertEquals(Money(10_000), sold)
        assertEquals(state(0, 417, 0), current)
    }

    @Test
    fun `a cost below one minor unit still counts as cost`() {
        // 1000 unidades por 400 son 0,4 por unidad: el promedio redondea a 0, pero el valor no.
        val box = cost.entry(state(0, 0, 0), qty(1_000_000), Money(400)).accepted()
        assertEquals(state(1_000_000, 0, 400), box)

        // Una unidad sale con 0, y el valor sigue llevando el total.
        assertEquals(Checked.Value(Money(0)), cost.exitCost(box, qty(1000)))
        assertEquals(state(999_000, 0, 400), cost.exit(box, qty(1000), Money(0)))

        // 250 unidades cuestan 400 × 250 / 1000 = 100.
        assertEquals(Checked.Value(Money(100)), cost.exitCost(box, qty(250_000)))
    }

    @Test
    fun `a sale after a mistyped purchase takes its share of the stock value`() {
        // Inventario inicial de 10 a 1000 y una compra digitada de 1 a 50000: 60000 por 11
        // unidades, 5454,5.
        val opening = cost.entry(state(0, 0, 0), qty(10_000), Money(10_000)).accepted()
        val mistyped = cost.entry(opening, qty(1000), Money(50_000)).accepted()
        assertEquals(state(11_000, 5455, 60_000), mistyped)

        // Se venden 9: 60000 × 9 / 11 = 49090,9, que redondea a 49091. Quedan 10909.
        assertEquals(Checked.Value(Money(49_091)), cost.exitCost(mistyped, qty(9000)))
        assertEquals(state(2000, 5455, 10_909), cost.exit(mistyped, qty(9000), Money(49_091)))
    }

    @Test
    fun `an exit that takes more than the stock value leaves the rest without value`() {
        // La reproducción puede restar un costo congelado mayor que el valor que queda.
        assertEquals(
            state(1000, 1000, 0),
            cost.exit(state(10_000, 1000, 10_000), qty(9000), Money(49_091)),
        )

        // Esa unidad que queda sale sin costo, porque el stock ya no tiene valor.
        assertEquals(Checked.Value(Money(0)), cost.exitCost(state(1000, 1000, 0), qty(1000)))
    }

    @Test
    fun `an entry over stock whose value rounded away prices those units at the entry cost`() {
        // 3 unidades por 1 valen 1, a 0,33 cada una, que redondea a 0.
        val tiny = cost.entry(state(0, 0, 0), qty(3000), Money(1)).accepted()
        assertEquals(state(3000, 0, 1), tiny)

        // Se venden 2: 1 × 2 / 3 = 0,67, que redondea a 1. Queda 1 unidad sin valor.
        assertEquals(Checked.Value(Money(1)), cost.exitCost(tiny, qty(2000)))
        val withoutValue = cost.exit(tiny, qty(2000), Money(1))
        assertEquals(state(1000, 0, 0), withoutValue)

        // Llega 1 por 1000. El stock sin valor es costo desconocido, y la unidad que quedaba se
        // valora al costo de la compra: 1000 × 2 / 1 = 2000.
        assertEquals(
            Checked.Value(state(2000, 1000, 2000)),
            cost.entry(withoutValue, qty(1000), Money(1000)),
        )
    }

    @Test
    fun `adjustments take their value from the stock value without changing the average`() {
        // 11 unidades a 1355 que valen 14905.
        val start = state(11_000, 1355, 14_905)

        // Tres vencidas: 14905 × 3 / 11 = 4065.
        assertEquals(Checked.Value(Money(4065)), cost.exitCost(start, qty(3000)))
        val afterExpired = cost.exit(start, qty(3000), Money(4065))
        assertEquals(state(8000, 1355, 10_840), afterExpired)

        // El conteo dice que faltan 2: 10840 × 2 / 8 = 2710. Merma acumulada: 6775.
        assertEquals(Checked.Value(Money(2710)), cost.exitCost(afterExpired, qty(2000)))
        val afterMissing = cost.exit(afterExpired, qty(2000), Money(2710))
        assertEquals(state(6000, 1355, 8130), afterMissing)

        // Aparece 1: 8130 / 6 = 1355. Merma acumulada: 5420.
        assertEquals(
            Checked.Value(Money(1355)),
            cost.positiveAdjustmentCost(afterMissing, qty(1000)),
        )
        assertEquals(
            Checked.Value(state(7000, 1355, 9485)),
            cost.positiveAdjustment(afterMissing, qty(1000), Money(1355)),
        )
    }

    @Test
    fun `a physical count with two missing units costs their share of the stock value`() {
        // El libro dice 12 a 1355, que valen 16260, y hay 10.
        val start = state(12_000, 1355, 16_260)
        assertEquals(Checked.Value(Money(2710)), cost.exitCost(start, qty(2000)))
        assertEquals(state(10_000, 1355, 13_550), cost.exit(start, qty(2000), Money(2710)))
    }

    @Test
    fun `selling the whole stock takes its whole value`() {
        val start = state(15_000, 1300, 19_500)
        assertEquals(Checked.Value(Money(19_500)), cost.exitCost(start, qty(15_000)))
        assertEquals(state(0, 1300, 0), cost.exit(start, qty(15_000), Money(19_500)))
    }

    @Test
    fun `selling more than the stock values the excess at the average cost`() {
        // Hay 2 que valen 2600, y se venden 5: las 3 que faltan salen a 1300, 3900 más.
        val start = state(2000, 1300, 2600)
        assertEquals(Checked.Value(Money(6500)), cost.exitCost(start, qty(5000)))
        assertEquals(state(-3000, 1300, 0), cost.exit(start, qty(5000), Money(6500)))
    }

    @Test
    fun `selling with negative stock uses the average cost`() {
        assertEquals(Checked.Value(Money(1300)), cost.exitCost(state(-2000, 1300, 0), qty(1000)))
    }

    @Test
    fun `a positive adjustment without stock enters at the average cost`() {
        // Con 1 en negativo, aparece 1 a 1300: el stock queda en cero y sin valor.
        val negative = state(-1000, 1300, 0)
        assertEquals(Checked.Value(Money(1300)), cost.positiveAdjustmentCost(negative, qty(1000)))
        assertEquals(
            Checked.Value(state(0, 1300, 0)),
            cost.positiveAdjustment(negative, qty(1000), Money(1300)),
        )

        // Aparecen 3 a 1300, 3900: las 2 que quedan valen 3900 × 2 / 3 = 2600.
        assertEquals(Checked.Value(Money(3900)), cost.positiveAdjustmentCost(negative, qty(3000)))
        assertEquals(
            Checked.Value(state(2000, 1300, 2600)),
            cost.positiveAdjustment(negative, qty(3000), Money(3900)),
        )

        // Sin stock, aparecen 2 a 1300: valen 2600.
        assertEquals(
            Checked.Value(state(2000, 1300, 2600)),
            cost.positiveAdjustment(state(0, 1300, 0), qty(2000), Money(2600)),
        )
    }

    @Test
    fun `a positive adjustment of stock without value enters at the average cost`() {
        // Sin valor ni costo, el ajuste vale 0.
        assertEquals(
            Checked.Value(Money(0)),
            cost.positiveAdjustmentCost(state(5000, 0, 0), qty(1000)),
        )

        // Sin valor, pero con costo 1000, aparece 1 que vale 1000.
        val withoutValue = state(1000, 1000, 0)
        assertEquals(
            Checked.Value(Money(1000)),
            cost.positiveAdjustmentCost(withoutValue, qty(1000)),
        )
        assertEquals(
            Checked.Value(state(2000, 1000, 1000)),
            cost.positiveAdjustment(withoutValue, qty(1000), Money(1000)),
        )
    }

    @Test
    fun `a return to the supplier leaves the rest at its remaining value`() {
        // 12 que valen 14400. Vuelven 2 de una línea a 1400, que costaron 2800: quedan 11600 por
        // 10 unidades.
        assertEquals(
            Checked.Value(state(10_000, 1160, 11_600)),
            cost.supplierReturn(state(12_000, 1200, 14_400), qty(2000), Money(2800)),
        )
    }

    @Test
    fun `a return to the supplier that empties the stock keeps the cost`() {
        assertEquals(
            Checked.Value(state(0, 1200, 0)),
            cost.supplierReturn(state(2000, 1200, 2400), qty(2000), Money(2800)),
        )
        assertEquals(
            Checked.Value(state(-1000, 1200, 0)),
            cost.supplierReturn(state(1000, 1200, 1200), qty(2000), Money(2800)),
        )
    }

    @Test
    fun `a return worth more than the stock leaves the rest without value`() {
        // 2 que valen 2000, y la unidad devuelta costó 5000: lo que queda vale cero.
        assertEquals(
            Checked.Value(state(1000, 0, 0)),
            cost.supplierReturn(state(2000, 1000, 2000), qty(1000), Money(5000)),
        )
    }

    @Test
    fun `correcting the cost gives value to stock that had none`() {
        // 5 unidades sin costo, corregido a 1000: dejan de estar sin costo y valen 5000.
        val corrected = cost.correctCost(state(5000, 0, 0), Money(1000)).accepted()
        assertEquals(state(5000, 1000, 5000), corrected)
        assertEquals(Checked.Value(Money(5000)), cost.exitCost(corrected, qty(5000)))
    }

    @Test
    fun `correcting the cost revalues the whole stock`() {
        // 4 que valían 4100 pasan a valer 4 × 1200 = 4800.
        assertEquals(
            Checked.Value(state(4000, 1200, 4800)),
            cost.correctCost(state(4000, 1000, 4100), Money(1200)),
        )
    }

    @Test
    fun `correcting the cost with negative stock only changes the cost`() {
        assertEquals(
            Checked.Value(state(-2000, 1500, 0)),
            cost.correctCost(state(-2000, 1300, 0), Money(1500)),
        )
    }

    @Test
    fun `the quantity of a movement is always positive`() {
        val start = state(10_000, 1000, 10_000)
        val rejected =
            listOf<() -> Any>(
                { cost.entry(start, qty(0), Money(1000)) },
                { cost.entry(start, qty(-1000), Money(1000)) },
                { cost.exitCost(start, qty(0)) },
                { cost.exit(start, qty(0), Money(0)) },
                { cost.positiveAdjustmentCost(start, qty(0)) },
                { cost.positiveAdjustment(start, qty(0), Money(0)) },
                { cost.supplierReturn(start, qty(0), Money(1000)) },
            )
        rejected.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `a cost is never negative`() {
        val start = state(10_000, 1000, 10_000)
        val rejected =
            listOf<() -> Any>(
                { cost.entry(start, qty(1000), Money(-1)) },
                { cost.exit(start, qty(1000), Money(-1)) },
                { cost.positiveAdjustment(start, qty(1000), Money(-1)) },
                { cost.supplierReturn(start, qty(1000), Money(-1)) },
                { cost.correctCost(start, Money(-1)) },
            )
        rejected.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `an entry whose figures do not fit in a long is rejected`() {
        // El total por mil no cabe: el promedio de la primera compra.
        assertEquals(Checked.Overflow, cost.entry(state(0, 0, 0), qty(1), Money(Long.MAX_VALUE)))
        // El valor de lo que queda no cabe: un billón de unidades sin valor, a 10^16 por unidad.
        assertEquals(
            Checked.Overflow,
            cost.entry(
                state(1_000_000_000_000, 0, 0),
                qty(1000),
                Money(10_000_000_000_000_000),
            ),
        )
        // La suma del valor no cabe.
        assertEquals(
            Checked.Overflow,
            cost.entry(state(1000, 1, Long.MAX_VALUE), qty(1000), Money(1)),
        )
        // El promedio ponderado no cabe.
        assertEquals(
            Checked.Overflow,
            cost.entry(state(1, 1000, 1), qty(1), Money(9_000_000_000_000_000_000)),
        )
        // El stock no cabe.
        assertEquals(Checked.Overflow, cost.entry(state(Long.MAX_VALUE, 0, 0), qty(1), Money(0)))
    }

    @Test
    fun `an exit or adjustment cost that does not fit in a long is rejected`() {
        val absurd = state(0, Long.MAX_VALUE, 0)
        assertEquals(Checked.Overflow, cost.exitCost(absurd, qty(2000)))
        assertEquals(Checked.Overflow, cost.positiveAdjustmentCost(absurd, qty(2000)))
        // Lo que sale por encima del stock no cabe, o no cabe sumado al valor.
        assertEquals(Checked.Overflow, cost.exitCost(state(1000, Long.MAX_VALUE, 5), qty(3000)))
        assertEquals(
            Checked.Overflow,
            cost.exitCost(state(1000, 1000, Long.MAX_VALUE), qty(2000)),
        )
    }

    @Test
    fun `a new state whose figures do not fit in a long is rejected`() {
        // El valor del stock más el ajuste no cabe.
        assertEquals(
            Checked.Overflow,
            cost.positiveAdjustment(state(1000, 0, Long.MAX_VALUE), qty(1000), Money(1)),
        )
        // El stock no cabe.
        assertEquals(
            Checked.Overflow,
            cost.positiveAdjustment(state(Long.MAX_VALUE, 0, 0), qty(1), Money(0)),
        )
        // El promedio de lo que queda tras la devolución no cabe.
        assertEquals(
            Checked.Overflow,
            cost.supplierReturn(state(1001, 0, Long.MAX_VALUE), qty(1000), Money(0)),
        )
        // El valor del stock al costo corregido no cabe.
        assertEquals(
            Checked.Overflow,
            cost.correctCost(state(Long.MAX_VALUE, 0, 0), Money(Long.MAX_VALUE)),
        )
    }

    @Test
    fun `moving a stock that does not fit in a long fails instead of wrapping around`() {
        assertThrows(ArithmeticException::class.java) {
            cost.exit(state(Long.MIN_VALUE, 0, 0), qty(1), Money(0))
        }
    }

    private fun state(onHand: Long, averageCost: Long, stockValue: Long) =
        CostState(qty(onHand), Money(averageCost), Money(stockValue))

    private fun qty(milli: Long) = QuantityMilli(milli)

    private fun <T> Checked<T>.accepted(): T = when (this) {
        is Checked.Value -> value
        Checked.Overflow -> throw AssertionError("La cuenta no cupo en Long")
        is Checked.AboveLimit -> throw AssertionError("La cuenta pasó el tope $limit")
    }
}
