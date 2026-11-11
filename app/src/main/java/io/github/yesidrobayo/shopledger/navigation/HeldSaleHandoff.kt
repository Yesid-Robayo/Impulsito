package io.github.yesidrobayo.shopledger.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lo que pasa entre la venta nueva y la lista de ventas en espera, sin nada de Compose.
 *
 * - La venta nueva publica si tiene líneas y de qué espera viene, para que la lista pregunte antes
 *   de cambiarla por otra.
 * - La lista deja aquí la espera elegida, y la venta nueva la toma una sola vez al volver a verse.
 *
 * Vive lo que la navegación: si Android cierra el proceso entre los dos pasos, la elección se pierde
 * y la persona la toca de nuevo; ninguna venta se pierde, porque las dos están guardadas.
 */
class HeldSaleHandoff {
    private val chosen = MutableStateFlow<Long?>(null)

    /** La espera elegida que la venta nueva aún no tomó. */
    val toResume: StateFlow<Long?> = chosen.asStateFlow()

    /** Si la venta nueva tiene líneas: retomar otra la pasa primero a espera. */
    var saleHasLines: Boolean = false

    /** La espera de la que viene la venta nueva, que la lista no ofrece retomar otra vez. */
    var editingHeldSaleId: Long? = null

    fun choose(heldSaleId: Long) {
        chosen.value = heldSaleId
    }

    /** Toma la espera elegida y la borra, para no retomarla dos veces. */
    fun take(): Long? = chosen.value.also { chosen.value = null }
}
