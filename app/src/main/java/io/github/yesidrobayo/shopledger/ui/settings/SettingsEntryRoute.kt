package io.github.yesidrobayo.shopledger.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.export.ExportCsvUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveAppLockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.format.CsvFormatFactory
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.localization.CsvExportNaming
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.permission.LocalNotificationPermission
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionPrompt
import io.github.yesidrobayo.shopledger.ui.rescue.SupportFacts
import io.github.yesidrobayo.shopledger.ui.rescue.SupportLine
import io.github.yesidrobayo.shopledger.ui.rescue.appVersionOf
import io.github.yesidrobayo.shopledger.ui.rescue.copyToClipboard
import io.github.yesidrobayo.shopledger.ui.rescue.supportText
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
import io.github.yesidrobayo.shopledger.ui.theme.LocalPaletteChooser
import io.github.yesidrobayo.shopledger.ui.theme.PaletteChoice
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La entrada a Ajustes en la navegación. Si la base no abre, muestra igual región e idioma, que no
 * la necesitan, y `Ayuda y acerca de`, y deja fuera el respaldo, el perfil del negocio, las propinas,
 * el bloqueo y los datos del negocio. Atrás con una hoja abierta la cierra primero.
 *
 * El aviso de bloqueo de pantalla se mira cada vez que la pantalla vuelve a primer plano: si la
 * persona lo configura en el sistema y vuelve, el aviso ya no está.
 */
