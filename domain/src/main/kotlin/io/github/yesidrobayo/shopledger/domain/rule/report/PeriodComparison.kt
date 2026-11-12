package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DAYS_IN_WEEK
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodVariation
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth

/**
 * Contra qué se compara un período y cuánto cambió.
 *
 * Un día se compara con el mismo día de la semana anterior, porque un sábado frente a un viernes
 * dice poco. Un período en curso se compara solo con los mismos días del anterior, para no mostrar
 * una caída falsa a inicio de mes. Un rango elegido por la persona no se compara con nada.
 */
object PeriodComparison {
    /**
     * El período con que se compara [period], si [today] es hoy, o `null` si no se compara.
     *
     * - Día `D`: `D − 7`.
     * - Semana cerrada: la anterior completa. En curso hasta su día `n`: los primeros `n` días de
     *   la anterior.
     * - Mes cerrado: el anterior completo. En curso hasta el día `n`: del 1 al `min(n, último día)`
     *   del anterior, porque el anterior puede ser más corto.
     */
    fun comparableOf(period: ReportPeriod, today: BusinessDate): ReportPeriod? = when (period) {
        is ReportPeriod.Day -> ReportPeriod.Day(period.date.plusDays(-DAYS_IN_WEEK))

        is ReportPeriod.Week -> {
            val previous = ReportPeriod.Week(period.start.plusDays(-DAYS_IN_WEEK))
            if (today in period) {
                ReportPeriod.Range(
                    previous.start,
                    previous.start.plusDays(daysUpTo(period, today) - 1),
                )
            } else {
                previous
            }
        }

        is ReportPeriod.Month -> {
            val previous = ReportPeriod.Month(period.yearMonth.minusMonth())
            if (today in period) {
                val days = minOf(daysUpTo(period, today), lengthOf(previous.yearMonth))
                ReportPeriod.Range(previous.start, previous.start.plusDays(days - 1))
            } else {
                previous
            }
        }

        is ReportPeriod.Range -> null
    }

    /**
     * La variación de una cifra de dinero frente al período anterior, en puntos básicos:
     * `(actual − anterior) × 10000 / |anterior|`, al punto básico más cercano. Sin anterior, o con
     * el anterior en cero, no hay variación.
     */
    fun variation(current: Money, previous: Money): BasisPoints? {
        if (previous == Money.ZERO) return null
        val change =
            try {
                current - previous
            } catch (overflow: ArithmeticException) {
                return null
            }
        val base = if (previous < Money.ZERO) -previous.minor else previous.minor
        return mulDivRound(change.minor, Scale.BASIS_POINTS, base).valueOrNull()?.let(::BasisPoints)
    }

    /**
     * La variación del margen, en puntos porcentuales: el margen actual menos el anterior, nunca un
     * porcentaje de un porcentaje. Si algún lado no tiene margen, no hay variación.
     */
    fun marginChange(current: Margin, previous: Margin): BasisPoints? {
        if (current !is Margin.Defined || previous !is Margin.Defined) return null
        return BasisPoints(current.value.bp - previous.value.bp)
    }

    /** Las variaciones de [current] frente a [previous]. */
    fun of(current: PeriodMetrics, previous: PeriodMetrics): PeriodVariation = PeriodVariation(
        netSales = variation(current.profit.netSales, previous.profit.netSales),
        grossProfit = variation(current.profit.grossProfit, previous.profit.grossProfit),
        netProfit = variation(current.profit.netProfit, previous.profit.netProfit),
        grossMargin = marginChange(current.profit.grossMargin, previous.profit.grossMargin),
    )

    /** Cuántos días de [period] van hasta [today], los dos incluidos. */
    private fun daysUpTo(period: ReportPeriod, today: BusinessDate): Int =
        PeriodNavigation.lengthOf(ReportPeriod.Range(period.start, today))

    private fun lengthOf(month: YearMonth): Int =
        PeriodNavigation.lengthOf(ReportPeriod.Month(month))
}
