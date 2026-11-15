package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las fechas de un gasto recurrente, con los ejemplos de la regla y del requisito a mano. Los
 * días de la semana de cada fecha se miraron en un calendario.
 */
class RecurrenceRulesTest {
    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    /** Los vencimientos que siguen a [from], uno tras otro. */
    private fun chain(recurrence: Recurrence, day: Int, from: String, count: Int): List<String> =
        generateSequence(date(from)) { RecurrenceRules.next(recurrence, day, it) }
            .take(count)
            .map { it.toString() }
            .toList()

    @Test
    fun `a monthly expense on day 31 uses the last day of a short month and goes back`() {
        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"),
            chain(Recurrence.MONTHLY, day = 31, from = "2027-01-31", count = 4),
        )
    }

    @Test
    fun `confirming the day 31 of January moves the date to the 28 of February`() {
        // El ejemplo del requisito: se confirma en 2027-01-31 y next_due_date es 2027-02-28.
        assertEquals(
            date("2027-02-28"),
            RecurrenceRules.next(Recurrence.MONTHLY, 31, date("2027-01-31")),
        )
    }

    @Test
    fun `a leap year keeps the 29 of February and December passes to January`() {
        assertEquals(
            date("2028-02-29"),
            RecurrenceRules.next(Recurrence.MONTHLY, 30, date("2028-01-30")),
        )
        assertEquals(
            date("2028-01-05"),
            RecurrenceRules.next(Recurrence.MONTHLY, 5, date("2027-12-05")),
        )
    }

    @Test
    fun `a yearly expense on the 29 of February waits for the next leap year`() {
        val leapDay = RecurrenceRules.yearDayOf(2, 29)
        assertEquals(60, leapDay)
        assertEquals(
            listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"),
            chain(Recurrence.YEARLY, leapDay, from = "2028-02-29", count = 5),
        )
    }

    @Test
    fun `century years are leap only every four hundred years`() {
        assertEquals(28, RecurrenceRules.daysInMonth(2100, 2))
        assertEquals(29, RecurrenceRules.daysInMonth(2000, 2))
        assertEquals(30, RecurrenceRules.daysInMonth(2027, 11))
        assertEquals(31, RecurrenceRules.daysInMonth(2027, 12))
    }

    @Test
    fun `a weekly expense comes back seven days later`() {
        assertEquals(
            listOf("2026-11-02", "2026-11-09", "2026-11-16"),
            chain(Recurrence.WEEKLY, day = 1, from = "2026-11-02", count = 3),
        )
    }

    @Test
    fun `the day of the year counts on a leap year from the first of January`() {
        assertEquals(1, RecurrenceRules.yearDayOf(1, 1))
        assertEquals(61, RecurrenceRules.yearDayOf(3, 1))
        assertEquals(366, RecurrenceRules.yearDayOf(12, 31))
        assertEquals(1 to 1, RecurrenceRules.monthAndDayOf(1))
        assertEquals(2 to 29, RecurrenceRules.monthAndDayOf(60))
        assertEquals(12 to 31, RecurrenceRules.monthAndDayOf(366))
        assertThrows(IllegalArgumentException::class.java) { RecurrenceRules.monthAndDayOf(0) }
        assertThrows(IllegalArgumentException::class.java) { RecurrenceRules.monthAndDayOf(367) }
    }

    @Test
    fun `each recurrence has its own range of days`() {
        assertEquals(7, RecurrenceRules.maxDay(Recurrence.WEEKLY))
        assertEquals(31, RecurrenceRules.maxDay(Recurrence.MONTHLY))
        assertEquals(366, RecurrenceRules.maxDay(Recurrence.YEARLY))
        assertTrue(RecurrenceRules.isValidDay(Recurrence.WEEKLY, 7))
        assertFalse(RecurrenceRules.isValidDay(Recurrence.WEEKLY, 8))
        assertFalse(RecurrenceRules.isValidDay(Recurrence.MONTHLY, 0))
        assertFalse(RecurrenceRules.isValidDay(Recurrence.MONTHLY, 32))
        assertTrue(RecurrenceRules.isValidDay(Recurrence.YEARLY, 366))
        assertFalse(RecurrenceRules.isValidDay(Recurrence.YEARLY, 367))
    }

    @Test
    fun `a day outside its range has no next date`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRules.next(Recurrence.WEEKLY, 8, date("2026-11-02"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRules.firstOnOrAfter(Recurrence.MONTHLY, 32, date("2026-11-02"))
        }
    }

    @Test
    fun `a date matches its day, counting the last day of a short month`() {
        // 2026-11-02 es lunes.
        assertTrue(RecurrenceRules.matches(Recurrence.WEEKLY, 1, date("2026-11-02")))
        assertFalse(RecurrenceRules.matches(Recurrence.WEEKLY, 2, date("2026-11-02")))
        assertTrue(RecurrenceRules.matches(Recurrence.MONTHLY, 31, date("2027-02-28")))
        assertFalse(RecurrenceRules.matches(Recurrence.MONTHLY, 31, date("2027-02-27")))
        assertTrue(RecurrenceRules.matches(Recurrence.YEARLY, 60, date("2029-02-28")))
        assertFalse(RecurrenceRules.matches(Recurrence.YEARLY, 60, date("2029-03-01")))
        assertFalse(RecurrenceRules.matches(Recurrence.MONTHLY, 0, date("2027-02-28")))
    }

    @Test
    fun `the first date on or after a day stays in its period or moves to the next`() {
        // 2026-11-04 es miércoles: el lunes siguiente es el 9, y el miércoles es ese mismo día.
        val wednesday = date("2026-11-04")
        assertEquals(
            date("2026-11-09"),
            RecurrenceRules.firstOnOrAfter(Recurrence.WEEKLY, 1, wednesday),
        )
        assertEquals(wednesday, RecurrenceRules.firstOnOrAfter(Recurrence.WEEKLY, 3, wednesday))
        assertEquals(
            date("2026-11-05"),
            RecurrenceRules.firstOnOrAfter(Recurrence.MONTHLY, 5, wednesday),
        )
        assertEquals(
            date("2026-12-01"),
            RecurrenceRules.firstOnOrAfter(Recurrence.MONTHLY, 1, wednesday),
        )
        assertEquals(
            date("2026-12-25"),
            RecurrenceRules.firstOnOrAfter(Recurrence.YEARLY, 360, wednesday),
        )
        assertEquals(
            date("2027-01-01"),
            RecurrenceRules.firstOnOrAfter(Recurrence.YEARLY, 1, wednesday),
        )
    }

    @Test
    fun `the day of a date keeps a later day that falls on the last of the month`() {
        val shortEnd = date("2027-02-28")
        assertEquals(31, RecurrenceRules.dayOf(Recurrence.MONTHLY, shortEnd, current = 31))
        assertEquals(28, RecurrenceRules.dayOf(Recurrence.MONTHLY, shortEnd, current = 5))
        assertEquals(28, RecurrenceRules.dayOf(Recurrence.MONTHLY, shortEnd))
        // 2027-02-28 es domingo, el día 59 de un año bisiesto.
        assertEquals(7, RecurrenceRules.dayOf(Recurrence.WEEKLY, shortEnd))
        assertEquals(59, RecurrenceRules.dayOf(Recurrence.YEARLY, shortEnd))
        assertEquals(60, RecurrenceRules.dayOf(Recurrence.YEARLY, shortEnd, current = 60))
    }

    @Test
    fun `occurrences count every due date up to the horizon`() {
        val from = date("2026-11-02")
        assertEquals(
            5,
            RecurrenceRules.occurrencesThrough(Recurrence.WEEKLY, 1, from, date("2026-12-02")),
        )
        assertEquals(
            1,
            RecurrenceRules.occurrencesThrough(Recurrence.MONTHLY, 2, from, date("2026-12-01")),
        )
        assertEquals(
            0,
            RecurrenceRules.occurrencesThrough(Recurrence.MONTHLY, 2, from, date("2026-11-01")),
        )
    }
}
