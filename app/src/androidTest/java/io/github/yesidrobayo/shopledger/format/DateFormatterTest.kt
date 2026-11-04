package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fechas y horas con las palabras del idioma de la interfaz y el reloj del formato regional, sea
 * cual sea el idioma, el calendario y la zona del teléfono. Los valores esperados están escritos a
 * mano, y se eligieron entre los que ICU da igual en API 26 y en API 37: las abreviaturas y el
 * reloj de algunas regiones cambiaron entre esas versiones.
 */
@RunWith(Parameterized::class)
class DateFormatterTest(device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun englishWordsWithATwelveHourRegion() {
        val formatter = dates(format = "en-US", ui = "en")
        assertEquals("10:42\u00A0AM", formatter.time(MORNING, zone("Etc/GMT+5")))
        assertEquals("9:14\u00A0PM", formatter.time(EVENING, zone("Etc/GMT+5")))
        assertEquals("Oct 28", formatter.dayMonth(date(2025, 10, 28)))
        assertEquals("Oct 28, 2025", formatter.dayMonthYear(date(2025, 10, 28)))
        assertEquals("Sun, Nov 1", formatter.weekdayDayMonth(date(2026, 11, 1)))
        assertEquals("November 1, 2026", formatter.longDate(date(2026, 11, 1)))
        assertEquals("November 2026", formatter.monthYear(YearMonth(2026, 11)))
    }

    @Test
    fun theClockComesFromTheRegionAndTheWordsFromTheInterfaceLanguage() {
        // Inglés en una región de 24 horas: la hora sigue en 24 horas, con meses en inglés.
        val formatter = dates(format = "ja-JP", ui = "en")
        assertEquals("10:42", formatter.time(MORNING, zone("Etc/GMT+5")))
        assertEquals("21:14", formatter.time(EVENING, zone("Etc/GMT+5")))
        assertEquals("November 1, 2026", formatter.longDate(date(2026, 11, 1)))
    }

    @Test
    fun spanishWordsWithATwentyFourHourRegion() {
        val formatter = dates(format = "es-ES", ui = "es")
        assertEquals("21:14", formatter.time(EVENING, zone("Etc/GMT+5")))
        assertEquals("1 de noviembre de 2026", formatter.longDate(date(2026, 11, 1)))
    }

    @Test
    fun aMonthTitleStartsWithACapitalLetter() {
        assertEquals("Noviembre de 2026", dates("es-ES", "es").monthYear(YearMonth(2026, 11)))
        assertEquals("Enero de 2026", dates("es-ES", "es").monthYear(YearMonth(2026, 1)))
        assertEquals("Novembro de 2026", dates("pt-BR", "pt").monthYear(YearMonth(2026, 11)))
    }

    @Test
    fun theRegionChangesTheDateWithTheSameInterfaceLanguage() {
        val brazil = dates(format = "pt-BR", ui = "pt")
        val switzerland = dates(format = "de-CH", ui = "pt")
        assertTrue(brazil.dayMonth(date(2025, 10, 28)).startsWith("28 de out"))
        assertEquals("28/10", switzerland.dayMonth(date(2025, 10, 28)))
        assertEquals("28/10/2025", switzerland.dayMonthYear(date(2025, 10, 28)))
        assertEquals("1 de novembro de 2026", brazil.longDate(date(2026, 11, 1)))
        assertEquals("1 de novembro de 2026", switzerland.longDate(date(2026, 11, 1)))
    }

    @Test
    fun anInstantIsShownInTheBusinessZoneNotTheDeviceZone() {
        val formatter = dates(format = "es-ES", ui = "es")
        assertEquals("21:14", formatter.time(EVENING, zone("Etc/GMT+5")))
        assertEquals("11:14", formatter.time(EVENING, zone("Etc/GMT-9")))
        assertEquals("7:59", formatter.time(EVENING, zone("Asia/Kathmandu")))
    }

    @Test
    fun aDateOfThisYearHidesTheYearAndOneOfAnotherYearShowsIt() {
        val formatter = dates(format = "en-US", ui = "en")
        val today = date(2026, 11, 3)
        assertEquals("Oct 28", formatter.shortDate(date(2026, 10, 28), today))
        assertEquals("Oct 28, 2025", formatter.shortDate(date(2025, 10, 28), today))
    }

    @Test
    fun theCalendarIsAlwaysGregorianEvenWhereTheRegionPrefersAnother() {
        // Tailandia usa el calendario budista y la región persa el suyo: el mes de un reporte es
        // gregoriano, así que la fecha también.
        assertEquals("November 1, 2026", dates("th-TH", "en").longDate(date(2026, 11, 1)))
        assertEquals("November 1, 2026", dates("fa-IR", "en").longDate(date(2026, 11, 1)))
        assertEquals(
            "November 1, 2026",
            dates("th-TH-u-ca-buddhist", "en").longDate(date(2026, 11, 1)),
        )
    }

    @Test
    fun anOldDateIsNotMovedToTheJulianCalendar() {
        assertEquals("March 1, 1500", dates("en-US", "en").longDate(date(1500, 3, 1)))
    }

    @Test
    fun aRegionWithOtherDigitsStillGetsLatinDigits() {
        val formatter = dates(format = "ar-EG", ui = "en")
        val texts =
            listOf(
                formatter.time(EVENING, zone("Etc/GMT+5")),
                formatter.dayMonthYear(date(2025, 10, 28)),
                formatter.longDate(date(2026, 11, 1)),
            )
        for (text in texts) {
            assertTrue(text, text.filter { it.isDigit() }.all { it in '0'..'9' })
            assertTrue(text, text.none { it == '\u200E' || it == '\u200F' || it == '\u061C' })
        }
    }

    private fun dates(format: String, ui: String): DateFormatter =
        DateFormatter(requireNotNull(FormatLocale.of(format)), Locale.forLanguageTag(ui))

    private fun zone(id: String): BusinessTimeZone = requireNotNull(BusinessTimeZone.of(id))

    private fun date(year: Int, month: Int, day: Int): BusinessDate =
        BusinessDate(LocalDate(year, month, day))

    companion object {
        /** 2026-11-01 a las 10:42 en `Etc/GMT+5`. */
        private val MORNING = Instant.parse("2026-11-01T15:42:00Z")

        /** 2026-11-01 a las 21:14 en `Etc/GMT+5`, ya 2026-11-02 en UTC. */
        private val EVENING = Instant.parse("2026-11-02T02:14:00Z")

        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
