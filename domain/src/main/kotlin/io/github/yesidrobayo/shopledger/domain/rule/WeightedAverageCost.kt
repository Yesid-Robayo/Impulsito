package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull

/**
 * Costo promedio ponderado móvil de un producto.
 *
 * Cada entrada de mercancía recalcula el promedio con el valor de lo que ya había y el total de lo
 * que entra. Una salida no lo cambia: se lleva el costo del momento, que la venta congela y que
 * nada posterior reescribe. Así una compra de hoy nunca cambia la ganancia de una venta pasada.
 *
 * Cada operación lleva en su KDoc el nombre de su fórmula en la documentación, para encontrarla con
 * una búsqueda: `entrada`, `costoDeSalida`, `salida`, `ajustePositivo`, `devolucionAProveedor`,
 * `corregirCosto` y `replayCostState`.
 *
 * Las operaciones que multiplican devuelven [Checked]: una cifra que no cabe en `Long` llega como
 * [Checked.Overflow] y no se guarda nada. Las que solo suman o restan lanzan [ArithmeticException],
 * igual que [Money]. Ninguna aplica topes: la reproducción del libro debe aceptar lo que ya está
 * guardado, aunque una anulación lo haya llevado por encima de un tope, y cada caso de uso valida
 * los suyos antes de confirmar.
 */
object WeightedAverageCost {
    /**
     * `entrada`: el estado tras sumar [quantity] milésimas que valen [total] en conjunto. La usan
     * la compra, el inventario inicial, lo que sale de una producción y lo que devuelve un cliente.
     *
     * El promedio sale del total y no del costo unitario, que ya viene redondeado: tres kilogramos
     * por 25000 son 8333,33 por kilogramo, y 8333 × 3 perdería una unidad menor.
     *
     * Sin unidades, o con stock sin valor, el costo anterior no pesa y el promedio es el de la
     * entrada. Con stock negativo, esas unidades ya se vendieron con su costo congelado, y
     * ponderarlas daría a lo que queda un costo mayor que lo pagado. Con stock sin valor,
     * ponderarlo lo haría pasar por gratuito. Un total cero, como una bonificación, sí es válido y
     * baja el promedio.
     */
    fun entry(state: CostState, quantity: QuantityMilli, total: Money): Checked<CostState> {
        requirePositive(quantity)
        requireNotNegative(total)
        return checked { applyEntry(state, quantity, total) }
    }

    /**
     * `costoDeSalida`: lo que cuestan [quantity] milésimas que salen del stock. Es el costo de una
     * línea de venta, de un ajuste negativo o de un insumo de producción, y se guarda congelado.
     *
     * Es la parte proporcional del valor del stock. Lo que sale por encima de lo que hay, o con
     * stock negativo, sale al costo promedio, que es el último que se conoce.
     */
    fun exitCost(state: CostState, quantity: QuantityMilli): Checked<Money> {
        requirePositive(quantity)
        return checked { Money(exitCostOf(state, quantity.milli)) }
    }

    /**
     * `salida`: el estado tras restar [quantity] milésimas. El costo promedio no cambia. Puede
     * dejar el stock negativo, porque vender nunca se bloquea por falta de stock.
     */
    fun exit(state: CostState, quantity: QuantityMilli): CostState {
        requirePositive(quantity)
        return applyExit(state, quantity)
    }

    /**
     * El valor de un ajuste que suma [quantity] milésimas. Esas unidades aparecen sin factura, así
     * que entran al costo vigente: la parte proporcional del valor del stock, o el costo promedio
     * si no hay unidades o no tienen valor.
     */
    fun positiveAdjustmentCost(state: CostState, quantity: QuantityMilli): Checked<Money> {
        requirePositive(quantity)
        return checked { Money(positiveAdjustmentCostOf(state, quantity.milli)) }
    }

    /**
     * `ajustePositivo`: el estado tras sumar [quantity] milésimas sin cambiar el costo promedio.
     */
    fun positiveAdjustment(state: CostState, quantity: QuantityMilli): CostState {
        requirePositive(quantity)
        return applyPositiveAdjustment(state, quantity)
    }

