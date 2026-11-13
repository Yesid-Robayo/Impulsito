package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListsOverview
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ArchivePriceListUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObservePriceListsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.PriceListLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ReactivatePriceListUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.Flow

/**
 * La pantalla de listas de precios en la navegación. Atrás cierra primero una hoja abierta, después
 * el detalle, y al final vuelve a donde se abrió. Mientras la base abre, o si no abre, la pantalla de
 * reserva de la navegación.
 */
@Composable
fun PriceListsRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val resources = LocalResources.current
    val numbers =
        PriceListNumberFormat(
            percentSymbols = formats.percent.inputSymbols,
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
        )
    val model =
        rememberAppViewModel { dependencies ->
            PriceListsViewModel(
                operations = PriceListsUseCases.of(dependencies),
                names = { list ->
                    SeedText.displayName(
                        resources,
                        SeedTable.PRICE_LISTS,
                        list.systemKey,
                        list.name,
                    )
                },
                numbers = numbers,
                formatAdjustment = { adjustmentInputText(it, numbers.percentSymbols) },
                formatPrice = {
                    fixedPointInputText(it.minor, numbers.currencyDecimals, numbers.moneySymbols)
                },
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable ->
            PlaceholderRoute(AppNavKey.PriceLists, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val listEditor by viewModel.listEditor.editor.collectAsStateWithLifecycle()
            val priceEditor by viewModel.priceEditor.editor.collectAsStateWithLifecycle()
            BackHandler(
                enabled =
                    listEditor == null && priceEditor == null && uiState.selectedId != null,
            ) {
                viewModel.onAction(PriceListsAction.CloseDetail)
            }
            PriceListsScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        PriceListsAction.Back -> navigator.back()

                        is PriceListsAction.UpdatePrices ->
                            navigator.open(AppNavKey.BulkPriceUpdate(action.listId))

                        else -> viewModel.onAction(action)
                    }
                },
                listEditor = listEditor,
                onListEditorAction = viewModel.listEditor::onAction,
                priceEditor = priceEditor,
                onPriceEditorAction = viewModel.priceEditor::onAction,
            )
        }
    }
}

/**
 * Un ajuste con signo como lo escribiría la persona con [symbols]: `-15`, `5,5` o `0`. El campo lo
 * vuelve a leer igual.
 */
internal fun adjustmentInputText(adjustment: BasisPoints, symbols: NumberSymbols): String {
    val digits = fixedPointInputText(adjustment.bp.absoluteValue, PERCENT_DECIMALS, symbols)
    return if (adjustment.bp < 0L) "-$digits" else digits
}

private const val PERCENT_DECIMALS = 2

/** Las operaciones de la pantalla sobre los casos de uso de listas de precios. */
class PriceListsUseCases(
    private val observeLists: ObservePriceListsUseCase,
    private val observeProducts: () -> Flow<List<Product>>,
    private val observePricing: () -> Flow<PricingContext>,
    private val saveUseCase: SavePriceListUseCase,
    private val archiveUseCase: ArchivePriceListUseCase,
    private val reactivateUseCase: ReactivatePriceListUseCase,
    private val setPriceUseCase: SetListPriceUseCase,
) : PriceListsOperations {
    override fun lists(): Flow<PriceListsOverview> = observeLists()

    override fun ownPrices(): Flow<Map<Long, Map<Long, Money>>> = observeLists.ownPrices()

    override fun customerCounts(): Flow<Map<Long, Int>> = observeLists.customerCounts()

    override fun products(): Flow<List<Product>> = observeProducts()

    override fun pricing(): Flow<PricingContext> = observePricing()

    override suspend fun saveList(editingId: Long?, draft: PriceListDraft): SavePriceListResult =
        saveUseCase(editingId, draft)

    override suspend fun archive(id: Long): PriceListLifecycleResult = archiveUseCase(id)

    override suspend fun reactivate(id: Long): PriceListLifecycleResult = reactivateUseCase(id)

    override suspend fun setPrice(
        listId: Long,
        productId: Long,
        price: Money?,
    ): SetListPriceResult = setPriceUseCase(listId, productId, price)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): PriceListsUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            return PriceListsUseCases(
                observeLists = ObservePriceListsUseCase(ports.priceLists, ports.productPrices),
                observeProducts = { ports.products.observe(ArchiveFilter.ACTIVE) },
                observePricing = {
                    ObservePricingContextUseCase(ports.settings, ports.categories, ports.taxRates)()
                },
                saveUseCase = SavePriceListUseCase(
                    tx,
                    ports.priceLists,
                    ports.productPrices,
                    clock,
                ),
                archiveUseCase = ArchivePriceListUseCase(tx, ports.priceLists, clock),
                reactivateUseCase = ReactivatePriceListUseCase(tx, ports.priceLists, clock),
                setPriceUseCase =
                    SetListPriceUseCase(
                        tx,
                        ports.priceLists,
                        ports.productPrices,
                        ports.products,
                        ports.priceHistory,
                        BusinessCalendarSource(ports.settings),
                        CurrencyLock(ports.settings),
                        clock,
                    ),
            )
        }
    }
}
