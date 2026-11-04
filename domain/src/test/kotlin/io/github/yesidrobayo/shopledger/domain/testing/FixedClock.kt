package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Instant

/** Reloj detenido en un instante, para que ninguna prueba dependa de la hora del equipo. */
class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant

    companion object {
        /** Un reloj detenido en [epochMillis], la forma en que se guardan los instantes. */
        fun atEpochMillis(epochMillis: Long): FixedClock =
            FixedClock(Instant.fromEpochMilliseconds(epochMillis))
    }
}
