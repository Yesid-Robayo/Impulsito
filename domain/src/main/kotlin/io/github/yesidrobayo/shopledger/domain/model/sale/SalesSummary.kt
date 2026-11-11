package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo vendido en un período y lo que dejó, antes de gastos: lo que Inicio muestra del día.
 *
 * Todo va sin impuesto, porque el impuesto no es de quien vende. La ganancia final, que resta los
 * gastos, no está aquí: sale del reporte completo del período.
 */
data class SalesSummary(
    /** Ventas sin impuesto, menos lo reembolsado en devoluciones. */
    val netSales: Money,
    /** Cuántas ventas activas hubo. */
    val salesCount: Long,
    /** Ganancia en ventas: [netSales] menos el costo de lo vendido. */
    val grossProfit: Money,
    /** Margen del período, calculado con las sumas: nunca es el promedio de los márgenes. */
    val grossMargin: Margin,
)
