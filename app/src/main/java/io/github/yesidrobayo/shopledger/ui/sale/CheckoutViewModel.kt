package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerSearch
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.RedemptionCheck
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutPaymentInput
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutPreview
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutProblem
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentFees
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCheckout
import io.github.yesidrobayo.shopledger.domain.rule.timesRate
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.customer.CustomerEditorModel
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El cobro: varios pagos, la comisión de cada uno, el ajuste de toda la venta y la imagen de cobro.
 *
 * - **Es opcional.** La venta común se confirma en la venta nueva sin pasar por aquí; esta pantalla
 *   resuelve lo que no es común.
 * - **Lo que se ve es lo que se guarda.** Las cuentas son las de la venta al confirmarse, con el
 *   recargo y la comisión de cada método.
 * - **Al abrir** hay un pago con el método elegido en la venta por todo lo que se cobra: un pago
 *   único completo ya cuadra.
 * - **Nada se escribe hasta confirmar.** Lo escrito vive en el estado guardado de la pantalla.
 *   `Registrar venta` siempre responde: si los pagos no suman, lo dice en la línea de balance y no
 *   registra.
 *
 * @param offered la venta que dejó la venta nueva, que se toma una sola vez al abrir.
 * @param findImage la imagen de cobro de un método, o `null` si no tiene o no se pudo leer.
 * @param customersSource los clientes que se pueden elegir: ni archivados ni anonimizados.
 * @param createCustomer crea un cliente desde el cobro, sin salir de la venta.
 *
 * El cliente se ofrece siempre y es obligatorio solo con un pago a crédito: elegir `A crédito` sin
 * cliente abre la lista de clientes, y quitar el cliente retira los pagos a crédito, con un aviso.
 *
 * Con el programa de puntos activo y un cliente, el cobro ofrece canjear sus puntos. El canje es un
 * descuento de la venta que entra en el ajuste, nunca un pago: los puntos no son dinero. Cambiar o
 * quitar el cliente quita el canje.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModel(
    private val catalogSource: () -> Flow<SaleCatalog>,
    private val registerSale: suspend (SaleRequest) -> RegisterSaleResult,
    private val findImage: suspend (PaymentMethodRecord) -> StoredImage?,
    private val savedState: SavedStateHandle,
    private val numbers: SaleNumberFormat,
    offered: () -> CheckoutHandoff.Offered?,
    computation: CoroutineDispatcher,
    customersSource: () -> Flow<List<Customer>> = { flowOf(emptyList()) },
    private val createCustomer: suspend (CustomerForm, Boolean) -> SaveCustomerResult =
        { _, _ -> SaveCustomerResult.NotFound },
) : ViewModel() {
    private val sale: SaleDraft?
    private val qrOnly: Boolean
    private val editing: MutableStateFlow<Editing>
    private val overlay = MutableStateFlow(Overlay())

    /** El nombre de cada método visto, para nombrar un pago cuyo método se archivó mientras tanto. */
    private val knownMethods = HashMap<Long, SaleMethod>()

    init {
        if (savedState.get<Boolean>(STARTED) == true) {
            sale = SaleDraftStore.read(savedState)
            qrOnly = savedState.get<Boolean>(QR_ONLY) ?: false
            editing = MutableStateFlow(restore())
        } else {
            val taken = offered()
            sale = taken?.draft?.takeIf { it.lines.isNotEmpty() }
            qrOnly = taken?.qrOnly ?: false
            // La venta abierta desde un cliente llega con él elegido.
            editing = MutableStateFlow(Editing(customerId = sale?.customerId))
            if (sale != null) {
                SaleDraftStore.write(savedState, sale)
                savedState[QR_ONLY] = qrOnly
                savedState[STARTED] = true
            }
        }
    }

    /** Cada `Reintentar` vuelve a leer el catálogo; los pagos escritos siguen en [editing]. */
    private val attempts = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val catalog: StateFlow<CatalogLoad> = attempts
        .flatMapLatest {
            catalogSource()
                .map<SaleCatalog, CatalogLoad> { CatalogLoad.Ready(it) }
                .onStart { emit(CatalogLoad.Loading) }
                .catch { emit(CatalogLoad.Failed) }
        }
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogLoad.Loading)

    /** Los clientes que se pueden elegir. Una falla de lectura deja la lista vacía, no el cobro. */
    private val customers: StateFlow<List<Customer>> = customersSource()
        .catch { emit(emptyList()) }
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** La hoja de crear un cliente desde el cobro: al guardarlo, queda elegido. */
    val customerEditor = CustomerEditorModel(
        scope = viewModelScope,
        save = { _, form, allowSameName -> createCustomer(form, allowSameName) },
        onSaved = ::pickCustomer,
    )

    val uiState: StateFlow<CheckoutUiState> = combine(catalog, editing, overlay, customers) {
            load,
            written,
            shown,
            choices,
        ->
        build(load, written, shown, choices)
    }.flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            CheckoutUiState(),
        )

    init {
        viewModelScope.launch {
            catalog.collect { load -> if (load is CatalogLoad.Ready) onCatalog(load.catalog) }
        }
    }

    fun onAction(action: CheckoutAction) {
        when (action) {
            CheckoutAction.Retry -> attempts.update { it + 1 }

            is CheckoutAction.AmountChange -> amount(action.index, action.text)

            is CheckoutAction.FeeChange -> fee(action.index, action.text)

            is CheckoutAction.TenderedChange -> tendered(action.index, action.text)

            is CheckoutAction.ExactAmount -> exactAmount(action.index)

            is CheckoutAction.RemovePayment -> removePayment(action.index)

            is CheckoutAction.TipChange -> tip(action.text)

            CheckoutAction.NoTip -> tip("")

            CheckoutAction.OpenAddPayment -> overlay.update {
                it.copy(sheet = CheckoutSheet.AddPayment)
            }

            is CheckoutAction.AddPayment -> addPayment(action.methodId)

            CheckoutAction.UseDue -> useDue()

            CheckoutAction.OpenAdjustment -> openAdjustment()

            is CheckoutAction.AdjustmentKindChange -> editAdjustment {
                it.copy(kind = action.kind, error = null)
            }

            is CheckoutAction.AdjustmentInputChange ->
                editAdjustment { it.copy(input = action.input, text = "", error = null) }

            is CheckoutAction.AdjustmentTextChange -> editAdjustment {
                it.copy(text = action.text, error = null)
            }

            CheckoutAction.ApplyAdjustment -> applyAdjustment()

            CheckoutAction.RemoveAdjustment -> setAdjustment(Money.ZERO)

            CheckoutAction.CloseSheet -> overlay.update { it.copy(sheet = null) }

            is CheckoutAction.ShowQr -> showQr(action.index)

            CheckoutAction.CloseQr -> overlay.update { it.copy(qrIndex = null, qrImage = null) }

            CheckoutAction.Register -> register(zeroConfirmed = false)

            CheckoutAction.ConfirmZeroTotal -> register(zeroConfirmed = true)

            CheckoutAction.DismissDialog -> overlay.update { it.copy(zeroDialog = false) }

            CheckoutAction.RegisteredShown -> overlay.update { it.copy(registered = null) }

            CheckoutAction.OpenCustomers -> overlay.update {
                it.copy(sheet = CheckoutSheet.Customers, customerQuery = "")
            }

            is CheckoutAction.CustomerQueryChange -> overlay.update {
                it.copy(customerQuery = action.query)
            }

            is CheckoutAction.PickCustomer -> pickCustomer(action.customerId)

            is CheckoutAction.NewCustomer -> {
                overlay.update { it.copy(sheet = null) }
                customerEditor.openNew(action.name.trim())
            }

            CheckoutAction.RemoveCustomer -> removeCustomer()

            is CheckoutAction.CreditDueChange -> creditDue(action.date)

            CheckoutAction.CustomerNoticeShown -> overlay.update {
                it.copy(customerRemoved = false)
            }

            is CheckoutAction.PointsChange -> change {
                it.copy(pointsText = action.text, pointsError = null)
            }

            CheckoutAction.RedeemPoints -> redeemPoints()
        }
    }

    // ------------------------------------------------------------------ cliente

    /**
     * Elige el cliente de la venta. Si cambia, el canje de puntos se quita: eran puntos del cliente
     * anterior. Con un solo pago que cubría todo, el pago sigue cubriendo todo.
     */
    private fun pickCustomer(customerId: Long) {
        keepingSinglePaymentFull {
            if (it.customerId == customerId) {
                it
            } else {
                it.copy(customerId = customerId).withoutRedemption()
            }
        }
        overlay.update {
            it.copy(sheet = null, customerQuery = "", customerMissing = false)
        }
    }

    /**
     * Quita el cliente, y con él los pagos a crédito y el canje de puntos, que no pueden quedar sin
     * cliente. Si había alguno, lo avisa: los demás pagos siguen.
     */
    private fun removeCustomer() {
        val catalog = readyCatalog()
        val credit = catalog?.creditMethods?.map { it.id }?.toSet().orEmpty()
        // El canje se quita primero, con el cliente todavía elegido: así un pago único que cubría
        // todo sigue cubriendo todo sin el descuento.
        val redeemed = editing.value.redeemed > 0L
        if (redeemed) keepingSinglePaymentFull { it.withoutRedemption() }
        val written = editing.value
        val kept = written.payments.filter { it.methodId !in credit }
        change {
            it.copy(
                customerId = null,
                creditDue = null,
                payments = kept,
                active = if (kept.size == it.payments.size) it.active else 0,
            )
        }
        overlay.update {
            it.copy(
                customerRemoved = kept.size != written.payments.size || redeemed,
                dueError = false,
            )
        }
    }

    // ------------------------------------------------------------------ puntos

    /**
     * `Canjear puntos`: lee lo escrito y lo aplica como descuento de la venta, nunca más que los
     * puntos del cliente ni más que lo que suman las líneas. En blanco, quita el canje. Con un solo
     * pago que cubría todo, el pago sigue cubriendo todo.
     */
    private fun redeemPoints() {
        val catalog = readyCatalog() ?: return
        val current = sale ?: return
        val written = editing.value
        val customer = customers.value.firstOrNull { it.id == written.customerId } ?: return
        val pointValue = catalog.loyalty.pointValue
        if (!catalog.loyalty.active || pointValue == null) return
        val read = NumberInputParser.count(
            written.pointsText,
            numbers.quantitySymbols,
            Limit.POINTS,
        )
        val points = when (read) {
            NumberInput.Blank -> 0L

            is NumberInput.Value -> read.value

            // Más que el tope de puntos siempre es más de lo que tiene.
            is NumberInput.TooLarge ->
                return pointsError(CheckoutPointsError.NotEnough(customer.pointsBalance))

            NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                return pointsError(CheckoutPointsError.NotANumber)
        }
        if (points < 0L) return pointsError(CheckoutPointsError.NotANumber)
        if (points > 0L) {
            when (
                val check = LoyaltyLedgerRules.redemption(
                    points,
                    customer.pointsBalance,
                    pointValue,
                )
            ) {
                is RedemptionCheck.Discount -> {
                    val preview = previewOf(catalog, current, written.copy(redeemed = points))
                    if (preview.problem == CheckoutProblem.PointsAboveSale) {
                        return pointsError(CheckoutPointsError.AboveSale)
                    }
                }

                is RedemptionCheck.NotEnough ->
                    return pointsError(CheckoutPointsError.NotEnough(check.available))

                RedemptionCheck.AboveSale -> return pointsError(CheckoutPointsError.AboveSale)

                // Cero ya se resolvió arriba: quita el canje.
                RedemptionCheck.NothingToRedeem -> Unit
            }
        }
        keepingSinglePaymentFull {
            it.copy(
                redeemed = points,
                pointsText = if (points == 0L) "" else it.pointsText,
                pointsError = null,
            )
        }
    }

    private fun pointsError(error: CheckoutPointsError) = change { it.copy(pointsError = error) }

    /**
     * El descuento de los puntos canjeados, o cero si no hay canje, si el cliente ya no se puede
     * elegir o si el programa se apagó mientras se cobraba: como la propina, deja de contar.
     */
    private fun pointsDiscountOf(catalog: SaleCatalog, written: Editing): Money {
        val pointValue = catalog.loyalty.pointValue
        val customerId = written.customerId
        if (written.redeemed <= 0L || customerId == null || !catalog.loyalty.active ||
            pointValue == null || customers.value.none { it.id == customerId }
        ) {
            return Money.ZERO
        }
        // El canje ya se comprobó al aplicarlo: su descuento cabe en el tope de un documento.
        val discount = LoyaltyProgramRules.discountFor(written.redeemed, pointValue)
        return (discount as? Checked.Value)?.value ?: Money.ZERO
    }

    /** Los puntos que se canjean al registrar: los aplicados, si siguen contando. */
    private fun pointsToRedeem(catalog: SaleCatalog, written: Editing): Long =
        if (pointsDiscountOf(catalog, written) > Money.ZERO) written.redeemed else 0L

    /** La sección `Puntos`, solo con el programa activo y un cliente que se puede elegir. */
    private fun pointsOf(
        catalog: SaleCatalog,
        written: Editing,
        choices: List<Customer>,
    ): CheckoutPoints? {
        val pointValue = catalog.loyalty.pointValue
        if (!catalog.loyalty.active || pointValue == null) return null
        val customer = choices.firstOrNull { it.id == written.customerId } ?: return null
        val typed = NumberInputParser.count(
            written.pointsText,
            numbers.quantitySymbols,
            Limit.POINTS,
        ) as? NumberInput.Value
        val discount = typed?.value?.takeIf { it > 0L }?.let { points ->
            (LoyaltyProgramRules.discountFor(points, pointValue) as? Checked.Value)?.value
        }
        return CheckoutPoints(
            available = customer.pointsBalance,
            text = written.pointsText,
            discount = discount,
            redeemed = written.redeemed,
            error = written.pointsError,
        )
    }

    /** El vencimiento del cargo a crédito: nunca antes de la fecha de la venta. */
    private fun creditDue(date: BusinessDate?) {
        val catalog = readyCatalog() ?: return
        val saleDate = sale?.date ?: catalog.today
        if (date != null && saleDate != null && date < saleDate) {
            overlay.update { it.copy(dueError = true) }
            return
        }
        change { it.copy(creditDue = date) }
        overlay.update { it.copy(dueError = false) }
    }

    /** Si algún pago escrito es a crédito. */
    private fun hasCredit(catalog: SaleCatalog, written: Editing): Boolean {
        val credit = catalog.creditMethods.map { it.id }.toSet()
        return written.payments.any { it.methodId in credit }
    }

    // ------------------------------------------------------------------ pagos

    /**
     * La primera vez que llega el catálogo, el cobro empieza con un pago del método elegido en la
     * venta por todo lo que se cobra. Si se abrió para enseñar la imagen de cobro, la abre.
     */
    private fun onCatalog(catalog: SaleCatalog) {
        val current = sale ?: return
        if (editing.value.started) return
        val methodId = current.methodId?.takeIf { id -> catalog.methods.any { it.id == id } }
            ?: catalog.methods.firstOrNull()?.id
            ?: return
        val due = previewOf(catalog, current, editing.value).due ?: Money.ZERO
        change {
            // El cliente con que llegó la venta se conserva al poner el primer pago.
            it.copy(payments = listOf(Written(methodId, due, moneyText(due))), started = true)
        }
        if (qrOnly) showQr(0)
    }

    private fun amount(index: Int, text: String) = changePayment(index) { written ->
        val updated = written.copy(amountText = text, amountError = null)
        when (val read = readMoney(text)) {
            NumberInput.Blank -> updated.copy(amount = Money.ZERO)

            is NumberInput.Value -> updated.copy(amount = read.value)

            NumberInput.Malformed,
            is NumberInput.TooManyDecimals,
            is NumberInput.TooLarge,
            -> updated.copy(amountError = errorOf(read))
        }
    }

    private fun fee(index: Int, text: String) = changePayment(index) { written ->
        val updated = written.copy(feeText = text, feeError = null)
        when (val read = readMoney(text)) {
            // Sin comisión escrita vale la del método.
            NumberInput.Blank -> updated.copy(fee = null, feeText = null)

            is NumberInput.Value -> updated.copy(fee = read.value)

            NumberInput.Malformed,
            is NumberInput.TooManyDecimals,
            is NumberInput.TooLarge,
            -> updated.copy(feeError = errorOf(read))
        }
    }

    private fun tendered(index: Int, text: String) = changePayment(index) { written ->
        val updated = written.copy(tenderedText = text, tenderedError = null)
        when (val read = readMoney(text)) {
            // Sin lo recibido no hay cambio: es solo una ayuda, y no impide registrar.
            NumberInput.Blank -> updated.copy(tendered = null)

            is NumberInput.Value -> updated.copy(tendered = read.value)

            else -> updated.copy(tenderedError = errorOf(read))
        }
    }

    /** `Monto exacto`: lo recibido es justo lo que cubre el pago, ya redondeado, y no hay cambio. */
    private fun exactAmount(index: Int) {
        val catalog = readyCatalog() ?: return
        val current = sale ?: return
        val amount = previewOf(catalog, current, editing.value).payments.getOrNull(index)?.amount
            ?: return
        changePayment(index) {
            it.copy(tendered = amount, tenderedText = moneyText(amount), tenderedError = null)
        }
    }

    /**
     * Lee la propina. Con un solo pago que cubría todo, el pago sigue cubriendo todo, como con el
     * ajuste de la venta: la propina se escribe y el cobro ya cuadra.
     */
    private fun tip(text: String) {
        val read = readMoney(text)
        val tip = when (read) {
            NumberInput.Blank -> Money.ZERO

            is NumberInput.Value -> read.value

            NumberInput.Malformed,
            is NumberInput.TooManyDecimals,
            is NumberInput.TooLarge,
            -> {
                change { it.copy(tipText = text, tipError = errorOf(read)) }
                return
            }
        }
        keepingSinglePaymentFull {
            it.copy(
                tip = tip,
                tipText = if (read is NumberInput.Blank) "" else text,
                tipError = null,
            )
        }
    }

    private fun removePayment(index: Int) {
        val current = editing.value
        if (current.payments.size <= 1 || index !in current.payments.indices) return
        change {
            it.copy(
                payments = it.payments.filterIndexed { i, _ -> i != index },
                active = it.active.coerceAtMost(it.payments.size - 2),
            )
        }
        clearBalanceError()
    }

    private fun addPayment(methodId: Long) {
        val catalog = readyCatalog() ?: return
        val current = sale ?: return
        val written = editing.value
        if (written.payments.any { it.methodId == methodId }) return
        val preview = previewOf(catalog, current, written)
        val missing = (preview.balance as? CheckoutBalance.Missing)?.amount ?: Money.ZERO
        change {
            it.copy(
                payments = it.payments + Written(methodId, missing, moneyText(missing)),
                active = it.payments.size,
            )
        }
        val needsCustomer = written.customerId == null &&
            catalog.creditMethods.any { it.id == methodId }
        overlay.update {
            it.copy(
                sheet = if (needsCustomer) CheckoutSheet.Customers else null,
                customerQuery = "",
                balanceError = false,
            )
        }
    }

    /** `Usar {amount}`: completa el pago activo con lo que falta, o le quita lo que sobra. */
    private fun useDue() {
        val catalog = readyCatalog() ?: return
        val current = sale ?: return
        val written = editing.value
        val index = written.active.coerceIn(written.payments.indices)
        val row = written.payments[index]
        val amount = when (val balance = previewOf(catalog, current, written).balance) {
            is CheckoutBalance.Missing -> row.amount + balance.amount
            is CheckoutBalance.Excess -> maxOf(Money.ZERO, row.amount - balance.amount)
            CheckoutBalance.Complete, null -> return
        }
        changePayment(index) {
            it.copy(amount = amount, amountText = moneyText(amount), amountError = null)
        }
        clearBalanceError()
    }

    /**
     * Cambia el pago número [index] con [edit]. El aviso de que los pagos no suman solo se quita si
     * cambió un monto: al tocar `Registrar venta`, el campo pierde el foco y vuelve a avisar su
     * mismo texto, y eso no debe borrar el aviso que se acaba de mostrar.
     */
    private fun changePayment(index: Int, edit: (Written) -> Written) {
        val before = editing.value.payments.getOrNull(index) ?: return
        val after = edit(before)
        change { written ->
            written.copy(
                payments = written.payments.mapIndexed { i, row -> if (i == index) after else row },
                active = index,
            )
        }
        val changed = after.amount != before.amount || after.fee != before.fee ||
            after.tendered != before.tendered
        if (changed) clearBalanceError()
    }

    // ------------------------------------------------------------------ ajuste de la venta

    private fun openAdjustment() {
        val adjustment = editing.value.adjustment
        val editor = AdjustmentEditor(
            kind = if (adjustment >
                Money.ZERO
            ) {
                AdjustmentKind.SURCHARGE
            } else {
                AdjustmentKind.DISCOUNT
            },
            text = if (adjustment == Money.ZERO) "" else moneyText(absolute(adjustment)),
            canRemove = adjustment != Money.ZERO,
        )
        overlay.update { it.copy(sheet = CheckoutSheet.Adjustment(editor)) }
    }

    private fun editAdjustment(change: (AdjustmentEditor) -> AdjustmentEditor) =
        overlay.update { shown ->
            val sheet = shown.sheet as? CheckoutSheet.Adjustment ?: return@update shown
            shown.copy(sheet = CheckoutSheet.Adjustment(change(sheet.editor)))
        }

    /**
     * Lee el ajuste escrito y lo aplica. Un porcentaje se convierte a monto sobre lo que suman las
     * líneas tras sus descuentos, y no se guarda: la venta guarda el monto.
     */
    private fun applyAdjustment() {
        val catalog = readyCatalog() ?: return
        val current = sale ?: return
        val editor = (overlay.value.sheet as? CheckoutSheet.Adjustment)?.editor ?: return
        val subtotal =
            previewOf(catalog, current, editing.value.copy(adjustment = Money.ZERO)).subtotal
                ?: return
        val amount: Money = when (editor.input) {
            AdjustmentInput.AMOUNT -> when (val read = readMoney(editor.text)) {
                NumberInput.Blank -> Money.ZERO

                is NumberInput.Value -> read.value

                NumberInput.Malformed,
                is NumberInput.TooManyDecimals,
                is NumberInput.TooLarge,
                -> return adjustmentError(read)
            }

            AdjustmentInput.PERCENT -> when (
                val read = NumberInputParser.percent(
                    editor.text,
                    numbers.percentSymbols,
                    BasisPoints.ONE_HUNDRED_PERCENT,
                )
            ) {
                NumberInput.Blank -> Money.ZERO

                is NumberInput.Value -> when (
                    val share = subtotal.timesRate(
                        absoluteRate(read.value),
                    )
                ) {
                    is Checked.Value -> share.value

                    Checked.Overflow, is Checked.AboveLimit ->
                        return setAdjustmentError(AdjustmentError.TOO_HIGH)
                }

                NumberInput.Malformed,
                is NumberInput.TooManyDecimals,
                is NumberInput.TooLarge,
                -> return adjustmentError(read)
            }
        }
        val signed = when (editor.kind) {
            AdjustmentKind.DISCOUNT -> -amount
            AdjustmentKind.SURCHARGE -> amount
        }
        if (editor.kind == AdjustmentKind.DISCOUNT && amount > subtotal) {
            return setAdjustmentError(AdjustmentError.DISCOUNT_ABOVE_TOTAL)
        }
        setAdjustment(signed)
    }

    /**
     * Cambia el ajuste y cierra la hoja. Con un solo pago que cubría todo, el pago sigue cubriendo
     * todo: así la venta con un pago cuadra sin escribir nada más.
     */
    private fun setAdjustment(adjustment: Money) {
        keepingSinglePaymentFull { it.copy(adjustment = adjustment) }
        overlay.update { it.copy(sheet = null, balanceError = false) }
    }

    /**
     * Aplica [update] a lo escrito. Si había un solo pago que cubría todo, lo sigue cubriendo con lo
     * que se cobra después del cambio: así la venta con un pago cuadra sin escribir nada más.
     */
    private fun keepingSinglePaymentFull(update: (Editing) -> Editing) {
        val catalog = readyCatalog()
        val current = sale
        val written = editing.value
        val single = written.payments.singleOrNull()
        change(update)
        if (catalog != null && current != null && single != null &&
            single.amount == previewOf(catalog, current, written).due
        ) {
            val due = previewOf(catalog, current, editing.value).due ?: Money.ZERO
            change {
                it.copy(
                    payments = listOf(
                        it.payments.single().copy(amount = due, amountText = moneyText(due)),
                    ),
                )
            }
        }
        clearBalanceError()
    }

    private fun adjustmentError(read: NumberInput<*>) = setAdjustmentError(
        when (errorOf(read)) {
            SaleFieldError.TOO_HIGH -> AdjustmentError.TOO_HIGH

            SaleFieldError.NOT_A_NUMBER,
            SaleFieldError.FRACTION_NOT_ALLOWED,
            SaleFieldError.ZERO_QUANTITY,
            SaleFieldError.DISCOUNT_OVER_LINE,
            -> AdjustmentError.NOT_A_NUMBER
        },
    )

    private fun setAdjustmentError(error: AdjustmentError) = editAdjustment {
        it.copy(error = error)
    }

    // ------------------------------------------------------------------ imagen de cobro

    private fun showQr(index: Int) {
        val catalog = readyCatalog() ?: return
        val row = editing.value.payments.getOrNull(index) ?: return
        val record = catalog.payable.firstOrNull { it.id == row.methodId } ?: return
        overlay.update { it.copy(qrIndex = index, qrImage = null) }
        if (record.details.instructionsMediaId == null) return
        viewModelScope.launch {
            val image = findImage(record)
            overlay.update { if (it.qrIndex == index) it.copy(qrImage = image) else it }
        }
    }

    // ------------------------------------------------------------------ confirmar

    private fun register(zeroConfirmed: Boolean) {
        if (overlay.value.status == RegisterStatus.WORKING) return
        val catalog = readyCatalog() ?: return
        val current = sale ?: return
        val written = editing.value
        if (written.payments.any { row -> catalog.payable.none { it.id == row.methodId } }) return
        if (written.payments.any { it.hasError } || written.tipError != null) return
        val credit = hasCredit(catalog, written)
        val customerId = written.customerId?.takeIf { id -> customers.value.any { it.id == id } }
        val redeemed = pointsToRedeem(catalog, written)
        if (credit && customerId == null) {
            overlay.update {
                it.copy(customerMissing = true, sheet = CheckoutSheet.Customers, customerQuery = "")
            }
            return
        }
        val preview = previewOf(catalog, current, written)
        if (preview.problem != null) return
        if (preview.balance != CheckoutBalance.Complete) {
            overlay.update { it.copy(balanceError = true) }
            return
        }
        val charged = preview.amountDue ?: return
        if (charged == Money.ZERO && !zeroConfirmed) {
            overlay.update { it.copy(zeroDialog = true) }
            return
        }
        overlay.update { it.copy(zeroDialog = false, status = RegisterStatus.WORKING) }
        viewModelScope.launch {
            val request = SaleRequest(
                lines = current.lines,
                paymentMethodId = written.payments.first().methodId,
                date = current.date,
                note = current.note.takeIf { it.isNotBlank() },
                payments = written.payments.map {
                    SalePaymentRequest(it.methodId, it.amount, it.fee, it.tendered)
                },
                orderAdjustment = written.adjustment,
                // Una venta retomada de la espera sale de ella al confirmarse, también desde aquí.
                heldSaleId = current.heldSaleId,
                tipAmount = tipOf(catalog, written),
                customerId = customerId,
                creditDueDate = written.creditDue.takeIf { credit },
                pointsRedeemed = redeemed,
            )
            when (val result = registerSale(request)) {
                is RegisterSaleResult.Registered -> overlay.update {
                    Overlay(
                        registered = RegisteredSale(result.saleId, charged, result.pointsEarned),
                    )
                }

                // El saldo de puntos cambió mientras se cobraba: se dice en el campo, y el cobro
                // sigue armado para corregirlo.
                is RegisterSaleResult.NotEnoughPoints -> {
                    pointsError(CheckoutPointsError.NotEnough(result.available))
                    overlay.update { it.copy(status = RegisterStatus.IDLE) }
                }

                RegisterSaleResult.PointsAboveSale -> {
                    pointsError(CheckoutPointsError.AboveSale)
                    overlay.update { it.copy(status = RegisterStatus.IDLE) }
                }

                is RegisterSaleResult.PaymentsDoNotMatch -> overlay.update {
                    it.copy(status = RegisterStatus.IDLE, balanceError = true)
                }

                // Cada rechazo se nombra, sin rama por defecto, para que un resultado nuevo obligue
                // a decidir aquí cómo se muestra. Los demás dejan el cobro armado y avisan que no se
                // pudo registrar: todo sigue en pantalla para reintentar.
                RegisterSaleResult.EmptySale,
                RegisterSaleResult.TooManyLines,
                RegisterSaleResult.NoteTooLong,
                RegisterSaleResult.CurrencyNotConfirmed,
                is RegisterSaleResult.UnknownTimeZone,
                RegisterSaleResult.FutureDate,
                RegisterSaleResult.PaymentMethodNotFound,
                RegisterSaleResult.PaymentMethodArchived,
                RegisterSaleResult.PaymentNeedsCustomer,
                RegisterSaleResult.CustomerUnavailable,
                RegisterSaleResult.CreditDueBeforeSale,
                is RegisterSaleResult.LineRejected,
                RegisterSaleResult.SaleTotalTooLarge,
                is RegisterSaleResult.StorageFailed,
                is RegisterSaleResult.PaymentRejected,
                RegisterSaleResult.OrderDiscountTooLarge,
                RegisterSaleResult.TipNotAccepted,
                RegisterSaleResult.PointsNotAvailable,
                -> overlay.update { it.copy(status = RegisterStatus.FAILED) }
            }
        }
    }

    // ------------------------------------------------------------------ estado

    private fun build(
        load: CatalogLoad,
        written: Editing,
        shown: Overlay,
        choices: List<Customer>,
    ): CheckoutUiState {
        val current = sale ?: return CheckoutUiState(content = CheckoutContent.Unavailable)
        val catalog = when (load) {
            CatalogLoad.Loading -> return CheckoutUiState()
            CatalogLoad.Failed -> return CheckoutUiState(content = CheckoutContent.ReadFailed)
            is CatalogLoad.Ready -> load.catalog
        }
        val methods = catalog.payable.associateBy { it.id }
        methods.values.forEach { knownMethods[it.id] = saleMethodOf(it) }
        val preview = previewOf(catalog, current, written)
        val date = current.date ?: catalog.today
        val rows = written.payments.mapIndexed { index, row ->
            val record = methods[row.methodId]
            val computed = preview.payments.getOrNull(index)
            val details = record?.details
            val hasFee = details != null &&
                (details.feeRate != BasisPoints.ZERO || details.feeFixed != Money.ZERO)
            val days = record?.details?.settlementDays ?: 0
            CheckoutPaymentRow(
                method =
                    record?.let(::saleMethodOf) ?: knownMethods[row.methodId]
                        ?: unknownMethod(row.methodId),
                amountText = row.amountText,
                amountError = row.amountError,
                covers = computed?.amount?.takeIf { it != row.amount },
                showsFee = hasFee || row.fee != null,
                feeText = row.feeText ?: computed?.fee?.let(::moneyText).orEmpty(),
                feeError = row.feeError,
                feeAboveAmount = computed?.feeAboveAmount ?: false,
                net = computed?.netReceived,
                settlesOn = if (days >
                    0
                ) {
                    date?.let { PaymentFees.settlementDateOf(it, days) }
                } else {
                    null
                },
                gone = record == null,
                isCash = details?.kind == PaymentMethodKind.CASH,
                tenderedText = row.tenderedText,
                tenderedError = row.tenderedError,
                change = computed?.change,
                tenderedBelow = computed?.tenderedBelowAmount ?: false,
            )
        }
        val used = written.payments.map { it.methodId }.toSet()
        val summary = CheckoutSummary(
            amountDue = preview.amountDue ?: preview.due,
            subtotal = preview.subtotal,
            writtenAdjustment = written.adjustment,
            methodAdjustments = preview.payments.mapIndexedNotNull { index, payment ->
                payment.surcharge.takeIf {
                    it != Money.ZERO
                }?.let { MethodAdjustment(rows[index].method, it) }
            },
            tax = preview.taxTotal?.takeIf { catalog.taxes.mode == TaxMode.ADDED },
            cashRounding = preview.cashRounding,
            tip = preview.tip,
            pointsDiscount = preview.pointsDiscount,
        )
        val qr = shown.qrIndex?.let { index ->
            val row = written.payments.getOrNull(index) ?: return@let null
            val record = methods[row.methodId] ?: return@let null
            CheckoutQr(
                method = saleMethodOf(record),
                amount = preview.payments.getOrNull(index)?.amount,
                image = shown.qrImage,
                instructions = record.details.instructionsText,
                qrOnly = qrOnly,
            )
        }
        return CheckoutUiState(
            content = CheckoutContent.Ready,
            summary = summary,
            adjustment = written.adjustment,
            payments = rows,
            addable = catalog.payable.filter { it.id !in used }.map(::saleMethodOf),
            balance = preview.balance,
            due = preview.due,
            balanceError = shown.balanceError,
            problem = preview.problem,
            sheet = shown.sheet,
            qr = qr,
            zeroDialog = shown.zeroDialog,
            status = shown.status,
            registered = shown.registered,
            tipsEnabled = catalog.tipsEnabled,
            tipText = written.tipText,
            tipError = written.tipError,
            customer = customerOf(choices, written, shown),
            customerChoices =
                if (shown.sheet == CheckoutSheet.Customers) {
                    CustomerSearch.filter(choices, shown.customerQuery)
                } else {
                    emptyList()
                },
            customerQuery = shown.customerQuery,
            customerRemoved = shown.customerRemoved,
            credit = creditOf(catalog, current, written, shown, choices, preview),
            points = pointsOf(catalog, written, choices),
        )
    }

    /** El cliente elegido, si sigue entre los que se pueden elegir, o si falta uno a crédito. */
    private fun customerOf(
        choices: List<Customer>,
        written: Editing,
        shown: Overlay,
    ): CheckoutCustomer? {
        val chosen = choices.firstOrNull { it.id == written.customerId }
        return when {
            chosen != null -> CheckoutCustomer.Chosen(
                chosen.id,
                chosen.contact?.name.orEmpty(),
                chosen.balance,
            )

            shown.customerMissing -> CheckoutCustomer.Missing

            else -> null
        }
    }

    /**
     * El crédito de la venta, si algún pago es a crédito: el vencimiento y el saldo que dejará al
     * cliente elegido.
     */
    private fun creditOf(
        catalog: SaleCatalog,
        current: SaleDraft,
        written: Editing,
        shown: Overlay,
        choices: List<Customer>,
        preview: CheckoutPreview,
    ): CheckoutCredit? {
        if (!hasCredit(catalog, written)) return null
        val credit = catalog.creditMethods.map { it.id }.toSet()
        val onAccount = written.payments.mapIndexedNotNull { index, row ->
            preview.payments.getOrNull(index)?.amount?.takeIf { row.methodId in credit }
        }
        val customer = choices.firstOrNull { it.id == written.customerId }
        val after = customer?.let {
            runCatching { onAccount.fold(it.balance) { sum, amount -> sum + amount } }.getOrNull()
        }
        return CheckoutCredit(
            due = written.creditDue,
            dueError = shown.dueError,
            balanceAfter = after,
            saleDate = current.date ?: catalog.today,
            weekStartDay = catalog.weekStartDay,
        )
    }

    /** Las cuentas del cobro con lo escrito, las mismas que hará la venta al confirmarse. */
    private fun previewOf(
        catalog: SaleCatalog,
        current: SaleDraft,
        written: Editing,
    ): CheckoutPreview {
        val methods = catalog.payable.associateBy { it.id }
        val pricing = CartPricing(
            taxMode = catalog.taxes.mode,
            rates = catalog.taxRates,
            defaultRateId = catalog.taxes.defaultTaxRateId,
        )
        val inputs = written.payments.map { row ->
            val details = methods[row.methodId]?.details
            CheckoutPaymentInput(
                methodId = row.methodId,
                kind = details?.kind ?: PaymentMethodKind.OTHER,
                amount = row.amount,
                priceAdjustment = details?.priceAdjustment ?: BasisPoints.ZERO,
                feeRate = details?.feeRate ?: BasisPoints.ZERO,
                feeFixed = details?.feeFixed ?: Money.ZERO,
                feeAmount = row.fee,
                tenderedAmount = row.tendered.takeIf { details?.kind == PaymentMethodKind.CASH },
            )
        }
        return SaleCheckout.preview(
            current.lines,
            catalog.products.associateBy { it.id },
            pricing,
            written.adjustment,
            inputs,
            cashStep = catalog.cashRounding,
            conversions = catalog.conversions,
            tip = tipOf(catalog, written),
            pointsDiscount = pointsDiscountOf(catalog, written),
        )
    }

    /** La propina que cuenta: ninguna si las propinas se apagaron mientras tanto. */
    private fun tipOf(catalog: SaleCatalog, written: Editing): Money =
        if (catalog.tipsEnabled) written.tip else Money.ZERO

    private fun readyCatalog(): SaleCatalog? = (catalog.value as? CatalogLoad.Ready)?.catalog

    private fun clearBalanceError() = overlay.update {
        if (it.balanceError || it.status == RegisterStatus.FAILED) {
            it.copy(balanceError = false, status = RegisterStatus.IDLE)
        } else {
            it
        }
    }

    private fun change(update: (Editing) -> Editing) {
        editing.update(update)
        save(editing.value)
    }

    /** Guarda lo escrito: seis números por pago y el ajuste. Los textos se rearman al volver. */
    private fun save(written: Editing) {
        val values = LongArray(written.payments.size * FIELDS)
        written.payments.forEachIndexed { index, row ->
            val base = index * FIELDS
            values[base] = row.methodId
            values[base + 1] = row.amount.minor
            values[base + 2] = if (row.fee == null) 0L else 1L
            values[base + 3] = row.fee?.minor ?: 0L
            values[base + 4] = if (row.tendered == null) 0L else 1L
            values[base + 5] = row.tendered?.minor ?: 0L
        }
        savedState[PAYMENTS] = values
        savedState[ADJUSTMENT] = written.adjustment.minor
        savedState[TIP] = written.tip.minor
        savedState[CUSTOMER] = written.customerId
        savedState[CREDIT_DUE] = written.creditDue?.toString()
        savedState[POINTS] = written.redeemed
    }

    private fun restore(): Editing {
        val values = savedState.get<LongArray>(PAYMENTS)?.takeIf { it.size % FIELDS == 0 }
            ?: return Editing()
        val tip = Money(savedState.get<Long>(TIP) ?: 0L)
        val redeemed = savedState.get<Long>(POINTS) ?: 0L
        val payments = (0 until values.size / FIELDS).map { index ->
            val base = index * FIELDS
            val amount = Money(values[base + 1])
            val fee = if (values[base + 2] == 1L) Money(values[base + 3]) else null
            val tendered = if (values[base + 4] == 1L) Money(values[base + 5]) else null
            Written(
                values[base],
                amount,
                moneyText(amount),
                fee = fee,
                feeText = fee?.let(::moneyText),
                tendered = tendered,
                tenderedText = tendered?.let(::moneyText).orEmpty(),
            )
        }
        return Editing(
            payments = payments,
            adjustment = Money(savedState.get<Long>(ADJUSTMENT) ?: 0L),
            started = payments.isNotEmpty(),
            tip = tip,
            tipText = if (tip == Money.ZERO) "" else moneyText(tip),
            customerId = savedState.get<Long>(CUSTOMER),
            creditDue = savedState.get<String>(CREDIT_DUE)?.let(BusinessDate::parse),
            redeemed = redeemed,
            // Sin separadores: el campo los acepta igual, y no depende del formato al volver.
            pointsText = if (redeemed > 0L) redeemed.toString() else "",
        )
    }

    private fun readMoney(text: String): NumberInput<Money> {
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.DOCUMENT_TOTAL,
        )
        // Un pago, una comisión o un ajuste nunca son negativos: el signo lo da el tipo de ajuste.
        return if (read is NumberInput.Value &&
            read.value < Money.ZERO
        ) {
            NumberInput.Malformed
        } else {
            read
        }
    }

    /**
     * El error de un campo que no se leyó. Un valor leído o un campo vacío no son errores: quien
     * llama ya los resolvió, y aquí se dicen como texto ilegible solo para nombrar cada caso.
     */
    private fun errorOf(read: NumberInput<*>): SaleFieldError = when (read) {
        is NumberInput.TooLarge -> SaleFieldError.TOO_HIGH

        NumberInput.Malformed,
        is NumberInput.TooManyDecimals,
        is NumberInput.Value,
        NumberInput.Blank,
        -> SaleFieldError.NOT_A_NUMBER
    }

    private fun moneyText(amount: Money): String =
        plainNumberText(amount.minor, numbers.currencyDecimals, numbers.moneySymbols)

    /** Lo que se escribe en un pago o un ajuste. */
    private data class Written(
        val methodId: Long,
        val amount: Money,
        val amountText: String,
        val amountError: SaleFieldError? = null,
        val fee: Money? = null,
        val feeText: String? = null,
        val feeError: SaleFieldError? = null,
        val tendered: Money? = null,
        val tenderedText: String = "",
        val tenderedError: SaleFieldError? = null,
    ) {
        /** Si algún campo del pago no se pudo leer: el cobro no se registra así. */
        val hasError: Boolean
            get() = amountError != null || feeError != null || tenderedError != null
    }

    /**
     * Lo escrito en el cobro: los pagos, el ajuste y el pago activo, el último tocado, que es el que
     * completa `Usar {amount}`. [started] es si ya se puso el primer pago.
     */
    private data class Editing(
        val payments: List<Written> = emptyList(),
        val adjustment: Money = Money.ZERO,
        val active: Int = 0,
        val started: Boolean = false,
        val tip: Money = Money.ZERO,
        val tipText: String = "",
        val tipError: SaleFieldError? = null,
        val customerId: Long? = null,
        val creditDue: BusinessDate? = null,
        val redeemed: Long = 0L,
        val pointsText: String = "",
        val pointsError: CheckoutPointsError? = null,
    ) {
        /** Lo mismo sin canje de puntos: lo que queda al cambiar o quitar el cliente. */
        fun withoutRedemption(): Editing = copy(redeemed = 0L, pointsText = "", pointsError = null)
    }

    /** Lo que está abierto encima del cobro, y lo que pasa una sola vez. */
    private data class Overlay(
        val sheet: CheckoutSheet? = null,
        val qrIndex: Int? = null,
        val qrImage: StoredImage? = null,
        val zeroDialog: Boolean = false,
        val balanceError: Boolean = false,
        val status: RegisterStatus = RegisterStatus.IDLE,
        val registered: RegisteredSale? = null,
        val customerQuery: String = "",
        val customerMissing: Boolean = false,
        val customerRemoved: Boolean = false,
        val dueError: Boolean = false,
    )

    private companion object {
        const val STARTED = "checkout.started"
        const val QR_ONLY = "checkout.qrOnly"
        const val PAYMENTS = "checkout.payments"
        const val ADJUSTMENT = "checkout.adjustment"
        const val TIP = "checkout.tip"
        const val CUSTOMER = "checkout.customer"
        const val CREDIT_DUE = "checkout.creditDue"
        const val POINTS = "checkout.points"
        const val FIELDS = 6

        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer el catálogo. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Un método de pago como se ofrece al cobrar. */
internal fun saleMethodOf(record: PaymentMethodRecord) = SaleMethod(
    id = record.id,
    systemKey = record.systemKey,
    name = record.details.name,
    hasQr = record.details.instructionsText != null || record.details.instructionsMediaId != null,
)

/** Un método del que ya no se sabe nada: solo pasa si se archivó y el proceso se cerró. */
private fun unknownMethod(id: Long) = SaleMethod(id, systemKey = null, name = "", hasQr = false)

private fun absolute(amount: Money): Money = if (amount < Money.ZERO) -amount else amount

private fun absoluteRate(rate: BasisPoints): BasisPoints = if (rate.bp <
    0L
) {
    BasisPoints(-rate.bp)
} else {
    rate
}
