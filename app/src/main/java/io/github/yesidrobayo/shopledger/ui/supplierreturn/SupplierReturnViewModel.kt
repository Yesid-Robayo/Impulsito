package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnLinePricing
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnPricingLine
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnRules
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnForm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** En qué va la lectura de lo que la devolución necesita. */
private sealed interface FormLoad {
    data object Loading : FormLoad

    data class Ready(val form: SupplierReturnForm) : FormLoad

    data object Failed : FormLoad
}

/**
 * La devolución a proveedor: arma la devolución en memoria y la confirma de una sola vez.
 *
 * - **Nada se escribe hasta confirmar.** Lo elegido vive en el estado guardado de la pantalla.
 * - **Lo que se ve es lo que se guarda.** Cada línea, su costo y el total salen del mismo cálculo
 *   que usa el caso de uso al registrar, con el stock y lo devuelto que se leen al momento.
 * - **Con compra, las líneas salen de ella.** La persona escribe cuánto devuelve de cada una, y lo
 *   que pase de lo que queda se marca sin dejar confirmar. El proveedor es el de la compra.
 * - **Sin compra, se agregan productos**, que salen al costo de salida.
 * - **Dejar el stock negativo avisa sin bloquear**, como cualquier salida.
 * - **Un error nunca borra lo escrito.** Un campo vacío solo se marca después de intentar registrar.
 *
 * @param formSource lo que la devolución lee para una compra y un proveedor; se vuelve a pedir al
 *   reintentar o al elegir otra compra.
 * @param register confirma la devolución, en una sola transacción.
 * @param purchasesOf las compras activas que se pueden elegir de origen, de un proveedor o de todos.
 * @param prefillPurchaseId la compra con que se abrió, como desde su detalle.
 * @param prefillSupplierId el proveedor con que se abrió, como desde su detalle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierReturnViewModel(
    private val formSource: (purchaseId: Long?, supplierId: Long?) -> Flow<SupplierReturnForm>,
    private val register: suspend (SupplierReturnRequest) -> RegisterSupplierReturnResult,
    private val purchasesOf: suspend (supplierId: Long?) -> List<PurchaseHistoryRow>,
    private val savedState: SavedStateHandle,
    private val quantitySymbols: NumberSymbols,
    computation: CoroutineDispatcher,
    prefillPurchaseId: Long? = null,
    prefillSupplierId: Long? = null,
    payingAccounts: Flow<List<MoneyAccount>> = flowOf(emptyList()),
) : ViewModel() {
    private val draft = MutableStateFlow(
        SupplierReturnDraftStore.read(
            savedState,
            SupplierReturnDraft(purchaseId = prefillPurchaseId, supplierId = prefillSupplierId),
        ),
    )
    private val overlay = MutableStateFlow(Overlay())
    private val attempts = MutableStateFlow(0)

    private val form: StateFlow<FormLoad> = combine(
        attempts,
        draft.map { it.purchaseId to it.supplierId }.distinctUntilChanged(),
        ::Pair,
    ).flatMapLatest { (_, ids) ->
        formSource(ids.first, ids.second)
            .map<SupplierReturnForm, FormLoad> { FormLoad.Ready(it) }
            // Una falla de lectura no cierra la devolución: muestra el error y deja reintentar.
            .catch { emit(FormLoad.Failed) }
    }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormLoad.Loading)

    val uiState: StateFlow<SupplierReturnUiState> = combine(form, draft, overlay, ::build)
        .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SupplierReturnUiState(withPurchase = prefillPurchaseId != null),
        )

    init {
        viewModelScope.launch {
            // Sin cuentas legibles, el reembolso no ofrece ninguna: se registra sin dinero.
            payingAccounts.catch { emit(emptyList()) }.collect { accounts ->
                overlay.update { it.copy(accounts = accounts) }
            }
        }
        viewModelScope.launch {
            // Con la compra a la vista, el proveedor es el suyo.
            form.collect { load ->
                val purchase = (load as? FormLoad.Ready)?.form?.purchase ?: return@collect
                val supplier = purchase.purchase.content.supplierId ?: return@collect
                if (draft.value.supplierId !=
                    supplier
                ) {
                    changeDraft { it.copy(supplierId = supplier) }
                }
            }
        }
    }

    fun onAction(action: SupplierReturnAction) {
        when (action) {
            SupplierReturnAction.OpenSuppliers -> overlay.update {
                it.copy(sheet = SheetKind.SUPPLIERS, query = "")
            }

            SupplierReturnAction.OpenProducts -> overlay.update {
                it.copy(sheet = SheetKind.PRODUCTS, query = "")
            }

            SupplierReturnAction.OpenPurchases -> openPurchases()

            is SupplierReturnAction.QueryChange -> overlay.update { it.copy(query = action.query) }

            SupplierReturnAction.CloseSheet -> closeSheet()

            is SupplierReturnAction.SelectSupplier -> {
                changeDraft { it.copy(supplierId = action.supplierId) }
                closeSheet()
            }

            is SupplierReturnAction.SelectPurchase -> {
                changeDraft {
                    it.copy(
                        purchaseId = action.purchaseId,
                        purchaseQuantities = emptyMap(),
                        loose = emptyList(),
                        showMissing = false,
                    )
                }
                closeSheet()
            }

            is SupplierReturnAction.AddProduct -> {
                changeDraft {
                    it.copy(
                        loose = it.loose + LooseLineDraft(it.nextKey, action.productId, ""),
                        nextKey = it.nextKey + 1,
                    )
                }
                closeSheet()
            }

            is SupplierReturnAction.QuantityChange -> changeDraft { current ->
                if (current.purchaseId != null) {
                    current.copy(
                        purchaseQuantities =
                            current.purchaseQuantities + (action.key to action.text),
                    )
                } else {
                    current.copy(
                        loose = current.loose.map {
                            if (it.key == action.key) it.copy(quantityText = action.text) else it
                        },
                    )
                }
            }

            is SupplierReturnAction.RemoveLine -> changeDraft { current ->
                current.copy(loose = current.loose.filterNot { it.key == action.key })
            }

            is SupplierReturnAction.ResolutionChange -> changeDraft {
                it.copy(resolution = action.resolution)
            }

            is SupplierReturnAction.AccountChange -> {
                changeDraft { it.copy(accountId = action.accountId) }
                overlay.update { it.copy(notice = null) }
            }

            is SupplierReturnAction.NoteChange -> changeDraft { it.copy(note = action.text) }

            SupplierReturnAction.Register -> registerNow()

            // Reintentar vuelve a leer si la lectura falló, y si no, vuelve a registrar lo mismo.
            SupplierReturnAction.Retry ->
                if (form.value == FormLoad.Failed) attempts.update { it + 1 } else registerNow()

            SupplierReturnAction.ConfirmCurrency -> overlay.update { it.copy(notice = null) }

            SupplierReturnAction.Close -> overlay.update {
                if (uiState.value.hasInput) {
                    it.copy(discardAsked = true)
                } else {
                    it.copy(closed = true)
                }
            }

            SupplierReturnAction.ConfirmLeave -> overlay.update {
                it.copy(discardAsked = false, closed = true)
            }

            SupplierReturnAction.DismissDialog -> overlay.update { it.copy(discardAsked = false) }

            SupplierReturnAction.RegisteredShown -> overlay.update { it.copy(closed = true) }
        }
    }

    /** Si la pantalla terminó y la navegación vuelve. */
    val closed: StateFlow<Boolean> = overlay.map { it.closed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun closeSheet() = overlay.update {
        it.copy(sheet = null, query = "", purchaseOptions = emptyList())
    }

    private fun openPurchases() {
        val supplierId = draft.value.supplierId
        viewModelScope.launch {
            val rows = try {
                purchasesOf(supplierId)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Sin poder leerlas, la hoja sale vacía y la devolución sigue sin compra.
                emptyList()
            }
            overlay.update {
                it.copy(
                    sheet = SheetKind.PURCHASES,
                    purchaseOptions = rows.filterNot { row -> row.voided }.map { row ->
                        ReturnPurchaseOption(row.id, row.businessDate, row.supplierName, row.total)
                    },
                )
            }
        }
    }

    private fun changeDraft(change: (SupplierReturnDraft) -> SupplierReturnDraft) {
        val next = draft.value.let(change)
        draft.value = next
        SupplierReturnDraftStore.write(savedState, next)
    }

    private fun registerNow() {
        val state = uiState.value
        if (state.registering || state.registered != null) return
        val current = draft.value
        val load = form.value as? FormLoad.Ready ?: return
        changeDraft { it.copy(showMissing = true) }
        val request = requestOf(current, load.form)
        if (request == null) {
            overlay.update { it.copy(notice = SupplierReturnNotice.NothingToReturn) }
            return
        }
        val withoutSupplier = request.resolution == PurchaseReturnResolution.SUPPLIER_CREDIT &&
            current.supplierId == null
        if (withoutSupplier || state.noteTooLong || state.lines.any { it.error != null }) return
        overlay.update { it.copy(registering = true, notice = null) }
        viewModelScope.launch {
            val result = register(request)
            overlay.update { it.copy(registering = false).after(result) }
        }
    }

    /**
     * La devolución que se registra con [current], o `null` si no hay ninguna línea con cantidad.
     * Las líneas con un error quedan fuera, pero la pantalla no deja confirmar con ellas.
     */
    private fun requestOf(
        current: SupplierReturnDraft,
        form: SupplierReturnForm,
    ): SupplierReturnRequest? {
        val lines = if (current.purchaseId != null) {
            form.purchase?.lines.orEmpty().mapNotNull { line ->
                quantityOf(current.purchaseQuantities[line.line.id].orEmpty())
                    ?.let { SupplierReturnLineRequest.FromPurchaseLine(line.line.id, it) }
            }
        } else {
            current.loose.mapNotNull { line ->
                quantityOf(line.quantityText)?.let {
                    SupplierReturnLineRequest.Loose(line.productId, it)
                }
            }
        }
        if (lines.isEmpty()) return null
        return SupplierReturnRequest(
            lines = lines,
            resolution = current.resolution,
            purchaseId = current.purchaseId?.takeIf { form.purchase != null },
            supplierId = current.supplierId,
            moneyAccountId = current.accountId,
            note = current.note.trim().ifEmpty { null },
        )
    }

    private fun quantityOf(text: String): QuantityMilli? = (
        NumberInputParser.quantity(
            text,
            quantitySymbols,
            Limit.LINE_QUANTITY_MILLI,
        ) as? NumberInput.Value
        )
        ?.value?.takeIf { it.milli > 0L }

    private fun Overlay.after(result: RegisterSupplierReturnResult): Overlay = when (result) {
        is RegisterSupplierReturnResult.Registered ->
            copy(registered = result.total, registeredId = result.purchaseReturnId)

        RegisterSupplierReturnResult.CurrencyNotConfirmed ->
            copy(notice = SupplierReturnNotice.CurrencyUnconfirmed)

        is RegisterSupplierReturnResult.AccountUnavailable ->
            copy(notice = SupplierReturnNotice.AccountUnavailable)

        is RegisterSupplierReturnResult.StorageFailed ->
            copy(
                notice = SupplierReturnNotice.RegisterFailed(
                    result.failure.isOutOfSpace,
                ),
            )

        // Lo demás lo evita la pantalla antes de confirmar, o cambió mientras se escribía, como el
        // stock o lo ya devuelto: la pantalla ya muestra lo de ahora, y se reintenta con eso.
        RegisterSupplierReturnResult.EmptyReturn,
        RegisterSupplierReturnResult.TooManyLines,
        RegisterSupplierReturnResult.NoteTooLong,
        is RegisterSupplierReturnResult.LineRejected,
        is RegisterSupplierReturnResult.AboveRemaining,
        RegisterSupplierReturnResult.TotalTooLarge,
        RegisterSupplierReturnResult.PurchaseNotFound,
        RegisterSupplierReturnResult.PurchaseVoided,
        RegisterSupplierReturnResult.SupplierMismatch,
        RegisterSupplierReturnResult.SupplierNotFound,
        RegisterSupplierReturnResult.SupplierRequiredForCredit,
        is RegisterSupplierReturnResult.UnknownTimeZone,
        RegisterSupplierReturnResult.AccountOverflow,
        RegisterSupplierReturnResult.SupplierBalanceTooLarge,
        -> copy(notice = SupplierReturnNotice.RegisterFailed(outOfSpace = false))
    }

    private fun build(
        load: FormLoad,
        current: SupplierReturnDraft,
        shown: Overlay,
    ): SupplierReturnUiState {
        val base = SupplierReturnUiState(
            withPurchase = current.purchaseId != null,
            resolution = current.resolution,
            accounts = shown.accounts,
            accountId = current.accountId?.takeIf { id -> shown.accounts.any { it.id == id } },
            note = current.note,
            noteTooLong = current.note.trim().length > MAX_NOTE_LENGTH,
            discardAsked = shown.discardAsked,
            notice = shown.notice,
            registering = shown.registering,
            registered = shown.registered,
            registeredId = shown.registeredId,
        )
        val form = when (load) {
            FormLoad.Loading -> return base.copy(loading = true)
            FormLoad.Failed -> return base.copy(loading = false, loadFailed = true)
            is FormLoad.Ready -> load.form
        }
        val purchase = form.purchase
        val subjects = subjectsOf(current, form)
        val lines = linesOf(subjects, current.showMissing)
        val total = if (lines.any { it.error != null }) {
            null
        } else {
            lines.mapNotNull {
                it.lineTotal
            }.takeIf { it.isNotEmpty() }?.fold(Money.ZERO, Money::plus)
        }
        val supplier = form.supplier
        val credit = current.resolution == PurchaseReturnResolution.SUPPLIER_CREDIT
        return base.copy(
            loading = false,
            withPurchase = purchase != null,
            supplierName = supplier?.details?.name,
            supplierLocked = purchase?.purchase?.content?.supplierId != null,
            origin = purchase?.let {
                ReturnOriginUi(it.purchase.id, it.purchase.content.businessDate)
            },
            lines = lines,
            balanceAfter = supplier?.takeIf { credit }?.let { it.balance - (total ?: Money.ZERO) },
            creditNeedsSupplier = credit && supplier == null && current.showMissing,
            total = total,
            sheet = sheetOf(shown, form),
        )
    }

    /** Lo que muestra cada línea antes de valorarla: su producto, lo escrito y su línea de compra. */
    private fun subjectsOf(
        current: SupplierReturnDraft,
        form: SupplierReturnForm,
    ): List<LineSubject> {
        val purchase = form.purchase
        if (purchase != null) {
            return purchase.lines.map { line ->
                LineSubject(
                    key = line.line.id,
                    product = line.product,
                    text = current.purchaseQuantities[line.line.id].orEmpty(),
                    pricing = { quantity ->
                        SupplierReturnPricingLine(line.product.id, line.line, quantity)
                    },
                    taken = line.line.id to line.taken,
                    removable = false,
                    form = form,
                )
            }
        }
        val products = form.products.associateBy { it.id }
        return current.loose.mapNotNull { line ->
            products[line.productId]?.let { product ->
                LineSubject(
                    key = line.key,
                    product = product,
                    text = line.quantityText,
                    pricing = { quantity ->
                        SupplierReturnPricingLine(product.id, null, quantity)
                    },
                    taken = null,
                    removable = true,
                    form = form,
                )
            }
        }
    }

    /**
     * Cada línea con su cantidad leída y, si es válida, valorada con el mismo cálculo del caso de
     * uso. Una línea vacía no se valora: con compra, es lo que no se devuelve.
     */
    private fun linesOf(
        subjects: List<LineSubject>,
        showMissing: Boolean,
    ): List<SupplierReturnLineUi> {
        val read = subjects.map { subject -> subject to readQuantity(subject, showMissing) }
        val valid = read.mapNotNull { (subject, quantity) ->
            (quantity as? QuantityRead.Valid)?.let { subject to it.quantity }
        }
        val taken = subjects.mapNotNull { it.taken }.toMap()
        val pricing = SupplierReturnRules.price(
            valid.map { (subject, quantity) -> subject.pricing(quantity) },
            stateOf = { id ->
                subjects.first { it.product.id == id }.product.let {
                    CostState(it.onHand, it.averageCost, it.stockValue)
                }
            },
            takenOf = { id -> taken[id] ?: LineAmounts.NONE },
        )
        val priced = valid.map { it.first.key }.zip(pricing.lines).toMap()
        return read.map { (subject, quantity) ->
            val base = SupplierReturnLineUi(
                key = subject.key,
                name = subject.product.details.name,
                unit = subject.form.units[subject.product.details.baseUnitId],
                quantityText = subject.text,
                removable = subject.removable,
            )
            when (quantity) {
                QuantityRead.Empty -> base

                is QuantityRead.Invalid -> base.copy(error = quantity.error)

                is QuantityRead.Valid -> when (val line = priced.getValue(subject.key)) {
                    is SupplierReturnLinePricing.Priced -> base.copy(
                        unitCost = line.amounts.unitCost,
                        lineTotal = line.amounts.lineTotal,
                        costBefore = line.before.averageCost,
                        costAfter = line.after.averageCost,
                        leftAfter = line.after.onHand.takeIf { it < QuantityMilli.ZERO },
                    )

                    is SupplierReturnLinePricing.AboveRemaining ->
                        base.copy(error = SupplierReturnLineError.AboveRemaining(line.remaining))

                    SupplierReturnLinePricing.TooLarge -> base.copy(
                        error = SupplierReturnLineError.TooHigh,
                    )

                    // Solo se valoran cantidades positivas: no llega aquí.
                    SupplierReturnLinePricing.InvalidQuantity ->
                        base.copy(error = SupplierReturnLineError.NotANumber)
                }
            }
        }
    }

    private fun readQuantity(subject: LineSubject, showMissing: Boolean): QuantityRead {
        val read = NumberInputParser.quantity(
            subject.text,
            quantitySymbols,
            Limit.LINE_QUANTITY_MILLI,
        )
        val allowsFraction =
            subject.form.units[subject.product.details.baseUnitId]?.allowsFraction ?: false
        return when (read) {
            NumberInput.Blank ->
                // Sin compra, una línea agregada pide su cantidad; con compra, vacía es no devolver.
                if (showMissing && subject.removable) {
                    QuantityRead.Invalid(SupplierReturnLineError.NotANumber)
                } else {
                    QuantityRead.Empty
                }

            is NumberInput.Value -> when (UnitQuantityRule.check(read.value, allowsFraction)) {
                UnitQuantityCheck.Valid -> QuantityRead.Valid(read.value)

                UnitQuantityCheck.NotPositive -> QuantityRead.Empty

                UnitQuantityCheck.FractionNotAllowed ->
                    QuantityRead.Invalid(SupplierReturnLineError.FractionNotAllowed)
            }

            NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                QuantityRead.Invalid(SupplierReturnLineError.NotANumber)

            is NumberInput.TooLarge -> QuantityRead.Invalid(SupplierReturnLineError.TooHigh)
        }
    }

    private fun sheetOf(shown: Overlay, form: SupplierReturnForm): SupplierReturnSheet? {
        val query = TextNormalizer.normalize(shown.query)
        return when (shown.sheet) {
            null -> null

            SheetKind.SUPPLIERS -> SupplierReturnSheet.Suppliers(
                shown.query,
                form.suppliers.filter { query.isEmpty() || query in it.details.normalizedName }
                    .map { ReturnSupplierOption(it.id, it.details.name) },
            )

            SheetKind.PRODUCTS -> SupplierReturnSheet.Products(
                shown.query,
                form.products.filter { query.isEmpty() || query in it.details.normalizedName }
                    .map { ReturnProductOption(it.id, it.details.name) },
            )

            SheetKind.PURCHASES -> SupplierReturnSheet.Purchases(shown.purchaseOptions)
        }
    }

    /**
     * Una línea antes de valorarla. [taken] es lo que ya se llevaron de su línea de compra las
     * devoluciones activas, si la tiene.
     */
    private class LineSubject(
        val key: Long,
        val product: Product,
        val text: String,
        val pricing: (QuantityMilli) -> SupplierReturnPricingLine,
        val taken: Pair<Long, LineAmounts>?,
        val removable: Boolean,
        val form: SupplierReturnForm,
    )

    private sealed interface QuantityRead {
        data object Empty : QuantityRead

        data class Valid(val quantity: QuantityMilli) : QuantityRead

        data class Invalid(val error: SupplierReturnLineError) : QuantityRead
    }

    private enum class SheetKind { SUPPLIERS, PRODUCTS, PURCHASES }

    /** Lo que se superpone al formulario y no se guarda: hojas, diálogos y avisos. */
    private data class Overlay(
        val sheet: SheetKind? = null,
        val query: String = "",
        val purchaseOptions: List<ReturnPurchaseOption> = emptyList(),
        val discardAsked: Boolean = false,
        val notice: SupplierReturnNotice? = null,
        val registering: Boolean = false,
        val registered: Money? = null,
        val registeredId: Long? = null,
        val closed: Boolean = false,
        val accounts: List<MoneyAccount> = emptyList(),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Largo máximo de la nota de una devolución. */
        const val MAX_NOTE_LENGTH = 200
    }
}
