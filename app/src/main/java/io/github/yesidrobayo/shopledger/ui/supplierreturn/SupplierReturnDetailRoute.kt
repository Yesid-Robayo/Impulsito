package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ObserveSupplierReturnDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * El detalle de una devolución a proveedor en la navegación. Anular la devolución la deja anulada
 * con el motivo de error al registrar: quien anula una devolución corrige una que no debió pasar.
 * Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun SupplierReturnDetailRoute(key: AppNavKey.SupplierReturnDetail, navigator: AppNavigator) {
    val model =
        rememberAppViewModel(key = "supplierReturn-${key.purchaseReturnId}") { dependencies ->
            val ports = dependencies.ports
            val observe = ObserveSupplierReturnDetailUseCase(
                purchaseReturns = ports.purchaseReturns,
                products = ports.products,
                suppliers = ports.suppliers,
                units = ports.units,
            )
            val void = VoidSupplierReturnUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                products = ports.products,
                purchaseReturns = ports.purchaseReturns,
                supplierLedger = ports.supplierLedger,
                ledger = ports.stockLedger,
                clock = dependencies.container.clock,
                money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                alerts = ports.lowStockAlerts,
            )
            SupplierReturnDetailViewModel(
                detailSource = { observe(key.purchaseReturnId) },
                voidReturn = { void(key.purchaseReturnId, VoidReason.ENTRY_ERROR, null) },
            )
        }
    when (model) {
        ScreenModel.Opening -> SupplierReturnDetailScreen(SupplierReturnDetailUiState.Loading, {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            SupplierReturnDetailScreen(uiState, onAction = { action ->
                when (action) {
                    SupplierReturnDetailAction.Back -> navigator.back()

                    SupplierReturnDetailAction.Retry,
                    SupplierReturnDetailAction.AskVoid,
                    SupplierReturnDetailAction.DismissVoid,
                    SupplierReturnDetailAction.ConfirmVoid,
                    SupplierReturnDetailAction.VoidShown,
                    -> viewModel.onAction(action)
                }
            })
        }
    }
}
