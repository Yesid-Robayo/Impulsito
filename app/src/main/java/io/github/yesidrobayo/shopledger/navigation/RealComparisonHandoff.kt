package io.github.yesidrobayo.shopledger.navigation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * El pedido de ver la comparación real en `Números`, sin nada de Compose.
 *
 * - `Índice de precios` lo deja con [request] al tocar `Ver comparación en Números`, y la
 *   navegación abre `Números`.
 * - `Comparar`, en `Números`, lo toma una sola vez al verse, como si se hubiera tocado `Real`.
 *
 * Vive lo que la navegación: si Android cierra el proceso entre los dos pasos, el pedido se pierde
 * y `Números` abre en `Normal`. No se escribe nada.
 */
class RealComparisonHandoff {
    private val requested = AtomicBoolean(false)

    fun request() {
        requested.set(true)
    }

    /** Toma el pedido y lo borra: `true` si había uno. */
    fun take(): Boolean = requested.getAndSet(false)
}
