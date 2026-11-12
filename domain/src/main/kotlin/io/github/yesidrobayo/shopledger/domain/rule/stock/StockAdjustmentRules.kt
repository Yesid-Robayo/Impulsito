package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost

/**
 * Qué deja un ajuste de stock y cuánto vale.
 *
 * Un ajuste corrige el libro cuando la realidad es otra: nunca edita una fila, siempre agrega una.
 * Su valor sale del costo vigente, no de lo que la persona escribe, porque la mercancía que aparece
 * o desaparece no trae factura. Ese valor es la merma, que resta de la ganancia neta.
 */
object StockAdjustmentRules {
    /**
     * Cuánto cambia el stock con un conteo: lo contado [counted] menos lo que dice el libro
     * [onHand]. Cero es que no hay diferencias, y entonces no se crea ninguna fila.
     */
    fun countDifference(onHand: QuantityMilli, counted: QuantityMilli): QuantityMilli =
        counted - onHand

    /**
     * Revisa la cantidad contada: puede ser cero, porque se acabó, pero nunca negativa, y en una
     * unidad sin fracciones va en unidades enteras.
     */
    fun checkCount(counted: QuantityMilli, allowsFraction: Boolean): UnitQuantityCheck = when {
        counted.milli < 0L -> UnitQuantityCheck.NotPositive
        !allowsFraction && !counted.isWholeUnits -> UnitQuantityCheck.FractionNotAllowed
        else -> UnitQuantityCheck.Valid
    }

    /**
     * El valor del ajuste que cambia [state] en [change] milésimas, con su signo, y cómo queda el
     * costo del producto después.
     *
     * Uno negativo sale como una venta: se lleva su parte del valor del stock, o el costo promedio
     * por lo que sale de más, y el promedio no cambia. Uno positivo entra al costo vigente, sin
     * mover el promedio. El total lleva el signo de la cantidad.
     *
     * Un rechazo dice que la cuenta no cabe en `Long`, o que el valor del stock pasaría su tope.
     * Lanza [IllegalArgumentException] con [change] en cero: un ajuste siempre mueve algo.
     */
    fun value(state: CostState, change: QuantityMilli): Checked<AdjustmentValue> {
        require(change != QuantityMilli.ZERO) { "Un ajuste siempre mueve algo" }
        return if (change.milli < 0L) decrease(state, -change) else increase(state, change)
    }

    /**
     * Si una fila de tipo [type] se anula desde el historial del producto: un ajuste o un
     * inventario inicial, que la persona escribió a mano y pudo escribir mal. Lo demás se anula
     * desde su documento, o no se anula.
     */
    fun isVoidable(type: StockMovementType): Boolean = when (type) {
        StockMovementType.ADJUSTMENT, StockMovementType.OPENING_BALANCE -> true

        StockMovementType.PURCHASE, StockMovementType.SALE, StockMovementType.COST_CORRECTION,
        StockMovementType.PURCHASE_VOID, StockMovementType.SALE_VOID,
        StockMovementType.ADJUSTMENT_VOID, StockMovementType.SALE_RETURN,
        StockMovementType.SALE_RETURN_VOID, StockMovementType.PURCHASE_RETURN,
        StockMovementType.PURCHASE_RETURN_VOID, StockMovementType.PRODUCTION_OUT,
        StockMovementType.PRODUCTION_IN, StockMovementType.PRODUCTION_VOID,
        StockMovementType.TRANSFER_OUT, StockMovementType.TRANSFER_IN,
        StockMovementType.TRANSFER_VOID,
        -> false
    }

    // Las cuentas del costo no aplican topes, así que su único rechazo es no caber en Long.
    private fun decrease(state: CostState, quantity: QuantityMilli): Checked<AdjustmentValue> {
        val cost = WeightedAverageCost.exitCost(state, quantity).valueOrNull()
            ?: return Checked.Overflow
        val after = WeightedAverageCost.exit(state, quantity, cost)
        return Checked.Value(AdjustmentValue(totalCost = -cost, after = after))
    }

    private fun increase(state: CostState, quantity: QuantityMilli): Checked<AdjustmentValue> {
        val total = WeightedAverageCost.positiveAdjustmentCost(state, quantity).valueOrNull()
            ?: return Checked.Overflow
        val after = WeightedAverageCost.positiveAdjustment(state, quantity, total).valueOrNull()
            ?: return Checked.Overflow
        return if (Limit.STOCK_VALUE.allows(after.stockValue.minor)) {
            Checked.Value(AdjustmentValue(total, after))
        } else {
            Checked.AboveLimit(Limit.STOCK_VALUE)
        }
    }
}

/**
 * Lo que deja un ajuste: [totalCost], su valor con el signo de la cantidad, y [after], cómo queda
 * el costo del producto. Un valor negativo es merma; uno positivo, un sobrante.
 */
data class AdjustmentValue(val totalCost: Money, val after: CostState)
