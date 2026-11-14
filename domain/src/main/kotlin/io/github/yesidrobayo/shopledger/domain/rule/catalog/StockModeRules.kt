package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * Qué hace cada modo de stock con el libro y con el costo de una venta.
 *
 * Solo un producto con stock propio tiene libro. Un servicio no tiene existencias: vender no
 * escribe ninguna fila, su saldo es siempre cero y su costo es un costo fijo por unidad, que la
 * persona escribe porque no hay compras de dónde sacarlo. Un combo tampoco tiene libro: si lo
 * tuviera, habría dos verdades para el mismo stock. Al venderse descuenta cada componente con
 * stock, y su costo es la suma de sus componentes.
 *
 * El modo se copia en la línea como su efecto de stock, para que la línea diga siempre qué filas
 * le corresponden, y para anular la venta igual aunque el producto cambie después.
 *
 * Los costos que multiplican devuelven [Checked]: una cifra que no cabe en `Long`, o que pasa del
 * tope de una línea, llega como rechazo y no se guarda nada.
 */
object StockModeRules {
    /** El efecto de stock que guarda una línea de un producto en modo [mode]. */
    fun stockEffectOf(mode: ProductStockMode): LineStockEffect = when (mode) {
        ProductStockMode.TRACKED -> LineStockEffect.DIRECT
        ProductStockMode.UNTRACKED -> LineStockEffect.NONE
        ProductStockMode.COMPONENTS -> LineStockEffect.COMPONENTS
    }

    /**
     * Si un producto en modo [mode] tiene libro de stock propio. Solo entonces tiene filas,
     * saldo distinto de cero, valor del stock y alerta de stock bajo.
     */
    fun hasLedger(mode: ProductStockMode): Boolean = mode == ProductStockMode.TRACKED

    /**
     * El saldo que debe guardar un producto en modo [mode] cuyo libro suma [ledgerSum]: la suma,
     * si tiene libro, y cero si no.
     *
     * Devuelve `null` si ningún saldo cuadra: un producto sin libro cuyas filas no suman cero. La
     * app no puede escribir filas en un producto así, así que el daño está en el libro, y corregir
     * el saldo guardado no lo arregla.
     */
    fun expectedOnHand(mode: ProductStockMode, ledgerSum: QuantityMilli): QuantityMilli? = when {
        hasLedger(mode) -> ledgerSum
        ledgerSum == QuantityMilli.ZERO -> QuantityMilli.ZERO
        else -> null
    }

    /**
     * Si el saldo guardado [onHand] de un producto en modo [mode] cumple el invariante del saldo:
     * es igual a la suma de su libro, [ledgerSum], y además vale cero si el producto no tiene
     * libro. Es la misma condición que comprueba la consulta del invariante sobre la base.
     */
    fun onHandHolds(
        mode: ProductStockMode,
        onHand: QuantityMilli,
        ledgerSum: QuantityMilli,
    ): Boolean = expectedOnHand(mode, ledgerSum) == onHand

    /**
     * Qué hace con el stock la línea [subject] y cuánto cuesta.
     *
     * - Con stock propio, una salida de su libro, que cuesta su parte del valor del stock.
     * - Sin stock, ninguna fila, y cuesta el costo fijo por la cantidad, redondeado.
     * - Un combo, una salida por cada hoja con stock y ninguna por las demás, y cuesta la suma de
     *   sus hojas. El combo mismo no escribe nada.
     *
     * Nunca rechaza una salida por falta de stock: el saldo de un producto puede quedar negativo.
     */
    fun planLine(subject: LineStockSubject): Checked<LineStockPlan> = when (subject) {
        is LineStockSubject.Tracked -> when (val lots = subject.lots) {
            null -> exitOf(subject.costState, subject.quantity)
            else -> lotExitOf(subject.costState, subject.quantity, lots, subject.chosenLotId)
        }.map(LineStockPlan::Direct)

        is LineStockSubject.Untracked ->
            fixedCost(subject.fixedUnitCost, subject.quantity).map { lineCost ->
                LineStockPlan.NoStock(unitCost = subject.fixedUnitCost, lineCost = lineCost)
            }

        is LineStockSubject.Components -> planComponents(subject)
    }

