package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.ADJUSTMENT_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.COST_CORRECTION
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.OPENING_BALANCE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_OUT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PRODUCTION_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.PURCHASE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_RETURN_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.SALE_VOID
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_IN
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_OUT
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType.TRANSFER_VOID
import org.junit.Assert.assertEquals
import org.junit.Test

// Libros de un solo producto, con los valores que cada fila guardó al escribirse. Cada resultado
// esperado está calculado a mano desde las reglas de costo, nunca con el código que se prueba.
class CostReplayTest {
    @Test
    fun `voiding a sale brings its units back at their original cost`() {
        val purchase = row(1, PURCHASE, 10_000, 1000, 10_000)
        val sale = row(2, SALE, -4000, 1000, -4000)
        // 6000 + 8400 = 14400 por 12 unidades.
        val secondPurchase = row(3, PURCHASE, 6000, 1400, 8400)
        val saleVoid = row(4, SALE_VOID, 4000, 1000, 4000, reverses = 2)

        assertEquals(replayed(6000, 1000), replay(listOf(purchase, sale)))
        assertEquals(replayed(12_000, 1200), replay(listOf(purchase, sale, secondPurchase)))

        // Se reproducen 1 y 3: (12 × 1200 + 4 × 1000) / 16 = 1150, el valor real.
        val result = replay(listOf(purchase, sale, secondPurchase, saleVoid))
        assertEquals(replayed(16_000, 1150), result)
        // Las 16 unidades valen 18400.
        assertEquals(
            Checked.Value(Money(18_400)),
            WeightedAverageCost.exitCost(stateOf(result), QuantityMilli(16_000)),
        )
    }

    @Test
    fun `a purchase voided right away leaves the exact previous cost`() {
        // Se digitó 1 unidad a 50000 en vez de 5000.
        val opening = row(1, OPENING_BALANCE, 10_000, 1000, 10_000)
        val wrongPurchase = row(2, PURCHASE, 1000, 50_000, 50_000)
        val purchaseVoid = row(3, PURCHASE_VOID, -1000, 50_000, -50_000, reverses = 2)

        // 60000 por 11 unidades: 5454,5.
        assertEquals(replayed(11_000, 5455), replay(listOf(opening, wrongPurchase)))
        // Restar la compra daría 1001, porque arrastra el redondeo. Reproducir da 1000 exacto.
        assertEquals(replayed(10_000, 1000), replay(listOf(opening, wrongPurchase, purchaseVoid)))
    }

    @Test
    fun `a purchase voided after part of it was sold goes back to the cost of what remains`() {
        val opening = row(1, OPENING_BALANCE, 10_000, 1000, 10_000)
        val wrongPurchase = row(2, PURCHASE, 1000, 50_000, 50_000)
        // La venta salió con el costo errado y lo conserva.
        val sale = row(3, SALE, -9000, 5455, -49_091)
        val purchaseVoid = row(4, PURCHASE_VOID, -1000, 50_000, -50_000, reverses = 2)

        assertEquals(replayed(2000, 5455), replay(listOf(opening, wrongPurchase, sale)))
        // Restar la compra daría −39090. Reproducir 1 y 3 da 1000.
        assertEquals(
            replayed(1000, 1000),
            replay(listOf(opening, wrongPurchase, sale, purchaseVoid)),
        )
    }

    @Test
    fun `voiding the only purchase of a sold product goes back to its initial cost`() {
        val ledger =
            listOf(
                row(1, PURCHASE, 10_000, 1000, 10_000),
                row(2, SALE, -10_000, 1000, -10_000),
                row(3, PURCHASE_VOID, -10_000, 1000, -10_000, reverses = 1),
            )

        // Sin costo inicial queda sin costo y en negativo. Con 900 de costo inicial, en 900.
        assertEquals(replayed(-10_000, 0), replay(ledger))
        assertEquals(replayed(-10_000, 900), replay(ledger, initialCost = 900))
    }

