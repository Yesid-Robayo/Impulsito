package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * Qué hace cada modo de stock con el libro y con el costo de una venta.
 *
 * Solo un producto con stock propio tiene libro. Un servicio no tiene existencias: vender no escribe
 * ninguna fila, su saldo es siempre cero y su costo es un costo fijo por unidad, que la persona
 * escribe porque no hay compras de dónde sacarlo. Un combo tampoco tiene libro: si lo tuviera,
 * habría dos verdades para el mismo stock. Al venderse descuenta cada componente con stock, y su
 * costo es la suma de sus componentes.
 *
 * El modo se copia en la línea como su efecto de stock, para que la línea diga siempre qué filas le
 * corresponden, y para anular la venta igual aunque el producto cambie después.
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
     * El saldo que debe guardar un producto en modo [mode] cuyo libro suma [ledgerSum]: la suma, si
     * tiene libro, y cero si no.
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
        is LineStockSubject.Tracked ->
            exitOf(subject.costState, subject.quantity).map(LineStockPlan::Direct)

        is LineStockSubject.Untracked ->
            fixedCost(subject.fixedUnitCost, subject.quantity).map { lineCost ->
                LineStockPlan.NoStock(unitCost = subject.fixedUnitCost, lineCost = lineCost)
            }

        is LineStockSubject.Components -> planComponents(subject)
    }

    private fun planComponents(subject: LineStockSubject.Components): Checked<LineStockPlan> {
        val states = LinkedHashMap(subject.costStates)
        val leaves = ArrayList<LeafStockPlan>(subject.leaves.size)
        var lineCost = 0L
        for (leaf in subject.leaves) {
            val planned = when (val result = planLeaf(leaf, states)) {
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
    ): Checked<LeafStockPlan> = when (leaf) {
        is ComponentLeaf.Tracked -> {
            val state = requireNotNull(states[leaf.productId]) {
                "Falta el estado de costo de un componente con stock"
            }
            exitOf(state, leaf.quantity).map { exit ->
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

    /** El costo fijo por la cantidad, redondeado, dentro del tope de una línea. */
    private fun fixedCost(fixedUnitCost: Money, quantity: QuantityMilli): Checked<Money> =
        fixedUnitCost.timesQuantity(quantity, Limit.DOCUMENT_TOTAL)
}
