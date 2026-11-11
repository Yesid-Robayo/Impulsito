package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt

/**
 * La pantalla del respaldo en la navegación. Al volver a primer plano recalcula el estado: un día
 * sin copia se ve aunque nada haya cambiado mientras tanto.
 */
@Composable
fun BackupRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val container = dependencies.container
            val overview = ObserveBackupOverviewUseCase(ports.backupStatus, container.clock)
            BackupViewModel(
                overview = { refreshes -> overview(refreshes) },
                destination = BackupDestinationReader(container.data.backupDestination),
                calendar = BusinessCalendarSource(ports.settings).observe(),
                requestBackup = { container.data.backupScheduler.backupNow() },
                computation = container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> BackupScreen(BackupUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Backup, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val policy = LocalPolicyPrompt.current
            val noInternet = stringResource(R.string.backup_now_no_internet)
            LaunchedEffect(uiState.message) {
                when (uiState.message) {
                    BackupMessage.NO_INTERNET -> {
                        viewModel.onAction(BackupAction.MessageShown)
                        messages.show(AppSnackbarVisuals(noInternet, SnackbarVariant.INFO))
                    }

                    null -> Unit
                }
            }
            LifecycleResumeEffect(viewModel) {
                viewModel.onReturnToForeground()
                onPauseOrDispose {}
            }
            BackupScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        BackupAction.Back -> navigator.back()

                        BackupAction.ChooseDestination ->
                            navigator.open(AppNavKey.BackupDestinationChoice())

                        BackupAction.ReviewPolicy -> policy.review()

                        BackupAction.BackUpNow,
                        BackupAction.RetryCopies,
                        BackupAction.MessageShown,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}
