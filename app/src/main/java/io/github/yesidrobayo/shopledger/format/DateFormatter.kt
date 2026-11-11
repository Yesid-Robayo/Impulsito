package io.github.yesidrobayo.shopledger.format

import android.icu.text.DateFormat
import android.icu.text.DateFormatSymbols
import android.icu.text.DateTimePatternGenerator
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import java.util.Date
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Formatea fechas y horas con el formato regional [formatLocale] y las palabras del idioma de la
 * interfaz [uiLocale].
 *
 * El formato regional decide el reloj de 12 o 24 horas; el idioma de la interfaz, el nombre del mes
 * y del día. El orden de las partes lo da ICU para ese idioma en esa región: ICU no tiene, por
 * ejemplo, un inglés con el orden de cualquier región, y entonces usa el orden del inglés.
 *
 * Una fecha de negocio se muestra tal cual, sin zona: ya es un día del calendario. Un instante se
 * pasa primero a la zona del negocio, con la misma regla que calcula la fecha de negocio, y nunca a
 * la zona del teléfono.
 *
 * Es seguro usarlo desde varios hilos: ICU no lo es, y cada llamada toma su formato en exclusiva.
 */
class DateFormatter(val formatLocale: FormatLocale, val uiLocale: Locale) {
    private val locale: ULocale = formatLocale.dateLocale(uiLocale)

    private val timeFormat: DateFormat =
        dateFormat(if (usesTwentyFourHours(formatLocale)) TIME_24_HOURS else TIME_12_HOURS)
    private val dayMonthFormat: DateFormat = dateFormat(DAY_MONTH)
    private val dayMonthTimeFormat: DateFormat =
        dateFormat(
            DAY_MONTH + if (usesTwentyFourHours(formatLocale)) TIME_24_HOURS else TIME_12_HOURS,
        )
    private val dayMonthYearFormat: DateFormat = dateFormat(DAY_MONTH_YEAR)
    private val weekdayDayMonthFormat: DateFormat = dateFormat(WEEKDAY_DAY_MONTH)
    private val longDateFormat: DateFormat = dateFormat(LONG_DATE)
    private val monthYearFormat: DateFormat =
        dateFormat(MONTH_YEAR).apply {
            // Va como título: con mayúscula inicial, aunque el idioma escriba el mes en minúscula.
            setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
        }

    /**
     * La hora del instante en la zona del negocio: `10:42`, o `10:42 a. m.` en reloj de 12 horas.
     *
     * Sus espacios son duros, para que la hora nunca se parta entre dos líneas. Las versiones
     * nuevas de ICU ya ponen uno antes de AM y PM; las viejas, un espacio común.
     */
    fun time(instant: Instant, zone: BusinessTimeZone): String =
        timeFormat.render(instant.toLocalDateTime(zone.timeZone))
            .replace(' ', IcuTextNormalizer.NO_BREAK_SPACE)

    /**
     * Día, mes corto y hora del instante en la zona del negocio: `28 oct, 21:14`, con el reloj de la
     * región y el orden que da ICU para el idioma.
     */
    fun dayMonthTime(instant: Instant, zone: BusinessTimeZone): String =
        dayMonthTimeFormat.render(instant.toLocalDateTime(zone.timeZone))
            .replace(' ', IcuTextNormalizer.NO_BREAK_SPACE)

    /** Hace [minutes] minutos, en corto: `hace 12 min`. Es una duración, no una fecha. */
    fun minutesAgo(minutes: Long): String =
        relative(minutes, RelativeDateTimeFormatter.RelativeUnit.MINUTES)

    /** Hace [hours] horas, en corto: `hace 3 h`. */
    fun hoursAgo(hours: Long): String =
        relative(hours, RelativeDateTimeFormatter.RelativeUnit.HOURS)

    private val relativeFormat: RelativeDateTimeFormatter =
        RelativeDateTimeFormatter.getInstance(
            locale,
            null,
            RelativeDateTimeFormatter.Style.SHORT,
            DisplayContext.CAPITALIZATION_NONE,
        )

    // ICU pide la cantidad como número de coma flotante; aquí siempre es un entero pequeño de
    // minutos u horas, que se representa exacto.
    private fun relative(count: Long, unit: RelativeDateTimeFormatter.RelativeUnit): String {
        val text =
            synchronized(relativeFormat) {
                relativeFormat.format(
                    count.toDouble(),
                    RelativeDateTimeFormatter.Direction.LAST,
                    unit,
                )
            }
        return IcuTextNormalizer.normalizeText(text)
    }

    /** Día y mes corto, para una fecha de este año: `28 oct`. */
    fun dayMonth(date: BusinessDate): String = dayMonthFormat.render(date)

