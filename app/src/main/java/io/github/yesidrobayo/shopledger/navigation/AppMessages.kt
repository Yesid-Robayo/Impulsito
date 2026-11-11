package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Los mensajes breves que sobreviven a la pantalla que los pide, como `Venta registrada`: la venta
 * se cierra al confirmar, y su mensaje se ve en la pantalla a la que vuelve. Los muestra la
 * navegación, sobre la barra inferior.
 */
@Stable
class AppMessages(private val scope: CoroutineScope) {
    val host: SnackbarHostState = SnackbarHostState()

    /** Muestra [visuals]; si la persona toca su acción, corre [onAction]. */
    fun show(visuals: AppSnackbarVisuals, onAction: () -> Unit = {}) {
        scope.launch {
            if (host.showSnackbar(visuals) == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/** Los mensajes de la navegación. No tiene valor por defecto: solo existe dentro de ella. */
val LocalAppMessages =
    staticCompositionLocalOf<AppMessages> {
        error("Faltan los mensajes de la navegación: solo existen dentro de ella")
    }
