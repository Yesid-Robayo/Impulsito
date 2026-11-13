package io.github.yesidrobayo.shopledger.ui.product

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveBulkPriceChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveBulkUndoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObservePriceListsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObservePriceReviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.navigation.AppMessages
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.navigation.rememberBulkPriceUndo
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * La actualización masiva en la navegación. Al aplicar vuelve atrás con su aviso, que sobrevive a
 * la pantalla; un fallo se avisa sin salir, con lo elegido intacto.
 */
@Composable
fun BulkPriceRoute(key: AppNavKey.BulkPriceUpdate, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        BulkNumberFormat(
            percentSymbols = formats.percent.inputSymbols,
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
        )
    val model =
        rememberAppViewModel { dependencies ->
            BulkPriceViewModel(
                BulkPriceUseCases.of(dependencies),
                numbers,
                dependencies.container.dispatchers.default,
                initialListId = key.priceListId,
                pendingReview = key.pendingReview,
                review = key.review,
            )
        }
    when (model) {
        ScreenModel.Opening -> BulkPriceScreen(BulkPriceUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val resources = LocalResources.current
            val undo = rememberBulkPriceUndo()
            val labels = BulkUndoLabels(
                undo = stringResource(R.string.action_undo),
                retry = stringResource(R.string.action_retry),
            )
            val message = uiState.message
            LaunchedEffect(message) {
                if (message == null) return@LaunchedEffect
                viewModel.onAction(BulkPriceAction.MessageShown)
                if (message is BulkMessage.Applied) {
                    // El aviso vive en la navegación: sigue a la vista al volver a la lista, con
                    // `Deshacer` mientras se ve.
                    messages.show(bulkMessageVisuals(resources, message, labels.undo)) {
                        undoFromMessage(undo, messages, resources, message.batchId, labels)
                    }
                    navigator.back()
                } else {
                    messages.show(bulkMessageVisuals(resources, message))
                }
            }
            BulkPriceScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action ==
                        BulkPriceAction.Back
                    ) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** Las operaciones de la pantalla sobre los casos de uso de leer y aplicar. */
class BulkPriceUseCases(
    private val choices: ObserveBulkPriceChoicesUseCase,
    private val applyUseCase: ApplyBulkPriceUseCase,
    private val undoOfferUseCase: ObserveBulkUndoUseCase,
    private val undoUseCase: UndoBulkPriceUseCase,
    private val lists: ObservePriceListsUseCase,
    private val pricing: ObservePricingContextUseCase,
    private val review: ObservePriceReviewUseCase? = null,
) : BulkPriceOperations {
    override fun catalog(): Flow<List<Product>> = choices.catalog()

    override fun categories(): Flow<List<Category>> = choices.categories()

    override fun suppliers(): Flow<List<Supplier>> = choices.suppliers()

    override fun defaultStep(): Flow<Money> = choices.defaultStep()

    override suspend fun apply(
        productIds: Collection<Long>,
        request: BulkPriceRequest,
        context: BulkPriceContext,
    ): ApplyBulkPriceResult = applyUseCase(productIds, request, context)

    override fun listChoices(): Flow<BulkListChoices> = combine(
        lists(),
        lists.ownPrices(),
        pricing(),
    ) { overview, byList, context ->
        val byProduct = mutableMapOf<Long, MutableMap<Long, Money>>()
        byList.forEach { (list, prices) ->
            prices.forEach { (product, price) ->
                byProduct.getOrPut(product) { mutableMapOf() }[list] =
                    price
            }
        }
        BulkListChoices(
            (overview.active + overview.archived).sortedBy {
                it.id
            },
            byProduct,
            context,
        )
    }

    override fun reviewIds(): Flow<Set<Long>> =
        review?.let { reviews -> reviews().map { list -> list.map { it.product.id }.toSet() } }
            ?: flowOf(emptySet())

    override fun undoOffer(): Flow<BulkUndoPlan?> = undoOfferUseCase()

    override suspend fun undo(batchId: Uuid): UndoBulkPriceResult = undoUseCase(batchId)

    companion object {
        fun of(dependencies: ScreenDependencies): BulkPriceUseCases {
            val ports = dependencies.ports
            val pricing = ObservePricingContextUseCase(
                ports.settings,
                ports.categories,
                ports.taxRates,
            )
            return BulkPriceUseCases(
                choices = ObserveBulkPriceChoicesUseCase(
                    ports.products,
                    ports.categories,
                    ports.suppliers,
                    ports.settings,
                ),
                applyUseCase = ApplyBulkPriceUseCase(
                    ports.transactions,
                    ports.products,
                    ports.systemRows,
                    ports.priceHistory,
                    BusinessCalendarSource(ports.settings),
                    CurrencyLock(ports.settings),
                    dependencies.container.clock,
                    priceLists = ports.priceLists,
                    productPrices = ports.productPrices,
                ),
                undoOfferUseCase = ObserveBulkUndoUseCase(
                    ports.priceHistory,
                    ports.products,
                    ports.productPrices,
                    ports.systemRows,
                ),
                undoUseCase = UndoBulkPriceUseCase(
                    ports.transactions,
                    ports.products,
                    ports.systemRows,
                    ports.priceHistory,
                    BusinessCalendarSource(ports.settings),
                    dependencies.container.clock,
                    productPrices = ports.productPrices,
                ),
                lists = ObservePriceListsUseCase(ports.priceLists, ports.productPrices),
                pricing = pricing,
                review = ObservePriceReviewUseCase(
                    products = ports.products,
                    pricing = pricing,
                    calendar = BusinessCalendarSource(ports.settings),
                    settings = ports.settings,
                    clock = dependencies.container.clock,
                ),
            )
        }
    }
}

/** Los rótulos del aviso, ya en el idioma de la app. */
private data class BulkUndoLabels(val undo: String, val retry: String)

/**
 * Deshace [batchId] desde el aviso y dice cómo terminó. Si falló, `Reintentar` lo intenta otra vez.
 */
private fun undoFromMessage(
    undo: BulkPriceUndo,
    messages: AppMessages,
    resources: Resources,
    batchId: Uuid,
    labels: BulkUndoLabels,
) {
    undo.undo(batchId) { outcome ->
        if (outcome is BulkMessage.Failed) {
            messages.show(bulkMessageVisuals(resources, outcome, labels.retry)) {
                undoFromMessage(undo, messages, resources, batchId, labels)
            }
        } else {
            messages.show(bulkMessageVisuals(resources, outcome))
        }
    }
}
