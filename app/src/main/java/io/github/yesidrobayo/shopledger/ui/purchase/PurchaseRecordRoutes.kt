package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.payable.ObserveOpenCreditPurchasesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.ObservePurchaseBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.ObservePurchaseDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.PurchaseHistoryReader
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.sale.RecordsTab
import kotlinx.coroutines.flow.combine

/**
 * El historial de compras en la navegación. Abrir una compra va a su detalle, o en una ventana ancha
 * la abre a la derecha, sin salir de la lista. `Nueva compra` abre la compra nueva.
 *
 * Como segmento de Registros, con [onSegment], `Ventas` y `Gastos` cambian el contenido ahí
 * mismo, sin abrir otra pantalla. Como destino propio, como desde un proveedor, va sin segmentos y
 * atrás vuelve.
 *
 * @param onSegment elige otro segmento de Registros, o `null` si las compras van como destino
 *   propio.
 */
@Composable
fun PurchaseHistoryRoute(
    key: AppNavKey.PurchaseHistory,
    navigator: AppNavigator,
    onSegment: ((RecordsTab) -> Unit)? = null,
) {
    val asTab = onSegment != null
    val model =
        rememberAppViewModel(
            key = "purchases-${key.supplierId}-${key.withoutSupplier}",
        ) { dependencies ->
            val ports = dependencies.ports
            val reader = PurchaseHistoryReader(ports.purchaseHistory, dependencies.container.clock)
            // Al filtrar se ofrecen todos, también los archivados: sus compras siguen ahí.
            val suppliers =
                combine(
                    ports.suppliers.observe(ArchiveFilter.ACTIVE),
                    ports.suppliers.observe(ArchiveFilter.ARCHIVED),
                ) { active, archived ->
                    (active + archived).map { SupplierChoice(it.id, it.details.name) }
                }
            PurchaseHistoryViewModel(
                reader = reader,
                suppliers = suppliers,
                calendar = BusinessCalendarSource(ports.settings).observe(),
                computation = dependencies.container.dispatchers.io,
                initialSupplier = initialSupplierOf(key),
                openCredit = ObserveOpenCreditPurchasesUseCase(ports.payables)(),
            )
        }
    when (model) {
        ScreenModel.Opening ->
            PurchaseHistoryScreen(PurchaseHistoryUiState(), onAction = {}, asTab = asTab)

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> BoxWithConstraints {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val wide = maxWidth >= WIDE
            PurchaseHistoryScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is PurchaseHistoryAction.OpenPurchase ->
                            if (wide) {
                                viewModel.onAction(action)
                            } else {
                                navigator.open(AppNavKey.PurchaseDetail(action.purchaseId))
                            }

                        PurchaseHistoryAction.NewPurchase -> navigator.open(AppNavKey.Purchase())

                        // Sin segmentos, la flecha de atrás también llega como `Ventas`.
                        PurchaseHistoryAction.ShowSales -> when (onSegment) {
                            null -> navigator.back()
                            else -> onSegment(RecordsTab.SALES)
                        }

                        PurchaseHistoryAction.ShowExpenses ->
                            onSegment?.invoke(RecordsTab.EXPENSES)

                        else -> viewModel.onAction(action)
                    }
                },
                detail = { purchaseId ->
                    PurchaseDetailRoute(
                        AppNavKey.PurchaseDetail(purchaseId),
                        navigator,
                        onBack = { viewModel.onAction(PurchaseHistoryAction.ClosePurchase) },
                    )
                },
                asTab = asTab,
                backupBanner = { RootBackupBanner(navigator::open) },
            )
        }
    }
}

/** El proveedor con que se abre la lista: desde su detalle, o `Sin proveedor`. */
private fun initialSupplierOf(key: AppNavKey.PurchaseHistory): SupplierFilter = when {
    key.withoutSupplier -> SupplierFilter.None
    key.supplierId != null -> SupplierFilter.Of(key.supplierId)
    else -> SupplierFilter.Any
}

/**
 * El detalle de una compra en la navegación: arma su ViewModel con los casos de uso. Si la base va
 * al rescate, la pantalla no lee nada.
 *
 * @param onBack lo que hace atrás: volver en la pila, o cerrar el panel si el detalle va junto al
 *   historial en una ventana ancha.
 */
@Composable
fun PurchaseDetailRoute(
    key: AppNavKey.PurchaseDetail,
    navigator: AppNavigator,
    onBack: () -> Unit = { navigator.back() },
) {
    val model =
        rememberAppViewModel(key = "purchase-${key.purchaseId}") { dependencies ->
            val ports = dependencies.ports
            val observe = ObservePurchaseDetailUseCase(
                purchases = ports.purchases,
                products = ports.products,
                suppliers = ports.suppliers,
                units = ports.units,
                calendar = BusinessCalendarSource(ports.settings),
            )
            val void = VoidPurchaseUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                products = ports.products,
                purchases = ports.purchases,
                ledger = ports.stockLedger,
                clock = dependencies.container.clock,
                alerts = ports.lowStockAlerts,
                supplierLedger = ports.supplierLedger,
                money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                lots = ports.stockLots,
            )
            val balance = ObservePurchaseBalanceUseCase(ports.supplierLedger)
            PurchaseDetailViewModel(
                detailSource = { observe(key.purchaseId) },
                voidPurchase = { reason, note -> void(key.purchaseId, reason, note) },
                purchaseId = key.purchaseId,
                purchaseBalance = { supplierId -> balance(supplierId, key.purchaseId) },
                returnsSource = { ports.purchaseReturns.observeByPurchase(key.purchaseId) },
            )
        }
    when (model) {
        ScreenModel.Opening -> PurchaseDetailScreen(PurchaseDetailUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            // Anulada para corregir: la compra nueva abre con sus datos.
            val corrected = (uiState as? PurchaseDetailUiState.Ready)?.correctedId
            LaunchedEffect(corrected) {
                if (corrected == null) return@LaunchedEffect
                viewModel.onAction(PurchaseDetailAction.CorrectionOpened)
                navigator.open(AppNavKey.Purchase(prefillFromPurchaseId = corrected))
            }
            PurchaseDetailScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        PurchaseDetailAction.Back -> onBack()

                        PurchaseDetailAction.ReturnToSupplier ->
                            navigator.open(AppNavKey.SupplierReturn(purchaseId = key.purchaseId))

                        is PurchaseDetailAction.OpenReturn ->
                            navigator.open(AppNavKey.SupplierReturnDetail(action.purchaseReturnId))

                        is PurchaseDetailAction.OpenPayable ->
                            navigator.open(AppNavKey.PayableDetail(action.supplierId))

                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

// La misma clase de ventana que decide los dos paneles en la pantalla.
private val WIDE = 840.dp
