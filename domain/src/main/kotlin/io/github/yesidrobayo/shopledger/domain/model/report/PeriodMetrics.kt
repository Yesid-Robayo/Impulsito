package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary

/**
 * Las métricas de un período que muestran las estadísticas, calculadas desde sus [PeriodTotals].
 *
 * Un período sin datos vale cero en cada cifra de dinero y no tiene margen ni ticket promedio: la
 * pantalla muestra «—» en esos dos, nunca un 0 % que parecería medido. Con gastos y sin ventas, la
 * ganancia final es negativa y se muestra así.
 */
data class PeriodMetrics(
    /** Ventas, costo, ganancia en ventas, margen, gastos y ganancia final. */
    val profit: ProfitSummary,
    /** Cuántas ventas activas hubo. */
    val salesCount: Long,
    /**
     * Lo vendido sin impuesto, menos lo devuelto, dividido por el número de ventas, redondeado a la
     * unidad menor. `null` si no hubo ventas: sin ventas no hay promedio.
     */
    val averageTicket: Money?,
    /**
     * Si el período tuvo algún movimiento que cuente: una venta, una devolución, un gasto, un
     * ajuste de stock o una comisión. Sin ninguno, la pantalla explica que no hubo movimientos, para
     * que los ceros no parezcan un error.
     */
    val hasMovements: Boolean,
)
