package io.github.yesidrobayo.shopledger.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * La entrada a Ajustes en la navegación. Si la base no abre, muestra igual región e idioma, que no
 * la necesitan, y deja fuera el perfil del negocio y las propinas. Atrás con una hoja abierta la
 * cierra primero.
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
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> SettingsEntryScreen(onOpen, onBack)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            )
        }
    }
}
