package io.github.yesidrobayo.shopledger.ui.returns

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePayingAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * La devolución de una venta en la navegación: arma su ViewModel con los casos de uso y resuelve
 * adónde se va.
 *
 * Al registrar, vuelve al detalle de la venta con `Devolución registrada` y una vibración de
 * confirmación. Atrás con algo elegido pregunta antes de descartarlo. Elegir el cliente del saldo a
 * favor abre la lista de clientes, que lo devuelve al volver. Continuar con el cambio abre la venta
 * nueva encima, con lo elegido: esta pantalla queda debajo, intacta, por si la persona sale del
 * cambio sin registrarlo. Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun SaleReturnRoute(key: AppNavKey.SaleReturn, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val notes = rememberExchangeNoteHandoff()
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val observe = ObserveSaleDetailUseCase(
                ports.sales,
                ports.salePayments,
                ports.products,
                ports.saleReturns,
            )
            val register = RegisterSaleReturnUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                products = ports.products,
                sales = ports.sales,
                returns = ports.saleReturns,
                units = ports.units,
                ledger = ports.stockLedger,
                clock = container.clock,
                alerts = ports.lowStockAlerts,
                moneyAccounts = ports.moneyAccounts,
                moneyLedger = ports.moneyLedger,
                customers = ports.customers,
                customerLedger = ports.customerLedger,
            )
            SaleReturnViewModel(
                saleId = key.saleId,
                observeDetail = { observe(it) },
                observeUnits = { ports.units.observeAll() },
                observeCalendar = { BusinessCalendarSource(ports.settings).observe() },
                baseUnits = { ids ->
                    ports.products.findByIds(ids).associate { it.id to it.details.baseUnitId }
                },
                payingAccounts = ObservePayingAccountsUseCase(
                    ports.moduleUsage,
                    ports.settings,
                    ports.moneyAccounts,
                )(),
                observeCustomer = { ports.customers.observeById(it) },
                hasPoints = { ports.loyaltyLedger.findBySale(it).isNotEmpty() },
                register = { register(it) },
                savedState = savedState,
                quantitySymbols = formats.quantity.inputSymbols,
                now = { container.clock.now() },
                computation = container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> SaleReturnScreen(SaleReturnUiState(saleId = key.saleId), onAction = {
        })

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val closed by viewModel.closed.collectAsStateWithLifecycle()
            val haptics = LocalHapticFeedback.current
            val messages = LocalAppMessages.current
            val registered = uiState.registered
            val registeredText = registered?.let {
                stringResource(
                    R.string.sale_return_success,
                    rememberMoneyDisplay(it, formats.money).text.text,
                )
            }
            LaunchedEffect(registered) {
                if (registered == null || registeredText == null) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                messages.show(AppSnackbarVisuals(registeredText, SnackbarVariant.SUCCESS))
                viewModel.onAction(SaleReturnAction.RegisteredShown)
            }
            val exchange = uiState.exchange
            LaunchedEffect(exchange) {
                if (exchange == null) return@LaunchedEffect
                notes.offer(exchange.saleId, uiState.note.trim().takeIf { it.isNotEmpty() })
                viewModel.onAction(SaleReturnAction.ExchangeShown)
                navigator.open(AppNavKey.NewSale(exchange = exchange))
            }
            // El cliente elegido en la lista de clientes llega al volver, una sola vez.
            val picked by navigator.customerPick.toPick.collectAsStateWithLifecycle()
            LaunchedEffect(picked) {
                if (picked == null) return@LaunchedEffect
                navigator.customerPick.take()?.let {
                    viewModel.onAction(SaleReturnAction.CustomerPicked(it))
                }
            }
            LaunchedEffect(closed) {
                if (closed) navigator.back()
            }
            // Con algo elegido, atrás pregunta antes de descartarlo; sin nada, sale sin más.
            BackHandler(enabled = uiState.hasInput && uiState.registered == null) {
                viewModel.onAction(SaleReturnAction.Close)
            }
            SaleReturnScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    if (action == SaleReturnAction.PickCustomer) {
                        navigator.open(AppNavKey.Customers(pickForSale = true))
                    }
                },
            )
        }
    }
}