    /**
     * `devolucionAProveedor`: el estado tras devolver [quantity] milésimas al costo de su línea de
     * compra, [total]. A diferencia de una venta, sí cambia el promedio: lo devuelto sale a lo que
     * costó, y lo que queda vale el resto. Si no queda stock, el costo no cambia.
     */
    fun supplierReturn(
        state: CostState,
        quantity: QuantityMilli,
        total: Money,
    ): Checked<CostState> {
        requirePositive(quantity)
        requireNotNegative(total)
        return checked { applySupplierReturn(state, quantity, total) }
    }

    /**
     * `corregirCosto`: fija el costo promedio en [newCost] sin mover unidades. Sirve para dar costo
     * a un stock que no lo tenía, o corregir uno errado sin una compra. No reescribe ninguna venta.
     */
    fun correctCost(state: CostState, newCost: Money): CostState {
        requireNotNegative(newCost)
        return state.copy(averageCost = newCost)
    }

    /**
     * `replayCostState`: el estado que deja el libro efectivo del producto, reproducido desde el
     * principio.
     *
     * Tras anular un documento, el costo no se revierte con una resta: arrastraría el redondeo,
     * daría un costo negativo si parte de la compra ya se vendió y dependería del orden de las
     * anulaciones. Se reproduce el libro como si el documento anulado no hubiera existido.
     *
     * El libro efectivo deja fuera cada fila anulada y la fila que la anula. Las transferencias se
     * saltan, porque no cambian el costo: transferir todo el stock no lo borra. Las filas se
     * aplican por [CostMovement.id], que es el orden en que se registraron, cada una con los
     * valores que guardó: nada ya escrito se recalcula. Recibir las filas ya filtradas da el mismo
     * resultado.
     *
     * Empieza sin stock y con [initialCost], el costo que se escribió al crear el producto.
     */
    fun replayCostState(initialCost: Money, movements: List<CostMovement>): CostReplayResult {
        val reversed = movements.mapNotNullTo(mutableSetOf()) { it.reversesMovementId }
        var state = CostState(QuantityMilli.ZERO, initialCost)
        for (movement in movements.sortedBy { it.id }) {
            if (movement.reversesMovementId != null || movement.id in reversed) continue
            val next = try {
                replayStep(state, movement)
            } catch (overflow: ArithmeticException) {
                return CostReplayResult.Overflow(movement.id)
            }
            state = next ?: return CostReplayResult.InvalidMovement(movement.id)
        }
        return CostReplayResult.Replayed(state)
    }

    /**
     * Aplica una fila del libro efectivo con los valores que guardó, o devuelve `null` si
     * contradice su tipo. Se revisa antes de aplicarla porque el libro puede venir de una copia
     * restaurada.
     */
    private fun replayStep(state: CostState, movement: CostMovement): CostState? {
        val quantity = movement.quantity.milli
        val total = movement.totalCost.minor
        val adds = quantity > 0L && total >= 0L
        val removes = quantity < 0L && total <= 0L
        return when (movement.type) {
            StockMovementType.OPENING_BALANCE,
            StockMovementType.PURCHASE,
            StockMovementType.PRODUCTION_IN,
            StockMovementType.SALE_RETURN,
            -> if (adds) applyEntry(state, movement.quantity, movement.totalCost) else null

            StockMovementType.SALE,
            StockMovementType.PRODUCTION_OUT,
            -> if (removes) applyExit(state, -movement.quantity) else null

            StockMovementType.ADJUSTMENT -> when {
                removes -> applyExit(state, -movement.quantity)
                adds -> applyPositiveAdjustment(state, movement.quantity)
                else -> null
            }

            StockMovementType.PURCHASE_RETURN ->
                if (removes) {
                    applySupplierReturn(state, -movement.quantity, -movement.totalCost)
                } else {
                    null
                }

            StockMovementType.COST_CORRECTION -> {
                val valid = quantity == 0L && total == 0L && movement.unitCost.minor >= 0L
                if (valid) state.copy(averageCost = movement.unitCost) else null
            }

            StockMovementType.TRANSFER_OUT,
            StockMovementType.TRANSFER_IN,
            -> state

            // Una anulación siempre apunta a la fila que anula, y así queda fuera del libro
            // efectivo. Una que no apunta a ninguna no puede salir de la app.
            StockMovementType.PURCHASE_VOID,
            StockMovementType.SALE_VOID,
            StockMovementType.ADJUSTMENT_VOID,
            StockMovementType.SALE_RETURN_VOID,
            StockMovementType.PURCHASE_RETURN_VOID,
            StockMovementType.PRODUCTION_VOID,
            StockMovementType.TRANSFER_VOID,
            -> null
        }
    }

