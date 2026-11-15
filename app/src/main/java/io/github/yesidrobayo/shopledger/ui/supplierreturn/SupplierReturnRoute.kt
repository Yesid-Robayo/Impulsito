package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePayingAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ObserveSupplierReturnFormUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.MarkUnsavedWork
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * La devolución a proveedor en la navegación: arma su ViewModel con los casos de uso y resuelve
 * adónde se va.
 *
 * Al registrar, vuelve al origen con `Devolución registrada` y una vibración de confirmación. Atrás
 * con algo escrito pregunta antes de descartarlo. Confirmar la moneda abre la región y, al volver,
 * lo escrito sigue ahí. Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun SupplierReturnRoute(key: AppNavKey.SupplierReturn, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val observe = ObserveSupplierReturnFormUseCase(
                products = ports.products,
                units = ports.units,
                suppliers = ports.suppliers,
                purchases = ports.purchases,
                purchaseReturns = ports.purchaseReturns,
            )
            val register = RegisterSupplierReturnUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                products = ports.products,
                units = ports.units,
                purchases = ports.purchases,
                purchaseReturns = ports.purchaseReturns,
                suppliers = ports.suppliers,
                supplierLedger = ports.supplierLedger,
                systemRows = ports.systemRows,
                ledger = ports.stockLedger,
                clock = container.clock,
                money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                alerts = ports.lowStockAlerts,
                lots = ports.stockLots,
            )
            SupplierReturnViewModel(
                formSource = { purchaseId, supplierId -> observe(purchaseId, supplierId) },
                register = { register(it) },
                purchasesOf = { supplierId ->
                    val supplier = supplierId?.let(SupplierFilter::Of) ?: SupplierFilter.Any
                    ports.purchaseHistory.page(
                        PurchaseHistoryFilter(supplier = supplier),
                        after = null,
                        limit = PURCHASE_CHOICES,
                    )
                },
                savedState = savedState,
                quantitySymbols = formats.quantity.inputSymbols,
                computation = container.dispatchers.default,
                prefillPurchaseId = key.purchaseId,
                prefillSupplierId = key.supplierId,
                payingAccounts = ObservePayingAccountsUseCase(
                    ports.moduleUsage,
                    ports.settings,
                    ports.moneyAccounts,
                )(),
            )
        }
    when (model) {
        ScreenModel.Opening ->
            SupplierReturnScreen(
                SupplierReturnUiState(
                    withPurchase = key.purchaseId != null,
                ),
                onAction = {
                },
            )

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val closed by viewModel.closed.collectAsStateWithLifecycle()
            val haptics = LocalHapticFeedback.current
            val messages = LocalAppMessages.current
            val registered = uiState.registered
            val viewLabel = stringResource(R.string.action_view)
            val registeredText = registered?.let {
                stringResource(
                    R.string.supplier_return_success,
                    rememberMoneyDisplay(it, formats.money).text.text,
                )
            }
            LaunchedEffect(registered) {
                if (registered == null || registeredText == null) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                val returnId = uiState.registeredId
                messages.show(
                    AppSnackbarVisuals(registeredText, SnackbarVariant.SUCCESS, viewLabel),
                ) {
                    returnId?.let { navigator.open(AppNavKey.SupplierReturnDetail(it)) }
                }
                viewModel.onAction(SupplierReturnAction.RegisteredShown)
            }
            LaunchedEffect(closed) {
                if (closed) navigator.back()
            }
            // Con algo escrito, atrás pregunta antes de descartarlo; sin nada, sale sin más.
            MarkUnsavedWork(key, uiState.hasInput && uiState.registered == null)
            BackHandler(enabled = uiState.hasInput && uiState.registered == null) {
                viewModel.onAction(SupplierReturnAction.Close)
            }
            SupplierReturnScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    if (action == SupplierReturnAction.ConfirmCurrency) {
                        navigator.open(AppNavKey.RegionalSettings())
                    }
                },
            )
        }
    }
}

/** Cuántas compras ofrece la hoja de la compra de origen: las más recientes. */
private const val PURCHASE_CHOICES = 50