    @Test
    fun `voiding two purchases in either order gives the same cost`() {
        val ledger =
            listOf(
                row(1, OPENING_BALANCE, 10_000, 1000, 10_000),
                // 10000 + 8000 = 18000 por 15 unidades.
                row(2, PURCHASE, 5000, 1600, 8000),
                row(3, SALE, -3000, 1200, -3600),
                // 14400 + 8000 = 22400 por 16 unidades.
                row(4, PURCHASE, 4000, 2000, 8000),
            )
        assertEquals(replayed(16_000, 1400), replay(ledger))

        val voidSecond = { id: Long -> row(id, PURCHASE_VOID, -5000, 1600, -8000, reverses = 2) }
        val voidFourth = { id: Long -> row(id, PURCHASE_VOID, -4000, 2000, -8000, reverses = 4) }

        // Solo la segunda: 7000 + 8000 = 15000 por 11 unidades, 1363,64.
        assertEquals(replayed(11_000, 1364), replay(ledger + voidSecond(5)))
        // Solo la cuarta: queda lo de antes de ella.
        assertEquals(replayed(12_000, 1200), replay(ledger + voidFourth(5)))
        // Las dos, en un orden y en el otro: quedan la apertura y la venta.
        assertEquals(replayed(7000, 1000), replay(ledger + voidSecond(5) + voidFourth(6)))
        assertEquals(replayed(7000, 1000), replay(ledger + voidFourth(5) + voidSecond(6)))
    }

    @Test
    fun `transferring the whole stock does not erase its cost`() {
        val ledger =
            listOf(
                row(1, PURCHASE, 10_000, 1200, 12_000),
                row(2, TRANSFER_OUT, -10_000, 0, 0),
                row(3, TRANSFER_IN, 10_000, 0, 0),
                // 12000 + 14000 = 26000 por 20 unidades. Si la transferencia vaciara el stock y lo
                // volviera a llenar sin valor, el promedio saltaría a 1400.
                row(4, PURCHASE, 10_000, 1400, 14_000),
            )
        assertEquals(replayed(20_000, 1300), replay(ledger))

        // Una transferencia anulada tampoco cuenta.
        val voided =
            ledger +
                row(5, TRANSFER_OUT, -5000, 0, 0) +
                row(6, TRANSFER_VOID, 5000, 0, 0, reverses = 5)
        assertEquals(replayed(20_000, 1300), replay(voided))
    }

    @Test
    fun `the ledger is replayed in the order it was written whatever order it arrives in`() {
        val purchase = row(1, PURCHASE, 10_000, 1000, 10_000)
        val sale = row(2, SALE, -4000, 1000, -4000)
        val secondPurchase = row(3, PURCHASE, 6000, 1400, 8400)
        val saleVoid = row(4, SALE_VOID, 4000, 1000, 4000, reverses = 2)

        assertEquals(
            replayed(16_000, 1150),
            replay(listOf(saleVoid, sale, purchase, secondPurchase)),
        )
        // Las filas ya filtradas, como las lee la consulta del libro efectivo, dan lo mismo.
        assertEquals(replayed(16_000, 1150), replay(listOf(purchase, secondPurchase)))
    }

