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
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrections
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveQuotesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareQuoteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveCashRoundingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveHeldSalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumeHeldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LoadLotChoicesUseCase
import io.github.yesidrobayo.shopledger.navigation.AppMessages
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.TopLevelDestination
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.navigation.rememberSaleUndo
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.media.ProvidePhotos
import io.github.yesidrobayo.shopledger.ui.returns.rememberExchangeNoteHandoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * La venta nueva en la navegación: arma su ViewModel con los casos de uso y resuelve adónde se va.
 *
 * Al registrar, vuelve a Inicio con `Venta registrada` y una vibración de confirmación; el mensaje
 * ofrece `Deshacer` durante 10 segundos, que anula la venta como siempre, sin borrar nada. Atrás
 * con productos pregunta antes de descartarlos. Crear un producto que la búsqueda no encontró abre
 * su formulario con lo escrito, y al volver el producto ya está en la venta. Si la base va al
 * rescate, la pantalla no lee nada.
 *
 * La venta nueva de un cambio registra la venta y su devolución juntas, y vuelve al detalle de la
 * venta de origen con `Cambio registrado`, sin `Deshacer`: un cambio se corrige anulando su
 * devolución. Salir sin registrarlo vuelve a la devolución, con lo elegido intacto.
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
    val exchangeNotes = rememberExchangeNoteHandoff()
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
                    priceLists = ports.priceLists,
                    productPrices = ports.productPrices,
                    recipes = ports.productComponents,
                    modifiers = ports.productModifiers,
                    locations = ports.locations,
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
                    loyaltyLedger = ports.loyaltyLedger,
                    moneyAccounts = ports.moneyAccounts,
                    moneyLedger = ports.moneyLedger,
                    priceLists = ports.priceLists,
                    productPrices = ports.productPrices,
                    recipes = ports.productComponents,
                    lineComponents = ports.saleLineComponents,
                    locations = ports.locations,
                    lots = ports.stockLots,
                    quotes = ports.quotes,
                )
            val hold =
                HoldSaleUseCase(
                    ports.transactions,
                    ports.heldSales,
                    ports.products,
                    container.clock,
                    ports.productUnits,
                    ports.priceLists,
                    ports.productPrices,
                )
            val resume =
                ResumeHeldSaleUseCase(
                    ports.transactions,
                    ports.heldSales,
                    ports.products,
                    ports.priceHistory,
                    ports.priceLists,
                )
            val observeHeld = ObserveHeldSalesUseCase(ports.heldSales)
            val reactivate =
                ReactivateProductUseCase(
                    ports.transactions,
                    ports.products,
                    ports.lowStockAlerts,
                    calendar,
                    container.clock,
                    ports.productVariants,
                )
            val registerReturn =
                RegisterSaleReturnUseCase(
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
                    lineComponents = ports.saleLineComponents,
                )
            val exchangeSale = ExchangeSaleUseCase(ports.transactions, register, registerReturn)
            val loadLots = LoadLotChoicesUseCase(
                ports.stockLots,
                ports.products,
                ports.systemRows,
                calendar,
                ports.settings,
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
                customerPriceList = { id -> ports.customers.findById(id)?.priceListId },
                loadCorrection = { saleId ->
                    ObserveSaleDetailUseCase(
                        ports.sales,
                        ports.salePayments,
                        ports.products,
                    )(saleId)
                        .first()
                        ?.let(SaleCorrections::of)
                },
                exchange = key.exchange,
                exchangeSale = { exchangeSale(it) },
                exchangeNote = { key.exchange?.let { exchangeNotes.noteFor(it.saleId) } },
                lotChoices = { productId, locationId -> loadLots(productId, locationId) },
                document = key.document,
                documents = SaleQuoteOperations(dependencies),
            )
        }
    when (model) {
        ScreenModel.Opening -> NewSaleScreen(NewSaleUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> ProvidePhotos {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val haptics = LocalHapticFeedback.current
            val registered = uiState.registered
            val registeredText =
                registered?.let {
                    stringResource(
                        if (it.moneyAccountSkipped) {
                            R.string.confirm_sale_registered_account_skipped
                        } else {
                            R.string.confirm_sale_registered
                        },
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
            val exchangedText = stringResource(R.string.sale_return_exchange_success)
            val quoteSaved = uiState.quoteSaved
            val quoteSavedText = quoteSaved?.let {
                stringResource(
                    R.string.quote_saved,
                    formats.quantity.format(QuantityMilli.ofUnits(it), allowsFraction = false),
                )
            }
            LaunchedEffect(quoteSaved) {
                if (quoteSaved == null || quoteSavedText == null) return@LaunchedEffect
                viewModel.onAction(NewSaleAction.QuoteSavedShown)
                // El mensaje vive en la navegación: sigue a la vista al cerrar la cotización.
                messages.show(AppSnackbarVisuals(quoteSavedText, SnackbarVariant.SUCCESS))
            }
            LaunchedEffect(registered) {
                if (registered == null || registeredText == null) return@LaunchedEffect
                if (registered.exchanged) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    viewModel.onAction(NewSaleAction.RegisteredShown)
                    messages.show(AppSnackbarVisuals(exchangedText, SnackbarVariant.SUCCESS))
                    // La devolución que abrió el cambio ya se registró con él: se cierra también.
                    navigator.back()
                    if (navigator.current is AppNavKey.SaleReturn) navigator.back()
                    return@LaunchedEffect
                }
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
            // Lo que trajo el escáner llega al volver a verse, en el orden en que se leyó.
            LaunchedEffect(viewModel) {
                navigator.scans.toTake.collect { pending ->
                    if (pending.none { it.purpose == ScanPurpose.SALE }) return@collect
                    for (scanned in navigator.scans.take(ScanPurpose.SALE)) {
                        val productId = scanned.productId ?: continue
                        viewModel.onAction(NewSaleAction.AddScanned(productId, scanned.unitId))
                    }
                }
            }
            LaunchedEffect(uiState.closed) {
                if (uiState.closed) navigator.back()
            }
            // Con productos, o en un cambio, atrás pregunta antes de descartarlos; sin ellos, sale.
            BackHandler(enabled = !uiState.cart.isEmpty || uiState.exchange != null) {
                viewModel.onAction(NewSaleAction.Close)
            }
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

                        NewSaleAction.Scan -> navigator.open(AppNavKey.Scanner(ScanPurpose.SALE))

                        is NewSaleAction.QuoteNewCustomer -> navigator.open(AppNavKey.Customers())

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

/** Las cotizaciones de la venta nueva sobre los casos de uso de la base abierta. */
private class SaleQuoteOperations(dependencies: ScreenDependencies) : SaleDocumentOperations {
    private val ports = dependencies.ports
    private val clock = dependencies.container.clock
    private val save = SaveQuoteUseCase(
        transactions = ports.transactions,
        settings = ports.settings,
        quotes = ports.quotes,
        customers = ports.customers,
        clock = clock,
        products = ports.products,
        units = ports.units,
        taxRates = ports.taxRates,
        systemRows = ports.systemRows,
        productUnits = ports.productUnits,
        priceLists = ports.priceLists,
        productPrices = ports.productPrices,
    )
    private val prepare = PrepareQuoteConversionUseCase(
        transactions = ports.transactions,
        settings = ports.settings,
        quotes = ports.quotes,
        products = ports.products,
        customers = ports.customers,
        clock = clock,
        productUnits = ports.productUnits,
        priceLists = ports.priceLists,
        productPrices = ports.productPrices,
    )
    private val observe = ObserveQuotesUseCase(ports.quotes, ports.customers)

    override suspend fun saveQuote(request: QuoteRequest): SaveQuoteResult = save(request)

    override suspend fun quote(quoteId: Long): QuoteView? = observe.detail(quoteId).first()

    override suspend fun conversion(quoteId: Long): QuoteConversionResult = prepare(quoteId)

    override fun customers(): Flow<List<Customer>> = ports.customers.observeSelectable()
}
