package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El punto de equilibrio de un período: cuánto habría que vender, con el margen del período, para
 * cubrir los gastos fijos. Sin margen no se divide: la pantalla muestra «—» y un aviso.
 */
sealed interface BreakEven {
    /**
     * El período tiene ventas, pero ningún gasto de una categoría marcada como fija. La pantalla
     * lo dice y lleva a marcar categorías.
     */
    data object NoFixedExpenses : BreakEven

    /**
     * Hay gastos fijos, pero el margen del período es cero o negativo, o no hubo ventas: ninguna
     * cifra de ventas los cubre con ese margen, y no se divide.
     */
    data class NoMargin(val fixedExpenses: Money) : BreakEven

    /** Una suma o la división no caben en `Long`: la pantalla muestra «sin valor». */
    data object Unavailable : BreakEven

    /**
     * El punto de equilibrio.
     *
     * @property fixedExpenses los gastos fijos del período.
     * @property margin el margen del período, mayor que cero.
     * @property breakEvenSales `roundDiv(fixedExpenses × 10000, margin)`: con 640000 y 3600 bp,
     *   1777778.
     * @property netSales lo vendido en el período sin impuesto, menos lo devuelto.
     * @property remaining lo que falta vender para llegar, o cero si ya se llegó.
     */
    data class Calculated(
        val fixedExpenses: Money,
        val margin: BasisPoints,
        val breakEvenSales: Money,
        val netSales: Money,
        val remaining: Money,
    ) : BreakEven
}
