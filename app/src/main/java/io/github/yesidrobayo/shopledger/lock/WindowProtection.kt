package io.github.yesidrobayo.shopledger.lock

import android.os.Build
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * El único dueño de lo que la ventana protege. Varias razones lo piden a la vez, como el bloqueo
 * encendido y una pantalla con la contraseña del servicio: cada protección queda mientras quede una
 * razón. Así, salir de una pantalla protegida nunca quita la protección que pide el bloqueo.
 *
 * - **Capturas y Recientes**: con el bloqueo encendido, o mientras se ve una pantalla sensible.
 * - **Superposiciones de otras apps**: se ocultan mientras se ve una acción o una pantalla sensible.
 * - **Toques tapados**: en una pantalla entera sensible, un gesto que llega a través de otra app se
 *   descarta completo y se avisa. En una acción sola lo hace el nodo, con `rejectObscuredTouches`.
 *
 * @param applySecure pone o quita la protección de capturas. Solo se llama cuando cambia.
 * @param applyHideOverlays oculta o deja ver las superposiciones. Solo se llama cuando cambia.
 */
class WindowProtection(
    private val applySecure: (Boolean) -> Unit,
    private val applyHideOverlays: (Boolean) -> Unit = {},
) {
    private val secureReasons = mutableSetOf<Any>()
    private val overlayReasons = mutableSetOf<Any>()
    private val sensitiveScreens = LinkedHashMap<Any, () -> Unit>()
    private val gestures = ObscuredGestureFilter()
    private var lockEnabled = false

    // La ventana nace sin ninguna protección: solo se toca cuando algo la pide.
    private var secureApplied = false
    private var overlaysApplied = false

    /** Con el bloqueo encendido, toda la ventana va protegida. */
    fun lockEnabled(enabled: Boolean) {
        lockEnabled = enabled
        update()
    }

    /** Una pantalla que siempre va protegida de capturas empieza a verse. */
    fun add(reason: Any) {
        secureReasons += reason
        update()
    }

    /** Esa pantalla dejó de verse. */
    fun remove(reason: Any) {
        secureReasons -= reason
        update()
    }

    /** Una acción sensible está a la vista: las superposiciones de otras apps se ocultan. */
    fun hideOverlays(reason: Any) {
        overlayReasons += reason
        update()
    }

    /** Esa acción dejó de verse. */
    fun showOverlays(reason: Any) {
        overlayReasons -= reason
        update()
    }

    /**
     * Una pantalla entera sensible empieza a verse: va protegida de capturas, oculta las
     * superposiciones y descarta los toques tapados, avisando con [onObscuredTouch].
     */
    fun addSensitiveScreen(reason: Any, onObscuredTouch: () -> Unit) {
        sensitiveScreens[reason] = onObscuredTouch
        add(reason)
        hideOverlays(reason)
    }

    /** Esa pantalla dejó de verse. */
    fun removeSensitiveScreen(reason: Any) {
        sensitiveScreens -= reason
        remove(reason)
        showOverlays(reason)
    }

    /**
     * `true` si la actividad debe descartar [event]: hay una pantalla entera sensible a la vista y el
     * gesto empezó tapado por otra app. Avisa una vez por gesto.
     */
    fun rejects(event: MotionEvent): Boolean = rejects(event.actionMasked, event.isObscured())

    /** Lo mismo que [rejects] con la acción y la marca ya leídas del evento. */
    fun rejects(actionMasked: Int, obscured: Boolean): Boolean {
        val onObscured = sensitiveScreens.values.lastOrNull() ?: return false
        return gestures.reject(actionMasked, obscured, onObscured)
    }

    private fun update() {
        val secure = lockEnabled || secureReasons.isNotEmpty()
        if (secureApplied != secure) {
            secureApplied = secure
            applySecure(secure)
        }
        val hide = overlayReasons.isNotEmpty()
        if (overlaysApplied != hide) {
            overlaysApplied = hide
            applyHideOverlays(hide)
        }
    }

    companion object {
        /**
         * La protección de [window]: `FLAG_SECURE` para capturas y Recientes, y desde Android 12
         * `setHideOverlayWindows`, que pide `HIDE_OVERLAY_WINDOWS`. Antes de Android 12 no existe, y
         * queda el filtro de toques tapados.
         */
        fun of(window: Window): WindowProtection = WindowProtection(
            applySecure = { secure ->
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            },
            applyHideOverlays = { hide ->
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S
                ) {
                    window.setHideOverlayWindows(hide)
                }
            },
        )
    }
}

/**
 * La protección de la ventana de la actividad principal. Fuera de ella, en vistas previas y pruebas
 * de una pantalla suelta, cada lectura recibe una propia que no toca ninguna ventana.
 */
val LocalWindowProtection = staticCompositionLocalOf { WindowProtection({}) }

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

/** Las superposiciones de otras apps se ocultan mientras una acción sensible está a la vista. */
@Composable
fun HideOverlaysWhileShown() {
    val protection = LocalWindowProtection.current
    DisposableEffect(protection) {
        val reason = Any()
        protection.hideOverlays(reason)
        onDispose { protection.showOverlays(reason) }
    }
}

/**
 * Toda la pantalla es sensible mientras esto se compone: sin capturas ni Recientes, sin
 * superposiciones de otras apps, y sin toques que lleguen tapados, que avisan con [onObscuredTouch].
 */
@Composable
fun SensitiveScreen(onObscuredTouch: () -> Unit) {
    val protection = LocalWindowProtection.current
    val notify by rememberUpdatedState(onObscuredTouch)
    DisposableEffect(protection) {
        val reason = Any()
        protection.addSensitiveScreen(reason) { notify() }
        onDispose { protection.removeSensitiveScreen(reason) }
    }
}
