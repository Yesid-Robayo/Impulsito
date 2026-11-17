package io.github.yesidrobayo.shopledger.navigation

import io.github.yesidrobayo.shopledger.ui.phrase.VoiceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lo que `Decir` escuchó encima de la pantalla visible, para la hoja `Decir o escribir` que se abre
 * después: la frase oída, o el aviso de por qué queda el teclado.
 *
 * **Por qué no va en la clave de la hoja.** La pila guardada nunca lleva textos del negocio, y una
 * frase dicha los tiene: nombres, productos y montos. Esto vive solo en memoria, lo que la
 * navegación. Si Android cierra el proceso entre los dos pasos, la hoja abre vacía y la persona lo
 * dice de nuevo; nada se registra sin la tarjeta.
 */
class SaidPhraseHandoff {
    private val pending = MutableStateFlow<VoiceResult?>(null)

    /** Lo que la hoja no tomó todavía. */
    val toTake: StateFlow<VoiceResult?> = pending.asStateFlow()

    /** Deja [result] para la hoja; reemplaza lo que no se tomó, que ya no corresponde. */
    fun deliver(result: VoiceResult) {
        pending.value = result
    }

    /** Toma lo pendiente y lo borra, para no usarlo dos veces. */
    fun take(): VoiceResult? {
        val taken = pending.value
        pending.compareAndSet(taken, null)
        return taken
    }
}