    /** Día, mes corto y año, para una fecha de otro año: `28 oct 2025`. */
    fun dayMonthYear(date: BusinessDate): String = dayMonthYearFormat.render(date)

    /** [dayMonth] si [date] es del mismo año que [today], y si no [dayMonthYear]. */
    fun shortDate(date: BusinessDate, today: BusinessDate): String =
        if (date.date.year == today.date.year) dayMonth(date) else dayMonthYear(date)

    /** Día de la semana abreviado, día y mes corto: `dom 1 nov`. */
    fun weekdayDayMonth(date: BusinessDate): String = weekdayDayMonthFormat.render(date)

    /** La fecha completa, para un título: `1 de noviembre de 2026`. */
    fun longDate(date: BusinessDate): String = longDateFormat.render(date)

    /** Mes y año como título, con mayúscula inicial: `Noviembre de 2026`. */
    fun monthYear(month: YearMonth): String = monthYearFormat.render(BusinessDate(month.firstDay))

    /**
     * El nombre corto de [day], para el encabezado de un calendario: `lu` o `Mo`, en el idioma de la
     * interfaz. Es la forma independiente de ICU, la que va sola y no dentro de una fecha.
     */
    fun weekdayShort(day: DayOfWeek): String {
        // ICU numera del domingo, 1, al sábado, 7; la numeración ISO va del lunes, 1, al domingo, 7.
        val icuIndex = day.isoDayNumber % DAYS_IN_WEEK + 1
        return IcuTextNormalizer.normalizeText(weekdayNames[icuIndex])
    }

    private val weekdayNames: Array<String> =
        DateFormatSymbols(locale).getWeekdays(DateFormatSymbols.STANDALONE, DateFormatSymbols.SHORT)

    private fun dateFormat(skeleton: String): DateFormat =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            calendar = gregorianUtc(locale)
        }

    private fun DateFormat.render(date: BusinessDate): String =
        render(date.date.atStartOfDayIn(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds())

    private fun DateFormat.render(dateTime: LocalDateTime): String =
        render(dateTime.toInstant(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds())

    /**
     * Las fechas y horas ya son del calendario local: se dibujan en UTC, que no corre ninguna
     * cifra.
     */
    private fun DateFormat.render(epochMillis: Long): String {
        val text = synchronized(this) { this.format(Date(epochMillis)) }
        return IcuTextNormalizer.normalizeText(text)
    }

    private companion object {
        // Esqueletos de ICU: piden las partes, que ICU ordena y separa según el idioma y la región.
        const val TIME_24_HOURS = "Hm"
        const val TIME_12_HOURS = "hm"
        const val DAY_MONTH = "MMMd"
        const val DAY_MONTH_YEAR = "yMMMd"
        const val WEEKDAY_DAY_MONTH = "MMMEd"
        const val LONG_DATE = "yMMMMd"
        const val MONTH_YEAR = "yMMMM"

        /** La hora en el reloj que prefiere la región, sea de 12 o de 24 horas. */
        const val PREFERRED_HOUR = "j"

        /**
         * Si el formato regional usa reloj de 24 horas. Se pregunta al formato y no a la mezcla con
         * el idioma de la interfaz, porque el reloj es de la región: con la app en inglés y una
         * región de 24 horas, la hora sigue en 24 horas.
         */
        fun usesTwentyFourHours(formatLocale: FormatLocale): Boolean {
            val pattern =
                DateTimePatternGenerator.getInstance(formatLocale.numberLocale)
                    .getBestPattern(PREFERRED_HOUR)
            return hourFields(pattern).any { it == 'H' || it == 'k' }
        }

        /** Las letras de campo de un patrón, sin el texto literal entre comillas, como `'h'`. */
        fun hourFields(pattern: String): List<Char> {
            val fields = mutableListOf<Char>()
            var quoted = false
            for (char in pattern) {
                when {
                    char == '\'' -> quoted = !quoted
                    !quoted && char in "HhKk" -> fields += char
                }
            }
            return fields
        }

        /**
         * Calendario gregoriano en UTC, sin el salto al calendario juliano de 1582: así un año
         * antiguo se ve igual que en la fecha de negocio, que es gregoriana en todos los años.
         */
        fun gregorianUtc(locale: ULocale): GregorianCalendar =
            GregorianCalendar(TimeZone.getTimeZone(UTC_ID), locale).apply {
                gregorianChange = Date(Long.MIN_VALUE)
            }

        const val UTC_ID = "Etc/UTC"

        /** Días de una semana. */
        const val DAYS_IN_WEEK = 7
    }
}
