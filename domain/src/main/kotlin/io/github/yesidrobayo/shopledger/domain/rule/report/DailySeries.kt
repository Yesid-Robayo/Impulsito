package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayExtremes
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.DayTotals

/**
 * La serie por día de un período, para la gráfica y su lista: un punto por cada día, del primero al
 * último, y cero en los días sin documentos, porque un día sin ventas vale cero y no es un hueco.
 */
object DailySeries {
    /**
     * Los puntos de cada día de [period], con las sumas de [days]. Un día de [days] fuera del
     * período no entra. Devuelve [Checked.Overflow] si una resta no cabe en `Long`: solo pasa con
     * sumas absurdas, y la gráfica no se dibuja en vez de caerse.
     */
    fun of(period: ReportPeriod, days: List<DayTotals>): Checked<List<DayPoint>> = try {
        val byDate = days.associateBy { it.date }
        Checked.Value(
            datesOf(period).map { date ->
                val day = byDate[date]
                if (day == null) {
                    DayPoint(date, Money.ZERO, Money.ZERO)
                } else {
                    val profit = day.totals.profit
                    // Lo mismo que la ganancia en ventas del período, día por día: un gasto
                    // pagado un martes no hunde las ventas de ese martes.
                    val netSales = profit.salesNetTotal - profit.returnsNetRefund
                    val cogs = profit.salesTotalCost - profit.returnsCostReturned
                    DayPoint(date, netSales, netSales - cogs)
                }
            },
        )
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }

    /**
     * El día mayor y el menor de [points] según [value], solo entre los días hasta [today]
     * incluido, en la zona del negocio. Un día que todavía no llega vale cero porque no pasó, no
     * porque fuera flojo; y con la hora del teléfono corrida puede tener registros que no cuentan
     * como el mejor día. Devuelve `null` si ningún día del período llegó todavía.
     */
    fun extremesOf(
        points: List<DayPoint>,
        today: BusinessDate,
        value: (DayPoint) -> Money,
    ): DayExtremes? {
        val past = points.filter { it.date <= today }
        if (past.isEmpty()) return null
        return DayExtremes(highest = past.maxBy(value), lowest = past.minBy(value))
    }

    /** Cada fecha de [period], de la primera a la última. */
    fun datesOf(period: ReportPeriod): List<BusinessDate> =
        generateSequence(period.start) { date -> date.plusDays(1).takeIf { it <= period.end } }
            .toList()
}
