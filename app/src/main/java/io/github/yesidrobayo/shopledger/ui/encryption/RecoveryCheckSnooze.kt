package io.github.yesidrobayo.shopledger.ui.encryption

import kotlin.time.Instant

/**
 * Hasta cuándo dura `Más tarde` en la prueba periódica del código.
 *
 * Vive solo en la memoria del proceso, a propósito: nunca se escribe en disco. Si el sistema cierra
 * la app, la prueba se vuelve a pedir, que es lo seguro. Dentro del mismo proceso, salir de la
 * pantalla y volver no la pide de nuevo hasta el día siguiente.
 */
class RecoveryCheckSnooze {
    @Volatile
    var until: Instant? = null

    companion object {
        /** El de este proceso: lo comparten todas las pantallas del cifrado. */
        val Process = RecoveryCheckSnooze()
    }
}
