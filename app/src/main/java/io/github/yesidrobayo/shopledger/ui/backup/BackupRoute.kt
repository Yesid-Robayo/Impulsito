package io.github.yesidrobayo.shopledger.ui.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ChangeMeteredBackupPolicyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RecordCloudCheckUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RunRestoreDrillUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
import kotlinx.coroutines.launch

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
                recordCheck = { generation ->
                    RecordCloudCheckUseCase(ports.humanCheck, container.clock)(generation)
                },
                computation = container.dispatchers.io,
                restore = RestoreBackupUseCase(container.data.backupRestore),
                changePolicy = { policy ->
                    ChangeMeteredBackupPolicyUseCase(
                        ports.settings,
                        container.data.backupSettings,
                        container.clock,
                    )(policy)
                },
                releaseCopy = { key -> container.data.backupSettings.releasePinnedCopy(key) },
                uploadedThisMonth = { container.data.backupSettings.uploadedThisMonth() },
                copiesEncrypted = { container.data.backupSettings.copiesEncrypted() },
                link = BackupLinkUseCase(container.data.backupLink),
                drill = RunRestoreDrillUseCase(container.data.restoreDrill),
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
            val checkSaved =
                stringResource(R.string.backup_check_saved, stringResource(R.string.app_name))
            val released = stringResource(R.string.backup_release_done)
            val releaseFailed = stringResource(R.string.backup_release_failed)
            val retry = stringResource(R.string.action_retry)
            val context = LocalContext.current
            // Con el bloqueo encendido, restaurar pide de nuevo la verificación del sistema.
            val sensitive = rememberSensitiveCheck()
            val scope = rememberCoroutineScope()
            val restoreTitle = stringResource(R.string.backup_action_restore)
            val provider = uiState.wording?.providerLabel ?: NO_VALUE_TEXT
            val openFailed =
                stringResource(
                    R.string.backup_check_open_failed,
                    provider,
                    uiState.handoff?.fileName ?: NO_VALUE_TEXT,
                )
            val sessionOpenFailed = stringResource(R.string.backup_session_open_failed, provider)
            val disconnected = stringResource(R.string.confirm_destination_disconnected)
            val disconnectFailed = stringResource(R.string.error_disconnect)
            val pinnedMax = stringResource(R.string.backup_pinned_max, MAX_PINNED_COPIES)
            val unreachable = stringResource(R.string.backup_list_error)
            val conflictOffline = stringResource(R.string.backup_list_no_internet)
            val drillPassed = stringResource(R.string.backup_drill_passed)
            val drillFailed = stringResource(R.string.backup_drill_failed)
            val drillSkipped = stringResource(R.string.backup_drill_skipped)
            val drillNoCopy = stringResource(R.string.backup_drill_no_copy)
            LaunchedEffect(uiState.message) {
                val message = uiState.message ?: return@LaunchedEffect
                viewModel.onAction(BackupAction.MessageShown)
                when (message) {
                    BackupMessage.NO_INTERNET ->
                        messages.show(AppSnackbarVisuals(noInternet, SnackbarVariant.INFO))

                    BackupMessage.CHECK_SAVED ->
                        messages.show(AppSnackbarVisuals(checkSaved, SnackbarVariant.SUCCESS))

                    BackupMessage.RELEASED ->
                        messages.show(AppSnackbarVisuals(released, SnackbarVariant.SUCCESS))

                    BackupMessage.RELEASE_FAILED -> {
                        val failed = (uiState.release as? ReleaseUi.Failed)?.copy?.key
                        messages.show(
                            AppSnackbarVisuals(releaseFailed, SnackbarVariant.ERROR, retry),
                        ) {
                            if (failed != null) viewModel.onAction(BackupAction.ReleaseCopy(failed))
                        }
                    }

                    BackupMessage.DISCONNECTED ->
                        messages.show(AppSnackbarVisuals(disconnected, SnackbarVariant.SUCCESS))

                    BackupMessage.DISCONNECT_FAILED ->
                        messages.show(AppSnackbarVisuals(disconnectFailed, SnackbarVariant.ERROR))

                    BackupMessage.PINNED_MAX ->
                        messages.show(AppSnackbarVisuals(pinnedMax, SnackbarVariant.INFO))

                    BackupMessage.CONFLICT_UNREACHABLE ->
                        messages.show(AppSnackbarVisuals(unreachable, SnackbarVariant.ERROR))

                    BackupMessage.CONFLICT_NO_INTERNET ->
                        messages.show(AppSnackbarVisuals(conflictOffline, SnackbarVariant.INFO))

                    BackupMessage.DRILL_PASSED ->
                        messages.show(AppSnackbarVisuals(drillPassed, SnackbarVariant.SUCCESS))

                    BackupMessage.DRILL_FAILED ->
                        messages.show(AppSnackbarVisuals(drillFailed, SnackbarVariant.ERROR))

                    BackupMessage.DRILL_SKIPPED ->
                        messages.show(AppSnackbarVisuals(drillSkipped, SnackbarVariant.INFO))

                    BackupMessage.DRILL_NO_COPY ->
                        messages.show(AppSnackbarVisuals(drillNoCopy, SnackbarVariant.INFO))
                }
            }
            // El selector del sistema, para `Buscar un archivo`: cualquier tipo, porque una copia
            // puede llegar sin un tipo conocido. Solo se lee, sin permiso persistente.
            val picker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { viewModel.onAction(BackupAction.FilePicked(it.toString())) }
                }
            // Lista para reemplazar: el arranque hace el reemplazo, así que la app se reinicia.
            LaunchedEffect(uiState.restore) {
                if (uiState.restore == RestoreUi.Ready) restartForRestore(context)
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

                        BackupAction.OpenLocalCopies -> navigator.open(AppNavKey.LocalCopies)

                        BackupAction.FindFile -> {
                            viewModel.onAction(BackupAction.CloseRestore)
                            // Desde la hoja del conflicto, el archivo de la ranura se busca aquí.
                            viewModel.onAction(BackupAction.DecideLater)
                            picker.launch(arrayOf(ANY_FILE))
                        }

                        BackupAction.StartRestore -> scope.launch {
                            if (sensitive.confirm(restoreTitle)) viewModel.onAction(action)
                        }

                        // WebDAV pide la contraseña otra vez; la nube del teléfono, sus archivos.
                        BackupAction.Reconnect ->
                            navigator.open(
                                when (uiState.destination) {
                                    is BackupDestinationInfo.WebDav -> AppNavKey.WebDavConnect()
                                    is BackupDestinationInfo.Slots -> AppNavKey.DocumentSlotsSetup
                                    null -> AppNavKey.BackupDestinationChoice()
                                },
                            )

                        // Con la sesión cerrada se inicia sesión en esa nube: nunca archivos nuevos.
                        BackupAction.SignInToProvider -> {
                            val uri = (uiState.destination as? BackupDestinationInfo.Slots)
                                ?.documentUri
                            val opened = uri?.let { openInProvider(context, it) } == true
                            if (!opened) {
                                messages.show(
                                    AppSnackbarVisuals(sessionOpenFailed, SnackbarVariant.INFO),
                                )
                            }
                        }

                        BackupAction.CreateConflictFile -> {
                            viewModel.onAction(action)
                            navigator.open(AppNavKey.DocumentSlotsSetup)
                        }

                        BackupAction.OpenProvider -> {
                            val opened =
                                uiState.handoff?.let { openInProvider(context, it.documentUri) } ==
                                    true
                            if (!opened) {
                                messages.show(AppSnackbarVisuals(openFailed, SnackbarVariant.INFO))
                            }
                        }

                        BackupAction.BackUpNow,
                        BackupAction.RetryCopies,
                        BackupAction.MessageShown,
                        BackupAction.MarkSeen,
                        BackupAction.ShowMissingHelp,
                        BackupAction.CloseMissingHelp,
                        is BackupAction.OpenCopy,
                        is BackupAction.ConfirmCopy,
                        is BackupAction.FilePicked,
                        is BackupAction.Answer,
                        BackupAction.CancelRestore,
                        BackupAction.CloseRestore,
                        BackupAction.OpenMeteredPolicy,
                        is BackupAction.PickMeteredPolicy,
                        BackupAction.ConfirmWifiOnly,
                        BackupAction.CloseMeteredPolicy,
                        is BackupAction.ReleaseCopy,
                        BackupAction.ConfirmRelease,
                        BackupAction.CloseRelease,
                        BackupAction.Disconnect,
                        BackupAction.ConfirmDisconnect,
                        BackupAction.DismissDisconnect,
                        BackupAction.ReviewConflict,
                        BackupAction.RetryConflict,
                        BackupAction.DecideLater,
                        is BackupAction.RestoreCloud,
                        BackupAction.KeepPhone,
                        BackupAction.ConfirmKeep,
                        BackupAction.DismissKeep,
                        BackupAction.RunDrill,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * Abre el documento en la app de su nube, con `ACTION_VIEW` y sin conceder ningún permiso sobre él:
 * esa app ya lo tiene, porque es suyo. Devuelve `false` si ninguna app lo abre, y la pantalla dice
 * cómo buscarlo a mano.
 */
private fun openInProvider(context: Context, documentUri: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(documentUri)))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/**
 * Reinicia la app con el diario de la restauración ya escrito: el arranque hace el reemplazo antes
 * de abrir la base. El proceso termina para que nada siga con la base anterior abierta.
 */
internal fun restartForRestore(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val component = launch.component ?: return
    context.startActivity(Intent.makeRestartActivityTask(component))
    Runtime.getRuntime().exit(0)
}

/** Cualquier tipo de archivo en el selector. */
private const val ANY_FILE = "*/*"

/** Cuántas copias fijadas admite el destino, como dice la regla de retención. */
private const val MAX_PINNED_COPIES = 3
