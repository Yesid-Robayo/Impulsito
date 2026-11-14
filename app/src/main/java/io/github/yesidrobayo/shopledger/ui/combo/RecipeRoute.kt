package io.github.yesidrobayo.shopledger.ui.combo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveRecipeEditorUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * La receta de un combo en la navegación: arma su ViewModel con los casos de uso y resuelve adónde
 * se va.
 *
 * Al guardar, vuelve con `Combo guardado` y una vibración de confirmación. Atrás con cambios
 * pregunta antes de descartarlos. `Editar producto` abre el formulario del combo, y al volver la
 * receta sigue como estaba. Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun RecipeRoute(key: AppNavKey.Components, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val observe =
                ObserveRecipeEditorUseCase(ports.products, ports.productComponents, ports.units)
            val save = SaveRecipeUseCase(
                ports.transactions,
                ports.products,
                ports.productComponents,
                dependencies.container.clock,
            )
            RecipeViewModel(
                comboId = key.productId,
                editorSource = { observe(key.productId) },
                save = { save(key.productId, it) },
                savedState = savedState,
                quantitySymbols = formats.quantity.inputSymbols,
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> RecipeScreen(RecipeUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val closed by viewModel.closed.collectAsStateWithLifecycle()
            val haptics = LocalHapticFeedback.current
            val messages = LocalAppMessages.current
            val savedText = if (uiState.produced) {
                stringResource(R.string.combo_recipe_saved)
            } else {
                stringResource(R.string.combo_saved)
            }
            LaunchedEffect(uiState.saved) {
                if (!uiState.saved) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                messages.show(AppSnackbarVisuals(savedText, SnackbarVariant.SUCCESS))
                viewModel.onAction(RecipeAction.SavedShown)
            }
            LaunchedEffect(closed) {
                if (closed) navigator.back()
            }
            // Con cambios, atrás pregunta antes de descartarlos; sin ellos, sale sin más.
            BackHandler(enabled = uiState.dirty && !uiState.saved) {
                viewModel.onAction(RecipeAction.Close)
            }
            RecipeScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    if (action == RecipeAction.EditProduct) {
                        navigator.open(AppNavKey.ProductEdit(key.productId))
                    }
                    if (action == RecipeAction.Produce) {
                        navigator.open(AppNavKey.ProductionForm(key.productId))
                    }
                },
            )
        }
    }
}
