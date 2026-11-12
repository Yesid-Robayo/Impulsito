package io.github.yesidrobayo.shopledger.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Si la app deja ver el negocio. */
sealed interface AppLockState {
    /** Todavía no se leyó el ajuste del bloqueo: no se muestra nada del negocio. */
    data object Unknown : AppLockState

    /**
     * Se ve el negocio. [lockEnabled] dice si el bloqueo está encendido: con él, la ventana no deja
     * capturas, y la app pedirá desbloquear al volver tras el tiempo elegido.
     */
    data class Open(val lockEnabled: Boolean) : AppLockState

    /** Hay que desbloquear: no se compone nada del negocio hasta autenticar. */
    data object Locked : AppLockState
}

/**
 * El bloqueo de la app, uno por proceso. Sigue a la app entera, no a una pantalla: se registra en el
 * ciclo de vida del proceso, así que rotar la pantalla no bloquea, y pasar a otra app sí cuenta.
 *
 * - **Al arrancar el proceso**, con el bloqueo encendido, empieza bloqueada.
 * - **Al volver de segundo plano**, se bloquea si pasó el tiempo elegido. Se mide con un reloj que no
 *   cambia con la hora del teléfono ni se detiene al dormir: atrasar la hora no evita el bloqueo.
 * - **Mientras se autentica**, salir a la pantalla del PIN del sistema no cuenta como segundo plano:
 *   si no, con `Inmediato`, volver del PIN volvería a bloquear para siempre.
 * - **Encender el bloqueo** no bloquea en ese momento: la persona acaba de autenticarse para hacerlo.
 *
 * Es un bloqueo de pantalla. No cifra nada, y el respaldo sigue corriendo con la app bloqueada.
 *
 * @param elapsedMillis milisegundos desde que arrancó el teléfono, que solo avanzan.
 */
class AppLock(private val elapsedMillis: () -> Long) : DefaultLifecycleObserver {
    private val current = MutableStateFlow<AppLockState>(AppLockState.Unknown)
    private val prompts = MutableStateFlow(0)
    private var timeout: AppLockTimeout = AppLockTimeout.ONE_MINUTE
    private var backgroundedAt: Long? = null
    private var authenticating = false

    val state: StateFlow<AppLockState> = current.asStateFlow()

    /**
     * Cuántas veces se pidió abrir la verificación del sistema por sí sola: al bloquearse, o al volver
     * a la app que sigue bloqueada. La pantalla del bloqueo la abre con cada cambio.
     */
    val promptRequests: StateFlow<Int> = prompts.asStateFlow()

    /** El ajuste del bloqueo, al leerlo y cada vez que cambia. */
    fun apply(enabled: Boolean, timeout: AppLockTimeout) {
        this.timeout = timeout
        when (current.value) {
            AppLockState.Unknown -> if (enabled) {
                lock()
            } else {
                current.value =
                    AppLockState.Open(false)
            }

            // Apagado sigue abierto; encendido ya autenticó para encenderlo.
            is AppLockState.Open -> current.value = AppLockState.Open(enabled)

            // Bloqueada, solo apagarlo abre: así lo pide una restauración en un teléfono sin
            // bloqueo de pantalla, que nunca deja a nadie fuera.
            AppLockState.Locked -> if (!enabled) current.value = AppLockState.Open(false)
        }
    }

    /** Sin base no hay ajuste que leer: el rescate nunca queda detrás de un bloqueo. */
    fun withoutSettings() {
        if (current.value == AppLockState.Unknown) current.value = AppLockState.Open(false)
    }

    /** La persona se autenticó: se ve el negocio. */
    fun unlocked() {
        backgroundedAt = null
        current.value = AppLockState.Open(true)
    }

    /**
     * El teléfono ya no tiene con qué autenticar: la app se abre y queda sin bloqueo, porque nunca
     * deja a nadie fuera de sus datos. Quien llama apaga también el ajuste guardado.
     */
    fun noDeviceCredential() {
        backgroundedAt = null
        current.value = AppLockState.Open(false)
    }

    /**
     * Corre [block] mientras se muestra la verificación del sistema. Lo que pase con el ciclo de vida
     * en ese rato no bloquea.
     */
    suspend fun <T> authenticating(block: suspend () -> T): T {
        authenticating = true
        return try {
            block()
        } finally {
            authenticating = false
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!authenticating) backgroundedAt = elapsedMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt
        backgroundedAt = null
        if (authenticating) return
        when (val now = current.value) {
            AppLockState.Unknown -> Unit

            is AppLockState.Open -> {
                val expired = since != null && elapsedMillis() - since >= timeout.seconds * MILLIS
                if (now.lockEnabled && expired) lock()
            }

            AppLockState.Locked -> prompts.update { it + 1 }
        }
    }

    private fun lock() {
        current.value = AppLockState.Locked
        prompts.update { it + 1 }
    }

    private companion object {
        const val MILLIS = 1_000L
    }
}
