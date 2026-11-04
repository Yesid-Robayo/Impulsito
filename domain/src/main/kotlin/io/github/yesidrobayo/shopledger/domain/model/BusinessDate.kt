package io.github.yesidrobayo.shopledger.domain.model

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Fecha de negocio: el día del calendario al que pertenece un registro, y por el que se agrupan
 * todos los reportes.
 *
 * Es la fecha del instante del registro en la zona del negocio vigente al escribir. Se calcula una
 * sola vez y se guarda: si después la persona cambia la zona, los registros viejos conservan su
 * fecha y solo los nuevos usan la zona nueva. El día va de medianoche a medianoche, sin cierre
 * tardío.
 *
 * Se guarda como texto `AAAA-MM-DD`, que ordena igual que las fechas, en los años 0000 a 9999 que
 * acepta la base de datos.
 */
@JvmInline
value class BusinessDate(val date: LocalDate) : Comparable<BusinessDate> {
    /** El mismo día del calendario corrido `days` días; negativo va hacia atrás. */
    fun plusDays(days: Int): BusinessDate = BusinessDate(date.plus(days, DateTimeUnit.DAY))

    override fun compareTo(other: BusinessDate): Int = date.compareTo(other.date)

    /** El texto `AAAA-MM-DD` con el que se guarda, igual en cualquier idioma y región. */
    override fun toString(): String = date.toString()

    companion object {
        private val STORED_FORM = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        /**
         * La fecha de negocio de un registro ocurrido en [occurredAt], en la zona del negocio
         * [zone].
         *
         * La zona llega siempre por parámetro: la del teléfono puede ser otra, por ejemplo de
         * viaje.
         */
        fun of(occurredAt: Instant, zone: BusinessTimeZone): BusinessDate =
            BusinessDate(occurredAt.toLocalDateTime(zone.timeZone).date)

        /**
         * Lee una fecha guardada como `AAAA-MM-DD`, o devuelve `null` si el texto no es una fecha
         * real.
         *
         * Acepta solo esa forma exacta, con cifras ASCII, porque el texto puede venir de una copia
         * o de un archivo importado, que no son confiables. Rechaza fechas imposibles como el 30 de
         * febrero.
         */
        fun parse(text: String): BusinessDate? {
            if (!STORED_FORM.matches(text)) return null
            return LocalDate.Formats.ISO.parseOrNull(text)?.let(::BusinessDate)
        }
    }
}
