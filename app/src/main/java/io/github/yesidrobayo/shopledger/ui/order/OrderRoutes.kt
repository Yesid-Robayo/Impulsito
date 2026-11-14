package io.github.yesidrobayo.shopledger.ui.order

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.rule.document.DocumentReceipt
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObservePaymentChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelOrderUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveOrdersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderChangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDepositResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRow
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderView
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareOrderDeliveryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.RecordOrderDepositUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SetOrderPreparationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.SaleDocumentMode
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.document.DocumentShareRequest
import io.github.yesidrobayo.shopledger.ui.document.DocumentSource
import io.github.yesidrobayo.shopledger.ui.document.ShareDocumentPdf
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.RecordsTab
import io.github.yesidrobayo.shopledger.ui.sale.SaleNumberFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Los pedidos en la navegación: como segmento de Registros, o como destino propio desde el centro
 * `Negocio`. En un teléfono, tocar uno abre su detalle encima; en una ventana ancha, a la derecha,
 * sin salir de la lista.
 *
 * @param onSegment elige otro segmento de Registros, que se dibuja ahí mismo.
 */
@Composable
fun OrdersRoute(navigator: AppNavigator, asTab: Boolean, onSegment: (RecordsTab) -> Unit = {}) {
    val model = rememberAppViewModel { dependencies ->
        OrdersViewModel(OrderListUseCases(dependencies))
    }
    when (model) {
        ScreenModel.Opening -> OrdersScreen(OrdersUiState(), onAction = {}, asTab = asTab)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Orders(), navigator)

        is ScreenModel.Ready -> BoxWithConstraints {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val wide = maxWidth >= WIDE
            OrdersScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is OrdersAction.Open ->
                            if (wide) {
                                viewModel.onAction(action)
                            } else {
                                navigator.open(AppNavKey.Orders(action.orderId))
                            }

                        OrdersAction.New ->
                            navigator.open(AppNavKey.NewSale(document = SaleDocumentMode.NewOrder))

                        OrdersAction.Back -> navigator.back()

                        else -> viewModel.onAction(action)
                    }
                },
                asTab = asTab,
                onSegment = { index -> onSegment(RecordsTab.entries[index]) },
                backupBanner = { RootBackupBanner(navigator::open) },
                detail = { orderId ->
                    OrderDetailRoute(
                        orderId,
                        navigator,
                        onBack = { viewModel.onAction(OrdersAction.CloseDetail) },
                    )
                },
            )
        }
    }
}

/**
 * El detalle de un pedido en la navegación. Editar abre la venta en modo pedido; entregar, la venta
 * con sus líneas, su cliente y su envío, que lo deja entregado al registrarse.
 */
