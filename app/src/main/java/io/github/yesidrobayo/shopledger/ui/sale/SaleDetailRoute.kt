package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * El detalle de una venta en la navegación: arma su ViewModel con los casos de uso. Si la base va
 * al rescate, la pantalla no lee nada.
 *
 * @param onBack lo que hace atrás: volver en la pila, o cerrar el panel si el detalle va junto al
 *   historial en una ventana ancha.
 */
@Composable
fun SaleDetailRoute(
    key: AppNavKey.SaleDetail,
    navigator: AppNavigator,
    onBack: () -> Unit = { navigator.back() },
) {
    val model =
        rememberAppViewModel(key = "sale-${key.saleId}") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val observe = ObserveSaleDetailUseCase(ports.sales, ports.salePayments, ports.products)
            val void = VoidSaleUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                products = ports.products,
                sales = ports.sales,
                ledger = ports.stockLedger,
                clock = clock,
            )
            SaleDetailViewModel(
                saleId = key.saleId,
                observeDetail = { observe(it) },
                observeCalendar = { BusinessCalendarSource(ports.settings).observe() },
                observeUnits = { ports.units.observeAll() },
                voidSale = { id, reason, note -> void(id, reason, note) },
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> SaleDetailScreen(SaleDetailUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            SaleDetailScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == SaleDetailAction.Back) onBack() else viewModel.onAction(action)
                },
            )
        }
    }
}
