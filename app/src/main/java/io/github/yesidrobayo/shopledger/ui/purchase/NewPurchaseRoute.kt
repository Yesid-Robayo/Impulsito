package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseCorrections
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePayingAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.ObservePurchaseCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.ObservePurchaseDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.DocumentUndoTexts
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.navigation.rememberPurchaseUndo
import io.github.yesidrobayo.shopledger.navigation.showUndoable
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.first

/**
 * La compra nueva en la navegación: arma su ViewModel con los casos de uso y resuelve adónde se va.
 *
 * Al registrar, la pantalla muestra el resumen del costo con una vibración de confirmación, y
 * `Cerrar` vuelve. Atrás con líneas pregunta antes de descartarlas. Crear un producto que la
 * búsqueda no encontró abre su formulario con lo escrito, y al volver el producto ya está en la
 * compra. Confirmar la moneda abre la región y, al volver, lo escrito sigue ahí. Si la base va al
 * rescate, la pantalla no lee nada.
 */
@Composable
fun NewPurchaseRoute(key: AppNavKey.Purchase, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        PurchaseNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
        )
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val calendar = BusinessCalendarSource(ports.settings)
            val observe =
                ObservePurchaseCatalogUseCase(
                    products = ports.products,
                    units = ports.units,
                    suppliers = ports.suppliers,
                    calendar = calendar,
                    taxSettings = ports.taxSettings,
                    taxRates = ports.taxRates,
                    clock = container.clock,
                    productUnits = ports.productUnits,
                )
            val register =
                RegisterPurchaseUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    products = ports.products,
                    purchases = ports.purchases,
                    suppliers = ports.suppliers,
                    units = ports.units,
                    systemRows = ports.systemRows,
                    ledger = ports.stockLedger,
                    taxRates = ports.taxRates,
                    clock = container.clock,
                    productUnits = ports.productUnits,
                    alerts = ports.lowStockAlerts,
                    categories = ports.categories,
                    supplierLedger = ports.supplierLedger,
                    money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                )
            val reactivate =
                ReactivateProductUseCase(
                    ports.transactions,
                    ports.products,
                    ports.lowStockAlerts,
                    calendar,
                    container.clock,
                    ports.productVariants,
                )
            NewPurchaseViewModel(
                catalogSource = { observe() },
                registerPurchase = { register(it) },
                reactivate = { reactivate(it) == ReactivateProductResult.Reactivated },
                savedState = savedState,
                numbers = numbers,
                computation = container.dispatchers.default,
                prefillProductId = key.prefillProductId,
                prefillSupplierId = key.prefillSupplierId,
                prefillFromPurchaseId = key.prefillFromPurchaseId,
                loadCorrection = { purchaseId ->
                    val detail = ObservePurchaseDetailUseCase(
                        purchases = ports.purchases,
                        products = ports.products,
                        suppliers = ports.suppliers,
                        units = ports.units,
                        calendar = calendar,
                    )(purchaseId).first()
                    detail?.let {
                        // El proveedor se trae solo si hoy se puede elegir.
                        val supplier = it.purchase.content.supplierId
                            ?.let { id -> ports.suppliers.findById(id) }
                        PurchaseCorrections.of(
                            it,
                            supplierAvailable = supplier?.isArchived == false,
                        )
                    }
                },
                payingAccounts = ObservePayingAccountsUseCase(
                    ports.moduleUsage,
                    ports.settings,
                    ports.moneyAccounts,
                )(),
            )
        }
    when (model) {
        ScreenModel.Opening -> NewPurchaseScreen(NewPurchaseUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val haptics = LocalHapticFeedback.current
            val registered = uiState.registered
            val registeredText = registered?.let {
                stringResource(
                    R.string.confirm_purchase_registered,
                    rememberMoneyDisplay(it.total, formats.money).text.text,
                )
            }
            val messages = LocalAppMessages.current
            val undo = rememberPurchaseUndo()
            val undoTexts = DocumentUndoTexts(
                undoLabel = stringResource(R.string.action_undo),
                undone = stringResource(R.string.confirm_purchase_voided),
                failed = stringResource(R.string.error_purchase_void),
                retry = stringResource(R.string.action_retry),
            )
            LaunchedEffect(registered) {
                if (registered == null || registeredText == null) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                // El aviso ofrece `Deshacer` mientras se ve, también después de cerrar el resumen.
                messages.showUndoable(registeredText, registered.purchaseId, undo, undoTexts)
            }
            // Lo que trajo el escáner llega al volver a verse, en el orden en que se leyó.
            LaunchedEffect(viewModel) {
                navigator.scans.toTake.collect { pending ->
                    if (pending.none { it.purpose == ScanPurpose.PURCHASE }) return@collect
                    for (scanned in navigator.scans.take(ScanPurpose.PURCHASE)) {
                        val productId = scanned.productId ?: continue
                        viewModel.onAction(NewPurchaseAction.AddScanned(productId, scanned.unitId))
                    }
                }
            }
            LaunchedEffect(uiState.closed) {
                if (uiState.closed) navigator.back()
            }
            // Con líneas, atrás pregunta antes de descartarlas; sin ellas, sale sin más.
            BackHandler(enabled = uiState.hasLines && uiState.registered == null) {
                viewModel.onAction(NewPurchaseAction.Close)
            }
            NewPurchaseScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        is NewPurchaseAction.CreateProduct ->
                            navigator.open(AppNavKey.ProductEdit(prefillName = action.name))

                        NewPurchaseAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings())

                        is NewPurchaseAction.ReviewPrice ->
                            navigator.open(AppNavKey.ProductEdit(productId = action.productId))

                        NewPurchaseAction.Scan ->
                            navigator.open(AppNavKey.Scanner(ScanPurpose.PURCHASE))

                        else -> Unit
                    }
                },
            )
        }
    }
}
