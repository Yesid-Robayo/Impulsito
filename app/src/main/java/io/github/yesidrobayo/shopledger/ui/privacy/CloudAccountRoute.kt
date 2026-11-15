package io.github.yesidrobayo.shopledger.ui.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.safety.CloudAccountChecklistUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * `Seguridad de la cuenta de la nube` en la navegación. Lee el destino del teléfono y lo marcado
 * de su archivo; nada de esto usa la red.
 *
 * Cada enlace sale solo por un toque de la persona: `Abrir {provider}` abre esa nube en su propia
 * app, sin conceder ningún permiso, y las guías abren el navegador del sistema con direcciones
 * `https` fijas de la fuente. La app no lee ninguna respuesta.
 */
@Composable
fun CloudAccountRoute(navigator: AppNavigator) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val messages = LocalAppMessages.current
    val noBrowser = stringResource(R.string.transparency_no_browser)
    val openFailed = stringResource(R.string.state_error_read)
    val notSaved = stringResource(R.string.state_error_save)
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val checklist = CloudAccountChecklistUseCase(container.data.cloudAccountChecklist)
            CloudAccountViewModel(
                describeDestination = {
                    BackupDestinationReader(container.data.backupDestination).describe()
                },
                checkedFor = { kind -> checklist.checked(kind) },
                mark = { kind, check, marked -> checklist.mark(kind, check, marked) },
                backupStatus =
                    ObserveBackupOverviewUseCase(dependencies.ports.backupStatus, container.clock)(
                        flowOf(Unit),
                    ).map { it.report.status },
                computation = container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> CloudAccountScreen(CloudAccountUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.CloudAccountSecurity, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LifecycleResumeEffect(viewModel) {
                viewModel.onAction(CloudAccountAction.Refresh)
                onPauseOrDispose {}
            }
            LaunchedEffect(uiState.saveFailed) {
                if (uiState.saveFailed) {
                    viewModel.onAction(CloudAccountAction.MessageShown)
                    messages.show(AppSnackbarVisuals(notSaved, SnackbarVariant.ERROR))
                }
            }
            val onAction: (CloudAccountAction) -> Unit = { action ->
                when (action) {
                    CloudAccountAction.Back -> navigator.back()

                    CloudAccountAction.ChooseDestination ->
                        navigator.open(AppNavKey.BackupDestinationChoice())

                    // La pantalla del respaldo ofrece la salida que corresponde a cada destino.
                    CloudAccountAction.Reconnect -> navigator.open(AppNavKey.Backup)

                    CloudAccountAction.OpenProvider -> {
                        val uri =
                            (uiState.destination.knownOrNull() as? BackupDestinationInfo.Slots)
                                ?.documentUri
                        if (uri == null || !openInProvider(context, uri)) {
                            messages.show(AppSnackbarVisuals(openFailed, SnackbarVariant.INFO))
                        }
                    }

                    is CloudAccountAction.OpenGuide ->
                        when (openInBrowser(context, resources.getString(action.guide.urlRes()))) {
                            LinkOutcome.OPENED -> Unit

                            LinkOutcome.NO_APP, LinkOutcome.NOT_ALLOWED ->
                                messages.show(AppSnackbarVisuals(noBrowser, SnackbarVariant.INFO))
                        }

                    is CloudAccountAction.Mark,
                    CloudAccountAction.Refresh,
                    CloudAccountAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            }
            CloudAccountScreen(uiState, onAction)
        }
    }
}

/**
 * Abre la nube del teléfono en su propia app, con un archivo de las ranuras. No concede ningún
 * permiso sobre él. `false` si ninguna app lo abre.
 */
private fun openInProvider(context: Context, documentUri: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(documentUri)))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
