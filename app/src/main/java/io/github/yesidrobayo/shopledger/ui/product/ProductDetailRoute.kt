package io.github.yesidrobayo.shopledger.ui.product

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockAdjustmentUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.Flow

/**
 * El detalle de un producto en la navegación. Resuelve vender, comprar, ajustar, editar y abrir el
 * documento de una fila; al eliminarlo, vuelve atrás con su aviso. Atrás con la hoja abierta la
 * cierra primero.
 */
@Composable
fun ProductDetailRoute(key: AppNavKey.ProductDetail, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        ProductNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
            percentSymbols = formats.percent.inputSymbols,
        )
    val model =
        rememberAppViewModel(key = "product-${key.productId}") { dependencies ->
            ProductDetailViewModel(
                key.productId,
                ProductDetailUseCases.of(dependencies),
                numbers,
                openCostSheet = key.openCostSheet,
                openPriceSheet = key.openPriceSheet,
            )
        }
    when (model) {
        ScreenModel.Opening -> ProductDetailScreen(ProductDetailUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ShowMessage(uiState.message) { viewModel.onAction(ProductDetailAction.MessageShown) }
            LaunchedEffect(uiState.closed) { if (uiState.closed) navigator.back() }
            BackHandler(enabled = uiState.priceSheet != null) {
                viewModel.onAction(ProductDetailAction.DismissPriceSheet)
            }
            BackHandler(enabled = uiState.costSheet != null) {
                viewModel.onAction(ProductDetailAction.DismissCostSheet)
            }
            ProductDetailScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        ProductDetailAction.Back -> navigator.back()

                        ProductDetailAction.Edit ->
                            navigator.open(AppNavKey.ProductEdit(key.productId))

                        ProductDetailAction.Sell ->
                            navigator.open(AppNavKey.NewSale(prefillProductId = key.productId))

                        ProductDetailAction.RegisterPurchase ->
                            navigator.open(AppNavKey.Purchase(prefillProductId = key.productId))

                        ProductDetailAction.AdjustStock ->
                            navigator.open(AppNavKey.StockAdjustment(key.productId))

                        is ProductDetailAction.OpenDocument ->
                            when (val document = action.document) {
                                is MovementDocument.Sale ->
                                    navigator.open(AppNavKey.SaleDetail(document.saleId))

                                is MovementDocument.Purchase ->
                                    navigator.open(AppNavKey.PurchaseDetail(document.purchaseId))
                            }

                        ProductDetailAction.OpenPriceSheet, is ProductDetailAction.PriceText,
                        ProductDetailAction.SavePrice, ProductDetailAction.DismissPriceSheet,
                        is ProductDetailAction.SuggestionTarget,
                        ProductDetailAction.UseSuggestedPrice,
                        ProductDetailAction.FixCostForSuggestion,
                        is ProductDetailAction.SelectTab, ProductDetailAction.OpenCostSheet,
                        is ProductDetailAction.CostText, is ProductDetailAction.CostNote,
                        ProductDetailAction.SaveCost, ProductDetailAction.DismissCostSheet,
                        ProductDetailAction.AskArchive, ProductDetailAction.AskDelete,
                        is ProductDetailAction.AskVoid,
                        ProductDetailAction.Reactivate, ProductDetailAction.ConfirmDialog,
                        ProductDetailAction.DismissDialog, ProductDetailAction.Retry,
                        ProductDetailAction.MessageShown,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla: así
 * `Producto eliminado` se ve en la lista a la que se vuelve.
 */
@Composable
private fun ShowMessage(message: ProductDetailMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                ProductDetailMessage.CostCorrected -> R.string.product_cost_corrected

                ProductDetailMessage.PriceChanged -> R.string.product_price_changed

                ProductDetailMessage.Archived -> R.string.product_archived

                ProductDetailMessage.Reactivated -> R.string.product_reactivated

                ProductDetailMessage.Deleted -> R.string.product_deleted

                ProductDetailMessage.AdjustmentVoided -> R.string.stock_movement_adjustment_void

                ProductDetailMessage.NameTaken -> R.string.error_product_reactivate_duplicate

                ProductDetailMessage.ParentArchived ->
                    R.string.error_product_reactivate_parent_archived

                ProductDetailMessage.HasHistory -> R.string.product_error_has_history

                is ProductDetailMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val variant = when (message) {
        null, ProductDetailMessage.CostCorrected, ProductDetailMessage.PriceChanged,
        ProductDetailMessage.Archived,
        ProductDetailMessage.Reactivated, ProductDetailMessage.Deleted,
        ProductDetailMessage.AdjustmentVoided,
        -> SnackbarVariant.SUCCESS

        ProductDetailMessage.NameTaken, ProductDetailMessage.HasHistory,
        ProductDetailMessage.ParentArchived,
        is ProductDetailMessage.Failed,
        -> SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}

/** Las operaciones del detalle sobre los casos de uso del catálogo y del libro. */
class ProductDetailUseCases(
    private val observe: ObserveProductOverviewUseCase,
    private val correct: CorrectCostUseCase,
    private val changePriceUseCase: ChangePriceUseCase,
    private val archiveUseCase: ArchiveProductUseCase,
    private val reactivateUseCase: ReactivateProductUseCase,
    private val deleteUseCase: DeleteProductUseCase,
    private val voidUseCase: VoidStockAdjustmentUseCase,
    private val pricingUseCase: ObservePricingContextUseCase,
) : ProductDetailOperations {
    override fun overview(productId: Long): Flow<ProductOverview?> = observe(productId)

    override fun pricing(): Flow<PricingContext> = pricingUseCase()

    override suspend fun correctCost(
        productId: Long,
        cost: Money,
        note: String?,
    ): CorrectCostResult = correct(productId, cost, note)

    override suspend fun changePrice(productId: Long, price: Money): ChangePriceResult =
        changePriceUseCase(productId, price)

    override suspend fun archive(productId: Long): ArchiveProductResult = archiveUseCase(productId)

    override suspend fun reactivate(productId: Long): ReactivateProductResult =
        reactivateUseCase(productId)

    override suspend fun delete(productId: Long): DeleteProductResult = deleteUseCase(productId)

    override suspend fun voidAdjustment(movementId: Long): VoidAdjustmentResult =
        voidUseCase(movementId)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): ProductDetailUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            val calendar = BusinessCalendarSource(ports.settings)
            return ProductDetailUseCases(
                observe = ObserveProductOverviewUseCase(
                    ports.products,
                    ports.units,
                    ports.stockLedger,
                    ports.priceHistory,
                    ports.movementDocuments,
                    ports.purchases,
                ),
                correct = CorrectCostUseCase(
                    tx,
                    ports.products,
                    ports.systemRows,
                    ports.stockLedger,
                    calendar,
                    CurrencyLock(ports.settings),
                    clock,
                ),
                changePriceUseCase = ChangePriceUseCase(
                    tx,
                    ports.products,
                    ports.systemRows,
                    ports.priceHistory,
                    calendar,
                    CurrencyLock(ports.settings),
                    clock,
                ),
                archiveUseCase = ArchiveProductUseCase(
                    tx,
                    ports.products,
                    ports.lowStockAlerts,
                    clock,
                    ports.productVariants,
                ),
                reactivateUseCase =
                    ReactivateProductUseCase(
                        tx,
                        ports.products,
                        ports.lowStockAlerts,
                        calendar,
                        clock,
                        ports.productVariants,
                    ),
                deleteUseCase = DeleteProductUseCase(tx, ports.products),
                voidUseCase = VoidStockAdjustmentUseCase(
                    tx,
                    ports.products,
                    ports.stockLedger,
                    ports.lowStockAlerts,
                    calendar,
                    clock,
                ),
                pricingUseCase =
                    ObservePricingContextUseCase(ports.settings, ports.categories, ports.taxRates),
            )
        }
    }
}
