package io.github.yesidrobayo.shopledger.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveAppLockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.rescue.appVersionOf
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
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
    val authenticator = LocalDeviceAuthenticator.current
    var deviceLockMissing by remember { mutableStateOf(false) }
    val overviewViewModel = (overviewModel as? ScreenModel.Ready)?.viewModel
    LifecycleResumeEffect(authenticator, overviewViewModel) {
        deviceLockMissing = !authenticator.hasDeviceCredential()
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
    val promptTitle = stringResource(R.string.lock_setting)
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable ->
            SettingsEntryScreen(
                onOpen = onOpen,
                onBack = onBack,
                deviceLockMissing = deviceLockMissing,
                version = version,
                links = links,
                readLicense = readLicense,
            )

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val security = (securityModel as? ScreenModel.Ready)?.viewModel
            val securityState = security?.uiState?.collectAsStateWithLifecycle()?.value
            val overview = overviewViewModel?.uiState?.collectAsStateWithLifecycle()?.value
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
