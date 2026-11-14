package io.github.yesidrobayo.shopledger.ui.quote

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelQuoteUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveQuotesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareQuoteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRow
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.SaleDocumentMode
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.RecordsTab
import io.github.yesidrobayo.shopledger.ui.sale.shareIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las cotizaciones en la navegación: como segmento de Registros, o como destino propio desde el
 * centro `Negocio`. En un teléfono, tocar una abre su detalle encima; en una ventana ancha, a la
 * derecha, sin salir de la lista.
 *
 * @param onSegment elige otro segmento de Registros, que se dibuja ahí mismo.
 */
@Composable
fun QuotesRoute(
    navigator: AppNavigator,
    asTab: Boolean,
    onSegment: (RecordsTab) -> Unit = {},
) {
    val model = rememberAppViewModel { dependencies ->
        QuotesViewModel(QuoteListUseCases(dependencies))
    }
    when (model) {
        ScreenModel.Opening -> QuotesScreen(QuotesUiState(), onAction = {}, asTab = asTab)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Quotes, navigator)

        is ScreenModel.Ready -> BoxWithConstraints {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val wide = maxWidth >= WIDE
            QuotesScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is QuotesAction.Open ->
                            if (wide) {
                                viewModel.onAction(action)
                            } else {
                                navigator.open(AppNavKey.QuoteDetail(action.quoteId))
                            }

                        QuotesAction.New ->
                            navigator.open(AppNavKey.NewSale(document = SaleDocumentMode.NewQuote))

                        QuotesAction.Back -> navigator.back()

                        else -> viewModel.onAction(action)
                    }
                },
                asTab = asTab,
                onSegment = { index -> onSegment(RecordsTab.entries[index]) },
                backupBanner = { RootBackupBanner(navigator::open) },
                detail = { quoteId ->
                    QuoteDetailRoute(
                        AppNavKey.QuoteDetail(quoteId),
                        navigator,
                        onBack = { viewModel.onAction(QuotesAction.CloseDetail) },
                    )
                },
            )
        }
    }
}

/**
 * El detalle de una cotización en la navegación. Compartir entrega el texto al selector del
 * sistema, por un toque de la persona. Convertir abre la venta con sus líneas; editar, la venta en
 * modo cotización.
 */
@Composable
fun QuoteDetailRoute(
    key: AppNavKey.QuoteDetail,
    navigator: AppNavigator,
    onBack: () -> Unit = { navigator.back() },
) {
    val model = rememberAppViewModel(key = "quote-${key.quoteId}") { dependencies ->
        QuoteDetailViewModel(key.quoteId, QuoteDetailUseCases(dependencies))
    }
    when (model) {
        ScreenModel.Opening -> QuoteDetailScreen(QuoteDetailUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val formats = LocalAppFormats.current
            val chooserTitle = stringResource(R.string.action_share)
            LaunchedEffect(uiState.toSell) {
                val sell = uiState.toSell ?: return@LaunchedEffect
                viewModel.onAction(QuoteDetailAction.SellShown)
                navigator.open(
                    AppNavKey.NewSale(
                        document = SaleDocumentMode.FromQuote(sell.quoteId, sell.useCurrentPrices),
                    ),
                )
            }
            QuoteDetailScreen(
                uiState = uiState,
                onAction = { action ->
                    val quote = (uiState.content as? QuoteDetailContent.Ready)?.quote
                    when (action) {
                        QuoteDetailAction.Back -> onBack()

                        QuoteDetailAction.Edit -> navigator.open(
                            AppNavKey.NewSale(document = SaleDocumentMode.EditQuote(key.quoteId)),
                        )

                        QuoteDetailAction.Share -> if (quote != null) {
                            val text = QuoteText.of(quote, context.resources, formats)
                            context.startActivity(shareIntent(text, chooserTitle))
                        }

                        QuoteDetailAction.ViewSale -> quote?.convertedSaleId?.let {
                            navigator.open(AppNavKey.SaleDetail(it))
                        }

                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** La lista sobre los casos de uso de la base abierta. */
private class QuoteListUseCases(dependencies: ScreenDependencies) : QuoteListOperations {
    private val ports = dependencies.ports
    private val observe = ObserveQuotesUseCase(ports.quotes, ports.customers)
    private val clock = dependencies.container.clock

    override fun list(status: QuoteStatus): Flow<List<QuoteRow>> = observe.list(status)

    override fun hasAny(): Flow<Boolean> =
        ports.moduleUsage.observe().map { BusinessTool.QUOTES in it }.distinctUntilChanged()

    override fun today(): Flow<BusinessDate?> = todayOf(dependencies, clock)
}

/** El detalle sobre los casos de uso de la base abierta. */
private class QuoteDetailUseCases(private val dependencies: ScreenDependencies) :
    QuoteDetailOperations {
    private val ports = dependencies.ports
    private val clock = dependencies.container.clock
    private val observe = ObserveQuotesUseCase(ports.quotes, ports.customers)
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
    private val cancelUseCase = CancelQuoteUseCase(ports.transactions, ports.quotes, clock)

    override fun quote(quoteId: Long): Flow<QuoteView?> = observe.detail(quoteId)

    override fun units(): Flow<Map<Long, MeasureUnit>> =
        ports.units.observeAll().map { all -> all.associateBy { it.id } }

    override fun today(): Flow<BusinessDate?> = todayOf(dependencies, clock)

    override suspend fun conversion(quoteId: Long): QuoteConversionResult = prepare(quoteId)

    override suspend fun cancel(quoteId: Long): CancelQuoteResult = cancelUseCase(quoteId)
}

/** Hoy en la zona del negocio, o `null` si el teléfono no la conoce. */
private fun todayOf(dependencies: ScreenDependencies, clock: Clock): Flow<BusinessDate?> =
    BusinessCalendarSource(dependencies.ports.settings).observe().map { result ->
        when (result) {
            is BusinessCalendarResult.Ready -> result.calendar.stamp(clock.now()).businessDate
            is BusinessCalendarResult.UnknownZone -> null
        }
    }.distinctUntilChanged()

// La misma clase de ventana que decide los dos paneles en la pantalla.
private val WIDE = 840.dp
