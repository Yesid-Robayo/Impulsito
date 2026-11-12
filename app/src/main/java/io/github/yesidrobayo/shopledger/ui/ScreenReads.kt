package io.github.yesidrobayo.shopledger.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

/**
 * Las lecturas de una pantalla, con su falla y su `Reintentar`, en un solo lugar.
 *
 * La app no instala un manejador de excepciones en las corrutinas de los `ViewModel`: una lectura que
 * falla dentro de `viewModelScope` cierra la app. Por eso toda lectura de la base de una pantalla pasa
 * por aquí: un flujo con [guard] y una lectura suelta con [once]. Una falla no cierra nada: marca
 * [failed], la pantalla muestra `state_error_read` con `Reintentar`, y [retry] vuelve a empezar todas
 * las lecturas guardadas. Lo escrito vive en el estado de la pantalla, no en la lectura: reintentar
 * nunca lo borra.
 */
class ScreenReads {
    private val attempts = MutableStateFlow(0)
    private val failedState = MutableStateFlow(false)

    /** Si alguna lectura falló desde el último [retry]. */
    val failed: StateFlow<Boolean> = failedState.asStateFlow()

    /**
     * [source], que vuelve a empezar con cada [retry]. Si falla, marca [failed] y termina sin
     * emitir nada más: lo último leído sigue en el estado de la pantalla.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> guard(source: () -> Flow<T>): Flow<T> =
        attempts.flatMapLatest { source().catch { failedState.value = true } }

    /** Una lectura suelta. Si falla, marca [failed] y lo dice en el resultado. */
    suspend fun <T> once(block: suspend () -> T): ReadOutcome<T> =
        readOutcome(block).also { if (it == ReadOutcome.Failed) failedState.value = true }

    /** Borra la falla y vuelve a empezar las lecturas de [guard]. Las de [once] las repite quien llama. */
    fun retry() {
        failedState.value = false
        attempts.update { it + 1 }
    }
}
