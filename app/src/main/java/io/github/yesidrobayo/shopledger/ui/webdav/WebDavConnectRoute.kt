package io.github.yesidrobayo.shopledger.ui.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ConnectWebDavUseCase
import io.github.yesidrobayo.shopledger.lock.SecureWhileShown
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant

/**
 * `Conectar el servicio` en la navegación. La política ya se aceptó: la navegación la pregunta
 * antes de mostrar esta pantalla.
 *
 * La ventana lleva `FLAG_SECURE` mientras la pantalla se ve: ni capturas ni la vista de apps
 * recientes muestran lo escrito.
 *
 * @param onConnected lo que pasa al quedar conectado; [Boolean] dice si la carpeta ya tenía copias.
 *   Por defecto, vuelve a quien abrió `Dónde guardar las copias`, donde el estado del respaldo dice
 *   si sube la primera copia o si hay copias de otro teléfono.
 */
@Composable
fun WebDavConnectRoute(
    key: AppNavKey.WebDavConnect,
    navigator: AppNavigator,
    onConnected: (Boolean) -> Unit = { backToOpener(navigator) },
) {
    SecureWindow()
    val appName = stringResource(R.string.app_name)
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            WebDavConnectViewModel(
                port = ConnectWebDavUseCase(dependencies.container.data.webDavConnection),
                appName = appName,
                suggestionKey = key.suggestionKey,
                savedState = savedState,
                computation = dependencies.container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> WebDavConnectScreen(WebDavConnectUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val connectedText = stringResource(R.string.webdav_connected)
            LaunchedEffect(uiState.connected) {
                val connected = uiState.connected ?: return@LaunchedEffect
                viewModel.onAction(WebDavConnectAction.ConnectedHandled)
                messages.show(AppSnackbarVisuals(connectedText, SnackbarVariant.SUCCESS))
                onConnected(connected.hasCopies)
            }
            WebDavConnectScreen(
                uiState = uiState,
                onAction = { action ->
                    // Atrás lo resuelve la navegación; lo demás, el ViewModel.
                    if (action == WebDavConnectAction.Back) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * Tras conectar: sale de esta pantalla y de `Dónde guardar las copias`, y vuelve a quien las abrió,
 * como el respaldo o Inicio.
 */
private fun backToOpener(navigator: AppNavigator) {
    while (navigator.current is AppNavKey.WebDavConnect ||
        navigator.current is AppNavKey.BackupDestinationChoice
    ) {
        if (!navigator.back()) break
    }
}

/**
 * `FLAG_SECURE` en la ventana mientras esta pantalla está en la composición. Lo pide al dueño de la
 * protección de la ventana: al salir, la ventana sigue protegida si el bloqueo de la app lo pide.
 */
@Composable
internal fun SecureWindow() {
    SecureWhileShown()
}
