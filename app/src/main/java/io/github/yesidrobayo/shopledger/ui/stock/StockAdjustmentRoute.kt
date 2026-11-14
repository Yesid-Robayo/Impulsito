package io.github.yesidrobayo.shopledger.ui.stock

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveStockAdjustmentSubjectUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat

/**
 * El ajuste de stock en la navegación. Al guardar, o si no había diferencias, lo dice con un
 * mensaje breve y vuelve a donde se abrió. Cerrar, también con el gesto de atrás, pregunta antes si
 * hay algo escrito.
 */
@Composable
fun StockAdjustmentRoute(key: AppNavKey.StockAdjustment, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        ProductNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
        )
    val model =
        rememberAppViewModel { dependencies ->
            StockAdjustmentViewModel(useCasesOf(dependencies), numbers, key.productId)
        }
    when (model) {
        ScreenModel.Opening -> StockAdjustmentScreen(StockAdjustmentUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val subject = (uiState.content as? StockAdjustmentContent.Ready)?.subject
            val outcome = uiState.outcome
            val message =
                when (outcome) {
                    is AdjustmentOutcome.Saved ->
                        subject?.let {
                            stringResource(
                                R.string.confirm_stock_adjusted,
                                quantityText(outcome.onHandAfter, unitLabel(it.unit)),
                            )
                        }

                    AdjustmentOutcome.NoDifference ->
                        stringResource(R.string.confirm_stock_no_difference)

                    null -> null
                }
            LaunchedEffect(outcome, uiState.closed) {
                if (message != null) {
                    messages.show(AppSnackbarVisuals(message, SnackbarVariant.SUCCESS))
                }
                if (outcome != null || uiState.closed) navigator.back()
            }
            // Con algo escrito, atrás pregunta antes de descartarlo.
            BackHandler(enabled = uiState.dirty) {
                viewModel.onAction(StockAdjustmentAction.Close)
            }
            StockAdjustmentScreen(uiState, onAction = { action ->
                if (action == StockAdjustmentAction.FixCost) {
                    navigator.open(AppNavKey.ProductDetail(key.productId, openCostSheet = true))
                } else {
                    viewModel.onAction(action)
                }
            })
        }
    }
}

private fun useCasesOf(dependencies: ScreenDependencies): StockAdjustmentOperations {
    val ports = dependencies.ports
    val clock = dependencies.container.clock
    val calendar = BusinessCalendarSource(ports.settings)
    val currencyLock = CurrencyLock(ports.settings)
    return StockAdjustmentUseCases(
        observeUseCase =
            ObserveStockAdjustmentSubjectUseCase(
                ports.transactions,
                ports.products,
                ports.units,
                ports.stockLedger,
                ports.locationStock,
            ),
        adjustUseCase =
            AdjustStockUseCase(
                ports.transactions, ports.products, ports.units, ports.systemRows,
                ports.stockLedger, ports.lowStockAlerts, calendar, currencyLock, clock,
                ports.locations, ports.locationStock,
            ),
        openingUseCase =
            RegisterOpeningBalanceUseCase(
                ports.transactions, ports.products, ports.units, ports.systemRows,
                ports.stockLedger, ports.lowStockAlerts, calendar, currencyLock, clock,
            ),
        locationsSource = { ports.locations.observe(ArchiveFilter.ACTIVE) },
    )
}
