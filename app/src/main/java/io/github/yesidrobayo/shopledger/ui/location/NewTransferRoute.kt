package io.github.yesidrobayo.shopledger.ui.location

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LoadLotChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferUseCase
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
 * El formulario de una transferencia en la navegación. Al registrarla vuelve con `Transferencia
 * registrada` y una vibración de confirmación; atrás con algo escrito pregunta antes de
 * descartarlo.
 */
@Composable
fun NewTransferRoute(key: AppNavKey.NewStockTransfer, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val inventory =
                ObserveInventoryUseCase(ports.products, ports.units, ports.locationStock)
            val calendar = BusinessCalendarSource(ports.settings)
            val register = RegisterStockTransferUseCase(
                ports.transactions,
                ports.products,
                ports.units,
                ports.locations,
                ports.stockTransfers,
                ports.stockLedger,
                calendar,
                clock,
                ports.stockLots,
            )
            val loadLots = LoadLotChoicesUseCase(
                ports.stockLots,
                ports.products,
                ports.systemRows,
                calendar,
                ports.settings,
                clock,
            )
            NewTransferViewModel(
                initialFrom = key.fromLocationId,
                locationsSource = { ports.locations.observe(ArchiveFilter.ACTIVE) },
                stockAt = { inventory(it) },
                register = { register(it) },
                savedState = savedState,
                quantitySymbols = formats.quantity.inputSymbols,
                computation = dependencies.container.dispatchers.default,
                lotChoices = { productId, locationId -> loadLots(productId, locationId) },
            )
        }
    when (model) {
        ScreenModel.Opening -> NewTransferScreen(NewTransferUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val closed by viewModel.closed.collectAsStateWithLifecycle()
            val haptics = LocalHapticFeedback.current
            val messages = LocalAppMessages.current
            val savedText = stringResource(R.string.location_transfer_success)
            LaunchedEffect(uiState.saved) {
                if (!uiState.saved) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                messages.show(AppSnackbarVisuals(savedText, SnackbarVariant.SUCCESS))
                viewModel.onAction(NewTransferAction.SavedShown)
            }
            LaunchedEffect(closed) {
                if (closed) navigator.back()
            }
            // Con algo escrito, atrás pregunta antes de descartarlo; sin nada, sale sin más.
            BackHandler(enabled = uiState.dirty && !uiState.saved) {
                viewModel.onAction(NewTransferAction.Close)
            }
            NewTransferScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    if (action == NewTransferAction.ChooseZone) {
                        navigator.open(AppNavKey.RegionalSettings())
                    }
                },
            )
        }
    }
}
