package io.github.yesidrobayo.shopledger.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * El cliente que la pantalla de clientes eligió para el cobro, sin nada de Compose.
 *
 * - La pantalla de clientes, abierta para elegir, deja aquí el cliente tocado o el recién creado, y
 *   la navegación vuelve al cobro.
 * - El cobro lo toma una sola vez al volver a verse, como si se hubiera tocado en su lista.
 *
 * Vive lo que la navegación: si Android cierra el proceso entre los dos pasos, la elección se pierde
 * y la persona elige otra vez. Nada se escribe hasta registrar la venta, así que nada se pierde.
 */
class CustomerPickHandoff {
    private val chosen = MutableStateFlow<Long?>(null)

    /** El cliente elegido que el cobro aún no tomó. */
    val toPick: StateFlow<Long?> = chosen.asStateFlow()

    fun choose(customerId: Long) {
        chosen.value = customerId
    }

    /** Toma el cliente elegido y lo borra, para no elegirlo dos veces. */
    fun take(): Long? = chosen.value.also { chosen.value = null }
}
