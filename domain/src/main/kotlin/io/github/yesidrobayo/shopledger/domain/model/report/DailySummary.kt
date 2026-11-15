package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlinx.datetime.LocalTime

/**
 * El resumen del día para la notificación local: las mismas cifras de `Números` para ese día.
 *
 * @param salesCount cuántas ventas activas hubo.
 * @param netSales lo vendido sin impuesto, con lo devuelto restado.
 * @param grossProfit la ganancia en ventas.
 * @param discreet si el modo discreto está activo: entonces la notificación no lleva montos ni
 *   siquiera con el teléfono desbloqueado.
 */
data class DailySummary(
    val date: BusinessDate,
    val salesCount: Long,
    val netSales: Money,
    val grossProfit: Money,
    val discreet: Boolean,
)

/** Cuándo sale el resumen: a [time] cada día, en la zona del negocio. */
data class DailySummarySchedule(val time: LocalTime, val zone: BusinessTimeZone)
