package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/**
 * Las fechas de un gasto recurrente. Sin estado y sin reloj: todo sale de la fecha que se le da.
 *
 * - **Cada semana**, 7 días después.
 * - **Cada mes**, el mes siguiente en el día elegido, o el último del mes si es más corto: el
 *   día 31 da 2027-01-31, 2027-02-28 y 2027-03-31.
 * - **Cada año**, el año siguiente en el mismo mes y día, o el último del mes: el 29 de febrero da
 *   2028-02-29, 2029-02-28 y de vuelta 2032-02-29.
 *
 * La fecha siguiente se cuenta desde el vencimiento, nunca desde el día en que se confirma: un
 * alquiler del día 5 confirmado el 9 sigue el 5 del mes siguiente.
 */
object RecurrenceRules {
    /** Los días de una semana: el día va de 1, lunes, a 7, domingo. */
    const val DAYS_IN_WEEK: Int = 7

    /** El día más alto de un mes. */
    const val MAX_DAY_OF_MONTH: Int = 31

    /** Los días de un año bisiesto, sobre el que se numeran los días del año. */
    const val DAYS_IN_LEAP_YEAR: Int = 366

    private const val FEBRUARY = 2
    private const val DECEMBER = 12
    private const val SHORT_MONTH_DAYS = 30
    private const val LEAP_FEBRUARY_DAYS = 29
    private const val FEBRUARY_DAYS = 28
    private val SHORT_MONTHS = setOf(4, 6, 9, 11)

    /** El día del año, sobre un año bisiesto, en que empieza cada mes, menos uno. */
    private val LEAP_YEAR_MONTH_STARTS =
        intArrayOf(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)

    /** El día más alto que admite [recurrence]. */
    fun maxDay(recurrence: Recurrence): Int = when (recurrence) {
        Recurrence.WEEKLY -> DAYS_IN_WEEK
        Recurrence.MONTHLY -> MAX_DAY_OF_MONTH
        Recurrence.YEARLY -> DAYS_IN_LEAP_YEAR
    }

    /** Si [day] existe en [recurrence]. */
    fun isValidDay(recurrence: Recurrence, day: Int): Boolean = day in 1..maxDay(recurrence)

    /** El vencimiento que sigue a [due], para el día [day] de [recurrence]. */
    fun next(recurrence: Recurrence, day: Int, due: BusinessDate): BusinessDate {
        require(isValidDay(recurrence, day)) { "El día no existe en la repetición" }
        val date = due.date
        return when (recurrence) {
            Recurrence.WEEKLY -> due.plusDays(DAYS_IN_WEEK)

            Recurrence.MONTHLY -> if (date.month.number == DECEMBER) {
                clamped(date.year + 1, 1, day)
            } else {
                clamped(date.year, date.month.number + 1, day)
            }

            Recurrence.YEARLY -> {
                val (month, dayOfMonth) = monthAndDayOf(day)
                clamped(date.year + 1, month, dayOfMonth)
            }
        }
    }

    /** Si [date] es un vencimiento del día [day] de [recurrence]. */
    fun matches(recurrence: Recurrence, day: Int, date: BusinessDate): Boolean {
        if (!isValidDay(recurrence, day)) return false
        val local = date.date
        return when (recurrence) {
            Recurrence.WEEKLY -> local.dayOfWeek.isoDayNumber == day

            Recurrence.MONTHLY -> clamped(local.year, local.month.number, day) == date

            Recurrence.YEARLY -> {
                val (month, dayOfMonth) = monthAndDayOf(day)
                clamped(local.year, month, dayOfMonth) == date
            }
        }
    }

    /** El primer vencimiento del día [day] de [recurrence] que cae en [from] o después. */
    fun firstOnOrAfter(recurrence: Recurrence, day: Int, from: BusinessDate): BusinessDate {
        require(isValidDay(recurrence, day)) { "El día no existe en la repetición" }
        val date = from.date
        val candidate = when (recurrence) {
            Recurrence.WEEKLY -> {
                val ahead = (day - date.dayOfWeek.isoDayNumber + DAYS_IN_WEEK) % DAYS_IN_WEEK
                from.plusDays(ahead)
            }

            Recurrence.MONTHLY -> clamped(date.year, date.month.number, day)

            Recurrence.YEARLY -> {
                val (month, dayOfMonth) = monthAndDayOf(day)
                clamped(date.year, month, dayOfMonth)
            }
        }
        return if (candidate >= from) candidate else next(recurrence, day, candidate)
    }

    /**
     * El día de [recurrence] en que cae [date]. Si [current] ya da esa fecha, se conserva: el día
     * 31 sigue siendo 31 aunque se elija el 28 de febrero, porque ese mes es su último día.
     */
    fun dayOf(recurrence: Recurrence, date: BusinessDate, current: Int? = null): Int {
        if (current != null && matches(recurrence, current, date)) return current
        val local = date.date
        return when (recurrence) {
            Recurrence.WEEKLY -> local.dayOfWeek.isoDayNumber
            Recurrence.MONTHLY -> local.day
            Recurrence.YEARLY -> yearDayOf(local.month.number, local.day)
        }
    }

    /**
     * Cuántos vencimientos hay desde [from], incluido, hasta [until], incluido, si cada uno sigue
     * al anterior. Cero si [from] es posterior a [until].
     */
    fun occurrencesThrough(
        recurrence: Recurrence,
        day: Int,
        from: BusinessDate,
        until: BusinessDate,
    ): Int {
        var count = 0
        var due = from
        while (due <= until) {
            count++
            due = next(recurrence, day, due)
        }
        return count
    }

    /** El número del día de [month] y [dayOfMonth] en un año bisiesto: el 29 de febrero es 60. */
    fun yearDayOf(month: Int, dayOfMonth: Int): Int = LEAP_YEAR_MONTH_STARTS[month - 1] + dayOfMonth

    /** El mes y el día del número [yearDay] de un año bisiesto: 60 es el 29 de febrero. */
    fun monthAndDayOf(yearDay: Int): Pair<Int, Int> {
        require(yearDay in 1..DAYS_IN_LEAP_YEAR) { "El día del año no existe" }
        val index = LEAP_YEAR_MONTH_STARTS.indexOfLast { it < yearDay }
        return (index + 1) to (yearDay - LEAP_YEAR_MONTH_STARTS[index])
    }

    /** Cuántos días tiene [month] de [year]. */
    fun daysInMonth(year: Int, month: Int): Int = when {
        month == FEBRUARY -> if (isLeapYear(year)) LEAP_FEBRUARY_DAYS else FEBRUARY_DAYS
        month in SHORT_MONTHS -> SHORT_MONTH_DAYS
        else -> MAX_DAY_OF_MONTH
    }

    private fun isLeapYear(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    /** El día [day] de [month] de [year], o el último del mes si no lo tiene. */
    private fun clamped(year: Int, month: Int, day: Int): BusinessDate =
        BusinessDate(LocalDate(year, month, minOf(day, daysInMonth(year, month))))
}
