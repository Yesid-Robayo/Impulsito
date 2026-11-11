package io.github.yesidrobayo.shopledger.domain.usecase.home

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SalesReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SalesSummaries
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Lo vendido hoy, para Inicio: vuelve a emitir con cada venta, devolución o anulación confirmada,
 * sin recargar la pantalla.
 *
 * Hoy es la fecha de negocio del instante actual en la zona del negocio, nunca la del teléfono. La
 * fecha no cambia sola a medianoche: se vuelve a mirar con cada emisión de `dayChecks`, que la
 * pantalla emite al volver a primer plano. Así las cifras reinician al día siguiente sin un trabajo
 * en segundo plano que despierte la app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTodaySalesUseCase(
    private val calendar: BusinessCalendarSource,
    private val reports: SalesReportRepository,
    private val clock: Clock,
) {
    /**
     * Las ventas de hoy. Cada emisión de [dayChecks] vuelve a calcular qué día es; si sigue siendo
     * el mismo, no se rearma nada.
     */
    operator fun invoke(dayChecks: Flow<Unit>): Flow<TodaySales> = combine(
        calendar.observe(),
        dayChecks,
    ) { result, _ ->
        when (result) {
            is BusinessCalendarResult.Ready ->
                Today(result.calendar, result.calendar.businessDateOf(clock.now()))

            is BusinessCalendarResult.UnknownZone -> null
        }
    }.distinctUntilChanged().flatMapLatest { today ->
        if (today == null) {
            flowOf(TodaySales.UnknownZone)
        } else {
            reports.observeTotals(today.calendar.day(today.date)).map { totals ->
                TodaySales.Ready(
                    date = today.date,
                    zone = today.calendar.zone,
                    summary = SalesSummaries.of(totals).valueOrNull(),
                )
            }
        }
    }.distinctUntilChanged()

    private data class Today(val calendar: BusinessCalendar, val date: BusinessDate)
}

/** Lo vendido hoy, o por qué no se puede saber. */
sealed interface TodaySales {
    /**
     * Las cifras de [date], el día de hoy en [zone]. [summary] es `null` solo si una suma no cabe en
     * `Long`, y entonces la pantalla muestra «sin valor».
     */
    data class Ready(
        val date: BusinessDate,
        val zone: BusinessTimeZone,
        val summary: SalesSummary?,
    ) : TodaySales

    /**
     * Este teléfono no conoce la zona guardada del negocio. Sin zona no se sabe qué día es, y nunca
     * se supone otra: la pantalla no muestra cifras del día.
     */
    data object UnknownZone : TodaySales
}
