package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlinx.datetime.YearMonth

/**
 * Las metas que la persona escribió para un mes: cuánto quiere vender y cuánto quiere que quede de
 * ganancia final. Cada una es opcional, y un mes sin ninguna no tiene fila: no se muestra nada.
 *
 * Las metas son de la persona: la app no propone ni calcula ninguna.
 */
data class MonthGoals(val month: YearMonth, val sales: Money?, val profit: Money?)

/**
 * Cuánto se lleva de una meta.
 *
 * @property actual lo logrado en el mes, o `null` si una suma del mes no cabe en `Long`: entonces
 *   la pantalla muestra «sin valor» y no un avance inventado.
 * @property goal la meta, siempre mayor que cero.
 * @property progress `actual × 10000 / meta` en puntos básicos, redondeado: con 850000 frente a
 *   1000000, 8500, que se lee 85,0 %. Puede pasar de 10000 si se superó la meta, y ser negativo si
 *   la ganancia final es una pérdida. `null` sin lo logrado o si la cuenta no cabe.
 */
data class GoalAdvance(val actual: Money?, val goal: Money, val progress: BasisPoints?) {
    /** Si lo logrado ya llegó a la meta. Sin lo logrado, no se sabe y no se celebra nada. */
    val reached: Boolean get() = actual != null && actual >= goal
}
