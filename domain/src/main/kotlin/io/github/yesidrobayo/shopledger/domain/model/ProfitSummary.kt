package io.github.yesidrobayo.shopledger.domain.model

/**
 * La ganancia de un período, calculada desde sus [ProfitTotals].
 *
 * Ganancia en ventas es lo vendido sin impuesto menos lo que costó, y ganancia final es eso menos
 * los gastos. Los gastos incluyen los daños y pérdidas y el costo de cobrar, porque una unidad que
 * se pierde nunca se vende, y sin contarla aquí su costo no aparecería en ningún reporte.
 */
data class ProfitSummary(
    /** Ventas sin impuesto, menos lo reembolsado en devoluciones. */
    val netSales: Money,
    /** Costo de lo vendido, menos el costo de lo devuelto. */
    val cogs: Money,
    /** Ganancia en ventas: [netSales] menos [cogs]. */
    val grossProfit: Money,
    /** Margen del período, calculado con las sumas: nunca es el promedio de los márgenes. */
    val grossMargin: Margin,
    /** Gastos registrados en el período. */
    val recordedExpenses: Money,
    /** Daños y pérdidas del período, negativa si hubo más sobrantes que pérdidas. */
    val netShrinkage: Money,
    /** Costo de cobrar: comisiones menos el redondeo de efectivo. */
    val paymentCosts: Money,
    /** Gastos registrados, más daños y pérdidas, más costo de cobrar. */
    val totalExpenses: Money,
    /** Ganancia final: [grossProfit] menos [totalExpenses]. */
    val netProfit: Money,
)
