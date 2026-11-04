package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.DAYS_IN_WEEK
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.yearMonth

/**
 * Día, semana y mes que contienen una fecha de negocio, y los instantes en que empiezan y terminan.
 *
 * El día en que empieza la semana y la zona del negocio llegan siempre por parámetro: son datos del
 * negocio, y ninguno se deduce del idioma, la región o la zona del teléfono.
 */
object ReportPeriods {
    /** El día de [date]. */
    fun day(date: BusinessDate): ReportPeriod.Day = ReportPeriod.Day(date)

    /** La semana que contiene [date], si las semanas del negocio empiezan en [weekStartDay]. */
    fun week(date: BusinessDate, weekStartDay: DayOfWeek): ReportPeriod.Week =
        ReportPeriod.Week(weekStart(date, weekStartDay))

    /** El mes del calendario que contiene [date]. */
    fun month(date: BusinessDate): ReportPeriod.Month = ReportPeriod.Month(date.date.yearMonth)

    /**
     * El primer día de la semana que contiene [date]: el [weekStartDay] más reciente, o la misma
     * fecha si ya cae en él. Con la numeración ISO, de 1 el lunes a 7 el domingo, retrocede
     * `(día de date − weekStartDay + 7) mod 7` días, que siempre da de 0 a 6.
     */
    fun weekStart(date: BusinessDate, weekStartDay: DayOfWeek): BusinessDate {
        val daysBack =
            (date.date.dayOfWeek.isoDayNumber - weekStartDay.isoDayNumber + DAYS_IN_WEEK) %
                DAYS_IN_WEEK
        return date.plusDays(-daysBack)
    }

    /** El primer instante de [period] en la zona del negocio [zone]. */
    fun startInstant(period: ReportPeriod, zone: BusinessTimeZone): Instant =
        period.start.date.atStartOfDayIn(zone.timeZone)

    /**
     * El instante en que termina [period], que ya no le pertenece: el comienzo del día siguiente a
     * su último día, en la zona del negocio [zone].
     *
     * Se pide el comienzo de ese día, y nunca se suman 24 horas, porque con el horario de verano un
     * día puede durar 23 o 25 horas, y en algunas zonas ciertos días empiezan a la 01:00 porque esa
     * medianoche no existe.
     */
    fun endInstantExclusive(period: ReportPeriod, zone: BusinessTimeZone): Instant =
        period.end.plusDays(1).date.atStartOfDayIn(zone.timeZone)
}
