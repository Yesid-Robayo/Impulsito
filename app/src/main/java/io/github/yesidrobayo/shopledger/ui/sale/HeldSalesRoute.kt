package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.DiscardHeldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveHeldSalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RenameHeldSaleUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * Las ventas en espera en la navegación: arma su ViewModel con los casos de uso y resuelve adónde
 * se va. Retomar vuelve a la venta nueva si está abierta debajo, o abre una, y ella toma la venta
 * elegida. Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun HeldSalesRoute(navigator: AppNavigator) {
    val key = AppNavKey.HeldSales
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val container = dependencies.container
            val calendar = BusinessCalendarSource(ports.settings)
            val catalog =
                ObserveSaleCatalogUseCase(
                    ports.products,
                    ports.units,
                    ports.saleFrequency,
                    ports.paymentMethods,
                    calendar,
                    ports.taxSettings,
                    ports.taxRates,
                    container.clock,
                )
            val observe = ObserveHeldSalesUseCase(ports.heldSales)
            val rename = RenameHeldSaleUseCase(ports.transactions, ports.heldSales, container.clock)
            val discard = DiscardHeldSaleUseCase(ports.transactions, ports.heldSales)
            HeldSalesViewModel(
                heldSales = { observe() },
                catalog = { catalog() },
                calendar = { calendar.observe() },
                rename = { id, label -> rename(id, label) },
                discard = { discard(it) },
                saleInProgress = { navigator.saleOpen && navigator.heldSales.saleHasLines },
                editingHeldSaleId = {
                    if (navigator.saleOpen) navigator.heldSales.editingHeldSaleId else null
                },
                computation = container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> HeldSalesScreen(HeldSalesUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.resume) {
                val chosen = uiState.resume ?: return@LaunchedEffect
                viewModel.onAction(HeldSalesAction.ResumeShown)
                navigator.resumeHeldSale(chosen)
            }
            HeldSalesScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        HeldSalesAction.Back -> navigator.back()

                        HeldSalesAction.GoSell ->
                            if (navigator.saleOpen) navigator.back() else navigator.sell()

                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}
