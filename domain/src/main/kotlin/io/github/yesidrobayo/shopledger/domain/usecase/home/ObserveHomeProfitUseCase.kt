package io.github.yesidrobayo.shopledger.domain.usecase.home

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodComparisonUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * La ganancia final de esta semana y de este mes, para Inicio: lo vendido sin impuesto, menos su
 * costo, menos los gastos, los daños y pérdidas y el costo de cobrar.
 *
 * Sale de la misma regla que las estadísticas, con el mismo rango de fechas, así que la cifra del
 * mes en Inicio es la misma que la del mes en estadísticas. La semana empieza el día que eligió el
 * negocio. Como el día de hoy, se vuelve a mirar qué semana y qué mes son con cada emisión de
 * `dayChecks`, que la pantalla emite al volver a primer plano. No escribe nada.
 *
 * Con [comparison], cada cifra trae también su variación frente a los mismos días de la semana y
 * del mes anteriores, como en las estadísticas: a inicio de mes no se muestra una caída falsa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveHomeProfitUseCase(
    private val calendar: BusinessCalendarSource,
    private val reports: ObservePeriodReportUseCase,
    private val clock: Clock,
    private val comparison: ObservePeriodComparisonUseCase? = null,
) {
    operator fun invoke(dayChecks: Flow<Unit>): Flow<HomeProfit?> = combine(
        calendar.observe(),
        dayChecks,
    ) { result, _ ->
        when (result) {
            is BusinessCalendarResult.Ready -> {
                val today = result.calendar.businessDateOf(clock.now())
                Periods(today, result.calendar.week(today), result.calendar.month(today))
            }

            // Sin zona no se sabe qué día es: no hay semana ni mes que mostrar.
            is BusinessCalendarResult.UnknownZone -> null
        }
    }.distinctUntilChanged().flatMapLatest { periods ->
        if (periods == null) {
            flowOf(null)
        } else {
            combine(
                reports(periods.week),
                reports(periods.month),
                changeOf(periods.week, periods.today),
                changeOf(periods.month, periods.today),
            ) { week, month, weekChange, monthChange ->
                HomeProfit(
                    week = periods.week,
                    weekProfit = week.metrics?.profit?.netProfit,
                    month = periods.month,
                    monthProfit = month.metrics?.profit?.netProfit,
                    weekChange = weekChange,
                    monthChange = monthChange,
                )
            }
        }
    }.distinctUntilChanged()

    private fun changeOf(period: ReportPeriod, today: BusinessDate): Flow<ComparedPeriod?> =
        comparison?.invoke(period, today) ?: flowOf(null)

    private data class Periods(
        val today: BusinessDate,
        val week: ReportPeriod.Week,
        val month: ReportPeriod.Month,
    )
}

/**
 * La ganancia final de [week] y de [month], los dos en curso. Una cifra es `null` solo si una suma
 * no cabe en `Long`, y entonces la pantalla muestra «sin valor». Con gastos y sin ventas es
 * negativa, y se muestra así. [weekChange] y [monthChange] comparan cada una con los mismos días
 * del período anterior, o son `null` si no hay con qué.
 */
data class HomeProfit(
    val week: ReportPeriod.Week,
    val weekProfit: Money?,
    val month: ReportPeriod.Month,
    val monthProfit: Money?,
    val weekChange: ComparedPeriod? = null,
    val monthChange: ComparedPeriod? = null,
)