@Composable
fun SettingsEntryRoute(onOpen: (AppNavKey) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ShopLedgerApp).container
    val io = container.dispatchers.io
    val percentSymbols = LocalAppFormats.current.percent.inputSymbols
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val set =
                SetTipsEnabledUseCase(
                    ports.transactions,
                    ports.settings,
                    dependencies.container.clock,
                )
            val saveProfile =
                SaveBusinessProfileUseCase(
                    ports.transactions,
                    ports.settings,
                    dependencies.container.clock,
                )
            SettingsEntryViewModel(
                tipsSource = { ObserveTipsEnabledUseCase(ports.settings)() },
                setTips = { set(it) },
                profileSource = { ObserveBusinessProfileUseCase(ports.settings)() },
                saveProfile = { saveProfile(it) },
                percentSymbols = percentSymbols,
            )
        }
    val securityModel =
        rememberAppViewModel { dependencies ->
            val settings = dependencies.ports.settings
            val change = ChangePreferenceUseCase(settings, dependencies.container.clock)
            SecuritySettingsViewModel(
                lockSource = { ObserveAppLockUseCase(settings)() },
                changePreference = { change(it) },
            )
        }
    val overviewModel =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val app = dependencies.container
            SettingsOverviewViewModel(
                backupOverview = { refreshes ->
                    ObserveBackupOverviewUseCase(ports.backupStatus, app.clock)(refreshes)
                },
                describeDestination = {
                    BackupDestinationReader(app.data.backupDestination).describe()
                },
                zones = BusinessCalendarSource(ports.settings).observe().map {
                    (it as? BusinessCalendarResult.Ready)?.calendar?.zone
                },
                currencies = ports.settings.observe().map { it.regional.currency.code },
                language = app.language.language,
                computation = app.dispatchers.io,
            )
        }
    val usageModel =
        rememberAppViewModel { dependencies ->
            val settings = dependencies.ports.settings
            val change = ChangePreferenceUseCase(settings, dependencies.container.clock)
            UsageSettingsViewModel(
                preferences = { settings.observe().map { it.preferences } },
                changePreference = { change(it) },
            )
        }
    val authenticator = LocalDeviceAuthenticator.current
    var deviceLockMissing by remember { mutableStateOf(false) }
    var notificationsBlocked by remember { mutableStateOf(false) }
    val overviewViewModel = (overviewModel as? ScreenModel.Ready)?.viewModel
    LifecycleResumeEffect(authenticator, overviewViewModel) {
        deviceLockMissing = !authenticator.hasDeviceCredential()
        notificationsBlocked = NotificationPermissionPrompt.stockAlertsBlocked(context)
        overviewViewModel?.onReturnToForeground()
        onPauseOrDispose {}
    }
    val version by produceState<String?>(null) { value = withContext(io) { appVersionOf(context) } }
    val policy = LocalPolicyPrompt.current
    val privacyUrl = stringResource(R.string.legal_privacy_url)
    val links =
        SettingsLinks(
            onOpen = onOpen,
            onReviewPolicy = policy::review,
            onOpenTerms = policy::openTerms,
            // Sin la página publicada, los términos dentro de la app: nunca un enlace que no abre.
            onOpenPrivacy = {
                if (privacyUrl.isBlank() || !openInBrowser(context, privacyUrl)) {
                    policy.openTerms()
                }
            },
        )
    val readLicense: suspend (String) -> String? = { asset ->
        withContext(io) { readLicenseText(context, asset) }
    }
    val scope = rememberCoroutineScope()
    val appName = stringResource(R.string.app_name)
    val exportModel =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val settings = dependencies.ports.settings
            val exportCsv =
                ExportCsvUseCase(settings, container.data.csvExport) { tag ->
                    // Una etiqueta que este teléfono no reconoce sale con punto decimal, el de las
                    // hojas de cálculo sin coma decimal.
                    FormatLocale.of(tag)?.let(CsvFormatFactory::decimalSeparator)
                        ?: DecimalSeparator.POINT
                }
            val naming = CsvExportNaming { container.localizedContext.context().resources }
            CsvExportViewModel(
                export = { uri, range, progress -> exportCsv(uri, naming, range, progress) },
                zone = {
                    val current = BusinessCalendarSource(settings).current()
                    (current as? BusinessCalendarResult.Ready)?.calendar?.zone
                },
                calendar = {
                    val current = BusinessCalendarSource(settings).current()
                    (current as? BusinessCalendarResult.Ready)?.calendar
                },
                now = container.clock::now,
                appName = appName,
                computation = container.dispatchers.io,
            )
        }
    val export = (exportModel as? ScreenModel.Ready)?.viewModel
    val exportState = export?.uiState?.collectAsStateWithLifecycle()?.value
    val csvPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_TYPE)) { uri ->
            uri?.let { export?.onAction(CsvExportAction.DocumentCreated(it.toString())) }
        }
    // Con el bloqueo encendido, exportar pide de nuevo la verificación del sistema: el archivo lleva
    // todos los datos del negocio.
    val sensitive = rememberSensitiveCheck()
    val exportTitle = stringResource(R.string.settings_export)
    // Lo último pedido, todo o un rango: `Reintentar` repite eso mismo.
    var lastExport by remember { mutableStateOf<CsvExportAction>(CsvExportAction.Export) }
    val startExport: (CsvExportAction) -> Unit = { request ->
        if (export != null && exportState?.working != true) {
            lastExport = request
            export.onAction(request)
            val name = export.suggestedName()
            scope.launch {
                if (sensitive.confirm(exportTitle)) {
                    // Sin una app que cree documentos no hay selector: es como cancelar.
                    try {
                        csvPicker.launch(name)
                    } catch (_: ActivityNotFoundException) {
                        Unit
                    }
                }
            }
        }
    }
    CsvExportMessages(exportState?.message, onShown = {
        export?.onAction(CsvExportAction.MessageShown)
    }, onRetry = { startExport(lastExport) })
    val notificationPermission = LocalNotificationPermission.current
    // La paleta cambia en pantalla al instante; si no se guarda, vuelve la anterior y se dice.
    val paletteChooser = LocalPaletteChooser.current
    var failedPalette by remember { mutableStateOf<ColorPalette?>(null) }
    val choosePalette: (ColorPalette) -> Unit = { palette ->
        failedPalette = null
        if (paletteChooser != null) {
            scope.launch {
                when (paletteChooser.choose(palette)) {
                    PaletteChoice.SAVED -> Unit
                    PaletteChoice.NOT_SAVED -> failedPalette = palette
                }
            }
        }
    }
    val usageViewModel = (usageModel as? ScreenModel.Ready)?.viewModel
    val onUsageAction: (UsageAction) -> Unit = { action ->
        when (action) {
            // Encender los avisos ofrece el permiso si nunca se pidió; apagarlos no pregunta nada.
            is UsageAction.StockAlerts -> {
                usageViewModel?.onStockAlertsChange(action.enabled)
                if (action.enabled) notificationPermission?.offer()
            }

            is UsageAction.Theme -> usageViewModel?.onThemeChange(action.mode)

            UsageAction.OpenNotificationSettings ->
                NotificationPermissionPrompt.openSettings(context)

            UsageAction.Retry -> usageViewModel?.onRetry()
        }
    }
    val promptTitle = stringResource(R.string.lock_setting)
    val overview = overviewViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // Información para soporte: versión, Android, teléfono, última copia y último código de error.
    // Sin la base, la última copia dice que no está disponible. Nunca un dato del negocio.
    val backup = overview?.backup
    val lastBackup =
        backup?.overview?.lastSuccessAt?.let { at ->
            backup.zone?.let { LocalAppFormats.current.date.dayMonthTime(at, it) }
        }
    val lastError = backup?.overview?.failureCode?.name
    val handoff = backup?.slots == true
    val support by produceState(emptyList<SupportLine>(), lastBackup, lastError, handoff) {
        value = withContext(io) {
            SupportFacts.ofDevice(context, lastError, lastBackup, handoff).lines(context)
        }
    }
    val supportLabel = stringResource(R.string.settings_support)
    val labels = LocalResources.current
    val copySupport: () -> Unit = {
        copyToClipboard(
            context,
            supportLabel,
            supportText(support) { label, value ->
                labels.getString(R.string.support_line, label, value)
            },
        )
    }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable ->
            SettingsEntryScreen(
                onOpen = onOpen,
                onBack = onBack,
                deviceLockMissing = deviceLockMissing,
                version = version,
                links = links,
                readLicense = readLicense,
                support = support,
                onCopySupport = copySupport,
            )

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val security = (securityModel as? ScreenModel.Ready)?.viewModel
            val securityState = security?.uiState?.collectAsStateWithLifecycle()?.value
            val usage = usageViewModel?.uiState?.collectAsStateWithLifecycle()?.value
            BackHandler(enabled = uiState.profileSheet != null) {
                viewModel.onProfileAction(ProfileAction.Dismiss)
            }
            SettingsEntryScreen(
                onOpen = onOpen,
                onBack = onBack,
                tipsEnabled = uiState.tipsEnabled,
                onTipsChange = viewModel::onTipsChange,
                tipsSaveFailed = uiState.saveFailed,
                profile = uiState.profile,
                profileSheet = uiState.profileSheet,
                onProfileAction = viewModel::onProfileAction,
                security = securityState,
                onSecurityAction = { action ->
                    when (action) {
                        // Encender o apagar el bloqueo pide antes la verificación del sistema.
                        is SecurityAction.LockToggle -> scope.launch {
                            val purpose = SecuritySettingsViewModel.purposeFor(action.enabled)
                            val outcome = authenticator.authenticate(purpose, promptTitle, null)
                            security?.onLockAuthenticated(action.enabled, outcome)
                        }

                        is SecurityAction.Timeout -> security?.onTimeoutChange(action.timeout)
                    }
                },
                overview = overview,
                deviceLockMissing = deviceLockMissing,
                version = version,
                links = links,
                readLicense = readLicense,
                csvExport = exportState,
                onCsvExportAction = { action ->
                    when (action) {
                        CsvExportAction.Export, is CsvExportAction.ExportRange ->
                            startExport(action)

                        is CsvExportAction.DocumentCreated,
                        CsvExportAction.ObscuredTouch,
                        CsvExportAction.MessageShown,
                        -> export?.onAction(action)
                    }
                },
                usage = usage,
                notificationsBlocked = notificationsBlocked,
                onUsageAction = onUsageAction,
                onChoosePalette = choosePalette,
                paletteFailed = failedPalette != null,
                onRetryPalette = { failedPalette?.let(choosePalette) },
                support = support,
                onCopySupport = copySupport,
            )
        }
    }
}

