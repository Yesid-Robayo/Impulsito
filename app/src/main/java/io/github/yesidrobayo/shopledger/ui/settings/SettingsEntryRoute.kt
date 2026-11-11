package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * La entrada a Ajustes en la navegación. Si la base no abre, muestra igual región e idioma, que no
 * la necesitan, y deja fuera las propinas.
 */
@Composable
fun SettingsEntryRoute(onOpen: (AppNavKey) -> Unit, onBack: () -> Unit) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val set =
                SetTipsEnabledUseCase(
                    ports.transactions,
                    ports.settings,
                    dependencies.container.clock,
                )
            SettingsEntryViewModel(
                tipsSource = { ObserveTipsEnabledUseCase(ports.settings)() },
                setTips = { set(it) },
            )
        }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> SettingsEntryScreen(onOpen, onBack)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            SettingsEntryScreen(
                onOpen = onOpen,
                onBack = onBack,
                tipsEnabled = uiState.tipsEnabled,
                onTipsChange = viewModel::onTipsChange,
                tipsSaveFailed = uiState.saveFailed,
            )
        }
    }
}
