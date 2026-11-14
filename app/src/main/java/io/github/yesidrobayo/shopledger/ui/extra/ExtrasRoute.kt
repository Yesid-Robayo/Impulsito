package io.github.yesidrobayo.shopledger.ui.extra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveModifierEditorUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * Los extras de un producto en la navegación: arma su ViewModel con los casos de uso. Atrás vuelve
 * al formulario del producto: cada cambio ya quedó guardado. Si la base va al rescate, la pantalla
 * no lee nada.
 */
@Composable
fun ExtrasRoute(key: AppNavKey.Extras, navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val observe = ObserveModifierEditorUseCase(ports.products, ports.productModifiers)
            val save = SaveModifiersUseCase(
                ports.transactions,
                ports.products,
                ports.productModifiers,
                dependencies.container.clock,
            )
            ExtrasViewModel(
                editorSource = { observe(key.productId) },
                save = { save(key.productId, it) },
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> ExtrasScreen(ExtrasUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ExtrasScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    if (action == ExtrasAction.Back) navigator.back()
                },
            )
        }
    }
}
