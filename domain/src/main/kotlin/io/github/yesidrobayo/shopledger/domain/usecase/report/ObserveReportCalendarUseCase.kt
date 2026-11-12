package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodNavigation
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lo que las estadísticas necesitan para elegir y recorrer períodos: el calendario del negocio, qué
 * día es hoy y hasta dónde se puede avanzar.
 *
 * Hoy es la fecha de negocio del instante actual en la zona del negocio, nunca la del teléfono. Se
 * vuelve a mirar con cada emisión de `dayChecks`, que la pantalla emite al volver a primer plano: a
 * medianoche el período en curso cambia sin un trabajo en segundo plano. El límite hacia adelante
 * es hoy, o la última fecha con ventas o gastos si es posterior, porque un reloj adelantado pudo
 * dejar datos en el futuro y deben poder verse.
 */
class ObserveReportCalendarUseCase(
    private val calendar: BusinessCalendarSource,
    private val reports: PeriodReportRepository,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit>): Flow<ReportCalendar> = combine(
        calendar.observe(),
        reports.observeLastActiveDate(),
        dayChecks,
    ) { result, lastDataDate, _ ->
        when (result) {
            is BusinessCalendarResult.Ready -> {
                val today = result.calendar.businessDateOf(clock.now())
                ReportCalendar.Ready(
                    calendar = result.calendar,
                    today = today,
                    forwardLimit = PeriodNavigation.forwardLimit(today, lastDataDate),
                )
            }

            is BusinessCalendarResult.UnknownZone -> ReportCalendar.UnknownZone
        }
    }.distinctUntilChanged()
}

/** El calendario de las estadísticas, o por qué no se puede saber qué día es. */
sealed interface ReportCalendar {
    /**
     * La zona y las semanas del negocio en [calendar], el día de hoy en esa zona, y la última fecha
     * a la que se puede avanzar, [forwardLimit], que nunca es anterior a hoy.
     */
    data class Ready(
        val calendar: BusinessCalendar,
        val today: BusinessDate,
        val forwardLimit: BusinessDate,
    ) : ReportCalendar

    /**
     * Este teléfono no conoce la zona guardada del negocio. Sin zona no se sabe qué día es, y nunca
     * se supone otra: la pantalla pide elegirla.
     */
    data object UnknownZone : ReportCalendar
}
