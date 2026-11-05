package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Instant

/**
 * El reloj de la historia inventada: marca el instante simulado de lo que se escribe, nunca la hora
 * real del teléfono. Así la base no depende de cuándo se generó, y la misma semilla da las mismas
 * filas, también en `updated_at` y en las columnas del respaldo que fija la transacción.
 *
 * Solo lo avanza el generador, en un solo hilo.
 */
internal class SeedClock(start: Instant) : Clock {
    var current: Instant = start

    override fun now(): Instant = current
}