    @Test
    fun `a sale keeps the cost it was frozen with when a later purchase raises the average`() {
        val cost = WeightedAverageCost
        val first = row(1, PURCHASE, 10_000, 1200, 12_000)
        val second = row(2, PURCHASE, 5000, 1500, 7500)
        val beforeSale = CostState(QuantityMilli(15_000), Money(1300))
        assertEquals(replayed(15_000, 1300), replay(listOf(first, second)))

        // La venta de 8 congela el costo del momento: 1300 por unidad y 10400 en total.
        assertEquals(Checked.Value(Money(10_400)), cost.exitCost(beforeSale, QuantityMilli(8000)))
        val sale = row(3, SALE, -8000, 1300, -10_400)

        // Una compra posterior sube el promedio a 1355. Vender hoy esas mismas 8 costaría 10840,
        // pero la reproducción usa lo que la venta guardó, y su ganancia no cambia.
        val later = row(4, PURCHASE, 4000, 1450, 5800)
        val result = replay(listOf(first, second, sale, later))
        assertEquals(replayed(11_000, 1355), result)
        assertEquals(
            Checked.Value(Money(10_840)),
            cost.exitCost(stateOf(result), QuantityMilli(8000)),
        )
        assertEquals(Money(1300), sale.unitCost)
        assertEquals(Money(-10_400), sale.totalCost)
    }

    @Test
    fun `a customer return enters at its frozen cost`() {
        // 5 a 1000 valen 5000. Vuelve 1 que se vendió con 1250: 6250 por 6 unidades, 1041,67.
        val ledger =
            listOf(
                row(1, OPENING_BALANCE, 5000, 1000, 5000),
                row(2, SALE_RETURN, 1000, 1250, 1250),
            )
        assertEquals(replayed(6000, 1042), replay(ledger))
        // Anulada la devolución, queda lo de antes.
        assertEquals(
            replayed(5000, 1000),
            replay(ledger + row(3, SALE_RETURN_VOID, -1000, 1250, -1250, reverses = 2)),
        )
    }

    @Test
    fun `a return to the supplier leaves the rest of the stock at its remaining value`() {
        val ledger =
            listOf(
                row(1, PURCHASE, 10_000, 1000, 10_000),
                row(2, SALE, -4000, 1000, -4000),
                row(3, PURCHASE, 6000, 1400, 8400),
                // 14400 − 2800 = 11600 por 10 unidades.
                row(4, PURCHASE_RETURN, -2000, 1400, -2800),
            )
        assertEquals(replayed(10_000, 1160), replay(ledger))
        assertEquals(
            replayed(12_000, 1200),
            replay(ledger + row(5, PURCHASE_RETURN_VOID, 2000, 1400, 2800, reverses = 4)),
        )
    }

    @Test
    fun `a cost correction gives cost to stock that had none`() {
        val ledger =
            listOf(
                row(1, OPENING_BALANCE, 5000, 0, 0),
                row(2, COST_CORRECTION, 0, 1000, 0),
                row(3, SALE, -1000, 1000, -1000),
            )
        assertEquals(replayed(5000, 0), replay(ledger.take(1)))
        assertEquals(replayed(4000, 1000), replay(ledger))
    }

    @Test
    fun `adjustments move the stock but not the average`() {
        val ledger =
            listOf(
                // 11 unidades por 14905: 1355 exacto.
                row(1, OPENING_BALANCE, 11_000, 1355, 14_905),
                row(2, ADJUSTMENT, -3000, 1355, -4065),
                row(3, ADJUSTMENT, -2000, 1355, -2710),
                row(4, ADJUSTMENT, 1000, 1355, 1355),
            )
        assertEquals(replayed(7000, 1355), replay(ledger))
        // Anular los vencidos devuelve sus 3 unidades.
        assertEquals(
            replayed(10_000, 1355),
            replay(ledger + row(5, ADJUSTMENT_VOID, 3000, 1355, 4065, reverses = 2)),
        )
    }

    @Test
    fun `production consumes inputs like a sale and adds its output like a purchase`() {
        val input =
            listOf(
                row(1, PURCHASE, 4000, 500, 2000),
                row(2, PRODUCTION_OUT, -3000, 500, -1500),
            )
        assertEquals(replayed(1000, 500), replay(input))

        // Lo producido entra con el costo de sus insumos: 1500 por 2 unidades.
        val output = listOf(row(1, PRODUCTION_IN, 2000, 750, 1500))
        assertEquals(replayed(2000, 750), replay(output))
        assertEquals(
            replayed(0, 0),
            replay(output + row(2, PRODUCTION_VOID, -2000, 750, -1500, reverses = 1)),
        )
    }