@Composable
fun OrderDetailRoute(
    orderId: Long,
    navigator: AppNavigator,
    onBack: () -> Unit = { navigator.back() },
) {
    val formats = LocalAppFormats.current
    val numbers = SaleNumberFormat(
        moneySymbols = formats.money.inputSymbols,
        currencyDecimals = formats.money.currencyDecimals,
        quantitySymbols = formats.quantity.inputSymbols,
        percentSymbols = formats.percent.inputSymbols,
    )
    val model = rememberAppViewModel(key = "order-$orderId") { dependencies ->
        OrderDetailViewModel(orderId, OrderDetailUseCases(dependencies), numbers)
    }
    when (model) {
        ScreenModel.Opening -> OrderDetailScreen(OrderDetailUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Orders(orderId), navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ShareDocumentPdf(uiState.share) { shared ->
                viewModel.onAction(OrderDetailAction.ShareFinished(shared))
            }
            LaunchedEffect(uiState.toDeliver) {
                val deliver = uiState.toDeliver ?: return@LaunchedEffect
                viewModel.onAction(OrderDetailAction.DeliverShown)
                navigator.open(
                    AppNavKey.NewSale(document = SaleDocumentMode.FromOrder(deliver)),
                )
            }
            OrderDetailScreen(
                uiState = uiState,
                onAction = { action ->
                    val order = (uiState.content as? OrderDetailContent.Ready)?.order
                    when (action) {
                        OrderDetailAction.Back -> onBack()

                        OrderDetailAction.Edit -> navigator.open(
                            AppNavKey.NewSale(document = SaleDocumentMode.EditOrder(orderId)),
                        )

                        OrderDetailAction.ViewSale -> order?.deliveredSaleId?.let {
                            navigator.open(AppNavKey.SaleDetail(it))
                        }

                        OrderDetailAction.OpenCustomer -> order?.customerId?.let {
                            navigator.open(AppNavKey.CustomerDetail(it))
                        }

                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** La lista sobre los casos de uso de la base abierta. */
private class OrderListUseCases(private val dependencies: ScreenDependencies) :
    OrderListOperations {
    private val ports = dependencies.ports
    private val observe = ObserveOrdersUseCase(
        ports.orders,
        ports.customers,
        ports.products,
        ports.productComponents,
    )

    override fun list(status: OrderStatus): Flow<List<OrderRow>> = observe.list(status)

    override fun hasAny(): Flow<Boolean> =
        ports.moduleUsage.observe().map { BusinessTool.ORDERS in it }.distinctUntilChanged()

    override fun today(): Flow<BusinessDate?> = todayOf(dependencies)
}

/** El detalle sobre los casos de uso de la base abierta. */
private class OrderDetailUseCases(private val dependencies: ScreenDependencies) :
    OrderDetailOperations {
    private val ports = dependencies.ports
    private val clock = dependencies.container.clock
    private val observe = ObserveOrdersUseCase(
        ports.orders,
        ports.customers,
        ports.products,
        ports.productComponents,
    )
    private val preparation = SetOrderPreparationUseCase(ports.transactions, ports.orders, clock)
    private val cancelUseCase = CancelOrderUseCase(ports.transactions, ports.orders, clock)
    private val depositUseCase = RecordOrderDepositUseCase(
        transactions = ports.transactions,
        orders = ports.orders,
        payments = RecordCustomerPaymentUseCase(
            transactions = ports.transactions,
            settings = ports.settings,
            customers = ports.customers,
            ledger = ports.customerLedger,
            accounts = ports.moneyAccounts,
            money = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger),
            clock = clock,
        ),
    )
    private val prepare = PrepareOrderDeliveryUseCase(
        ports.transactions,
        ports.orders,
        ports.products,
        ports.customers,
    )
    private val choices = ObservePaymentChoicesUseCase(
        ports.moduleUsage,
        ports.paymentMethods,
        ports.moneyAccounts,
    )

    override fun order(orderId: Long): Flow<OrderView?> = observe.detail(orderId)

    override fun customer(customerId: Long): Flow<Customer?> =
        ports.customers.observeById(customerId)

    override fun units(): Flow<Map<Long, MeasureUnit>> =
        ports.units.observeAll().map { all -> all.associateBy { it.id } }

    override fun today(): Flow<BusinessDate?> = todayOf(dependencies)

    override fun paymentChoices(): Flow<PaymentChoices> = choices()

    override suspend fun setReady(orderId: Long, ready: Boolean): OrderChangeResult =
        preparation(orderId, ready)

    override suspend fun cancel(orderId: Long): OrderChangeResult = cancelUseCase(orderId)

    override suspend fun deposit(
        orderId: Long,
        amount: Money,
        accountId: Long?,
    ): OrderDepositResult = depositUseCase(orderId, amount, accountId)

    override suspend fun delivery(orderId: Long): OrderDeliveryResult = prepare(orderId)

    override suspend fun document(orderId: Long): DocumentShareRequest? {
        val view = observe.detail(orderId).first() ?: return null
        val source = DocumentSource.read(dependencies)
        return DocumentShareRequest(
            document = DocumentReceipt.ofOrder(
                view.order,
                view.lines.map { it.line },
                source.template,
                source.businessName,
                view.customerName,
            ),
            logo = source.logo,
            units = source.units,
            zone = source.zone,
            attempt = 0L,
        )
    }
}

/** Hoy en la zona del negocio, o `null` si el teléfono no la conoce. */
private fun todayOf(dependencies: ScreenDependencies): Flow<BusinessDate?> =
    BusinessCalendarSource(dependencies.ports.settings).observe().map { result ->
        when (result) {
            is BusinessCalendarResult.Ready ->
                result.calendar.stamp(dependencies.container.clock.now()).businessDate

            is BusinessCalendarResult.UnknownZone -> null
        }
    }.distinctUntilChanged()

// La misma clase de ventana que decide los dos paneles en la pantalla.
private val WIDE = 840.dp
