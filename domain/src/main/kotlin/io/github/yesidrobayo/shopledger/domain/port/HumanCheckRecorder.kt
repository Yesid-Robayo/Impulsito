package io.github.yesidrobayo.shopledger.domain.port

import kotlin.time.Instant

/**
 * Anota que la persona dijo haber visto la copia en la nube del teléfono. Solo registra lo que dijo:
 * nunca prueba la copia, y no cambia `verified_seq`, la cabeza ni los cambios pendientes.
 */
fun interface HumanCheckRecorder {
    /**
     * Guarda [at] y la [generation] que estaba en pantalla. Devuelve `false` si el destino ya no es
     * la nube del teléfono, y entonces no anota nada.
     */
    suspend fun recordSeen(generation: Long, at: Instant): Boolean
}
