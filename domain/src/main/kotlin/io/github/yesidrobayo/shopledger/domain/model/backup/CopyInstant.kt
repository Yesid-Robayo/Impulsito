package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * El instante UTC con que se nombra un archivo de copia, al segundo, como `20261101T203012Z`.
 *
 * Es la base de tiempo de la poda: las copias se agrupan por su [retentionDay]. Nunca se usa la
 * fecha que da el servicio, porque la controla el servicio. El instante sale del reloj del
 * teléfono, que puede estar mal puesto, así que el orden confiable entre copias es su generación,
 * no este instante.
 *
 * Se escribe y se lee siempre en UTC, sin zona ni formato regional: el nombre de una copia es el
 * mismo en cualquier teléfono, idioma y región.
 */
@JvmInline
value class CopyInstant private constructor(
    /** Segundos desde 1970-01-01T00:00Z. */
    val epochSeconds: Long,
) : Comparable<CopyInstant> {
    /**
     * El día UTC de esta copia, por el que la poda la agrupa.
     *
     * Es UTC y no la zona del negocio a propósito: una copia no depende de ninguna zona, y la zona
     * del negocio puede cambiar. Por eso aquí no hay forma de pedir el día en otra zona.
     */
    val retentionDay: RetentionDay
        get() = RetentionDay(toUtcDateTime().date)

    override fun compareTo(other: CopyInstant): Int = epochSeconds.compareTo(other.epochSeconds)

    /** El texto con que va en el nombre del archivo, como `20261101T203012Z`. */
    override fun toString(): String {
        val time = toUtcDateTime()
        return buildString(STAMP_LENGTH) {
            appendPadded(time.year, YEAR_DIGITS)
            appendPadded(time.month.number, 2)
            appendPadded(time.day, 2)
            append(TIME_SEPARATOR)
            appendPadded(time.hour, 2)
            appendPadded(time.minute, 2)
            appendPadded(time.second, 2)
            append(UTC_DESIGNATOR)
        }
    }

    private fun toUtcDateTime(): LocalDateTime =
        Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC)

    companion object {
        private const val STAMP_LENGTH = 16
        private const val YEAR_DIGITS = 4
        private const val TIME_SEPARATOR = 'T'
        private const val UTC_DESIGNATOR = 'Z'
        private const val TIME_SEPARATOR_INDEX = 8

        /** 0000-01-01T00:00:00Z, el primer instante que cabe en el nombre. */
        private const val MIN_EPOCH_SECONDS = -62_167_219_200L

        /** 9999-12-31T23:59:59Z, el último instante que cabe en el nombre. */
        private const val MAX_EPOCH_SECONDS = 253_402_300_799L

        /**
         * El instante de una copia creada en [instant], sin la fracción de segundo.
         *
         * Devuelve `null` si el año no cabe en cuatro cifras, del 0000 al 9999. Un reloj de teléfono
         * nunca llega ahí, pero el nombre de un archivo no podría escribirlo.
         */
        fun of(instant: Instant): CopyInstant? {
            val seconds = instant.epochSeconds
            if (seconds !in MIN_EPOCH_SECONDS..MAX_EPOCH_SECONDS) return null
            return CopyInstant(seconds)
        }

        /**
         * Lee el instante del nombre de un archivo, o devuelve `null` si [stamp] no es exactamente
         * un instante real con la forma `AAAAMMDDTHHMMSSZ`.
         *
         * El nombre viene de un servicio no confiable: solo se aceptan cifras ASCII en su lugar, y
         * se rechazan fechas y horas imposibles, como un 30 de febrero o un segundo 60.
         */
        fun parse(stamp: String): CopyInstant? {
            val wellPlaced = stamp.length == STAMP_LENGTH &&
                stamp[TIME_SEPARATOR_INDEX] == TIME_SEPARATOR &&
                stamp.last() == UTC_DESIGNATOR
            if (!wellPlaced) return null
            val digits = stamp.substring(0, TIME_SEPARATOR_INDEX) +
                stamp.substring(TIME_SEPARATOR_INDEX + 1, STAMP_LENGTH - 1)
            if (!digits.all { it in '0'..'9' }) return null
            // AAAAMMDDHHMMSS pasa a la forma ISO, que valida el calendario y la hora.
            val date = "${digits.substring(
                0,
                4,
            )}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
            val time = "${digits.substring(
                8,
                10,
            )}:${digits.substring(10, 12)}:${digits.substring(12)}"
            val iso = "${date}T$time"
            val dateTime = LocalDateTime.Formats.ISO.parseOrNull(iso) ?: return null
            return CopyInstant(dateTime.toInstant(TimeZone.UTC).epochSeconds)
        }
    }
}

/**
 * Un día UTC de la poda de copias.
 *
 * Es otro tipo que la fecha de negocio a propósito: la fecha de negocio usa la zona del negocio, y
 * este día no usa ninguna. Así no se pueden comparar por error.
 */
@JvmInline
value class RetentionDay(val utcDate: LocalDate) : Comparable<RetentionDay> {
    override fun compareTo(other: RetentionDay): Int = utcDate.compareTo(other.utcDate)

    /** El día como `AAAA-MM-DD`, igual en cualquier idioma y región. */
    override fun toString(): String = utcDate.toString()
}

private fun StringBuilder.appendPadded(value: Int, width: Int) {
    val digits = value.toString()
    repeat(width - digits.length) { append('0') }
    append(digits)
}
