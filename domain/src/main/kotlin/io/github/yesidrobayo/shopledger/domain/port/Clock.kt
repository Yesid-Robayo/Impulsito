package io.github.yesidrobayo.shopledger.domain.port

import kotlin.time.Instant

/**
 * Reloj de pared del teléfono, inyectado en cada caso de uso para que las pruebas fijen la hora.
 *
 * Solo entrega el instante, que es igual en cualquier lugar del mundo. La zona que lo vuelve fecha
 * de negocio es un dato del negocio que se configura aparte: el reloj nunca la decide, y por eso no
 * existe aquí ninguna forma de pedir la zona del teléfono.
 *
 * El reloj puede estar mal puesto y no hay red para comprobarlo. Nada que dependa de él debe borrar
 * datos ni detener una copia: el orden fiable de los registros es su identificador, no su hora.
 */
fun interface Clock {
    /** El instante actual. */
    fun now(): Instant

    /** El instante actual en milisegundos desde 1970-01-01T00:00Z, la forma en que se guarda. */
    fun nowEpochMillis(): Long = now().toEpochMilliseconds()
}

/** El reloj real del sistema. Las pruebas usan uno fijo en su lugar. */
object SystemClock : Clock {
    override fun now(): Instant = kotlin.time.Clock.System.now()
}
