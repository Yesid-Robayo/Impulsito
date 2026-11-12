package io.github.yesidrobayo.shopledger.domain.model

import kotlinx.datetime.YearMonth

/** Días de una semana de reporte. */
internal const val DAYS_IN_WEEK: Int = 7

/**
 * Período de un reporte: un rango de fechas de negocio que incluye sus dos extremos.
 *
 * Cada reporte agrupa por el rango de su propio período. Una semana puede partir dos meses o dos
 * años, y un mes nunca se arma sumando semanas.
 */
sealed interface ReportPeriod {
    /** Primer día incluido. */
    val start: BusinessDate

    /** Último día incluido. */
    val end: BusinessDate

    /** Si [date] cae dentro del período, con sus dos extremos incluidos. */
    operator fun contains(date: BusinessDate): Boolean = date >= start && date <= end

    /** Un solo día, que se identifica por su fecha. */
    data class Day(val date: BusinessDate) : ReportPeriod {
        override val start: BusinessDate get() = date
        override val end: BusinessDate get() = date
    }

    /**
     * Siete días seguidos desde [start], que identifica a la semana.
     *
     * No se usa un número de semana porque su numeración cambia de reglas en fin de año, y el día
     * en que empieza lo elige el negocio.
     */
    data class Week(override val start: BusinessDate) : ReportPeriod {
        override val end: BusinessDate get() = start.plusDays(DAYS_IN_WEEK - 1)
    }

    /**
     * Un mes del calendario, del día 1 al último, que se identifica por año y mes (`AAAA-MM`).
     *
     * Dura de 28 a 31 días: nunca se suponen 30.
     */
    data class Month(val yearMonth: YearMonth) : ReportPeriod {
        override val start: BusinessDate get() = BusinessDate(yearMonth.firstDay)
        override val end: BusinessDate get() = BusinessDate(yearMonth.lastDay)
    }

    /**
     * Un rango cualquiera, de [start] a [end], los dos incluidos: el que elige la persona, o los
     * primeros días de un período para compararlo con el que está en curso.
     */
    data class Range(override val start: BusinessDate, override val end: BusinessDate) :
        ReportPeriod {
        init {
            require(start <= end) { "Un rango empieza antes de terminar" }
        }
    }
}