    private fun planComponents(subject: LineStockSubject.Components): Checked<LineStockPlan> {
        val states = LinkedHashMap(subject.costStates)
        val lotStocks = HashMap<Long, List<LotStock>>()
        val leaves = ArrayList<LeafStockPlan>(subject.leaves.size)
        var lineCost = 0L
        for (leaf in subject.leaves) {
            val planned = when (val result = planLeaf(leaf, states, lotStocks)) {
                is Checked.Value -> result.value
                Checked.Overflow -> return Checked.Overflow
                is Checked.AboveLimit -> return result
            }
            if (planned is LeafStockPlan.Tracked) {
                states[planned.productId] = planned.exit.stateAfter
            }
            // Cada hoja y la suma anterior están dentro del tope de una línea, así que la suma
            // cabe en Long y basta compararla con el tope.
            lineCost += planned.lineCost.minor
            if (!Limit.DOCUMENT_TOTAL.allows(lineCost)) {
                return Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
            }
            leaves += planned
        }
        return Checked.Value(LineStockPlan.Components(leaves, Money(lineCost), states))
    }

    private fun planLeaf(
        leaf: ComponentLeaf,
        states: Map<Long, CostState>,
        lotStocks: MutableMap<Long, List<LotStock>>,
    ): Checked<LeafStockPlan> = when (leaf) {
        is ComponentLeaf.Tracked -> {
            val state = requireNotNull(states[leaf.productId]) {
                "Falta el estado de costo de un componente con stock"
            }
            val lots = lotStocks[leaf.productId] ?: leaf.lots
            when (lots) {
                null -> exitOf(state, leaf.quantity)
                else -> lotExitOf(state, leaf.quantity, lots, chosenLotId = null)
            }.map { exit ->
                // Un componente que vuelve a salir en la misma línea sigue con lo que dejó.
                if (lots != null) {
                    lotStocks[leaf.productId] = LotRules.afterOutflow(lots, exit.lots)
                }
                LeafStockPlan.Tracked(leaf.productId, exit)
            }
        }

        is ComponentLeaf.Untracked ->
            fixedCost(leaf.fixedUnitCost, leaf.quantity).map { lineCost ->
                LeafStockPlan.Untracked(
                    leaf.productId,
                    leaf.quantity,
                    leaf.fixedUnitCost,
                    lineCost,
                )
            }
    }

    /**
     * La salida de [quantity] milésimas desde [state]. Su costo es el de la salida del costo
     * promedio, y no puede pasar del tope de una línea.
     */
    private fun exitOf(state: CostState, quantity: QuantityMilli): Checked<StockExit> {
        // La salida del costo promedio no aplica topes: solo puede rechazar por desbordamiento.
        val cost = WeightedAverageCost.exitCost(state, quantity).valueOrNull()
            ?: return Checked.Overflow
        if (!Limit.DOCUMENT_TOTAL.allows(
                cost.minor,
            )
        ) {
            return Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
        }
        val after = try {
            WeightedAverageCost.exit(state, quantity, cost)
        } catch (overflow: ArithmeticException) {
            // Solo con un saldo negativo tan grande que restarle la cantidad no cabe en Long.
            return Checked.Overflow
        }
        return Checked.Value(StockExit(quantity, state.averageCost, cost, after))
    }

    /**
     * La salida de [quantity] milésimas repartida entre los lotes [lots]: primero el elegido,
     * luego el que vence antes, y lo que falte del lote sin asignar. Cada parte sale en orden con
     * su propio costo de salida, desde el estado que dejó la anterior, y la línea cuesta la suma.
     */
    private fun lotExitOf(
        state: CostState,
        quantity: QuantityMilli,
        lots: List<LotStock>,
        chosenLotId: Long?,
    ): Checked<StockExit> {
        val parts = ArrayList<LotExit>()
        var current = state
        var total = 0L
        for (part in LotRules.splitOutflow(quantity, lots, chosenLotId)) {
            val exit = when (val result = exitOf(current, part.quantity)) {
                is Checked.Value -> result.value
                Checked.Overflow -> return Checked.Overflow
                is Checked.AboveLimit -> return result
            }
            // Cada parte y la suma anterior caben en el tope de una línea: la suma cabe en Long.
            total += exit.cost.minor
            if (!Limit.DOCUMENT_TOTAL.allows(total)) {
                return Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
            }
            parts += LotExit(part.lotId, part.quantity, exit.cost, exit.stateAfter)
            current = exit.stateAfter
        }
        return Checked.Value(StockExit(quantity, state.averageCost, Money(total), current, parts))
    }

    /** El costo fijo por la cantidad, redondeado, dentro del tope de una línea. */
    private fun fixedCost(fixedUnitCost: Money, quantity: QuantityMilli): Checked<Money> =
        fixedUnitCost.timesQuantity(quantity, Limit.DOCUMENT_TOTAL)
}