    private fun applyEntry(state: CostState, quantity: QuantityMilli, total: Money): CostState {
        val onHandAfter = state.onHand + quantity
        val value = stockValueOf(state)
        val averageAfter = if (state.onHand.milli <= 0L || value == 0L) {
            mulDiv(total.minor, Scale.MILLI, quantity.milli)
        } else {
            mulDiv(Math.addExact(value, total.minor), Scale.MILLI, onHandAfter.milli)
        }
        return CostState(onHandAfter, Money(averageAfter))
    }

    private fun exitCostOf(state: CostState, quantity: Long): Long {
        val onHand = state.onHand.milli
        val average = state.averageCost.minor
        return when {
            onHand <= 0L -> mulDiv(quantity, average, Scale.MILLI)

            quantity <= onHand -> mulDiv(stockValueOf(state), quantity, onHand)

            else -> Math.addExact(
                stockValueOf(state),
                mulDiv(quantity - onHand, average, Scale.MILLI),
            )
        }
    }

    private fun applyExit(state: CostState, quantity: QuantityMilli): CostState =
        state.copy(onHand = state.onHand - quantity)

    private fun positiveAdjustmentCostOf(state: CostState, quantity: Long): Long {
        val onHand = state.onHand.milli
        val value = stockValueOf(state)
        return if (onHand > 0L && value > 0L) {
            mulDiv(value, quantity, onHand)
        } else {
            mulDiv(quantity, state.averageCost.minor, Scale.MILLI)
        }
    }

    private fun applyPositiveAdjustment(state: CostState, quantity: QuantityMilli): CostState =
        state.copy(onHand = state.onHand + quantity)

    private fun applySupplierReturn(
        state: CostState,
        quantity: QuantityMilli,
        total: Money,
    ): CostState {
        val onHandAfter = state.onHand - quantity
        if (onHandAfter.milli <= 0L) return state.copy(onHand = onHandAfter)
        // Si lo devuelto costó más de lo que vale el stock, lo que queda vale cero, nunca negativo.
        val valueAfter = Math.subtractExact(stockValueOf(state), total.minor).coerceAtLeast(0L)
        return CostState(onHandAfter, Money(mulDiv(valueAfter, Scale.MILLI, onHandAfter.milli)))
    }

    /**
     * El valor total del stock, deducido del promedio: `onHand × averageCost / 1000`, redondeado.
     * Sin unidades no hay nada que valorar, así que vale cero también con stock negativo.
     */
    private fun stockValueOf(state: CostState): Long = if (state.onHand.milli <= 0L) {
        0L
    } else {
        mulDiv(state.onHand.milli, state.averageCost.minor, Scale.MILLI)
    }

    /** [mulDivRound] dentro de una cuenta: si no cabe, la corta como cualquier desbordamiento. */
    private fun mulDiv(a: Long, b: Long, d: Long): Long = mulDivRound(a, b, d).valueOrNull()
        ?: throw ArithmeticException("El resultado no cabe en Long")

    /** Corre una cuenta y devuelve cualquier desbordamiento como [Checked.Overflow]. */
    private inline fun <T> checked(calculation: () -> T): Checked<T> = try {
        Checked.Value(calculation())
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }

    private fun requirePositive(quantity: QuantityMilli) {
        require(quantity.milli > 0L) { "La cantidad de un movimiento siempre es positiva" }
    }

    private fun requireNotNegative(amount: Money) {
        require(amount.minor >= 0L) { "Un costo nunca es negativo" }
    }
}
