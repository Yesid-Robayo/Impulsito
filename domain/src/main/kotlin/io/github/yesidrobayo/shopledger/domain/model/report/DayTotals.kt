package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate

/**
 * Las sumas de un solo día dentro de un período, para la gráfica por día.
 *
 * Solo existen los días con algún documento: quien dibuja completa con cero los que faltan, porque
 * un día sin ventas vale cero y no es un hueco.
 */
data class DayTotals(
    /** El día, como fecha de negocio. */
    val date: BusinessDate,
    /** Lo que sumaron los documentos activos de ese día. */
    val totals: PeriodTotals,
)
