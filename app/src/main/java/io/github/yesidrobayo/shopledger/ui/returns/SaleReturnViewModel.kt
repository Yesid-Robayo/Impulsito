package io.github.yesidrobayo.shopledger.ui.returns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.returns.ReturnShareCheck
import io.github.yesidrobayo.shopledger.domain.rule.returns.ReturnShares
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.navigation.SaleExchange
import io.github.yesidrobayo.shopledger.navigation.SaleExchangeLine
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La devolución de una venta: qué se devuelve de cada línea, si vuelve al inventario y cómo se
 * resuelve.
 *
 * - **El tope es lo que falta.** Cada línea llega hasta lo vendido menos lo devuelto en devoluciones
 *   activas, leído de la base: si otra devolución entra mientras tanto, el tope baja solo.
 * - **El total es el que se guarda.** Sale de la misma regla que reparte lo neto, el impuesto y el
 *   costo congelado de la línea, así que lo que se ve es lo que registra la devolución.
 * - **Cómo se resuelve.** Dinero desde la cuenta de la venta, si se llevan cuentas; saldo a favor del
 *   cliente de la venta, o de uno que se elige; o un cambio, que no escribe nada todavía: abre la
 *   venta nueva con lo elegido, y la devolución se guarda junto con ella.
 * - **Lo elegido no se pierde.** Vive en el estado guardado, y un error al registrar lo conserva.
 *
 * @param baseUnits la unidad base de cada producto, por id de producto.
 * @param hasPoints si la venta ganó o canjeó puntos, que no cambian al devolver.
 * @param now la hora del teléfono, para saber si la venta es de otro mes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleReturnViewModel(
    private val saleId: Long,
    private val observeDetail: (Long) -> Flow<SaleDetail?>,
    private val observeUnits: () -> Flow<List<MeasureUnit>>,
    private val observeCalendar: () -> Flow<BusinessCalendarResult>,
    private val baseUnits: suspend (Set<Long>) -> Map<Long, Long>,
    private val payingAccounts: Flow<List<MoneyAccount>>,
    private val observeCustomer: (Long) -> Flow<Customer?>,
    private val hasPoints: suspend (Long) -> Boolean,
    private val register: suspend (SaleReturnRequest) -> RegisterSaleReturnResult,
    private val savedState: SavedStateHandle,
    private val quantitySymbols: NumberSymbols,
    private val now: () -> Instant,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val draft = MutableStateFlow(SaleReturnDraftStore.read(savedState))
    private val overlay = MutableStateFlow(Overlay())
    private val attempts = MutableStateFlow(0)
    private val closedFlow = MutableStateFlow(false)

    /** Con `true`, la persona salió: la pantalla vuelve. */
    val closed: StateFlow<Boolean> = closedFlow.asStateFlow()

    private val source: StateFlow<Load> = attempts.flatMapLatest {
        combine(
            observeDetail(saleId),
            observeUnits(),
            observeCalendar(),
            payingAccounts,
        ) { detail, units, calendar, accounts ->
            if (detail == null) {
                Load.Missing
            } else {
                val ids = detail.lines.map { it.content.productId }.toSet()
                Load.Ready(
                    detail = detail,
                    units = units.associateBy { it.id },
                    baseUnits = baseUnits(ids),
                    calendar = (calendar as? BusinessCalendarResult.Ready)?.calendar,
                    accounts = accounts,
                )
            }
        }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(Load.Failed) }
    }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Load.Loading)

    /** El cliente del saldo a favor: el de la venta, o el que eligió la persona si no tiene. */
    private val customer: Flow<Customer?> = combine(source, draft) { load, current ->
        (load as? Load.Ready)?.detail?.sale?.content?.customerId ?: current.customerId
    }.distinctUntilChanged()
        .flatMapLatest { id ->
            // Sin poder leer el cliente, la devolución sigue: solo deja de mostrar su saldo.
            if (id == null) flowOf(null) else observeCustomer(id).catch { emit(null) }
        }

    private val points: Flow<Boolean> = flow { emit(hasPoints(saleId)) }.catch { emit(false) }

    val uiState: StateFlow<SaleReturnUiState> = combine(
        source,
        draft,
        customer,
        points,
        overlay,
    ) { load, current, buyer, withPoints, shown -> build(load, current, buyer, withPoints, shown) }
        .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SaleReturnUiState(),
        )

    fun onAction(action: SaleReturnAction) {
        when (action) {
            is SaleReturnAction.Increment -> step(action.lineId, STEP)

            is SaleReturnAction.Decrement -> step(action.lineId, -STEP)

            is SaleReturnAction.EditQuantity -> openQuantity(action.lineId)

            is SaleReturnAction.QuantityTextChange -> overlay.update { shown ->
                val sheet = shown.sheet ?: return@update shown
                shown.copy(sheet = sheet.copy(text = action.text, error = null))
            }

            SaleReturnAction.QuantityConfirm -> confirmQuantity()

            SaleReturnAction.CloseSheet -> overlay.update { it.copy(sheet = null) }

            is SaleReturnAction.RestockChange -> change {
                it.copy(
                    lost = if (action.restock) {
                        it.lost - action.lineId
                    } else {
                        it.lost +
                            action.lineId
                    },
                )
            }

            is SaleReturnAction.ResolutionChange -> change {
                it.copy(resolution = action.resolution)
            }

            is SaleReturnAction.AccountChange ->
                change { it.copy(accountId = action.accountId, accountChosen = true) }

            // Abrir la lista de clientes es navegación: la resuelve la ruta.
            SaleReturnAction.PickCustomer -> Unit

            is SaleReturnAction.CustomerPicked -> change { it.copy(customerId = action.customerId) }

            SaleReturnAction.RemoveCustomer -> change { it.copy(customerId = null) }

            is SaleReturnAction.NoteChange -> change { it.copy(note = action.text) }

            SaleReturnAction.ToggleTax -> overlay.update { it.copy(taxExpanded = !it.taxExpanded) }

            SaleReturnAction.Register -> registerReturn()

            SaleReturnAction.Retry -> if (source.value == Load.Failed) {
                attempts.update { it + 1 }
            } else {
                registerReturn()
            }

            SaleReturnAction.Close -> if (uiState.value.hasInput &&
                overlay.value.registered == null
            ) {
                overlay.update { it.copy(discardAsked = true) }
            } else {
                closedFlow.value = true
            }

            SaleReturnAction.ConfirmLeave -> closedFlow.value = true

            SaleReturnAction.DismissDialog -> overlay.update { it.copy(discardAsked = false) }

            SaleReturnAction.RegisteredShown -> {
                change { SaleReturnDraft() }
                closedFlow.value = true
            }

            SaleReturnAction.ExchangeShown -> overlay.update { it.copy(exchange = null) }
        }
    }

    // ------------------------------------------------------------------ elegir

    private fun step(lineId: Long, delta: Long) {
        val line = lineOf(lineId) ?: return
        if (!line.returnable) return
        val next = (line.quantity.milli + delta).coerceIn(0L, line.remaining.milli)
        setQuantity(lineId, next)
    }

    private fun setQuantity(lineId: Long, milli: Long) = change {
        val quantities = if (milli ==
            0L
        ) {
            it.quantities - lineId
        } else {
            it.quantities + (lineId to milli)
        }
        it.copy(quantities = quantities)
    }

    private fun openQuantity(lineId: Long) {
        val line = lineOf(lineId) ?: return
        if (!line.returnable) return
        val text = if (line.quantity == QuantityMilli.ZERO) {
            ""
        } else {
            plainNumberText(line.quantity.milli, QUANTITY_DECIMALS, quantitySymbols)
        }
        overlay.update {
            it.copy(sheet = ReturnQuantitySheet(lineId, line.name, line.unit, text))
        }
    }

    private fun confirmQuantity() {
        val sheet = overlay.value.sheet ?: return
        val line = lineOf(sheet.lineId) ?: return
        val quantity = when (
            val read = NumberInputParser.quantity(
                sheet.text,
                quantitySymbols,
                Limit.LINE_QUANTITY_MILLI,
            )
        ) {
            is NumberInput.Value -> read.value

            NumberInput.Blank -> QuantityMilli.ZERO

            NumberInput.Malformed, is NumberInput.TooManyDecimals, is NumberInput.TooLarge ->
                return sheetError(sheet, ReturnQuantityError.NotANumber)
        }
        if (quantity > line.remaining) {
            return sheetError(sheet, ReturnQuantityError.AboveRemaining(line.remaining))
        }
        setQuantity(sheet.lineId, quantity.milli)
        overlay.update { it.copy(sheet = null) }
    }

    private fun sheetError(sheet: ReturnQuantitySheet, error: ReturnQuantityError) =
        overlay.update { it.copy(sheet = sheet.copy(error = error)) }

    private fun lineOf(lineId: Long): SaleReturnLineUi? =
        uiState.value.lines.firstOrNull { it.lineId == lineId }

    private fun change(edit: (SaleReturnDraft) -> SaleReturnDraft) {
        draft.update(edit)
        SaleReturnDraftStore.write(savedState, draft.value)
        // Cambiar lo elegido deja atrás el aviso de un intento anterior.
        overlay.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------ confirmar

    private fun registerReturn() {
        val shown = overlay.value
        if (shown.registering || shown.registered != null) return
        val ready = source.value as? Load.Ready ?: return
        val state = uiState.value
        val chosen = state.lines.filter { it.quantity > QuantityMilli.ZERO }
        if (chosen.isEmpty()) {
            change { it.copy(showMissing = true) }
            return
        }
        if (state.noteTooLong) return
        val note = state.note.trim().takeIf { it.isNotEmpty() }
        val settlement = when (state.resolution) {
            ReturnResolution.EXCHANGE -> {
                // El cambio no escribe nada aquí: la venta nueva registra las dos cosas juntas.
                overlay.update { it.copy(exchange = exchangeOf(ready.detail, chosen, state.total)) }
                return
            }

            ReturnResolution.STORE_CREDIT -> {
                val buyer = state.customer
                if (buyer == null) {
                    change { it.copy(showMissing = true) }
                    return
                }
                // El cliente de la venta va implícito; el elegido, por su id.
                ReturnSettlement.StoreCredit(buyer.id.takeUnless { state.customerLocked })
            }

            ReturnResolution.REFUND -> ReturnSettlement.Refund(state.accountId)
        }
        val request = SaleReturnRequest(
            saleId = saleId,
            lines = chosen.map { ReturnLineRequest(it.lineId, it.quantity, it.restock) },
            settlement = settlement,
            note = note,
        )
        overlay.update { it.copy(registering = true, notice = null) }
        viewModelScope.launch {
            val result = register(request)
            overlay.update { it.copy(registering = false) }
            when (result) {
                is RegisterSaleReturnResult.Registered ->
                    overlay.update { it.copy(registered = result.totalRefund) }

                RegisterSaleReturnResult.CustomerRequired -> change { it.copy(showMissing = true) }

                is RegisterSaleReturnResult.AccountUnavailable ->
                    overlay.update { it.copy(notice = SaleReturnNotice.AccountUnavailable) }

                is RegisterSaleReturnResult.StorageFailed -> overlay.update {
                    val full = result.failure.isOutOfSpace
                    it.copy(notice = SaleReturnNotice.RegisterFailed(outOfSpace = full))
                }

                // Cada rechazo se nombra, sin rama por defecto, para que uno nuevo obligue a decidir
                // aquí cómo se muestra. Lo elegido se conserva, y la lectura ya trae el tope al día.
                RegisterSaleReturnResult.SaleNotFound,
                RegisterSaleReturnResult.SaleVoided,
                RegisterSaleReturnResult.NoLines,
                RegisterSaleReturnResult.TooManyLines,
                RegisterSaleReturnResult.NoteTooLong,
                is RegisterSaleReturnResult.LineRejected,
                is RegisterSaleReturnResult.AboveRemaining,
                RegisterSaleReturnResult.CustomerUnavailable,
                RegisterSaleReturnResult.CustomerNotOfSale,
                is RegisterSaleReturnResult.UnknownTimeZone,
                RegisterSaleReturnResult.LedgerDamaged,
                -> overlay.update {
                    it.copy(notice = SaleReturnNotice.RegisterFailed(outOfSpace = false))
                }
            }
        }
    }

    /**
     * Lo que se lleva la venta nueva del cambio. El crédito lo paga primero el método con que se
     * cobró la venta, que no sea a crédito: su dinero vuelve a la cuenta de la que sale el reembolso.
     */
    private fun exchangeOf(
        detail: SaleDetail,
        chosen: List<SaleReturnLineUi>,
        total: Money,
    ): SaleExchange = SaleExchange(
        saleId = saleId,
        lines = chosen.map { SaleExchangeLine(it.lineId, it.quantity.milli, it.restock) },
        credit = total.minor,
        creditMethodId = detail.payments
            .firstOrNull { it.content.methodKind != PaymentMethodKind.ON_ACCOUNT }
            ?.content
            ?.paymentMethodId,
    )

    // ------------------------------------------------------------------ armar el estado

    private fun build(
        load: Load,
        current: SaleReturnDraft,
        buyer: Customer?,
        withPoints: Boolean,
        shown: Overlay,
    ): SaleReturnUiState = when (load) {
        Load.Loading -> SaleReturnUiState(saleId = saleId)
        Load.Failed -> SaleReturnUiState(loading = false, loadFailed = true, saleId = saleId)
        Load.Missing -> SaleReturnUiState(loading = false, nothingLeft = true, saleId = saleId)
        is Load.Ready -> ready(load, current, buyer, withPoints, shown)
    }

    private fun ready(
        load: Load.Ready,
        current: SaleReturnDraft,
        buyer: Customer?,
        withPoints: Boolean,
        shown: Overlay,
    ): SaleReturnUiState {
        val detail = load.detail
        val sale = detail.sale.content
        val lines = detail.lines.map { line ->
            val row = line.content
            val remaining = detail.remainingOf(line)
            SaleReturnLineUi(
                lineId = line.id,
                name = row.productNameSnapshot,
                unit = load.baseUnits[row.productId]?.let { load.units[it] },
                sold = row.quantity,
                returnedBefore = detail.returned[line.id]?.quantity ?: QuantityMilli.ZERO,
                remaining = remaining,
                // Una cantidad guardada que ya no cabe, porque otra devolución entró, baja al tope.
                quantity = QuantityMilli(
                    (current.quantities[line.id] ?: 0L).coerceIn(0L, remaining.milli),
                ),
                restock = line.id !in current.lost,
                archived = row.productId in detail.archivedProductIds,
                returnable = row.stockEffect != LineStockEffect.COMPONENTS,
            )
        }
        var net = Money.ZERO
        var tax = Money.ZERO
        for (line in detail.lines) {
            val quantity = lines.first { it.lineId == line.id }.quantity
            if (quantity == QuantityMilli.ZERO) continue
            val row = line.content
            val sold = LineAmounts(row.quantity, row.netAmount, row.taxAmount, row.lineCost)
            val share = ReturnShares.of(
                sold,
                detail.returned[line.id] ?: LineAmounts.NONE,
                quantity,
            )
            if (share is ReturnShareCheck.Share) {
                net += share.share.net
                tax += share.share.tax
            }
        }
        val total = net + tax
        val creditSale = detail.payments.any {
            it.content.methodKind == PaymentMethodKind.ON_ACCOUNT
        }
        val customerLocked = sale.customerId != null
        val resolution = current.resolution
            ?: if (creditSale &&
                customerLocked
            ) {
                ReturnResolution.STORE_CREDIT
            } else {
                ReturnResolution.REFUND
            }
        val accountIds = load.accounts.map { it.id }.toSet()
        val saleAccount = detail.payments.firstNotNullOfOrNull { payment ->
            payment.content.moneyAccountId?.takeIf { it in accountIds }
        }
        val accountId = (if (current.accountChosen) current.accountId else saleAccount)
            ?.takeIf { it in accountIds }
        val itemCount = lines.count { it.quantity > QuantityMilli.ZERO }
        val customerUi = buyer?.let {
            ReturnCustomerUi(it.id, it.contact?.name, it.balance, it.balance - total)
        }
        return SaleReturnUiState(
            loading = false,
            nothingLeft = detail.sale.status != DocumentStatus.ACTIVE || !detail.canBeReturned,
            saleId = saleId,
            saleDate = sale.businessDate,
            lines = lines,
            resolution = resolution,
            accounts = load.accounts,
            accountId = accountId,
            customer = customerUi,
            customerLocked = customerLocked,
            creditSale = creditSale,
            needsCustomer = current.showMissing && resolution == ReturnResolution.STORE_CREDIT &&
                customerUi == null,
            countsOn = countsOn(
                load.calendar,
                sale.businessDate.date.year,
                sale.businessDate.date.month.ordinal,
            ),
            withPoints = withPoints,
            note = current.note,
            noteTooLong = current.note.length > MAX_NOTE_LENGTH,
            itemCount = itemCount,
            total = total,
            net = net,
            tax = tax,
            showsTax = detail.lines.any { it.content.taxAmount > Money.ZERO },
            taxExpanded = shown.taxExpanded,
            noneSelected = current.showMissing && itemCount == 0,
            sheet = shown.sheet,
            discardAsked = shown.discardAsked,
            notice = shown.notice,
            registering = shown.registering,
            registered = shown.registered,
            exchange = shown.exchange,
        )
    }

    /** Hoy, si la venta es de otro mes: la devolución cuenta hoy, y ese mes no cambia. */
    private fun countsOn(calendar: BusinessCalendar?, year: Int, month: Int) = calendar
        ?.businessDateOf(now())
        ?.takeIf { it.date.year != year || it.date.month.ordinal != month }

    private sealed interface Load {
        data object Loading : Load

        data object Failed : Load

        data object Missing : Load

        data class Ready(
            val detail: SaleDetail,
            val units: Map<Long, MeasureUnit>,
            val baseUnits: Map<Long, Long>,
            val calendar: BusinessCalendar?,
            val accounts: List<MoneyAccount>,
        ) : Load
    }

    private data class Overlay(
        val sheet: ReturnQuantitySheet? = null,
        val discardAsked: Boolean = false,
        val notice: SaleReturnNotice? = null,
        val registering: Boolean = false,
        val registered: Money? = null,
        val exchange: SaleExchange? = null,
        val taxExpanded: Boolean = false,
    )

    private companion object {
        /** Lo que sube o baja cada toque del selector: una unidad entera. */
        const val STEP = 1_000L

        const val QUANTITY_DECIMALS = 3

        /** Largo máximo de la nota de una devolución. */
        const val MAX_NOTE_LENGTH = 200

        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
