package io.github.yesidrobayo.shopledger.ui.combo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCombosUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * La lista de combos en la navegación. Un combo abre su receta, y `Nuevo combo` abre el formulario
 * de producto con `Por componentes` ya elegido; al guardarlo, sigue a su receta.
 */
@Composable
fun CombosRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val observe = ObserveCombosUseCase(
                dependencies.ports.products,
                dependencies.ports.productComponents,
            )
            CombosViewModel(
                combos = { observe() },
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> CombosScreen(CombosUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Combos, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            CombosScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        is CombosAction.Open ->
                            navigator.open(AppNavKey.Components(action.comboId))

                        CombosAction.NewCombo ->
                            navigator.open(AppNavKey.ProductEdit(asCombo = true))

                        CombosAction.Back -> navigator.back()

                        is CombosAction.QueryChange, CombosAction.Retry -> Unit
                    }
                },
            )
        }
    }
}
