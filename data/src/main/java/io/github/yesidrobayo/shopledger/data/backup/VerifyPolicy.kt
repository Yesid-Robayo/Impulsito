package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Lo que decide cuánto se lee de una copia al comprobarla en el destino.
 *
 * @property metered si la ejecución corre en red medida.
 * @property backupNow si la persona pidió la copia con `Respaldar ahora`.
 * @property resumed si es una copia que el paso 5 retomó ya entera en el destino, sin subirla en
 *   esta ejecución.
 * @property meteredAllowed si la política de la persona permite respaldar con datos móviles. Solo
 *   cuenta para una copia retomada en red medida.
 * @property lastFullVerifyAtMillis la última lectura completa que coincidió, del reloj de pared, o
 *   nula si nunca hubo una.
 * @property nowMillis el reloj de pared, ahora.
 */
internal data class VerifyRequest(
    val metered: Boolean,
    val backupNow: Boolean,
    val resumed: Boolean,
    val meteredAllowed: Boolean,
    val lastFullVerifyAtMillis: Long?,
    val nowMillis: Long,
)

/**
 * Cuánto se lee, según la tabla de la verificación con WebDAV:
 *
 * - **Una copia retomada por el paso 5:** siempre entera, nunca por muestra. Quizá una lectura
 *   completa ya la rechazó y su marca no llegó al disco, y un daño en el centro pasaría la muestra.
 *   En red medida sigue la regla de una subida: entera si la política lo permite, o si la persona
 *   pidió la copia, porque cuesta lo mismo que subirla otra vez. Si no, nula: la ejecución espera
 *   una red no medida.
 * - **Con red medida:** una muestra del comienzo y del final. Una lectura completa gastaría datos.
 * - **Con red no medida:** entera si la persona pidió la copia, o si pasaron [FULL_VERIFY_HOURS]
 *   desde la última lectura completa, o nunca hubo una. Si no, una muestra.
 *
 * Un reloj que retrocedió cuenta como plazo vencido: la lectura completa es lo seguro.
 */
internal fun verifyModeFor(request: VerifyRequest): VerifyMode? = when {
    request.resumed ->
        if (!request.metered || request.backupNow ||
            request.meteredAllowed
        ) {
            VerifyMode.FULL
        } else {
            null
        }

    request.metered -> VerifyMode.SAMPLE

    request.backupNow -> VerifyMode.FULL

    isFullVerifyDue(request.lastFullVerifyAtMillis, request.nowMillis) -> VerifyMode.FULL

    else -> VerifyMode.SAMPLE
}

/** Si toca una lectura completa: nunca hubo una, venció el plazo, o el reloj retrocedió. */
internal fun isFullVerifyDue(lastFullVerifyAtMillis: Long?, nowMillis: Long): Boolean {
    val last = lastFullVerifyAtMillis ?: return true
    return nowMillis < last || nowMillis - last >= FULL_VERIFY_HOURS.inWholeMilliseconds
}

/** Cada cuánto, como máximo, se lee entera la copia nueva con red no medida. */
internal val FULL_VERIFY_HOURS: Duration = 24.hours

/** Los eventos de la comprobación. Solo llevan códigos. */
internal enum class VerifyEvent(override val text: String) : LogEvent {
    RELIST_FAILED("La segunda lista falló: la copia sigue comprobada, y no se poda"),
    COPY_NOT_RELISTED("La segunda lista no muestra la copia nueva: no se poda"),
    FULL_VERIFY_NOT_SAVED("No se pudo guardar la última copia leída entera"),
}