/**
 * Abre [url] en el navegador del sistema, sin WebView: la app no lee la respuesta. `false` si no
 * hay ninguna app que lo abra.
 */
private fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Los avisos de la exportación, una sola vez cada uno: guardado, sin espacio u otra falla, estos dos
 * con `Reintentar`, que vuelve a empezar por la verificación y el selector.
 */
@Composable
private fun CsvExportMessages(
    message: CsvExportMessage?,
    onShown: () -> Unit,
    onRetry: () -> Unit,
) {
    val messages = LocalAppMessages.current
    val saved = stringResource(R.string.settings_export_saved)
    val noSpace = stringResource(R.string.settings_export_error_space)
    val failed = stringResource(R.string.settings_export_error)
    val obscured = stringResource(R.string.security_obscured_touch)
    val retry = stringResource(R.string.action_retry)
    LaunchedEffect(message) {
        when (message) {
            CsvExportMessage.SAVED -> {
                onShown()
                messages.show(AppSnackbarVisuals(saved, SnackbarVariant.SUCCESS))
            }

            CsvExportMessage.NO_SPACE -> {
                onShown()
                messages.show(AppSnackbarVisuals(noSpace, SnackbarVariant.ERROR, retry), onRetry)
            }

            CsvExportMessage.FAILED -> {
                onShown()
                messages.show(AppSnackbarVisuals(failed, SnackbarVariant.ERROR, retry), onRetry)
            }

            CsvExportMessage.OBSCURED_TOUCH -> {
                onShown()
                messages.show(AppSnackbarVisuals(obscured, SnackbarVariant.INFO))
            }

            null -> Unit
        }
    }
}

/** El tipo del archivo exportado: un `.zip` con un CSV por archivo. */
private const val ZIP_TYPE = "application/zip"
