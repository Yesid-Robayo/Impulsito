package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCatalog
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PricedPurchaseLine
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseCorrection
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchasePricing
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchasePricingResult
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseSubject
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseUnit
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import io.github.yesidrobayo.shopledger.ui.readOrNull
import io.github.yesidrobayo.shopledger.ui.readOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** En qué va la lectura del catálogo de la compra. */
internal sealed interface PurchaseCatalogLoad {
    data object Loading : PurchaseCatalogLoad

    data class Ready(val catalog: PurchaseCatalog) : PurchaseCatalogLoad

    data object Failed : PurchaseCatalogLoad
}

/**
 * La compra nueva: arma la compra en memoria y la confirma de una sola vez.
 *
 * - **Nada se escribe hasta confirmar.** La compra a medio escribir vive en el estado guardado de
 *   la pantalla: sobrevive a una llamada, a cambiar de app y a que Android cierre el proceso.
 * - **Lo que se ve es lo que se guarda.** El total y el costo que deja cada línea salen del mismo
 *   cálculo que usa el caso de uso al registrar, con el catálogo que se lee al momento.
 * - **Costo unitario o total, a elección**, línea por línea: el otro valor se muestra deducido.
 * - **Un costo muy distinto avisa sin bloquear**: más del triple o menos de un tercio del costo
 *   anterior suele ser un error de digitación, y es la causa más probable de anular una compra.
 * - **Un error nunca borra lo escrito.** Un campo vacío solo se marca después de intentar registrar.
 *
 * @param catalogSource la lectura del catálogo; se vuelve a pedir al reintentar.
 * @param registerPurchase confirma la compra, en una sola transacción.
 * @param reactivate reactiva un producto archivado; `true` si quedó vigente.
 * @param prefillProductId el producto con que se abrió la compra, como desde su detalle.
 * @param prefillSupplierId el proveedor con que se abrió la compra, como desde su detalle.
 * @param prefillFromPurchaseId la compra anulada que esta corrige: la nueva abre con sus datos.
 * @param loadCorrection lee lo que trae la corrección de una compra, o `null` si ya no existe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewPurchaseViewModel(
    private val catalogSource: () -> Flow<PurchaseCatalog>,
    private val registerPurchase: suspend (PurchaseRequest) -> RegisterPurchaseResult,
    private val reactivate: suspend (Long) -> Boolean,
    private val savedState: SavedStateHandle,
    private val numbers: PurchaseNumberFormat,
    computation: CoroutineDispatcher,
    private val prefillProductId: Long? = null,
    private val prefillSupplierId: Long? = null,
    private val prefillFromPurchaseId: Long? = null,
    private val loadCorrection: suspend (Long) -> PurchaseCorrection? = { null },
    payingAccounts: Flow<List<MoneyAccount>> = flowOf(emptyList()),
) : ViewModel() {
    private val draft = MutableStateFlow(PurchaseDraftStore.read(savedState))
    private val overlay = MutableStateFlow(Overlay())
    private val attempts = MutableStateFlow(0)

    private val catalog: StateFlow<PurchaseCatalogLoad> = attempts
        .flatMapLatest {
            catalogSource()
                .map<PurchaseCatalog, PurchaseCatalogLoad> { PurchaseCatalogLoad.Ready(it) }
                // Una falla de lectura no cierra la compra: muestra el error y deja reintentar.
                .catch { emit(PurchaseCatalogLoad.Failed) }
        }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PurchaseCatalogLoad.Loading)

    val uiState: StateFlow<NewPurchaseUiState> = combine(catalog, draft, overlay, ::build)
        .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            NewPurchaseUiState(),
        )

    init {
        viewModelScope.launch {
            // Sin cuentas legibles, la compra no ofrece ninguna: se guarda sin dinero.
            payingAccounts.catch { emit(emptyList()) }.collect { accounts ->
                overlay.update { it.copy(accounts = accounts) }
            }
        }
        viewModelScope.launch {
            catalog.collect { load ->
                if (load is PurchaseCatalogLoad.Ready) onCatalog(load.catalog)
            }
        }
    }

    fun onAction(action: NewPurchaseAction) {
        when (action) {
            NewPurchaseAction.OpenProducts -> overlay.update {
                it.copy(sheet = SheetKind.PRODUCTS, query = "")
            }

            NewPurchaseAction.OpenSuppliers -> overlay.update {
                it.copy(sheet = SheetKind.SUPPLIERS, query = "")
            }

            is NewPurchaseAction.QueryChange -> overlay.update { it.copy(query = action.query) }

            NewPurchaseAction.CloseSheet -> overlay.update { it.copy(sheet = null, query = "") }

            is NewPurchaseAction.AddProduct -> addProduct(action.productId)

            is NewPurchaseAction.CreateProduct -> awaitCreatedProduct()

            is NewPurchaseAction.SelectSupplier -> {
                changeDraft { it.copy(supplierId = action.supplierId, supplierTouched = true) }
                overlay.update { it.copy(sheet = null, query = "", notice = null) }
            }

            is NewPurchaseAction.DateChange -> changeDraft { it.copy(date = action.date) }

            is NewPurchaseAction.ReferenceChange -> changeDraft { it.copy(reference = action.text) }

            is NewPurchaseAction.PaymentModeChange -> changeDraft {
                it.copy(onCredit = action.onCredit)
            }

            is NewPurchaseAction.DueDateChange -> changeDraft { it.copy(dueDate = action.date) }

            is NewPurchaseAction.PaidNowChange -> changeDraft { it.copy(paidNowText = action.text) }

            is NewPurchaseAction.AccountChange ->
                changeDraft { it.copy(accountId = action.accountId) }

            is NewPurchaseAction.QuantityChange -> editLine(action.key) {
                it.copy(quantityText = action.text)
            }

            is NewPurchaseAction.UnitChange -> editLine(action.key) {
                it.copy(unitId = action.unitId)
            }

            is NewPurchaseAction.AmountChange -> editLine(action.key) {
                it.copy(amountText = action.text)
            }

            // Cambiar de forma limpia el monto: un costo unitario no es un total.
            is NewPurchaseAction.AmountModeChange -> editLine(action.key) {
                if (it.byTotal ==
                    action.byTotal
                ) {
                    it
                } else {
                    it.copy(byTotal = action.byTotal, amountText = "")
                }
            }

            is NewPurchaseAction.RemoveLine -> changeDraft { current ->
                current.copy(lines = current.lines.filterNot { it.key == action.key })
            }

            NewPurchaseAction.ConfirmReactivate -> confirmReactivate()

            NewPurchaseAction.Register -> register()

            NewPurchaseAction.ConfirmCurrency, NewPurchaseAction.DismissNotice ->
                overlay.update { it.copy(notice = null) }

            NewPurchaseAction.Retry -> retry()

            NewPurchaseAction.Close -> close()

            NewPurchaseAction.ConfirmLeave -> overlay.update {
                it.copy(dialog = null, closed = true)
            }

            NewPurchaseAction.DismissDialog -> overlay.update { it.copy(dialog = null) }

            NewPurchaseAction.Finish -> overlay.update { it.copy(closed = true) }

            // Revisar un precio lo resuelve la navegación; el resumen sigue ahí al volver.
            is NewPurchaseAction.ReviewPrice -> Unit
        }
    }

    /** El catálogo llegó o cambió: agrega el producto de entrada y el que se acaba de crear. */
    private fun onCatalog(catalog: PurchaseCatalog) {
        val current = draft.value
        if (!current.prefilled && prefillFromPurchaseId != null) {
            changeDraft { it.copy(prefilled = true) }
            prefillCorrection(catalog, prefillFromPurchaseId)
        } else if (!current.prefilled) {
            val supplier = prefillSupplierId?.takeIf { id -> catalog.suppliers.any { it.id == id } }
            changeDraft {
                it.copy(
                    prefilled = true,
                    supplierId = supplier ?: it.supplierId,
                    supplierTouched = it.supplierTouched || supplier != null,
                )
            }
            prefillProductId?.let { id ->
                if (catalog.products.any { it.id == id }) appendLine(catalog, id)
            }
        }
        val awaiting = draft.value.awaitingProductAfter ?: return
        val created = catalog.products.filter { it.id > awaiting }.minByOrNull { it.id } ?: return
        changeDraft { it.copy(awaitingProductAfter = null) }
        appendLine(catalog, created.id)
    }

    /**
     * Abre la compra con los datos de la que corrige: sus líneas, cada una con su cantidad en la
     * unidad en que se compró y su total, el proveedor, la referencia y la fecha. Se hace una sola
     * vez, también si Android recrea la pantalla, porque la marca queda en el estado guardado junto
     * con lo traído. Lo que quedó por fuera, por archivado, se dice.
     */
    private fun prefillCorrection(catalog: PurchaseCatalog, purchaseId: Long) {
        viewModelScope.launch {
            // Si no se pudo leer, la compra abre vacía: la anulada ya quedó anulada.
            val read = readOutcome { loadCorrection(purchaseId) }
            val correction = (read as? ReadOutcome.Read)?.value ?: return@launch
            val products = catalog.products.associateBy { it.id }
            // Un producto que se archivó entre la anulación y ahora tampoco se trae.
            val kept = correction.lines.filter { it.productId in products }
            val leftOut = correction.leftOut + correction.lines.size - kept.size
            changeDraft { current ->
                var key = current.nextKey
                val lines = kept.take(Limit.DOCUMENT_LINES.max.toInt()).map { line ->
                    val base = products.getValue(line.productId).details.baseUnitId
                    PurchaseLineDraft(
                        key = key++,
                        productId = line.productId,
                        quantityText = fixedPointInputText(
                            line.enteredQuantity.milli,
                            QUANTITY_DECIMALS,
                            numbers.quantitySymbols,
                        ),
                        amountText = fixedPointInputText(
                            line.lineTotal.minor,
                            numbers.currencyDecimals,
                            numbers.moneySymbols,
                        ),
                        byTotal = true,
                        unitId = line.unitId.takeIf { it != base },
                    )
                }
                current.copy(
                    lines = lines,
                    supplierId = correction.supplierId,
                    supplierTouched = true,
                    reference = correction.invoiceReference.orEmpty(),
                    date = correction.date.takeIf { it != catalog.today },
                    nextKey = key,
                )
            }
            if (leftOut > 0 || correction.supplierLeftOut) {
                notice(PurchaseNotice.CorrectionLeftOut(leftOut, correction.supplierLeftOut))
            }
        }
    }

    private fun addProduct(productId: Long) {
        val catalog = readyCatalog() ?: return
        val archived = catalog.archived.firstOrNull { it.id == productId }
        if (archived != null) {
            overlay.update {
                it.copy(
                    sheet = null,
                    query = "",
                    dialog = PurchaseDialog.Reactivate(archived.id, archived.details.name),
                )
            }
            return
        }
        overlay.update { it.copy(sheet = null, query = "") }
        appendLine(catalog, productId)
    }

    /**
     * Agrega una línea vacía del producto. Si la persona no eligió proveedor, el habitual del
     * producto queda elegido, salvo que esté archivado.
     */
    private fun appendLine(catalog: PurchaseCatalog, productId: Long) {
        val product = catalog.products.firstOrNull { it.id == productId } ?: return
        changeDraft { current ->
            if (current.lines.size >= Limit.DOCUMENT_LINES.max) return@changeDraft current
            val usual = product.details.defaultSupplierId
                ?.takeIf { id -> catalog.suppliers.any { it.id == id } }
            val line = PurchaseLineDraft(current.nextKey, productId, "", "", byTotal = false)
            current.copy(
                lines = current.lines + line,
                nextKey = current.nextKey + 1,
                supplierId = if (!current.supplierTouched &&
                    usual != null
                ) {
                    usual
                } else {
                    current.supplierId
                },
            )
        }
    }

    /** Al volver de crear un producto, el primero nuevo entra a la compra. */
    private fun awaitCreatedProduct() {
        val catalog = readyCatalog() ?: return
        val newest = (catalog.products + catalog.archived).maxOfOrNull { it.id } ?: 0L
        changeDraft { it.copy(awaitingProductAfter = newest) }
        overlay.update { it.copy(sheet = null, query = "") }
    }

    private fun confirmReactivate() {
        val dialog = overlay.value.dialog as? PurchaseDialog.Reactivate ?: return
        overlay.update { it.copy(dialog = null) }
        viewModelScope.launch {
            if (readOrNull { reactivate(dialog.productId) } == true) {
                // Se agrega cuando el catálogo lo traiga ya vigente.
                val catalog = catalog.first { load ->
                    load is PurchaseCatalogLoad.Ready &&
                        load.catalog.products.any { it.id == dialog.productId }
                }
                appendLine((catalog as PurchaseCatalogLoad.Ready).catalog, dialog.productId)
            }
        }
    }

    private fun close() {
        if (draft.value.lines.isEmpty()) {
            overlay.update { it.copy(closed = true) }
        } else {
            overlay.update { it.copy(dialog = PurchaseDialog.Discard) }
        }
    }

    private fun retry() {
        // Sin catálogo no hay qué registrar: reintentar siempre vuelve a leerlo primero.
        if (catalog.value is PurchaseCatalogLoad.Failed) {
            attempts.update { it + 1 }
            return
        }
        when (overlay.value.notice) {
            is PurchaseNotice.RegisterFailed -> register()

            // Sin una falla al registrar, reintentar es volver a leer el catálogo.
            null,
            PurchaseNotice.CurrencyUnconfirmed,
            PurchaseNotice.TotalTooLarge,
            PurchaseNotice.TooManyLines,
            PurchaseNotice.SupplierUnavailable,
            PurchaseNotice.AccountUnavailable,
            is PurchaseNotice.CorrectionLeftOut,
            -> attempts.update { it + 1 }
        }
    }

    private fun register() {
        val catalog = readyCatalog() ?: return
        if (overlay.value.registering) return
        val current = draft.value
        val requests = current.lines.map { lineRequest(catalog, it) }
        val reference = current.reference.trim()
        val complete = requests.all { it != null } && current.lines.isNotEmpty()
        val payment = paymentOf(catalog, current, uiState.value.total, overlay.value.accounts)
        if (!complete || reference.length > MAX_REFERENCE_LENGTH || payment.errors.isNotEmpty()) {
            overlay.update { it.copy(showMissing = true) }
            return
        }
        val request = PurchaseRequest(
            lines = requests.filterNotNull(),
            supplierId = current.supplierId,
            invoiceReference = reference.ifEmpty { null },
            date = current.date,
            // Pagada completa no lleva monto ni vencimiento: el caso de uso la toma como pagada.
            amountPaid = if (current.onCredit) paidNow(current) ?: Money.ZERO else null,
            dueDate = if (current.onCredit) payment.dueDate else null,
            // Una cuenta que dejó de ofrecerse mientras tanto no se usa.
            moneyAccountId = payment.accountId,
        )
        overlay.update { it.copy(registering = true, notice = null) }
        viewModelScope.launch {
            val result = registerPurchase(request)
            overlay.update { it.copy(registering = false) }
            onRegistered(catalog, result)
        }
    }

    private fun onRegistered(catalog: PurchaseCatalog, result: RegisterPurchaseResult) {
        when (result) {
            is RegisterPurchaseResult.Registered -> {
                val names = catalog.products.associate { it.id to it.details.name }
                val total = uiState.value.total ?: Money.ZERO
                val summary = PurchaseSummary(
                    purchaseId = result.purchaseId,
                    total = total,
                    costs = result.costChanges.map {
                        CostChangeUi(
                            it.productId,
                            names[it.productId].orEmpty(),
                            it.averageCostBefore,
                            it.averageCostAfter,
                            reviewPrice = it.marginBelowTarget,
                        )
                    },
                )
                // Lo escrito ya está guardado: el estado guardado se limpia.
                changeDraft { PurchaseDraft(prefilled = true) }
                overlay.update { it.copy(registered = summary, showMissing = false) }
            }

            is RegisterPurchaseResult.LineRejected ->
                overlay.update { it.copy(rejected = result.index to result.reason) }

            RegisterPurchaseResult.CurrencyNotConfirmed -> notice(
                PurchaseNotice.CurrencyUnconfirmed,
            )

            RegisterPurchaseResult.PurchaseTotalTooLarge -> notice(PurchaseNotice.TotalTooLarge)

            RegisterPurchaseResult.TooManyLines, RegisterPurchaseResult.EmptyPurchase ->
                notice(PurchaseNotice.TooManyLines)

            RegisterPurchaseResult.SupplierArchived, RegisterPurchaseResult.SupplierNotFound ->
                notice(PurchaseNotice.SupplierUnavailable)

            // La pantalla ya revisó la referencia, la fecha y la zona; si cambian entre medio, el
            // reintento las vuelve a leer.
            RegisterPurchaseResult.InvoiceReferenceTooLong,
            RegisterPurchaseResult.FutureDate,
            is RegisterPurchaseResult.UnknownTimeZone,
            -> notice(PurchaseNotice.RegisterFailed(outOfSpace = false))

            // La pantalla ya revisa lo pagado, el proveedor y el vencimiento antes de registrar; si
            // cambian entre medio, el aviso deja reintentar con lo escrito.
            RegisterPurchaseResult.AmountPaidOutOfRange,
            RegisterPurchaseResult.SupplierRequiredForCredit,
            RegisterPurchaseResult.DueDateBeforePurchase,
            RegisterPurchaseResult.SupplierBalanceTooLarge,
            -> notice(PurchaseNotice.RegisterFailed(outOfSpace = false))

            is RegisterPurchaseResult.AccountUnavailable -> notice(
                PurchaseNotice.AccountUnavailable,
            )

            // Un saldo que no cabe en la cuenta no se corrige escribiendo: se reintenta.
            RegisterPurchaseResult.AccountOverflow ->
                notice(PurchaseNotice.RegisterFailed(outOfSpace = false))

            is RegisterPurchaseResult.StorageFailed -> notice(
                PurchaseNotice.RegisterFailed(result.failure.isOutOfSpace),
            )
        }
    }

    private fun notice(notice: PurchaseNotice) = overlay.update { it.copy(notice = notice) }

    private fun build(
        load: PurchaseCatalogLoad,
        current: PurchaseDraft,
        shown: Overlay,
    ): NewPurchaseUiState {
        val catalog = when (load) {
            PurchaseCatalogLoad.Loading -> return NewPurchaseUiState(loading = true)

            PurchaseCatalogLoad.Failed ->
                return NewPurchaseUiState(loading = false, loadFailed = true)

            is PurchaseCatalogLoad.Ready -> load.catalog
        }
        val lines = buildLines(catalog, current, shown)
        val supplier = current.supplierId?.let { id ->
            catalog.suppliers.firstOrNull { it.id == id }
        }
        return NewPurchaseUiState(
            loading = false,
            supplierName = supplier?.details?.name,
            date = current.date,
            today = catalog.today,
            weekStartDay = catalog.weekStartDay,
            reference = current.reference,
            referenceTooLong = current.reference.trim().length > MAX_REFERENCE_LENGTH,
            lines = lines.lines,
            total = lines.total,
            sheet = sheetOf(catalog, shown),
            dialog = shown.dialog,
            notice = shown.notice ?: lines.notice,
            registering = shown.registering,
            registered = shown.registered,
            closed = shown.closed,
            payment = paymentOf(catalog, current, lines.total, shown.accounts),
        )
    }

    /**
     * Cómo se paga la compra, con lo que impide registrarla a plazo. El vencimiento es el elegido o
     * el que propone el plazo habitual del proveedor desde la fecha de la compra, igual que el caso
     * de uso: lo que se ve es lo que se guarda.
     */
    private fun paymentOf(
        catalog: PurchaseCatalog,
        current: PurchaseDraft,
        total: Money?,
        accounts: List<MoneyAccount>,
    ): PurchasePaymentUi {
        val purchaseDate = current.date ?: catalog.today
        val accountId = current.accountId?.takeIf { id -> accounts.any { it.id == id } }
        if (!current.onCredit) {
            return PurchasePaymentUi(
                purchaseDate = purchaseDate,
                accounts = accounts,
                accountId = accountId,
            )
        }
        val supplier = current.supplierId?.let { id ->
            catalog.suppliers.firstOrNull { it.id == id }
        }
        val terms = supplier?.details?.paymentTermsDays
        val proposed = purchaseDate?.let { SupplierLedgerRules.proposedDueDate(it, terms) }
        val due = current.dueDate ?: proposed
        val paid = paidNowInput(current)
        val errors = buildSet {
            if (supplier == null) add(PurchasePaymentError.SUPPLIER_REQUIRED)
            if (due != null && purchaseDate != null && due < purchaseDate) {
                add(PurchasePaymentError.DUE_BEFORE_PURCHASE)
            }
            when (paid) {
                is NumberInput.Value -> if (total != null && paid.value > total) {
                    add(PurchasePaymentError.PAID_OVER_TOTAL)
                }

                NumberInput.Blank -> Unit

                NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                    add(PurchasePaymentError.PAID_NOT_A_NUMBER)

                is NumberInput.TooLarge -> add(PurchasePaymentError.PAID_OVER_TOTAL)
            }
        }
        return PurchasePaymentUi(
            onCredit = true,
            dueDate = due,
            canClearDue = current.dueDate != null && proposed == null,
            purchaseDate = purchaseDate,
            paidNowText = current.paidNowText,
            errors = errors,
            accounts = accounts,
            accountId = accountId,
        )
    }

    private fun paidNowInput(current: PurchaseDraft): NumberInput<Money> = NumberInputParser.money(
        current.paidNowText,
        numbers.moneySymbols,
        numbers.currencyDecimals,
        Limit.DOCUMENT_TOTAL,
    )

    /** Lo pagado ahora, o `null` si no se escribió nada o no se puede leer. */
    private fun paidNow(current: PurchaseDraft): Money? =
        (paidNowInput(current) as? NumberInput.Value)?.value

    /**
     * Las líneas con sus cuentas. Las que ya se pueden leer se calculan juntas, en orden, con el
     * mismo cálculo del registro; las demás muestran solo lo escrito y su error.
     */
    private fun buildLines(
        catalog: PurchaseCatalog,
        current: PurchaseDraft,
        shown: Overlay,
    ): BuiltLines {
        val products = (catalog.products + catalog.archived).associateBy { it.id }
        val readable = current.lines.mapNotNull { line ->
            lineRequest(catalog, line)?.let {
                line.key to
                    it
            }
        }
        val subjects = subjectsOf(catalog)
        val pricing = PurchasePricing.price(readable.map { it.second }, subjects, catalog.taxes)
        val priced = HashMap<Long, PricedPurchaseLine>()
        var rejectedKey: Pair<Long, PurchaseLineRejection>? = null
        var notice: PurchaseNotice? = null
        var total: Money? = null
        when (pricing) {
            is PurchasePricingResult.Priced -> {
                pricing.lines.forEachIndexed { index, line -> priced[readable[index].first] = line }
                total = pricing.totals.total.takeIf { readable.isNotEmpty() }
            }

            is PurchasePricingResult.LineRejected ->
                rejectedKey = readable[pricing.index].first to pricing.reason

            PurchasePricingResult.TotalTooLarge -> notice = PurchaseNotice.TotalTooLarge
        }
        // Un rechazo del registro apunta por posición entre todas las líneas.
        shown.rejected?.let { (index, reason) ->
            current.lines.getOrNull(index)?.let { rejectedKey = it.key to reason }
        }
        val ui = current.lines.map { line ->
            val product = products[line.productId]
            val result = priced[line.key]
            val rejection = rejectedKey?.takeIf { it.first == line.key }?.second
            val (quantityError, amountError) = errorsOf(
                catalog,
                line,
                product,
                rejection,
                shown.showMissing,
            )
            PurchaseLineUi(
                key = line.key,
                productId = line.productId,
                name = product?.details?.name.orEmpty(),
                unit = product?.let { catalog.units[unitIdOf(it, line)] },
                units = product?.let { unitsOf(catalog, it) }.orEmpty(),
                quantityText = line.quantityText,
                amountText = line.amountText,
                byTotal = line.byTotal,
                lineTotal = result?.split?.lineTotal,
                derived = result?.let {
                    if (line.byTotal) it.amounts.unitCost else it.split.lineTotal
                },
                costBefore = result?.before?.averageCost,
                costAfter = result?.after?.averageCost,
                costDiffersFrom = result?.let(::costDiffersFrom),
                quantityError = quantityError,
                amountError = amountError,
                archived = product == null || product.isArchived,
            )
        }
        return BuiltLines(ui, total, notice)
    }

    /**
     * El costo anterior si el de la línea es más del triple o menos de un tercio: un aviso, no un
     * bloqueo. Sin costo anterior no hay con qué comparar.
     */
    private fun costDiffersFrom(line: PricedPurchaseLine): Money? {
        val previous = line.before.averageCost.minor
        if (previous <= 0L) return null
        val cost = line.movementCost.minor
        val far = cost > previous * COST_FACTOR || cost * COST_FACTOR < previous
        return line.before.averageCost.takeIf { far }
    }

    private fun errorsOf(
        catalog: PurchaseCatalog,
        line: PurchaseLineDraft,
        product: Product?,
        rejection: PurchaseLineRejection?,
        showMissing: Boolean,
    ): Pair<PurchaseFieldError?, PurchaseFieldError?> {
        val allowsFraction =
            product?.let { catalog.units[unitIdOf(it, line)]?.allowsFraction } ?: false
        val quantity = quantityError(line.quantityText, allowsFraction, showMissing)
        val amount = amountError(line, showMissing)
        val fromRule = when (rejection) {
            null,
            PurchaseLineRejection.PRODUCT_NOT_FOUND,
            PurchaseLineRejection.PRODUCT_ARCHIVED,
            PurchaseLineRejection.NOT_STOCKED,
            PurchaseLineRejection.LOTS_NOT_SUPPORTED,
            -> null to null

            PurchaseLineRejection.INVALID_QUANTITY -> PurchaseFieldError.TOO_HIGH to null

            // La conversión se quitó mientras se escribía: la línea pide elegir otra unidad.
            PurchaseLineRejection.UNIT_UNAVAILABLE -> PurchaseFieldError.UNIT_UNAVAILABLE to null

            PurchaseLineRejection.FRACTION_NOT_ALLOWED ->
                PurchaseFieldError.FRACTION_NOT_ALLOWED to null

            PurchaseLineRejection.NEGATIVE_AMOUNT -> null to PurchaseFieldError.NOT_A_NUMBER

            PurchaseLineRejection.UNIT_COST_TOO_LARGE,
            PurchaseLineRejection.LINE_TOTAL_TOO_LARGE,
            -> null to PurchaseFieldError.TOO_HIGH

            PurchaseLineRejection.STOCK_ABOVE_LIMIT -> PurchaseFieldError.STOCK_OVER_LIMIT to null
        }
        return (quantity ?: fromRule.first) to (amount ?: fromRule.second)
    }

    private fun quantityError(text: String, allowsFraction: Boolean, showMissing: Boolean) = when (
        val read = NumberInputParser.quantity(
            text,
            numbers.quantitySymbols,
            Limit.LINE_QUANTITY_MILLI,
        )
    ) {
        NumberInput.Blank -> PurchaseFieldError.REQUIRED.takeIf { showMissing }

        is NumberInput.Value -> when (UnitQuantityRule.check(read.value, allowsFraction)) {
            UnitQuantityCheck.Valid -> null
            UnitQuantityCheck.NotPositive -> PurchaseFieldError.ZERO_QUANTITY
            UnitQuantityCheck.FractionNotAllowed -> PurchaseFieldError.FRACTION_NOT_ALLOWED
        }

        NumberInput.Malformed, is NumberInput.TooManyDecimals -> PurchaseFieldError.NOT_A_NUMBER

        is NumberInput.TooLarge -> PurchaseFieldError.TOO_HIGH
    }

    private fun amountError(line: PurchaseLineDraft, showMissing: Boolean) =
        when (val read = readAmount(line)) {
            NumberInput.Blank -> PurchaseFieldError.REQUIRED.takeIf { showMissing }

            is NumberInput.Value ->
                PurchaseFieldError.NOT_A_NUMBER.takeIf { read.value < Money.ZERO }

            NumberInput.Malformed, is NumberInput.TooManyDecimals -> PurchaseFieldError.NOT_A_NUMBER

            is NumberInput.TooLarge -> PurchaseFieldError.TOO_HIGH
        }

    private fun readAmount(line: PurchaseLineDraft): NumberInput<Money> = NumberInputParser.money(
        line.amountText,
        numbers.moneySymbols,
        numbers.currencyDecimals,
        if (line.byTotal) Limit.DOCUMENT_TOTAL else Limit.UNIT_MONEY,
    )

    /** La línea lista para calcular, o `null` si algún campo todavía no se puede leer. */
    private fun lineRequest(
        catalog: PurchaseCatalog,
        line: PurchaseLineDraft,
    ): PurchaseLineRequest? {
        val product = (catalog.products + catalog.archived).firstOrNull { it.id == line.productId }
            ?: return null
        val allowsFraction = catalog.units[unitIdOf(product, line)]?.allowsFraction ?: false
        if (quantityError(line.quantityText, allowsFraction, showMissing = true) !=
            null
        ) {
            return null
        }
        if (amountError(line, showMissing = true) != null) return null
        val quantity = NumberInputParser.quantity(
            line.quantityText,
            numbers.quantitySymbols,
            Limit.LINE_QUANTITY_MILLI,
        ) as? NumberInput.Value<QuantityMilli> ?: return null
        val amount = readAmount(line) as? NumberInput.Value<Money> ?: return null
        return PurchaseLineRequest(
            productId = line.productId,
            unitId = line.unitId,
            quantity = quantity.value,
            amount = if (line.byTotal) {
                PurchaseLineAmount.LineTotal(amount.value)
            } else {
                PurchaseLineAmount.UnitCost(amount.value)
            },
        )
    }

    /** Lo que el cálculo necesita de cada producto, con la tasa que paga y su nombre. */
    private fun subjectsOf(catalog: PurchaseCatalog): Map<Long, PurchaseSubject> =
        (catalog.products + catalog.archived).associate { product ->
            val rate = PurchasePricing.taxRateIdOf(product, catalog.taxes)
                ?.let { catalog.taxRates[it] }
            product.id to PurchaseSubject(
                product = product,
                allowsFraction = catalog.units[product.details.baseUnitId]?.allowsFraction ?: false,
                taxRate = rate?.rate ?: BasisPoints.ZERO,
                taxName = rate?.name,
                purchaseUnits = catalog.conversions[product.id].orEmpty()
                    .mapValues { (unitId, details) ->
                        PurchaseUnit(
                            details.factorMilli,
                            catalog.units[unitId]?.allowsFraction ?: false,
                        )
                    },
            )
        }

    /** La unidad en que se escribe la línea: la elegida, o la base del producto. */
    private fun unitIdOf(product: Product, line: PurchaseLineDraft): Long =
        line.unitId ?: product.details.baseUnitId

    /** La base y las conversiones de compra del producto, en ese orden. */
    private fun unitsOf(catalog: PurchaseCatalog, product: Product) =
        (listOf(product.details.baseUnitId) + catalog.conversions[product.id].orEmpty().keys)
            .mapNotNull { catalog.units[it] }

    private fun sheetOf(catalog: PurchaseCatalog, shown: Overlay): PurchaseSheet? =
        when (shown.sheet) {
            null -> null

            SheetKind.PRODUCTS -> {
                val active = ProductSearch.filter(catalog.products, shown.query)
                    .map { ProductOption(it.id, it.details.name, archived = false) }
                // Los archivados solo aparecen al buscar: se ofrecen para reactivarlos.
                val archived = if (shown.query.isBlank()) {
                    emptyList()
                } else {
                    ProductSearch.filter(catalog.archived, shown.query)
                        .map { ProductOption(it.id, it.details.name, archived = true) }
                }
                PurchaseSheet.Products(shown.query, active + archived)
            }

            SheetKind.SUPPLIERS -> {
                val query = TextNormalizer.normalize(shown.query)
                val matching = catalog.suppliers.filter {
                    query.isEmpty() ||
                        query in it.details.normalizedName
                }
                val none = SupplierOption(null, null).takeIf { query.isEmpty() }
                PurchaseSheet.Suppliers(
                    shown.query,
                    listOfNotNull(none) + matching.map { SupplierOption(it.id, it.details.name) },
                )
            }
        }

    private fun readyCatalog(): PurchaseCatalog? =
        (catalog.value as? PurchaseCatalogLoad.Ready)?.catalog

    private fun editLine(key: Long, change: (PurchaseLineDraft) -> PurchaseLineDraft) {
        // Una corrección del registro deja de valer en cuanto se edita la línea.
        overlay.update { it.copy(rejected = null) }
        changeDraft { current ->
            current.copy(lines = current.lines.map { if (it.key == key) change(it) else it })
        }
    }

    private fun changeDraft(change: (PurchaseDraft) -> PurchaseDraft) {
        val updated = draft.updateAndGet(change)
        PurchaseDraftStore.write(savedState, updated)
    }

    private enum class SheetKind { PRODUCTS, SUPPLIERS }

    /** Lo que se superpone al formulario y no se guarda: hojas, diálogos y avisos. */
    private data class Overlay(
        val sheet: SheetKind? = null,
        val query: String = "",
        val dialog: PurchaseDialog? = null,
        val notice: PurchaseNotice? = null,
        val registering: Boolean = false,
        val registered: PurchaseSummary? = null,
        val showMissing: Boolean = false,
        val rejected: Pair<Int, PurchaseLineRejection>? = null,
        val closed: Boolean = false,
        val accounts: List<MoneyAccount> = emptyList(),
    )

    private class BuiltLines(
        val lines: List<PurchaseLineUi>,
        val total: Money?,
        val notice: PurchaseNotice?,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Largo máximo de la referencia del documento del proveedor. */
        const val MAX_REFERENCE_LENGTH = 40

        /** Un costo más del triple o menos de un tercio del anterior avisa. */
        const val COST_FACTOR = 3L
    }
}

/** Las cifras decimales de una cantidad en milésimas. */
private const val QUANTITY_DECIMALS = 3
