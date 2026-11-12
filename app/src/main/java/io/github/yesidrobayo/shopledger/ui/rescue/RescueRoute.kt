package io.github.yesidrobayo.shopledger.ui.rescue

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.localization.InstalledLanguages
import io.github.yesidrobayo.shopledger.lock.DeviceCredentialCheck
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import io.github.yesidrobayo.shopledger.ui.backup.restartForRestore
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.LanguagePicker
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsAction
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsViewModel
import io.github.yesidrobayo.shopledger.ui.i18n.languageFacts
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla de rescate con sus dependencias: todo sale de la capa de datos sin abrir la base. Va
 * en lugar de toda la navegación, así que no recibe un navegador: nada aquí lleva a otra pantalla.
 *
 * - `Buscar un archivo` abre el selector del sistema, solo para leer.
 * - Una restauración lista reinicia la app, que hace el reemplazo al arrancar.
 * - `Reintentar` también reinicia: la compuerta decide otra vez desde cero.
 * - Compartir la base dañada la empaqueta en `cache/exports/`, y esa carpeta se vacía al volver
 *   del selector.
 * - Restaurar pide siempre la verificación del sistema si el teléfono tiene bloqueo de pantalla:
 *   sin base no se sabe si el bloqueo de la app está encendido. Sin bloqueo de pantalla restaura
 *   igual, y si la verificación se cancela, se queda aquí sin restaurar.
 */
@Composable
fun RescueRoute(cause: RescueReason) {
    val context = LocalContext.current
    val app = context.applicationContext as ShopLedgerApp
    val container = app.container
    val rescue = container.data.rescue
    val io = container.dispatchers.io
    val viewModel: RescueViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        RescueViewModel(
                            cause = cause,
                            destination = BackupDestinationReader(rescue.destination),
                            destinationKind = rescue::savedDestinationKind,
                            hasDamagedOriginal = rescue::hasDamagedOriginal,
                            hasPendingRestore = rescue::hasPendingRestore,
                            use = RestoreBackupUseCase(rescue.restore),
                            computation = io,
                        )
                    }
                },
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val support by
        produceState(emptyList<SupportLine>(), cause) {
            value = withContext(io) { SupportFacts.of(context, cause).lines(context) }
        }
    // El registro de fallos solo se muestra en modo seguro, y solo lo que guardó este teléfono.
    val crashLog by
        produceState<String?>(null, cause) {
            value =
                if (cause == RescueReason.SafeMode) withContext(io) { app.crashReport() } else null
        }
    val supportLabel = stringResource(R.string.settings_support)
    val labels = LocalResources.current
    val authenticator = LocalDeviceAuthenticator.current
    val restoreCheck = remember(authenticator) { DeviceCredentialCheck(authenticator) }
    val restoreTitle = stringResource(R.string.backup_action_restore)
    // Lo que salió por el selector no se guarda: al volver, la carpeta se vacía.
    LaunchedEffect(Unit) { withContext(io) { ExportFolder.clear(context) } }
    val share =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            scope.launch { withContext(io) { ExportFolder.clear(context) } }
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                viewModel.onAction(RescueAction.Restore(BackupAction.FilePicked(it.toString())))
            }
        }
    LaunchedEffect(uiState.restore) {
        if (uiState.restore == RestoreUi.Ready) restartForRestore(context)
    }
    RescueScreen(
        uiState = uiState,
        support = support,
        onAction = { action ->
            when (action) {
                RescueAction.Retry -> restartForRestore(context)

                RescueAction.RetryOpen -> {
                    app.retryOpening()
                    restartForRestore(context)
                }

                RescueAction.CopySupport -> copyToClipboard(
                    context,
                    supportLabel,
                    supportText(support) { label, value ->
                        labels.getString(R.string.support_line, label, value)
                    },
                )

                RescueAction.ShareCrashLog -> {
                    val text = crashLog
                    if (!text.isNullOrEmpty()) {
                        scope.launch {
                            val uri = withContext(io) { exportCrashLog(context, text) }
                            if (uri != null) share.launch(crashLogShareIntent(uri))
                        }
                    }
                }

                RescueAction.ConfirmShareDamaged -> {
                    viewModel.onAction(action)
                    scope.launch {
                        val uri =
                            withContext(io) {
                                exportDamaged(context, rescue::exportDamagedOriginal)
                            }
                        if (uri != null) share.launch(damagedShareIntent(uri))
                    }
                }

                is RescueAction.Restore ->
                    if (action.action == BackupAction.FindFile) {
                        viewModel.onAction(RescueAction.Restore(BackupAction.CloseRestore))
                        picker.launch(arrayOf(ANY_FILE))
                    } else if (action.action == BackupAction.StartRestore) {
                        // Cancelada, no restaura y se queda en el rescate.
                        scope.launch {
                            if (restoreCheck.allows(restoreTitle)) viewModel.onAction(action)
                        }
                    } else {
                        viewModel.onAction(action)
                    }

                RescueAction.ShowCopies,
                RescueAction.CloseCopies,
                RescueAction.RetryCopies,
                RescueAction.ShowSupport,
                RescueAction.CloseSupport,
                RescueAction.ShowLanguage,
                RescueAction.CloseLanguage,
                RescueAction.ShareDamaged,
                RescueAction.BeforeUpdate,
                RescueAction.CloseShareDamaged,
                -> viewModel.onAction(action)
            }
        },
        crashLog = crashLog,
        languageSheet = {
            RescueLanguageSheet(onDismiss = { viewModel.onAction(RescueAction.CloseLanguage) })
        },
    )
}

/**
 * `Idioma` en el rescate: solo el selector, sin el enlace a la configuración regional, que vive en
 * la base. El idioma se guarda fuera de ella, y elegir uno recrea la pantalla en el idioma nuevo.
 */
@Composable
private fun RescueLanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ShopLedgerApp).container
    val resources = context.resources
    val viewModel: LanguageSettingsViewModel =
        viewModel(
            key = LANGUAGE_KEY,
            factory =
                viewModelFactory {
                    initializer {
                        LanguageSettingsViewModel(
                            language = container.language.language,
                            choose = container.language::choose,
                            installed = InstalledLanguages.tags(resources),
                        )
                    }
                },
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiTag = stringResource(R.string.resources_language)
    val facts = remember(uiState.installed, uiTag) { languageFacts(uiState.installed, uiTag) }
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.language_title)) {
        LanguagePicker(
            options = facts.options,
            selected = uiState.selected,
            phoneLanguage = facts.phoneLanguage,
            onSelect = { viewModel.onAction(LanguageSettingsAction.Choose(it)) },
        )
    }
}

/** Cualquier tipo de archivo en el selector: una copia puede llegar sin un tipo conocido. */
private const val ANY_FILE = "*/*"

private const val LANGUAGE_KEY = "rescueLanguage"
