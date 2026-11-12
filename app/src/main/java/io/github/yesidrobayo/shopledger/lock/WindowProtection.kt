package io.github.yesidrobayo.shopledger.lock

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * El único dueño de lo que la ventana no deja ver en capturas ni en Recientes. Varias razones lo
 * piden a la vez, como el bloqueo encendido y una pantalla con la contraseña del servicio: la ventana
 * queda protegida mientras quede alguna. Así, salir de una pantalla protegida nunca quita la
 * protección que pide el bloqueo.
 *
 * @param applySecure pone o quita la protección en la ventana. Solo se llama cuando cambia.
 */
class WindowProtection(private val applySecure: (Boolean) -> Unit) {
    private val reasons = mutableSetOf<Any>()
    private var lockEnabled = false
    private var applied: Boolean? = null

    /** Con el bloqueo encendido, toda la ventana va protegida. */
    fun lockEnabled(enabled: Boolean) {
        lockEnabled = enabled
        update()
    }

    /** Una pantalla que siempre va protegida empieza a verse. */
    fun add(reason: Any) {
        reasons += reason
        update()
    }

    /** Esa pantalla dejó de verse. */
    fun remove(reason: Any) {
        reasons -= reason
        update()
    }

    private fun update() {
        val secure = lockEnabled || reasons.isNotEmpty()
        if (applied != secure) {
            applied = secure
            applySecure(secure)
        }
    }

    companion object {
        /** La protección de [window], con `FLAG_SECURE`. */
        fun of(window: Window): WindowProtection = WindowProtection { secure ->
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/**
 * La protección de la ventana de la actividad principal. Fuera de ella, en vistas previas y pruebas
 * de una pantalla suelta, cada lectura recibe una propia que no toca ninguna ventana.
 */
val LocalWindowProtection = staticCompositionLocalOf { WindowProtection {} }

/** La ventana no deja capturas ni muestra el contenido en Recientes mientras esto se compone. */
@Composable
fun SecureWhileShown() {
    val protection = LocalWindowProtection.current
    DisposableEffect(protection) {
        val reason = Any()
        protection.add(reason)
        onDispose { protection.remove(reason) }
    }
}
