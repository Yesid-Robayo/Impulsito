package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.LocalMessageClearance
import io.github.yesidrobayo.shopledger.ui.components.MessageClearance
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
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

/**
 * [content], la navegación entera, con los mensajes de [host] encima.
 *
 * El mensaje va sobre la barra inferior en las raíces ([showsBottomBar]) y sobre la barra del sistema
 * en lo demás. Si la pantalla visible marcó un área fija abajo con `keepsMessagesAbove`, como el
 * panel de la venta, va encima de ella: un mensaje que sigue a la vista, como `Venta registrada` con
 * `Deshacer`, nunca tapa el botón principal de la pantalla siguiente.
 */
@Composable
internal fun NavigationMessages(
    host: SnackbarHostState,
    showsBottomBar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val clearance = remember { MessageClearance() }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMessageClearance provides clearance) { content() }
        val resting =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                if (showsBottomBar) {
                    AppTheme.sizes.bottomBar + AppTheme.sizes.sellButtonOverhang
                } else {
                    AppTheme.spacing.s16
                }
        val raised = with(LocalDensity.current) { clearance.bottomPx.toDp() }
        val lift by animateDpAsState(
            targetValue = maxOf(resting, raised),
            animationSpec = tween(AppMotion.SHORT, easing = AppMotion.StandardEasing),
            label = "messageLift",
        )
        AppSnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(bottom = lift))
    }
}
