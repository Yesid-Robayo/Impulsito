package io.github.yesidrobayo.shopledger.ui.sale

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrections
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveCashRoundingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveHeldSalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumeHeldSaleUseCase
import io.github.yesidrobayo.shopledger.navigation.AppMessages
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.TopLevelDestination
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.navigation.rememberSaleUndo
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.first

/**
 * La venta nueva en la navegación: arma su ViewModel con los casos de uso y resuelve adónde se va.
 *
 * Al registrar, vuelve a Inicio con `Venta registrada` y una vibración de confirmación; el mensaje
 * ofrece `Deshacer` durante 10 segundos, que anula la venta como siempre, sin borrar nada. Atrás
 * con productos pregunta antes de descartarlos. Crear un producto que la búsqueda no encontró abre
 * su formulario con lo escrito, y al volver el producto ya está en la venta. Si la base va al
 * rescate, la pantalla no lee nada.
 */
@Composable
fun NewSaleRoute(key: AppNavKey.NewSale, navigator: AppNavigator) {
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
            val calendar = BusinessCalendarSource(ports.settings)
            val observe =
                ObserveSaleCatalogUseCase(
                    ports.products,
                    ports.units,
                    ports.saleFrequency,
                    ports.paymentMethods,
                    calendar,
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
            val hold =
                HoldSaleUseCase(
                    ports.transactions,
                    ports.heldSales,
                    ports.products,
                    container.clock,
                    ports.productUnits,
                )
            val resume =
                ResumeHeldSaleUseCase(
                    ports.transactions,
                    ports.heldSales,
                    ports.products,
                    ports.priceHistory,
                )
            val observeHeld = ObserveHeldSalesUseCase(ports.heldSales)
            val reactivate =
                ReactivateProductUseCase(
                    ports.transactions,
                    ports.products,
                    ports.lowStockAlerts,
                    calendar,
                    container.clock,
                )
            NewSaleViewModel(
                catalogSource = { observe() },
                registerSale = { register(it) },
                reactivate = { reactivate(it) == ReactivateProductResult.Reactivated },
                savedState = savedState,
                numbers = numbers,
                computation = container.dispatchers.default,
                prefillProductId = key.prefillProductId,
                heldSales = { observeHeld() },
                holdSale = { hold(it) },
                resumeHeldSale = { resume(it) },
                prefillFromSaleId = key.prefillFromSaleId,
                prefillCustomerId = key.prefillCustomerId,
                loadCorrection = { saleId ->
                    ObserveSaleDetailUseCase(
                        ports.sales,
                        ports.salePayments,
                        ports.products,
                    )(saleId)
                        .first()
                        ?.let(SaleCorrections::of)
                },
            )
        }
    when (model) {
        ScreenModel.Opening -> NewSaleScreen(NewSaleUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            val undoLabel = stringResource(R.string.action_undo)
            val undoTexts =
                SaleUndoTexts(
                    undone = stringResource(R.string.confirm_sale_voided),
                    failed = stringResource(R.string.error_sale_void),
                    retry = stringResource(R.string.action_retry),
                )
            val undo = rememberSaleUndo()
            LaunchedEffect(registered) {
                if (registered == null || registeredText == null) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.onAction(NewSaleAction.RegisteredShown)
                // El mensaje vive en la navegación: sigue a la vista después de cerrar la venta.
                messages.show(SaleUndo.registeredMessage(registeredText, undoLabel)) {
                    undoSale(undo, messages, registered.saleId, undoTexts)
                }
                navigator.selectTab(TopLevelDestination.HOME)
            }
            // La lista de ventas en espera pregunta antes de cambiar esta venta por otra.
            SideEffect {
                navigator.heldSales.saleHasLines = !uiState.cart.isEmpty
                navigator.heldSales.editingHeldSaleId = uiState.heldSaleId
            }
            LaunchedEffect(viewModel) {
                navigator.heldSales.toResume.collect { chosen ->
                    if (chosen != null) {
                        navigator.heldSales.take()
                        viewModel.onAction(NewSaleAction.ResumeHeld(chosen))
                    }
                }
            }
            LaunchedEffect(uiState.closed) {
                if (uiState.closed) navigator.back()
            }
            // Con productos, atrás pregunta antes de descartarlos; sin ellos, sale sin más.
            BackHandler(enabled = !uiState.cart.isEmpty) { viewModel.onAction(NewSaleAction.Close) }
            NewSaleScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        is NewSaleAction.CreateProduct ->
                            navigator.open(AppNavKey.ProductEdit(prefillName = action.name))

                        NewSaleAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings())

                        NewSaleAction.OpenCheckout, NewSaleAction.ShowQr ->
                            viewModel.checkoutDraft()?.let { draft ->
                                handoff.offer(draft, qrOnly = action == NewSaleAction.ShowQr)
                                navigator.open(AppNavKey.Checkout)
                            }

                        NewSaleAction.OpenHeldSales -> navigator.open(AppNavKey.HeldSales)

                        else -> Unit
                    }
                },
            )
        }
    }
}

/** Deshace [saleId] y dice cómo terminó. Si falló, `Reintentar` lo intenta otra vez. */
private fun undoSale(undo: SaleUndo, messages: AppMessages, saleId: Long, texts: SaleUndoTexts) {
    undo.undo(saleId) { outcome ->
        messages.show(SaleUndo.outcomeMessage(outcome, texts)) {
            undoSale(undo, messages, saleId, texts)
        }
    }
}
