package io.github.yesidrobayo.shopledger.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveAppLockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.launch

/**
 * La entrada a Ajustes en la navegación. Si la base no abre, muestra igual región e idioma, que no
 * la necesitan, y deja fuera el perfil del negocio, las propinas y el bloqueo. Atrás con una hoja
 * abierta la cierra primero.
 */
@Composable
fun SettingsEntryRoute(onOpen: (AppNavKey) -> Unit, onBack: () -> Unit) {
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
    val authenticator = LocalDeviceAuthenticator.current
    val scope = rememberCoroutineScope()
    val promptTitle = stringResource(R.string.lock_setting)
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> SettingsEntryScreen(onOpen, onBack)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val security = (securityModel as? ScreenModel.Ready)?.viewModel
            val securityState = security?.uiState?.collectAsStateWithLifecycle()?.value
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
            )
        }
    }
}
