package io.github.yesidrobayo.shopledger.ui.lock

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.lock.AppLock
import io.github.yesidrobayo.shopledger.lock.AppLockState
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.lock.AuthPurpose
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.launch

/**
 * La compuerta del bloqueo, que envuelve la navegación entera: no es un destino. Bloqueada, el
 * contenido no se compone, así que el árbol no tiene ningún nodo del negocio; no es una capa encima.
 *
 * Lo que la navegación guarda, como la pila y la venta a medio escribir, queda guardado aquí mientras
 * está bloqueada, y vuelve igual al desbloquear. Los ViewModel de cada pantalla siguen vivos: la pila
 * no cambió.
 *
 * Mientras no se sabe si hay bloqueo, solo se ve el papel.
 *
 * @param locked lo que se ve bloqueada: la pantalla del bloqueo.
 */
@Composable
fun AppLockGate(
    state: AppLockState,
    locked: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val saved = rememberSaveableStateHolder()
    when (state) {
        AppLockState.Unknown ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))

        AppLockState.Locked -> locked()

        is AppLockState.Open -> saved.SaveableStateProvider(CONTENT_KEY) { content() }
    }
}

/**
 * La pantalla del bloqueo con su verificación. La verificación del sistema se abre sola al entrar y
 * cada vez que [lock] lo pide; si se cancela o falla, queda esta pantalla con `Desbloquear`, sin
 * perder nada.
 *
 * @param onNoDeviceCredential el teléfono ya no tiene bloqueo de pantalla: quien llama abre la app y
 *   apaga el bloqueo, porque nunca deja a nadie fuera de sus datos.
 */
@Composable
fun LockRoute(lock: AppLock, onNoDeviceCredential: () -> Unit) {
    val authenticator = LocalDeviceAuthenticator.current
    val prompts by lock.promptRequests.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.app_name)
    val body = stringResource(R.string.lock_screen_body)
    val unlock: suspend () -> Unit = {
        if (!busy) {
            busy = true
            try {
                when (val outcome = authenticator.authenticate(AuthPurpose.UNLOCK, title, body)) {
                    AuthOutcome.Success -> lock.unlocked()
                    AuthOutcome.Canceled -> Unit
                    AuthOutcome.NoDeviceCredential -> onNoDeviceCredential()
                    is AuthOutcome.Failed -> message = outcome.systemMessage
                }
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(prompts) { unlock() }
    LockScreen(message = message, onUnlock = { scope.launch { unlock() } })
}

/**
 * La pantalla del bloqueo: el héroe a sangre con el candado, el nombre de la app y `Desbloquear`
 * abajo. No lleva ningún dato del negocio.
 *
 * @param message el texto del sistema tras una falla, como demasiados intentos, o `null`.
 */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    LightSystemBarIcons()
    val colors = AppTheme.colors
    val spacing = AppTheme.spacing
    Box(
        modifier.fillMaxSize().background(colors.heroContainer).systemBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxSize()
                .padding(horizontal = spacing.s24, vertical = spacing.s24),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.s16),
            ) {
                Spacer(Modifier.height(spacing.s32))
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = colors.onHero,
                    modifier = Modifier.size(AppTheme.sizes.heroIcon),
                )
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = colors.onHero,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.lock_screen_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onHeroVariant,
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onHero,
                    )
                }
            }
            PrimaryActionButton(
                text = stringResource(R.string.lock_unlock),
                onClick = onUnlock,
                variant = ActionVariant.ON_HERO,
                modifier = Modifier.padding(top = spacing.s16).keepsMessagesAbove(),
            )
        }
    }
}

/**
 * Iconos claros en las barras del sistema mientras se ve el héroe a sangre, en los dos temas: el
 * blanco del héroe cumple en las nueve paletas. Al salir, vuelven como estaban.
 */
@Composable
private fun LightSystemBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

private const val CONTENT_KEY = "app-content"

@ThemePreviews
@Composable
private fun LockScreenPreview() {
    PreviewTheme { LockScreen(message = null, onUnlock = {}) }
}

@ThemePreviews
@Composable
private fun LockScreenFailedPreview() {
    PreviewTheme {
        LockScreen(message = stringResource(R.string.lock_no_device_lock), onUnlock = {})
    }
}
