package io.github.yesidrobayo.shopledger.ui.order

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentIssue
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderChangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDepositResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderView
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.readOutcome
import io.github.yesidrobayo.shopledger.ui.sale.SaleNumberFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que el detalle de un pedido lee y escribe. */
interface OrderDetailOperations {
    /** El pedido con sus líneas, lo que reserva cada una y lo que le falta, o `null`. */
    fun order(orderId: Long): Flow<OrderView?>

    /** El cliente [customerId], con su saldo, o `null` si ya no existe. */
    fun customer(customerId: Long): Flow<Customer?>

    /** Las unidades, por id, para decir cada cantidad con la suya. */
    fun units(): Flow<Map<Long, MeasureUnit>>

    /** Hoy en la zona del negocio, o `null` si el teléfono no la conoce. */
    fun today(): Flow<BusinessDate?>

    /** Con qué método y en qué cuenta puede entrar un anticipo. */
    fun paymentChoices(): Flow<PaymentChoices>

    suspend fun setReady(orderId: Long, ready: Boolean): OrderChangeResult

    suspend fun cancel(orderId: Long): OrderChangeResult

    suspend fun deposit(orderId: Long, amount: Money, accountId: Long?): OrderDepositResult

    suspend fun delivery(orderId: Long): OrderDeliveryResult
}

/**
 * Una línea como se muestra: lo acordado al pedir, sin costo, con lo que reserva y lo que le falta,
 * los dos en la unidad en que se pidió. [short] es cero si el stock alcanza.
 */
@Immutable
data class OrderLineUi(
    val name: String,
    val quantity: QuantityMilli,
    val unit: MeasureUnit?,
    val unitPrice: Money,
    val total: Money,
    val reserved: QuantityMilli,
    val short: QuantityMilli,
)

/**
 * El pedido abierto, listo para mostrar.
 *
 * - [deposit] es el anticipo: el saldo a favor de su cliente, hasta el total del pedido. El anticipo
 *   no queda atado al pedido, así que solo se muestra mientras está sin entregar.
 * - [due] es lo que queda por cobrar al entregar: el total menos el anticipo.
 * - [overdue] es sin entregar con la fecha de entrega pasada.
 */
@Immutable
data class OrderDetail(
    val id: Long,
    val status: OrderStatus,
    val customerId: Long?,
    val customerName: String?,
    val dueDate: BusinessDate?,
    val overdue: Boolean,
    val address: String?,
    val note: String?,
    val total: Money,
    val products: Money,
    val shipping: Money,
    val deposit: Money,
    val due: Money,
    val lines: List<OrderLineUi>,
    val deliveredSaleId: Long?,
)

sealed interface OrderDetailContent {
    data object Loading : OrderDetailContent

    /** Ya no existe. */
    data object Missing : OrderDetailContent

    data object Failed : OrderDetailContent

    data class Ready(val order: OrderDetail) : OrderDetailContent
}

/** Por qué no se guardó el anticipo escrito. */
enum class DepositError {
    /** En blanco, en cero o ilegible. */
    AMOUNT_ZERO,

    /** Pasa del tope de un abono. */
    AMOUNT_TOO_HIGH,
}

/**
 * La hoja de `Registrar anticipo`, con lo escrito. [methodId] y [accountId] solo cuentan con las
 * cuentas de dinero en uso. [failed] dice que no se guardó: lo escrito sigue aquí.
 */
@Immutable
data class DepositSheet(
    val amountText: String = "",
    val methodId: Long? = null,
    val accountId: Long? = null,
    val error: DepositError? = null,
    val failed: Boolean = false,
    val working: Boolean = false,
)

/** Lo que se pregunta antes de seguir. */
sealed interface OrderDetailDialog {
    /** Cancelar, con el nombre del cliente si tiene saldo a favor: el anticipo sigue siendo suyo. */
    data class ConfirmCancel(val creditOwner: String?) : OrderDetailDialog
}

/** Un aviso breve, que se muestra una vez. */
enum class OrderDetailNotice {
    CHANGE_FAILED,
    CANCELLED,
    DEPOSIT_RECORDED,
    DELIVER_FAILED,
}

