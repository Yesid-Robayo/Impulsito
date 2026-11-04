package io.github.yesidrobayo.shopledger.domain.model

import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone

/**
 * Zona horaria del negocio: la que convierte el instante de un registro en su fecha de negocio.
 *
 * Es un dato que la persona confirma en el primer uso y puede cambiar, no la zona actual del
 * teléfono: un teléfono de viaje sigue registrando con la zona del negocio. Por eso solo se
 * construye a partir de un identificador IANA explícito, como `Etc/GMT+5`, y nunca desde la zona
 * del sistema. Las reglas de horario de verano salen del identificador: nunca se resta un desfase
 * a mano.
 */
@JvmInline
value class BusinessTimeZone private constructor(val timeZone: TimeZone) {
    /** El identificador que se guarda, tal como lo normaliza la base de zonas horarias. */
    val id: String get() = timeZone.id

    override fun toString(): String = id

    companion object {
        /** Largo máximo del identificador que acepta la base de datos. */
        const val MAX_ID_LENGTH: Int = 64

        /**
         * La zona con ese identificador, o `null` si no es una zona conocida en este teléfono.
         *
         * El `null` es un caso real, no solo un error de escritura: una copia hecha en un teléfono
         * con la base de zonas más nueva puede traer una zona que otro teléfono aún no conoce.
         * Quien llama decide qué hacer, por ejemplo pedir a la persona que elija la zona otra vez.
         */
        fun of(id: String): BusinessTimeZone? {
            if (id.isEmpty() || id.length > MAX_ID_LENGTH) return null
            return try {
                BusinessTimeZone(TimeZone.of(id))
            } catch (_: IllegalTimeZoneException) {
                null
            }
        }
    }
}
