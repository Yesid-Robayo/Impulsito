package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutPaymentInput
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutPreview
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentFees
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCheckout
import io.github.yesidrobayo.shopledger.domain.rule.timesRate
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
            editing = MutableStateFlow(Editing())
            if (sale != null) {
                SaleDraftStore.write(savedState, sale)
                savedState[QR_ONLY] = qrOnly
                savedState[STARTED] = true
            }
        }
    }

    private val catalog: StateFlow<CatalogLoad> = catalogSource()
        .map<SaleCatalog, CatalogLoad> { CatalogLoad.Ready(it) }
        .catch { emit(CatalogLoad.Failed) }
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogLoad.Loading)

    val uiState: StateFlow<CheckoutUiState> = combine(catalog, editing, overlay) {
            load,
            written,
            shown,
        ->
        build(load, written, shown)
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
            is CheckoutAction.AmountChange -> amount(action.index, action.text)

            is CheckoutAction.FeeChange -> fee(action.index, action.text)

            is CheckoutAction.RemovePayment -> removePayment(action.index)

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
        }
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
            Editing(payments = listOf(Written(methodId, due, moneyText(due))), started = true)
        }
        if (qrOnly) showQr(0)
    }

    private fun amount(index: Int, text: String) = changePayment(index) { written ->
        val updated = written.copy(amountText = text, amountError = null)
        when (val read = readMoney(text)) {
            NumberInput.Blank -> updated.copy(amount = Money.ZERO)
            is NumberInput.Value -> updated.copy(amount = read.value)
            else -> updated.copy(amountError = errorOf(read))
        }
    }

    private fun fee(index: Int, text: String) = changePayment(index) { written ->
        val updated = written.copy(feeText = text, feeError = null)
        when (val read = readMoney(text)) {
            // Sin comisión escrita vale la del método.
            NumberInput.Blank -> updated.copy(fee = null, feeText = null)

            is NumberInput.Value -> updated.copy(fee = read.value)

            else -> updated.copy(feeError = errorOf(read))
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
        overlay.update { it.copy(sheet = null, balanceError = false) }
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
        if (after.amount != before.amount || after.fee != before.fee) clearBalanceError()
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
                else -> return adjustmentError(read)
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
                    else -> return setAdjustmentError(AdjustmentError.TOO_HIGH)
                }

                else -> return adjustmentError(read)
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
        val catalog = readyCatalog()
        val current = sale
        val written = editing.value
        val single = written.payments.singleOrNull()
        change { it.copy(adjustment = adjustment) }
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
        overlay.update { it.copy(sheet = null, balanceError = false) }
    }

    private fun adjustmentError(read: NumberInput<*>) = setAdjustmentError(
        when (read) {
            is NumberInput.TooLarge -> AdjustmentError.TOO_HIGH
            else -> AdjustmentError.NOT_A_NUMBER
        },
    )

    private fun setAdjustmentError(error: AdjustmentError) = editAdjustment {
        it.copy(error = error)
    }

    // ------------------------------------------------------------------ imagen de cobro

    private fun showQr(index: Int) {
        val catalog = readyCatalog() ?: return
        val row = editing.value.payments.getOrNull(index) ?: return
        val record = catalog.methods.firstOrNull { it.id == row.methodId } ?: return
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
        if (written.payments.any { row -> catalog.methods.none { it.id == row.methodId } }) return
        if (written.payments.any { it.amountError != null || it.feeError != null }) return
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
                    SalePaymentRequest(it.methodId, it.amount, it.fee)
                },
                orderAdjustment = written.adjustment,
                // Una venta retomada de la espera sale de ella al confirmarse, también desde aquí.
                heldSaleId = current.heldSaleId,
            )
            when (val result = registerSale(request)) {
                is RegisterSaleResult.Registered -> overlay.update {
                    Overlay(registered = RegisteredSale(result.saleId, charged))
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
                is RegisterSaleResult.LineRejected,
                RegisterSaleResult.SaleTotalTooLarge,
                is RegisterSaleResult.StorageFailed,
                is RegisterSaleResult.PaymentRejected,
                RegisterSaleResult.OrderDiscountTooLarge,
                -> overlay.update { it.copy(status = RegisterStatus.FAILED) }
            }
        }
    }

    // ------------------------------------------------------------------ estado

    private fun build(load: CatalogLoad, written: Editing, shown: Overlay): CheckoutUiState {
        val current = sale ?: return CheckoutUiState(content = CheckoutContent.Unavailable)
        val catalog = when (load) {
            CatalogLoad.Loading -> return CheckoutUiState()
            CatalogLoad.Failed -> return CheckoutUiState(content = CheckoutContent.Unavailable)
            is CatalogLoad.Ready -> load.catalog
        }
        val methods = catalog.methods.associateBy { it.id }
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
            addable = catalog.methods.filter { it.id !in used }.map(::saleMethodOf),
            balance = preview.balance,
            due = preview.due,
            balanceError = shown.balanceError,
            problem = preview.problem,
            sheet = shown.sheet,
            qr = qr,
            zeroDialog = shown.zeroDialog,
            status = shown.status,
            registered = shown.registered,
        )
    }

    /** Las cuentas del cobro con lo escrito, las mismas que hará la venta al confirmarse. */
    private fun previewOf(
        catalog: SaleCatalog,
        current: SaleDraft,
        written: Editing,
    ): CheckoutPreview {
        val methods = catalog.methods.associateBy { it.id }
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
            )
        }
        return SaleCheckout.preview(
            current.lines,
            catalog.products.associateBy { it.id },
            pricing,
            written.adjustment,
            inputs,
        )
    }

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

    /** Guarda lo escrito: cuatro números por pago y el ajuste. Los textos se rearman al volver. */
    private fun save(written: Editing) {
        val values = LongArray(written.payments.size * FIELDS)
        written.payments.forEachIndexed { index, row ->
            val base = index * FIELDS
            values[base] = row.methodId
            values[base + 1] = row.amount.minor
            values[base + 2] = if (row.fee == null) 0L else 1L
            values[base + 3] = row.fee?.minor ?: 0L
        }
        savedState[PAYMENTS] = values
        savedState[ADJUSTMENT] = written.adjustment.minor
    }

    private fun restore(): Editing {
        val values = savedState.get<LongArray>(PAYMENTS)?.takeIf { it.size % FIELDS == 0 }
            ?: return Editing()
        val payments = (0 until values.size / FIELDS).map { index ->
            val base = index * FIELDS
            val amount = Money(values[base + 1])
            val fee = if (values[base + 2] == 1L) Money(values[base + 3]) else null
            Written(
                values[base],
                amount,
                moneyText(amount),
                fee = fee,
                feeText = fee?.let(::moneyText),
            )
        }
        return Editing(
            payments = payments,
            adjustment = Money(savedState.get<Long>(ADJUSTMENT) ?: 0L),
            started = payments.isNotEmpty(),
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

    private fun errorOf(read: NumberInput<*>): SaleFieldError = when (read) {
        is NumberInput.TooLarge -> SaleFieldError.TOO_HIGH
        else -> SaleFieldError.NOT_A_NUMBER
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
    )

    /**
     * Lo escrito en el cobro: los pagos, el ajuste y el pago activo, el último tocado, que es el que
     * completa `Usar {amount}`. [started] es si ya se puso el primer pago.
     */
    private data class Editing(
        val payments: List<Written> = emptyList(),
        val adjustment: Money = Money.ZERO,
        val active: Int = 0,
        val started: Boolean = false,
    )

    /** Lo que está abierto encima del cobro, y lo que pasa una sola vez. */
    private data class Overlay(
        val sheet: CheckoutSheet? = null,
        val qrIndex: Int? = null,
        val qrImage: StoredImage? = null,
        val zeroDialog: Boolean = false,
        val balanceError: Boolean = false,
        val status: RegisterStatus = RegisterStatus.IDLE,
        val registered: RegisteredSale? = null,
    )

    private companion object {
        const val STARTED = "checkout.started"
        const val QR_ONLY = "checkout.qrOnly"
        const val PAYMENTS = "checkout.payments"
        const val ADJUSTMENT = "checkout.adjustment"
        const val FIELDS = 4

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
