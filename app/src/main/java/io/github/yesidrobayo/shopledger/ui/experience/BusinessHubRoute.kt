package io.github.yesidrobayo.shopledger.ui.experience

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.domain.usecase.experience.ObserveModuleUsageUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * El centro `Negocio` en la navegación. Las herramientas se ven enseguida: no esperan a la base, que
 * solo decide cuáles están en uso. Si la base no abre, todas quedan en `Más herramientas`.
 */
@Composable
fun BusinessHubRoute(onOpen: (AppNavKey) -> Unit, onBack: () -> Unit) {
    val tools = rememberHubTools()
    val model =
        rememberAppViewModel { dependencies ->
            BusinessHubViewModel(ObserveModuleUsageUseCase(dependencies.ports.moduleUsage)())
        }
    when (model) {
        ScreenModel.Opening ->
            BusinessHubScreen(BusinessHubUiState(), tools, onAction = {
            }, onOpen = onOpen, onBack = onBack)

        ScreenModel.Unavailable ->
            BusinessHubScreen(
                // Sin base no se puede plegar nada: todos los grupos quedan abiertos.
                BusinessHubUiState(inUse = emptySet(), expanded = ToolGroup.entries.toSet()),
                tools,
                onAction = {},
                onOpen = onOpen,
                onBack = onBack,
            )

        is ScreenModel.Ready -> {
            val uiState by model.viewModel.uiState.collectAsStateWithLifecycle()
            BusinessHubScreen(uiState, tools, model.viewModel::onAction, onOpen, onBack)
        }
    }
}
