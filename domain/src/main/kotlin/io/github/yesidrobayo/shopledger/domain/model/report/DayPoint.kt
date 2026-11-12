package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Un día de la gráfica de un período: lo vendido sin impuesto y la ganancia en ventas, los dos ya
 * con las devoluciones de ese día restadas.
 *
 * La ganancia del día es la de ventas y no la final: los gastos se pagan un día y sirven a todo el
 * período, y restarlos en su día dibujaría pérdidas que no ocurrieron.
 */
data class DayPoint(
    /** El día. */
    val date: BusinessDate,
    /** Ventas sin impuesto, menos lo devuelto ese día. Puede ser negativa. */
    val netSales: Money,
    /** Ventas sin impuesto menos su costo, con las devoluciones restadas. Puede ser negativa. */
    val grossProfit: Money,
)
