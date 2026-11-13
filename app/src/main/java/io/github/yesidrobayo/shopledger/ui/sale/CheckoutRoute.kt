package io.github.yesidrobayo.shopledger.ui.sale

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.FindPaymentImagesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveCashRoundingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.TopLevelDestination
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.customer.CustomerEditorAction
import io.github.yesidrobayo.shopledger.ui.customer.CustomerEditorSheet
import io.github.yesidrobayo.shopledger.ui.customer.rememberContactPicker
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * El cobro en la navegación: arma su ViewModel con la venta que dejó la venta nueva y resuelve
 * adónde se va.
 *
 * Atrás vuelve a la venta con todo lo elegido intacto, sin preguntar. Al registrar, vuelve a Inicio
 * con `Venta registrada` y una vibración de confirmación, igual que la venta común; la venta nueva
 * sale de la pila con él. Sin venta que cobrar, como tras restaurar la pila sin ella, vuelve atrás.
 */
@Composable
fun CheckoutRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        SaleNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
            percentSymbols = formats.percent.inputSymbols,
        )
    val handoff = rememberCheckoutHandoff()
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val observe =
                ObserveSaleCatalogUseCase(
                    ports.products,
                    ports.units,
                    ports.saleFrequency,
                    ports.paymentMethods,
                    BusinessCalendarSource(ports.settings),
                    ports.taxSettings,
                    ports.taxRates,
                    container.clock,
                    cashRounding = { ObserveCashRoundingUseCase(ports.settings)() },
                    productUnits = ports.productUnits,
                    tipsEnabled = { ObserveTipsEnabledUseCase(ports.settings)() },
                )
            val register =
                RegisterSaleUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    products = ports.products,
                    sales = ports.sales,
                    payments = ports.salePayments,
                    units = ports.units,
                    systemRows = ports.systemRows,
                    ledger = ports.stockLedger,
                    methodNames = container.paymentMethodNames,
                    taxRates = ports.taxRates,
                    clock = container.clock,
                    heldSales = ports.heldSales,
                    productUnits = ports.productUnits,
                    alerts = ports.lowStockAlerts,
                    customers = ports.customers,
                    customerLedger = ports.customerLedger,
                )
            val images = FindPaymentImagesUseCase(ports.transactions, ports.mediaAssets)
            val createCustomer =
                CreateCustomerUseCase(ports.transactions, ports.customers, container.clock)
            CheckoutViewModel(
                catalogSource = { observe() },
                registerSale = { register(it) },
                findImage = { images(listOf(it))[it.id] },
                savedState = savedState,
                numbers = numbers,
                offered = { handoff.take() },
                computation = container.dispatchers.default,
                customersSource = { ports.customers.observeSelectable() },
                createCustomer = { form, allowSameName -> createCustomer(form, allowSameName) },
            )
        }
    when (model) {
        ScreenModel.Opening ->
            CheckoutScreen(CheckoutUiState(), onAction = {}, onBack = { navigator.back() })

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Checkout, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val customerEditor by viewModel.customerEditor.editor.collectAsStateWithLifecycle()
            val pickContact = rememberContactPicker(viewModel.customerEditor)
            val messages = LocalAppMessages.current
            val haptics = LocalHapticFeedback.current
            val registered = uiState.registered
            val registeredText =
                registered?.let {
                    stringResource(
                        R.string.confirm_sale_registered,
                        rememberMoneyDisplay(it.total, formats.money).text.text,
                    )
                }
            val viewLabel = stringResource(R.string.action_view)
            LaunchedEffect(registered) {
                if (registered == null || registeredText == null) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.onAction(CheckoutAction.RegisteredShown)
                messages.show(
                    AppSnackbarVisuals(registeredText, SnackbarVariant.SUCCESS, viewLabel),
                ) { navigator.open(AppNavKey.SaleDetail(registered.saleId)) }
                navigator.selectTab(TopLevelDestination.HOME)
            }
            LaunchedEffect(uiState.content) {
                if (uiState.content == CheckoutContent.Unavailable) navigator.back()
            }
            val qr = uiState.qr
            // La imagen de cobro abierta desde un pago se cierra con atrás; abierta desde la venta,
            // atrás vuelve a la venta, como `Volver`.
            BackHandler(enabled = qr != null && !qr.qrOnly) {
                viewModel.onAction(CheckoutAction.CloseQr)
            }
            CheckoutScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == CheckoutAction.CloseQr && qr?.qrOnly == true) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
                onBack = { navigator.back() },
            )
            BackHandler(enabled = customerEditor != null) {
                viewModel.customerEditor.onAction(CustomerEditorAction.Dismiss)
            }
            customerEditor?.let { editor ->
                CustomerEditorSheet(editor, { action ->
                    viewModel.customerEditor.onAction(action)
                    if (action == CustomerEditorAction.PickContact) pickContact()
                })
            }
        }
    }
}
