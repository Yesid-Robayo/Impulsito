package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.safety.PolicyConsentUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
import kotlinx.coroutines.flow.map

/**
 * `Qué datos hay y adónde van` en la navegación. Lee la aceptación, el destino, el cifrado y el
 * segundo destino de archivos del teléfono, y el bloqueo y la búsqueda de versión de los ajustes.
 * Nada de esto usa la red.
 *
 * La política la abre el navegador del sistema, sin WebView. Mientras no esté publicada su página,
 * la fila abre los términos dentro de la app, como en Ajustes: nunca un enlace que no abre. Sin la
 * base, la pantalla sigue, con cada dato del estado como no disponible.
 */
@Composable
fun DataTransparencyRoute(navigator: AppNavigator) {
    val context = LocalContext.current
    val policy = LocalPolicyPrompt.current
    val messages = LocalAppMessages.current
    val privacyUrl = stringResource(R.string.legal_privacy_url)
    val noBrowser = stringResource(R.string.transparency_no_browser)
    val notSaved = stringResource(R.string.state_error_save)
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val consent = PolicyConsentUseCase(container.data.policyConsent)
            DataTransparencyViewModel(
                consentStatus = { consent.status() },
                acceptPolicy = { consent.accept() },
                describeDestination = {
                    BackupDestinationReader(container.data.backupDestination).describe()
                },
                encryptionStatus = { container.data.encryption.status() },
                secondDestination = { container.data.secondDestination.view() },
                preferences = dependencies.ports.settings.observe().map { it.preferences },
                computation = container.dispatchers.io,
            )
        }
    val viewModel = (model as? ScreenModel.Ready)?.viewModel
    val onAction: (DataTransparencyAction) -> Unit = { action ->
        when (action) {
            DataTransparencyAction.Back -> navigator.back()

            DataTransparencyAction.OpenTerms -> policy.openTerms()

            DataTransparencyAction.OpenPrivacy ->
                if (privacyUrl.isBlank()) {
                    policy.openTerms()
                } else {
                    when (openInBrowser(context, privacyUrl)) {
                        LinkOutcome.OPENED -> Unit

                        LinkOutcome.NO_APP ->
                            messages.show(AppSnackbarVisuals(noBrowser, SnackbarVariant.INFO))

                        // Una dirección que no es `https` nunca sale: quedan los términos aquí.
                        LinkOutcome.NOT_ALLOWED -> policy.openTerms()
                    }
                }

            DataTransparencyAction.AcceptPolicy,
            DataTransparencyAction.DecideLater,
            DataTransparencyAction.Resumed,
            DataTransparencyAction.MessageShown,
            -> viewModel?.onAction(action)
        }
    }
    when (model) {
        ScreenModel.Opening -> DataTransparencyScreen(DataTransparencyUiState(), onAction)

        // Sin la base no se adivina ningún dato: cada uno dice que no está disponible.
        ScreenModel.Unavailable ->
            DataTransparencyScreen(DataTransparencyUiState(loading = false), onAction)

        is ScreenModel.Ready -> {
            val ready = model.viewModel
            val uiState by ready.uiState.collectAsStateWithLifecycle()
            LifecycleResumeEffect(ready) {
                ready.onAction(DataTransparencyAction.Resumed)
                onPauseOrDispose {}
            }
            LaunchedEffect(uiState.acceptFailed) {
                if (uiState.acceptFailed) {
                    ready.onAction(DataTransparencyAction.MessageShown)
                    messages.show(AppSnackbarVisuals(notSaved, SnackbarVariant.ERROR))
                }
            }
            DataTransparencyScreen(uiState, onAction)
        }
    }
}
