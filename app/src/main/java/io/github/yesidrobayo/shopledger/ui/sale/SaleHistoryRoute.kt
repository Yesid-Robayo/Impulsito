package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import kotlinx.coroutines.flow.combine

/**
 * El historial de ventas en la navegación. En un teléfono, tocar una venta abre su detalle encima;
 * en una ventana ancha, a la derecha, sin salir de la lista, y atrás en el detalle lo cierra.
 */
@Composable
fun SaleHistoryRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            // Los métodos que se ofrecen al filtrar: los vigentes y los archivados, que pueden
            // tener ventas viejas.
            val methods =
                combine(
                    ports.paymentMethods.observe(ArchiveFilter.ACTIVE),
                    ports.paymentMethods.observe(ArchiveFilter.ARCHIVED),
                ) { active, archived ->
                    (active + archived).map {
                        SaleMethod(it.id, it.systemKey, it.details.name, hasQr = false)
                    }
                }
            SaleHistoryViewModel(
                reader = SaleHistoryReader(ports.saleHistory, dependencies.container.clock),
                methods = methods,
                calendar = BusinessCalendarSource(ports.settings).observe(),
                computation = dependencies.container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> SaleHistoryScreen(SaleHistoryUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.SaleHistory, navigator)

        is ScreenModel.Ready -> BoxWithConstraints {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val wide = maxWidth >= WIDE
            SaleHistoryScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is SaleHistoryAction.OpenSale ->
                            if (wide) {
                                viewModel.onAction(action)
                            } else {
                                navigator.open(AppNavKey.SaleDetail(action.saleId))
                            }

                        SaleHistoryAction.Sell -> navigator.sell()

                        else -> viewModel.onAction(action)
                    }
                },
                detail = { saleId ->
                    SaleDetailRoute(
                        AppNavKey.SaleDetail(saleId),
                        navigator,
                        onBack = { viewModel.onAction(SaleHistoryAction.CloseSale) },
                    )
                },
            )
        }
    }
}

// La misma clase de ventana que decide los dos paneles en la pantalla.
private val WIDE = 840.dp
