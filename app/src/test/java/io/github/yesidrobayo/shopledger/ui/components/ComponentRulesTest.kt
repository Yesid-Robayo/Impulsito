package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas de los componentes que no necesitan pantalla: insertar piezas con estilo en una frase,
 * limpiar y agrupar lo que se escribe en un campo numérico, y moverse entre períodos.
 */
class ComponentRulesTest {
    private val commaDecimal =
        NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = DigitGrouping('.', 3, 3))
    private val indianGroups =
        NumberSymbols(decimalSeparator = '.', minusSign = '-', grouping = DigitGrouping(',', 3, 2))
    private val calendar =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of("Etc/UTC")), DayOfWeek.MONDAY)

    @Test
    fun anAmountKeepsItsSymbolStyleInsideASentence() {
        val amount =
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("¤") }
                append(" 25.000")
            }

        val text = formatAnnotated("Registrar venta · %1\$s", amount)

        assertEquals("Registrar venta · ¤ 25.000", text.text)
        val span = text.spanStyles.single()
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
        assertEquals("Registrar venta · ".length, span.start)
    }

    @Test
    fun placeholdersFollowTheirPositionNotTheirOrder() {
        assertEquals("b a", formatAnnotated("%2\$s %1\$s", "a", "b").text)
    }

    @Test
    fun aMissingArgumentStaysVisible() {
        assertEquals("%2\$s", formatAnnotated("%2\$s", AnnotatedString("a")).text)
    }

    @Test
    fun bothCommaAndDotBecomeTheRegionDecimalSeparator() {
        assertEquals("12,5", cleanNumberInput("12.5", commaDecimal, maxDecimals = 2))
        assertEquals("12,5", cleanNumberInput("12,5", commaDecimal, maxDecimals = 2))
    }

    @Test
    fun onlyOneSeparatorAndTheAllowedDecimalsSurvive() {
        assertEquals("1234,56", cleanNumberInput("1.234,567", commaDecimal, maxDecimals = 2))
        assertEquals("1234567", cleanNumberInput("1.234.567", commaDecimal, maxDecimals = 2))
        // Sin decimales, un separador solo puede ser de grupos.
        assertEquals("1500", cleanNumberInput("1.500", commaDecimal, maxDecimals = 0))
        assertEquals("120", cleanNumberInput("¤ -1a2b0", commaDecimal, maxDecimals = 2))
    }

    @Test
    fun groupsAreDrawnWithoutChangingTheText() {
        val shown = DigitGroupingTransformation(commaDecimal).filter(AnnotatedString("1234567,5"))

        assertEquals("1.234.567,5", shown.text.text)
        assertEquals(0, shown.offsetMapping.originalToTransformed(0))
        assertEquals(1, shown.offsetMapping.originalToTransformed(1))
        assertEquals(11, shown.offsetMapping.originalToTransformed(9))
        assertEquals(1, shown.offsetMapping.transformedToOriginal(2))
        assertEquals(9, shown.offsetMapping.transformedToOriginal(11))
    }

    @Test
    fun regionsWithGroupsOfThreeAndTwoAreRespected() {
        val shown = DigitGroupingTransformation(indianGroups).filter(AnnotatedString("1234567"))

        assertEquals("12,34,567", shown.text.text)
    }

    @Test
    fun aLeadingMinusSurvivesOnlyWhereNegativesAreAllowed() {
        // El guion del teclado, U+2212 y el de la región se guardan como el de la región.
        val regionMinus = commaDecimal.copy(minusSign = '−')
        assertEquals("−2,5", cleanSignedNumberInput(" -2.5", regionMinus, 2, true))
        assertEquals("−2,5", cleanSignedNumberInput("−2,5", regionMinus, 2, true))
        assertEquals("-150", cleanSignedNumberInput("-150", commaDecimal, 2, true))
        // Sin negativos, o con el signo en otro lugar, el signo se descarta.
        assertEquals("150", cleanSignedNumberInput("-150", commaDecimal, 2, false))
        assertEquals("150", cleanSignedNumberInput("15-0", commaDecimal, 2, true))
        assertEquals("", cleanSignedNumberInput("", commaDecimal, 2, true))
        assertEquals("12,34", cleanSignedNumberInput("12,345", commaDecimal, 2, true))
    }

    @Test
    fun theMinusSignDoesNotCountAsADigitWhenGrouping() {
        val shown = DigitGroupingTransformation(commaDecimal).filter(AnnotatedString("-1234,5"))

        assertEquals("-1.234,5", shown.text.text)
        assertEquals(
            "-100",
            DigitGroupingTransformation(commaDecimal).filter(AnnotatedString("-100")).text.text,
        )
        assertEquals(4, shown.offsetMapping.originalToTransformed(3))
    }

    @Test
    fun aShortNumberHasNoSeparator() {
        assertEquals(
            "123",
            DigitGroupingTransformation(commaDecimal).filter(AnnotatedString("123")).text.text,
        )
    }

    @Test
    fun theWeekBeforeStartsOnTheBusinessWeekStartDay() {
        val week = calendar.week(date(2026, 11, 5))

        val previous = calendar.previous(week)

        assertEquals(ReportPeriod.Week(date(2026, 10, 26)), previous)
        assertEquals(DayOfWeek.MONDAY, previous.start.date.dayOfWeek)
    }

    @Test
    fun monthsMoveByCalendarMonthsAcrossYears() {
        val january = calendar.month(date(2027, 1, 15))

        assertEquals(ReportPeriod.Month(YearMonth(2026, 12)), calendar.previous(january))
        assertEquals(ReportPeriod.Month(YearMonth(2027, 2)), calendar.next(january))
    }

    @Test
    fun daysMoveOneByOne() {
        assertEquals(
            ReportPeriod.Day(date(2026, 3, 1)),
            calendar.next(calendar.day(date(2026, 2, 28))),
        )
    }

    @Test
    fun theNextArrowHidesInTheLatestPeriod() {
        val today = date(2026, 11, 5)

        assertFalse(canGoForward(calendar.week(today), today))
        assertTrue(canGoForward(calendar.previous(calendar.week(today)), today))
        assertTrue(canGoForward(calendar.month(today), date(2026, 12, 1)))
    }

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    @Test
    fun aSaveFailureAsksToFreeSpaceOnlyWhenTheDiskIsFull() {
        assertEquals(R.string.error_device_storage, saveErrorText(outOfSpace = true))
        assertEquals(R.string.state_error_save, saveErrorText(outOfSpace = false))
    }
}
