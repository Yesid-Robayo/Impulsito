package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DAYS_IN_WEEK
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth

/**
 * Cómo se recorren los períodos de las estadísticas: el anterior y el siguiente, sin límite hacia
 * atrás y, hacia adelante, hasta el período de hoy o el del último dato.
 *
 * Nada se purga por antigüedad, así que se puede volver años atrás. Hacia adelante, el período en
 * curso es el último, salvo que haya datos con fecha posterior a hoy: pasa cuando el reloj del
 * teléfono estuvo adelantado, y esos datos deben poder verse.
 */
object PeriodNavigation {
    /**
     * La primera fecha que la base puede guardar: el año 0000. Antes no hay datos, y no se navega
     * más atrás.
     */
    val EARLIEST_DATE: BusinessDate = BusinessDate(LocalDate(0, 1, 1))

    /** El período de tipo [kind] que contiene [date], con las semanas de [calendar]. */
    fun containing(kind: PeriodKind, date: BusinessDate, calendar: BusinessCalendar): ReportPeriod =
        when (kind) {
            PeriodKind.DAY -> calendar.day(date)
            PeriodKind.WEEK -> calendar.week(date)
            PeriodKind.MONTH -> calendar.month(date)
        }

    /** El tipo de [period]. */
    fun kindOf(period: ReportPeriod): PeriodKind = when (period) {
        is ReportPeriod.Day -> PeriodKind.DAY
        is ReportPeriod.Week -> PeriodKind.WEEK
        is ReportPeriod.Month -> PeriodKind.MONTH
    }

    /** El período del mismo tipo justo antes de [period]. */
    fun previous(period: ReportPeriod): ReportPeriod = when (period) {
        is ReportPeriod.Day -> ReportPeriod.Day(period.date.plusDays(-1))
        is ReportPeriod.Week -> ReportPeriod.Week(period.start.plusDays(-DAYS_IN_WEEK))
        is ReportPeriod.Month -> ReportPeriod.Month(period.yearMonth.minusMonth())
    }

    /** El período del mismo tipo justo después de [period]. */
    fun next(period: ReportPeriod): ReportPeriod = when (period) {
        is ReportPeriod.Day -> ReportPeriod.Day(period.date.plusDays(1))
        is ReportPeriod.Week -> ReportPeriod.Week(period.start.plusDays(DAYS_IN_WEEK))
        is ReportPeriod.Month -> ReportPeriod.Month(period.yearMonth.plusMonth())
    }

    /**
     * Hasta qué fecha se puede avanzar: hoy, o la última fecha con datos si es posterior, porque
     * un reloj adelantado pudo dejar registros en el futuro.
     */
    fun forwardLimit(today: BusinessDate, lastDataDate: BusinessDate?): BusinessDate =
        if (lastDataDate != null && lastDataDate > today) lastDataDate else today

    /** Si se puede ir al período siguiente: empieza en [limit] o antes. */
    fun hasNext(period: ReportPeriod, limit: BusinessDate): Boolean = next(period).start <= limit

    /** Si se puede ir al anterior: queda algún día que la base pueda guardar antes de [period]. */
    fun hasPrevious(period: ReportPeriod): Boolean = period.start > EARLIEST_DATE

    /** Si [period] es el que contiene [today]: el período en curso. */
    fun isCurrent(period: ReportPeriod, today: BusinessDate): Boolean = today in period
}