@Immutable
data class OrderDetailUiState(
    val content: OrderDetailContent = OrderDetailContent.Loading,
    val choices: PaymentChoices = PaymentChoices(),
    val dialog: OrderDetailDialog? = null,
    val deposit: DepositSheet? = null,
    val working: Boolean = false,
    val notice: OrderDetailNotice? = null,
    val toDeliver: Long? = null,
)

sealed interface OrderDetailAction {
    data object MarkReady : OrderDetailAction

    data object BackToPending : OrderDetailAction

    /** Prepara la entrega y, si se puede, abre la venta que la registra. */
    data object Deliver : OrderDetailAction

    data object AskCancel : OrderDetailAction

    data object ConfirmCancel : OrderDetailAction

    data object DismissDialog : OrderDetailAction

    data object OpenDeposit : OrderDetailAction

    data class DepositAmount(val text: String) : OrderDetailAction

    data class DepositMethod(val methodId: Long) : OrderDetailAction

    data class DepositAccount(val accountId: Long?) : OrderDetailAction

    data object SaveDeposit : OrderDetailAction

    data object DismissDeposit : OrderDetailAction

    data object Retry : OrderDetailAction

    data object NoticeShown : OrderDetailAction

    data object DeliverShown : OrderDetailAction

    /** Navegación: los resuelve la ruta. */
    data object Back : OrderDetailAction

    data object Edit : OrderDetailAction

    data object ViewSale : OrderDetailAction

    data object OpenCustomer : OrderDetailAction
}

