package io.github.yesidrobayo.shopledger.ui.storage

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.erase.EraseMyDataUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.lock.SecureWhileShown
import io.github.yesidrobayo.shopledger.lock.SensitiveScreen
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.backupChipLook
import io.github.yesidrobayo.shopledger.ui.backup.restartForRestore
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusChip
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.settings.DeleteGuideSheet
import io.github.yesidrobayo.shopledger.ui.settings.SettingsOverviewViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * `Almacenamiento y borrado` en la navegación, también desde los ajustes de Android.
 *
 * - El estado de las copias es el mismo de Ajustes, y tocarlo lleva al respaldo.
 * - Toda la pantalla descarta los toques tapados por otra app, y mientras se escribe la palabra o
 *   se borra, la ventana no deja capturas.
 * - Borrar pide antes la verificación del sistema si el bloqueo de la app está encendido.
 * - Al terminar, la app se reinicia como recién instalada. Mientras borra, atrás no sale.
 */
@Composable
fun StorageRoute(key: AppNavKey.StorageAndDeletion, navigator: AppNavigator) {
    val context = LocalContext.current
    val confirmWord = stringResource(R.string.erase_confirm_word)
    val model =
        rememberAppViewModel(key = "storage") { dependencies ->
            val app = dependencies.container
            val erase = EraseMyDataUseCase(app.data.eraseMyData, app.data.businessAuditRecorder)
            StorageViewModel(
                usage = { app.data.storage.usage() },
                summary = { app.data.storage.summary() },
                erase = { erase(it) },
                resume = { erase.resume() },
                deviceOnly = { erase.deviceOnly() },
                backupNow = { app.data.backupScheduler.backupNow() },
                confirmWord = confirmWord,
                computation = app.dispatchers.io,
                fromAndroid = key.fromAndroid,
            )
        }
    val overviewModel =
        rememberAppViewModel(key = "storage-overview") { dependencies ->
            val ports = dependencies.ports
            val app = dependencies.container
            SettingsOverviewViewModel(
                backupOverview = { refreshes ->
                    ObserveBackupOverviewUseCase(ports.backupStatus, app.clock)(refreshes)
                },
                describeDestination = {
                    BackupDestinationReader(app.data.backupDestination).describe()
                },
                zones =
                    BusinessCalendarSource(ports.settings).observe().map {
                        (it as? BusinessCalendarResult.Ready)?.calendar?.zone
                    },
                computation = app.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> StorageScreen(StorageUiState(fromAndroid = key.fromAndroid)) {}

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val overviewViewModel = (overviewModel as? ScreenModel.Ready)?.viewModel
            val overview = overviewViewModel?.uiState?.collectAsStateWithLifecycle()?.value
            var guideOpen by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val sensitive = rememberSensitiveCheck()
            val eraseTitle = stringResource(R.string.erase_title)
            val messages = LocalAppMessages.current
            val obscured = stringResource(R.string.security_obscured_touch)
            SensitiveScreen {
                scope.launch { messages.show(AppSnackbarVisuals(obscured, SnackbarVariant.INFO)) }
            }
            val flow = uiState.flow
            if (flow is EraseFlow.Confirm || flow is EraseFlow.Running) SecureWhileShown()
            LifecycleResumeEffect(viewModel, overviewViewModel) {
                viewModel.onAction(StorageAction.Refresh)
                overviewViewModel?.onReturnToForeground()
                onPauseOrDispose {}
            }
            LaunchedEffect(uiState.finished) {
                if (uiState.finished) restartForRestore(context)
            }
            BackHandler(enabled = flow != null || guideOpen) {
                if (guideOpen) guideOpen = false else viewModel.onAction(StorageAction.StepBack)
            }
            val backup = overview?.backup
            val backupStatus: (@Composable () -> Unit)? =
                if (backup == null) {
                    null
                } else {
                    {
                        BackupStatusChip(
                            backupChipLook(backup),
                            onClick = { navigator.open(AppNavKey.Backup) },
                        )
                    }
                }
            StorageScreen(
                uiState = uiState,
                backupStatus = backupStatus,
                topBarActions = { ScreenHelpLink(HelpTopic.STORAGE) },
            ) { action ->
                when (action) {
                    StorageAction.Back -> navigator.back()

                    StorageAction.OpenLocalCopies, StorageAction.ExportFirst ->
                        navigator.open(AppNavKey.LocalCopies)

                    StorageAction.Reconnect -> navigator.open(AppNavKey.Backup)

                    StorageAction.OpenGuide -> guideOpen = true

                    // La palabra ya coincide: falta la verificación del sistema, si la pide.
                    StorageAction.EraseAuthorized, StorageAction.DeviceOnlyAuthorized ->
                        scope.launch {
                            if (sensitive.confirm(eraseTitle)) viewModel.onAction(action)
                        }

                    else -> viewModel.onAction(action)
                }
            }
            if (guideOpen) DeleteGuideSheet(onDismiss = { guideOpen = false })
        }
    }
}
