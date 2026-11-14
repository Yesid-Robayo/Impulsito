package io.github.yesidrobayo.shopledger.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Un código que el escáner leyó o que la persona escribió, para la pantalla de [purpose]. Si el
 * escáner ya encontró su producto, trae [productId] y, si el código era el de una presentación, la
 * unidad [unitId] en que se vende o se compra.
 */
data class ScanDelivery(
    val purpose: ScanPurpose,
    val code: String,
    val productId: Long? = null,
    val unitId: Long? = null,
)

/**
 * Lo que pasa entre el escáner y la pantalla que lo abrió, sin nada de Compose.
 *
 * - El escáner deja aquí cada código leído, en orden: en lectura continua pueden ser varios.
 * - La pantalla de debajo los toma una sola vez al volver a verse, como si los hubiera escrito.
 *
 * Solo viaja el texto del código, nunca un cuadro de la cámara. Vive lo que la navegación: si
 * Android cierra el proceso entre los dos pasos, los códigos se pierden y la persona escanea de
 * nuevo; nada se escribe en la base hasta que la pantalla de debajo guarde.
 */
class ScanHandoff {
    private val pending = MutableStateFlow<List<ScanDelivery>>(emptyList())

    /** Los códigos que ninguna pantalla tomó todavía. */
    val toTake: StateFlow<List<ScanDelivery>> = pending.asStateFlow()

    fun deliver(delivery: ScanDelivery) {
        pending.update { it + delivery }
    }

    /**
     * Toma los códigos de [purpose], en el orden en que llegaron, y los borra para no usarlos dos
     * veces. Los de otro propósito se quedan.
     */
    fun take(purpose: ScanPurpose): List<ScanDelivery> {
        var taken = emptyList<ScanDelivery>()
        pending.update { all ->
            taken = all.filter { it.purpose == purpose }
            all.filterNot { it.purpose == purpose }
        }
        return taken
    }
}
