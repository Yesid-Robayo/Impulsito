package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * El resumen por categoría de los gastos de un mes: lo gastado en el mes y cada categoría con su
 * parte. Las filas suman [total].
 */
data class ExpenseCategorySummary(val total: Money, val rows: List<ExpenseShareRow>)

/** Una fila del resumen por categoría, con lo gastado y su parte del mes. */
sealed interface ExpenseShareRow {
    val amount: Money

    /** La parte del mes, al punto básico más cercano: 6100 son el 61 %. */
    val share: BasisPoints

    /** Una categoría, con su id para filtrar la lista por ella. */
    data class Category(val total: ExpenseCategoryTotal, override val share: BasisPoints) :
        ExpenseShareRow {
        override val amount: Money get() = total.amount
    }

    /** Las [count] categorías que no caben entre las primeras, juntas en una sola fila. */
    data class Others(
        val count: Int,
        override val amount: Money,
        override val share: BasisPoints,
    ) : ExpenseShareRow
}

/**
 * Arma el resumen por categoría de un mes desde las sumas por categoría que ya calcula la base para
 * las estadísticas: solo gastos activos, sin los anulados, con fecha de negocio en el mes. Así el
 * resumen y el total del mes salen de la misma regla.
 *
 * Las categorías van de mayor a menor y, en empate, por id, para que el orden no salte. Hasta
 * [SHOWN] van una por una; con más, las demás se juntan en una fila [ExpenseShareRow.Others]. Cada
 * parte se redondea por su cuenta, así que las partes pueden sumar 99,99 % o 100,01 %: los montos
 * sí suman el total exacto. No hay `Double`: la parte es un entero de puntos básicos.
 */
object ExpenseCategoryShares {
    /** Cuántas categorías se muestran una por una antes de juntar las demás. */
    const val SHOWN = 5

    /**
     * El resumen de [categories], o `null` si no hay nada que mostrar: el mes no tiene gastos
     * activos, o la suma no cabe en `Long`, y entonces la pantalla no lo muestra.
     */
    fun of(categories: List<ExpenseCategoryTotal>): ExpenseCategorySummary? {
        val spent = categories.filter { it.amount > Money.ZERO }
        if (spent.isEmpty()) return null
        val total = spent.map { it.amount }.sumOrNull() ?: return null
        val ordered =
            spent.sortedWith(
                compareByDescending<ExpenseCategoryTotal> { it.amount }.thenBy { it.categoryId },
            )
        val rows: MutableList<ExpenseShareRow> =
            ordered.take(SHOWN).mapTo(mutableListOf()) { category ->
                ExpenseShareRow.Category(category, shareOf(category.amount, total))
            }
        val rest = ordered.drop(SHOWN)
        if (rest.isNotEmpty()) {
            // Ya cabe: es parte de un total que cupo, así que esta suma tampoco desborda.
            val amount = Money(rest.fold(0L) { sum, it -> Math.addExact(sum, it.amount.minor) })
            rows += ExpenseShareRow.Others(rest.size, amount, shareOf(amount, total))
        }
        return ExpenseCategorySummary(total, rows)
    }

    /**
     * La parte de [total] que es [amount], al punto básico más cercano. [amount] nunca pasa de
     * [total], así que la parte no pasa de 10 000 y la cuenta, en 128 bits, no puede desbordar.
     */
    private fun shareOf(amount: Money, total: Money): BasisPoints = BasisPoints(
        (mulDivRound(amount.minor, Scale.BASIS_POINTS, total.minor) as Checked.Value).value,
    )
}