/**
 * El detalle de un pedido. `Marcar como listo` y `Volver a pendiente` cambian solo su preparación:
 * los dos siguen reservando. `Registrar entrega` prepara la venta sin escribir nada y abre la venta
 * nueva, que es la que lo deja entregado al registrarse. El anticipo es un abono del cliente que le
 * deja saldo a favor; cancelar no lo toca, y la confirmación lo dice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderDetailViewModel(
    private val orderId: Long,
    private val operations: OrderDetailOperations,
    private val numbers: SaleNumberFormat,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(OrderDetailUiState())

    // Reintentar vuelve a leer desde cero: una lectura que falló ya terminó su flujo.
    private val content: Flow<OrderDetailContent> = attempts.flatMapLatest {
        operations.order(orderId).flatMapLatest { view ->
            val customerId = view?.order?.customerId
            combine(
                customerId?.let { id -> operations.customer(id).catch { emit(null) } }
                    ?: flowOf(null),
                operations.units().catch { emit(emptyMap()) },
                operations.today().catch { emit(null) },
            ) { customer, units, today ->
                view?.let { OrderDetailContent.Ready(detailOf(it, customer, units, today)) }
                    ?: OrderDetailContent.Missing
            }
        }.catch { emit(OrderDetailContent.Failed) }
    }

    val uiState: StateFlow<OrderDetailUiState> = combine(
        content,
        operations.paymentChoices().catch { emit(PaymentChoices()) },
        overlay,
    ) { read, choices, shown ->
        shown.copy(content = read, choices = choices)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        OrderDetailUiState(),
    )

    fun onAction(action: OrderDetailAction) {
        when (action) {
            OrderDetailAction.MarkReady -> change { operations.setReady(orderId, ready = true) }

            OrderDetailAction.BackToPending ->
                change { operations.setReady(orderId, ready = false) }

            OrderDetailAction.Deliver -> deliver()

            OrderDetailAction.AskCancel -> overlay.update {
                it.copy(dialog = OrderDetailDialog.ConfirmCancel(creditOwner()))
            }

            OrderDetailAction.ConfirmCancel -> cancel()

            OrderDetailAction.DismissDialog -> overlay.update { it.copy(dialog = null) }

            OrderDetailAction.OpenDeposit -> openDeposit()

            is OrderDetailAction.DepositAmount -> editDeposit {
                it.copy(amountText = action.text, error = null, failed = false)
            }

            is OrderDetailAction.DepositMethod -> editDeposit {
                it.copy(methodId = action.methodId)
            }

            is OrderDetailAction.DepositAccount ->
                editDeposit { it.copy(accountId = action.accountId) }

            OrderDetailAction.SaveDeposit -> saveDeposit()

            OrderDetailAction.DismissDeposit -> overlay.update { it.copy(deposit = null) }

            OrderDetailAction.Retry -> attempts.update { it + 1 }

            OrderDetailAction.NoticeShown -> overlay.update { it.copy(notice = null) }

            OrderDetailAction.DeliverShown -> overlay.update { it.copy(toDeliver = null) }

            OrderDetailAction.Back,
            OrderDetailAction.Edit,
            OrderDetailAction.ViewSale,
            OrderDetailAction.OpenCustomer,
            -> Unit
        }
    }

    /** Cambia la preparación. Si ya no está sin entregar, el detalle se actualiza solo. */
    private fun change(write: suspend () -> OrderChangeResult) {
        if (overlay.value.working) return
        overlay.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = (readOutcome { write() } as? ReadOutcome.Read)?.value
            val notice = when (result) {
                OrderChangeResult.Changed -> null

                null,
                OrderChangeResult.NotOpen,
                is OrderChangeResult.StorageFailed,
                -> OrderDetailNotice.CHANGE_FAILED
            }
            overlay.update { it.copy(working = false, notice = notice) }
        }
    }

    private fun deliver() {
        if (overlay.value.working) return
        overlay.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = (readOutcome { operations.delivery(orderId) } as? ReadOutcome.Read)?.value
            when (result) {
                is OrderDeliveryResult.Ready ->
                    overlay.update { it.copy(working = false, toDeliver = orderId) }

                // Ya entregado, cancelado o sin nada que entregar: el pedido sigue igual.
                null,
                OrderDeliveryResult.NotFound,
                is OrderDeliveryResult.NotOpen,
                OrderDeliveryResult.NothingToDeliver,
                -> overlay.update {
                    it.copy(working = false, notice = OrderDetailNotice.DELIVER_FAILED)
                }
            }
        }
    }

    private fun cancel() {
        overlay.update { it.copy(dialog = null, working = true) }
        viewModelScope.launch {
            val result = (readOutcome { operations.cancel(orderId) } as? ReadOutcome.Read)?.value
            val notice = when (result) {
                OrderChangeResult.Changed -> OrderDetailNotice.CANCELLED

                null,
                OrderChangeResult.NotOpen,
                is OrderChangeResult.StorageFailed,
                -> OrderDetailNotice.CHANGE_FAILED
            }
            overlay.update { it.copy(working = false, notice = notice) }
        }
    }

    /** El nombre del cliente si tiene saldo a favor, para decir que el anticipo sigue siendo suyo. */
    private fun creditOwner(): String? {
        val order = (uiState.value.content as? OrderDetailContent.Ready)?.order ?: return null
        return order.customerName.takeIf { order.deposit > Money.ZERO }
    }

    private fun openDeposit() {
        val order = (uiState.value.content as? OrderDetailContent.Ready)?.order ?: return
        if (order.customerId == null || !order.status.isOpen) return
        val choices = uiState.value.choices
        // Sin cuentas en uso, el anticipo queda solo en el libro del cliente.
        val method = choices.methods.firstOrNull().takeIf { choices.accountsInUse }
        overlay.update { it.copy(deposit = DepositSheet(methodId = method?.id)) }
    }

    private fun editDeposit(change: (DepositSheet) -> DepositSheet) = overlay.update { shown ->
        val sheet = shown.deposit ?: return@update shown
        if (sheet.working) shown else shown.copy(deposit = change(sheet))
    }

    /** La cuenta donde entra el anticipo: la del método, o la elegida; sin cuentas, ninguna. */
    private fun accountFor(sheet: DepositSheet): Long? {
        val choices = uiState.value.choices
        if (!choices.accountsInUse) return null
        val method = choices.methods.firstOrNull { it.id == sheet.methodId }
        return method?.details?.moneyAccountId ?: sheet.accountId
    }

    private fun saveDeposit() {
        val sheet = overlay.value.deposit ?: return
        if (sheet.working) return
        val read = NumberInputParser.money(
            sheet.amountText,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.DOCUMENT_TOTAL,
        )
        val amount = when (read) {
            is NumberInput.Value -> read.value.takeIf { it > Money.ZERO }

            is NumberInput.TooLarge -> {
                editDeposit { it.copy(error = DepositError.AMOUNT_TOO_HIGH) }
                return
            }

            NumberInput.Blank, NumberInput.Malformed, is NumberInput.TooManyDecimals -> null
        }
        if (amount == null) {
            editDeposit { it.copy(error = DepositError.AMOUNT_ZERO) }
            return
        }
        val account = accountFor(sheet)
        overlay.update { it.copy(deposit = sheet.copy(working = true, failed = false)) }
        viewModelScope.launch {
            val result = (
                readOutcome { operations.deposit(orderId, amount, account) } as? ReadOutcome.Read
                )?.value
            overlay.update { shown ->
                val open = shown.deposit?.copy(working = false)
                when (result) {
                    is OrderDepositResult.Recorded -> shown.copy(
                        deposit = null,
                        notice = OrderDetailNotice.DEPOSIT_RECORDED,
                    )

                    is OrderDepositResult.Rejected ->
                        shown.copy(deposit = open?.let { rejectedBy(it, result) })

                    // Sin cliente o ya entregado: la hoja se cierra y el detalle lo muestra.
                    OrderDepositResult.NeedsCustomer, OrderDepositResult.OrderNotOpen ->
                        shown.copy(deposit = null, notice = OrderDetailNotice.CHANGE_FAILED)

                    null -> shown.copy(deposit = open?.copy(failed = true))
                }
            }
        }
    }

    /** La hoja tras un abono rechazado: el monto se marca en su campo, lo demás como falla. */
    private fun rejectedBy(sheet: DepositSheet, result: OrderDepositResult.Rejected): DepositSheet =
        when (val payment = result.result) {
            is RecordCustomerPaymentResult.InvalidAmount -> sheet.copy(
                error = when (payment.issue) {
                    CustomerPaymentIssue.AMOUNT_NOT_POSITIVE -> DepositError.AMOUNT_ZERO
                    CustomerPaymentIssue.AMOUNT_TOO_LARGE -> DepositError.AMOUNT_TOO_HIGH
                },
            )

            else -> sheet.copy(failed = true)
        }

    private fun detailOf(
        view: OrderView,
        customer: Customer?,
        units: Map<Long, MeasureUnit>,
        today: BusinessDate?,
    ): OrderDetail {
        val order = view.order
        val balance = customer?.balance ?: Money.ZERO
        val credit = if (balance < Money.ZERO) -balance else Money.ZERO
        // El anticipo solo cuenta mientras se puede entregar, y nunca pasa del total.
        val deposit = if (order.status.isOpen) minOf(credit, order.total) else Money.ZERO
        return OrderDetail(
            id = order.id,
            status = order.status,
            customerId = order.customerId,
            customerName = view.customerName,
            dueDate = order.dueDate,
            overdue = isOverdue(order, today),
            address = order.deliveryAddress,
            note = order.deliveryNote,
            total = order.total,
            products = order.products,
            shipping = order.shippingAmount,
            deposit = deposit,
            due = order.total - deposit,
            lines = view.lines.map { line ->
                val content = line.line.content
                OrderLineUi(
                    name = content.productNameSnapshot,
                    quantity = content.enteredQuantity,
                    unit = units[content.unitId],
                    unitPrice = content.unitPrice,
                    total = content.lineTotal,
                    // Lo reservado es lo que se pidió; lo que falta se pasa a la misma unidad.
                    reserved = if (line.reserved > QuantityMilli.ZERO) {
                        content.enteredQuantity
                    } else {
                        QuantityMilli.ZERO
                    },
                    short = inLineUnit(line.short, content.unitFactor),
                )
            },
            deliveredSaleId = order.deliveredSaleId,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * [base], en milésimas de la unidad base, en la unidad que trae [factor] milésimas de la base. Se
 * redondea hacia arriba: una falta, por chica que sea, nunca se muestra como cero.
 */
internal fun inLineUnit(base: QuantityMilli, factor: QuantityMilli): QuantityMilli {
    if (base.milli <= 0L || factor.milli <= 0L) return QuantityMilli.ZERO
    val scaled = Math.multiplyExact(base.milli, 1_000L)
    return QuantityMilli((scaled + factor.milli - 1) / factor.milli)
}