    @Test
    fun `an empty ledger starts from the initial cost`() {
        assertEquals(replayed(0, 900), replay(emptyList(), initialCost = 900))
    }

    @Test
    fun `a row that contradicts its type is reported with its id`() {
        val opening = row(1, OPENING_BALANCE, 10_000, 1000, 10_000)
        val impossible =
            listOf(
                // Una entrada que no suma, o con valor negativo.
                row(2, PURCHASE, 0, 1000, 0),
                row(2, PURCHASE, -1000, 1000, -1000),
                row(2, SALE_RETURN, 1000, 1000, -1000),
                // Una salida que no resta, o que devuelve valor.
                row(2, SALE, 1000, 1000, -1000),
                row(2, PRODUCTION_OUT, -1000, 1000, 1000),
                row(2, PURCHASE_RETURN, 1000, 1000, 1000),
                // Un ajuste sin unidades, o con el valor de signo contrario.
                row(2, ADJUSTMENT, 0, 1000, 0),
                row(2, ADJUSTMENT, -1000, 1000, 1000),
                row(2, ADJUSTMENT, 1000, 1000, -1000),
                // Una corrección de costo que mueve unidades o valor, o con costo negativo.
                row(2, COST_CORRECTION, 1000, 1000, 0),
                row(2, COST_CORRECTION, 0, 1000, 1000),
                row(2, COST_CORRECTION, 0, -1, 0),
            ) +
                // Una anulación que no apunta a ninguna fila.
                listOf(
                    PURCHASE_VOID,
                    SALE_VOID,
                    ADJUSTMENT_VOID,
                    SALE_RETURN_VOID,
                    PURCHASE_RETURN_VOID,
                    PRODUCTION_VOID,
                    TRANSFER_VOID,
                ).map { type -> row(2, type, 1000, 1000, 1000) }

        impossible.forEach { bad ->
            assertEquals(
                bad.toString(),
                CostReplayResult.InvalidMovement(2),
                replay(listOf(opening, bad, row(3, PURCHASE, 1000, 1000, 1000))),
            )
        }
    }

    @Test
    fun `a row whose figures do not fit in a long is reported with its id`() {
        // El total por mil no cabe en el promedio.
        assertEquals(
            CostReplayResult.Overflow(1),
            replay(listOf(row(1, PURCHASE, 1, 0, Long.MAX_VALUE))),
        )
        // El stock no cabe.
        assertEquals(
            CostReplayResult.Overflow(2),
            replay(listOf(row(1, PURCHASE, Long.MAX_VALUE, 0, 0), row(2, PURCHASE, 1, 0, 0))),
        )
        // La cantidad de la salida no tiene opuesto en Long.
        assertEquals(
            CostReplayResult.Overflow(1),
            replay(listOf(row(1, SALE, Long.MIN_VALUE, 0, 0))),
        )
    }

    private fun row(
        id: Long,
        type: StockMovementType,
        quantity: Long,
        unitCost: Long,
        totalCost: Long,
        reverses: Long? = null,
    ) = CostMovement(id, type, QuantityMilli(quantity), Money(unitCost), Money(totalCost), reverses)

    private fun replay(movements: List<CostMovement>, initialCost: Long = 0L) =
        WeightedAverageCost.replayCostState(Money(initialCost), movements)

    private fun replayed(onHand: Long, averageCost: Long) =
        CostReplayResult.Replayed(CostState(QuantityMilli(onHand), Money(averageCost)))

    private fun stateOf(result: CostReplayResult): CostState = when (result) {
        is CostReplayResult.Replayed -> result.state
        is CostReplayResult.InvalidMovement -> throw AssertionError("Fila imposible: $result")
        is CostReplayResult.Overflow -> throw AssertionError("No cupo en Long: $result")
    }
}
