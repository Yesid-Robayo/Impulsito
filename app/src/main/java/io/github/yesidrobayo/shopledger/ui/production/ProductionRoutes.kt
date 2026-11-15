package io.github.yesidrobayo.shopledger.ui.production

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.production.LoadProductionLotsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.ObserveProductionCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.ObserveProductionDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LoadLotChoicesUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.MarkUnsavedWork
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * La lista de producciones en la navegación. `Nueva producción` abre el formulario, y sin recetas
 * `Crear una receta` abre la lista de combos y recetas. Anular avisa con `Producción anulada`.
 */
@Composable
fun ProductionsRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val container = dependencies.container
            val catalog =
                ObserveProductionCatalogUseCase(
                    ports.products,
                    ports.productComponents,
                    ports.units,
                )
            val detail = ObserveProductionDetailUseCase(ports.productionBatches)
            val void = VoidProductionUseCase(
                ports.transactions,
                ports.products,
                ports.productionBatches,
                ports.stockLedger,
                ports.lowStockAlerts,
                BusinessCalendarSource(ports.settings),
                container.clock,
                lots = ports.stockLots,
            )
            ProductionsViewModel(
                catalogSource = { catalog() },
                recentSource = { ports.productionBatches.observeRecent(it) },
                detailSource = { detail(it) },
                void = { id, reason -> void(id, reason, note = null) },
                computation = container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> ProductionsScreen(ProductionsUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Production, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val voidedText = stringResource(R.string.production_voided)
            LaunchedEffect(uiState.voided) {
                if (!uiState.voided) return@LaunchedEffect
                messages.show(AppSnackbarVisuals(voidedText, SnackbarVariant.SUCCESS))
                viewModel.onAction(ProductionsAction.VoidedShown)
            }
            ProductionsScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        ProductionsAction.NewProduction ->
                            navigator.open(AppNavKey.ProductionForm())

                        ProductionsAction.CreateRecipe -> navigator.open(AppNavKey.Combos)

                        ProductionsAction.Back -> navigator.back()

                        is ProductionsAction.Open,
                        ProductionsAction.CloseDetail,
                        ProductionsAction.AskVoid,
                        ProductionsAction.ConfirmVoid,
                        ProductionsAction.DismissVoid,
                        ProductionsAction.VoidedShown,
                        ProductionsAction.LoadMore,
                        ProductionsAction.Retry,
                        -> Unit
                    }
                },
            )
        }
    }
}

/**
 * El formulario de una producción en la navegación. Al registrar vuelve con `Producción
 * registrada` y una vibración de confirmación. Atrás con cambios pregunta antes de descartarlos.
 */
@Composable
fun ProductionFormRoute(key: AppNavKey.ProductionForm, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val catalog =
                ObserveProductionCatalogUseCase(
                    ports.products,
                    ports.productComponents,
                    ports.units,
                )
            val calendar = BusinessCalendarSource(ports.settings)
            val register = RegisterProductionUseCase(
                ports.transactions, ports.products, ports.units, ports.productComponents,
                ports.productionBatches, ports.systemRows, ports.stockLedger, ports.lowStockAlerts,
                calendar, CurrencyLock(ports.settings), container.clock, ports.stockLots,
            )
            val loadLots = LoadProductionLotsUseCase(
                ports.stockLots,
                LoadLotChoicesUseCase(
                    ports.stockLots,
                    ports.products,
                    ports.systemRows,
                    calendar,
                    ports.settings,
                    container.clock,
                ),
                calendar,
                container.clock,
            )
            ProductionFormViewModel(
                catalogSource = { catalog() },
                register = { register(it) },
                savedState = savedState,
                quantitySymbols = formats.quantity.inputSymbols,
                initialProductId = key.productId,
                computation = container.dispatchers.default,
                lotsSource = { output, inputs -> loadLots(output, inputs) },
            )
        }
    when (model) {
        ScreenModel.Opening -> ProductionFormScreen(ProductionFormUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val closed by viewModel.closed.collectAsStateWithLifecycle()
            val haptics = LocalHapticFeedback.current
            val messages = LocalAppMessages.current
            val registeredText = stringResource(R.string.production_success)
            LaunchedEffect(uiState.registered) {
                if (!uiState.registered) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                messages.show(AppSnackbarVisuals(registeredText, SnackbarVariant.SUCCESS))
                viewModel.onAction(ProductionFormAction.RegisteredShown)
            }
            LaunchedEffect(closed) {
                if (closed) navigator.back()
            }
            MarkUnsavedWork(key, uiState.dirty && !uiState.registered)
            BackHandler(enabled = uiState.dirty && !uiState.registered) {
                viewModel.onAction(ProductionFormAction.Close)
            }
            ProductionFormScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    if (action == ProductionFormAction.CreateRecipe) {
                        navigator.open(AppNavKey.Combos)
                    }
                },
            )
        }
    }
}
