package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Las metas del mes: qué meta se puede guardar y cuánto se lleva de cada una.
 *
 * El avance se calcula en enteros, como `roundDiv(actual × 10000, meta)`, con el producto en 128
 * bits para que una venta enorme no desborde: nunca pasa por un número con decimales binarios.
 */
object GoalRules {
    /** Lo más que admite una meta, el mismo tope que la base: `MAX_STOCK_VALUE`. */
    val MAX_GOAL: Money = Money(Limit.STOCK_VALUE.max)

    /**
     * El avance de [actual] frente a [goal]. Una meta en cero o negativa, que la app no deja
     * escribir pero podría llegar en una copia, no divide: el avance queda sin valor.
     */
    fun progress(actual: Money?, goal: Money): GoalAdvance {
        val progress =
            if (actual == null || goal <= Money.ZERO) {
                null
            } else {
                mulDivRound(actual.minor, Scale.BASIS_POINTS, goal.minor)
                    .valueOrNull()
                    ?.let(::BasisPoints)
            }
        return GoalAdvance(actual, goal, progress)
    }

    /**
     * Si [goal] se puede guardar como meta: mayor que cero y sin pasar del tope. Una meta en cero
     * no mide nada, y una negativa no se escribe en el formulario.
     */
    fun isValid(goal: Money): Boolean = goal > Money.ZERO && goal <= MAX_GOAL
}
