package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogCodeLookup
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartChange
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartLineIssue
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPreview
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.HeldSaleRules
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrection
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeRequest
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumeHeldSaleResult
import io.github.yesidrobayo.shopledger.navigation.SaleExchange
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import io.github.yesidrobayo.shopledger.ui.readOrNull
import io.github.yesidrobayo.shopledger.ui.readOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** En qué va la lectura del catálogo de la venta. */
internal sealed interface CatalogLoad {
    data object Loading : CatalogLoad

    data class Ready(val catalog: SaleCatalog) : CatalogLoad

    data object Failed : CatalogLoad
}

/**
 * La venta nueva: arma la venta en memoria y la confirma de una sola vez.
 *
 * - **Nada se escribe hasta confirmar.** La venta a medio armar vive en el estado guardado de la
 *   pantalla: sobrevive a una llamada, a cambiar de app y a que Android cierre el proceso.
 * - **Vender nunca se bloquea por stock**: la tarjeta solo avisa cuánto quedaría.
 * - **Un toque agrega uno.** Un producto por peso o medida abre la hoja de cantidad, y uno sin precio
 *   abre su línea con el precio enfocado: dejarlo en cero está permitido.
 * - **`Registrar venta` confirma directo** con el método elegido, o con el primero que se ofrece si
 *   no se tocó ninguno. Solo una venta en cero pide confirmación.
 * - Lo escrito en la búsqueda se ve al instante, y la cuadrícula se rearma cuando la persona deja de
 *   escribir un momento. Las cuentas corren en [computation], nunca en el hilo principal.
 *
 * @param catalogSource la lectura del catálogo; se vuelve a pedir al reintentar.
 * @param registerSale confirma la venta, en una sola transacción.
 * @param reactivate reactiva un producto archivado; `true` si quedó vigente.
 * @param prefillProductId el producto con que se abrió la venta, como desde `Vender este`.
 * @param heldSales las ventas en espera, para contar las que hay además de la que se edita.
 * @param holdSale pone la venta en espera, sin contar como cambio sin respaldar.
 * @param resumeHeldSale lee una venta en espera para seguirla aquí. La espera sigue guardada hasta
 *   que esta venta se confirme, en la misma transacción, o hasta que se descarte.
 * @param prefillFromSaleId la venta anulada que esta corrige: la venta nueva abre con sus datos.
 * @param loadCorrection lee lo que trae la corrección de una venta, o `null` si ya no existe.
 * @param prefillCustomerId el cliente con que se abrió la venta, que el cobro trae elegido.
 * @param customerPriceList la lista de precios propia de un cliente, o `null` si no tiene: la venta
 *   abierta desde un cliente toma su lista.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NewSaleViewModel(
    private val catalogSource: () -> Flow<SaleCatalog>,
    private val registerSale: suspend (SaleRequest) -> RegisterSaleResult,
    private val reactivate: suspend (Long) -> Boolean,
    private val savedState: SavedStateHandle,
    private val numbers: SaleNumberFormat,
    computation: CoroutineDispatcher,
    private val prefillProductId: Long? = null,
    private val heldSales: () -> Flow<List<HeldSale>> = { flowOf(emptyList()) },
    // Sin espera conectada, como en una vista previa, poner en espera no guarda nada.
    private val holdSale: suspend (
        HoldSaleRequest,
    ) -> HoldSaleResult = { HoldSaleResult.EmptySale },
    private val resumeHeldSale: suspend (Long) -> ResumeHeldSaleResult = {
        ResumeHeldSaleResult.NotFound
    },
    private val prefillFromSaleId: Long? = null,
    private val loadCorrection: suspend (Long) -> SaleCorrection? = { null },
    prefillCustomerId: Long? = null,
    private val customerPriceList: suspend (Long) -> Long? = { null },
    private val exchange: SaleExchange? = null,
    // Sin cambio conectado, como en una vista previa, registrar un cambio no guarda nada.
    private val exchangeSale: suspend (ExchangeRequest) -> ExchangeResult = {
        ExchangeResult.SaleRejected(RegisterSaleResult.EmptySale)
    },
    private val exchangeNote: () -> String? = { null },
) : ViewModel() {
    private val draft = MutableStateFlow(SaleDraftStore.read(savedState))

    init {
        // El cliente se pone una sola vez: si Android recrea la pantalla, la marca sigue guardada,
        // y si la persona lo quita en el cobro, no vuelve.
        if (prefillCustomerId != null && savedState.get<Boolean>(CUSTOMER_PREFILLED) != true) {
            savedState[CUSTOMER_PREFILLED] = true
            draft.value = draft.value.copy(customerId = prefillCustomerId)
            SaleDraftStore.write(savedState, draft.value)
            viewModelScope.launch {
                // La lista del cliente, si tiene: la persona la puede cambiar en la venta.
                val listId = customerPriceList(prefillCustomerId) ?: return@launch
                changeDraft { if (it.priceListId == null) it.copy(priceListId = listId) else it }
            }
        }
    }
    private val query = MutableStateFlow("")
    private val overlay = MutableStateFlow(Overlay())
    private val attempts = MutableStateFlow(0)
    private var correctionLoading = false

    private val catalog: StateFlow<CatalogLoad> = attempts
        .flatMapLatest {
            catalogSource()
                .map<SaleCatalog, CatalogLoad> { CatalogLoad.Ready(it) }
                // Una falla de lectura no cierra la venta: muestra el error y deja reintentar.
                .catch { emit(CatalogLoad.Failed) }
        }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogLoad.Loading)

    // Una falla al leer la espera no frena la venta: solo deja de contar las que hay.
    private val held: Flow<List<HeldSale>> = heldSales().catch { emit(emptyList()) }

    val uiState: StateFlow<NewSaleUiState> = combine(
        catalog,
        draft,
        query,
        query.debounce { if (it.isBlank()) 0L else SEARCH_PAUSE_MILLIS },
        overlay,
    ) { load, current, typed, searched, shown -> build(load, current, typed, searched, shown) }
        .combine(held) { state, list ->
            // La venta que se edita no cuenta: ya está a la vista.
            state.copy(heldCount = list.count { it.id != state.heldSaleId })
        }
        .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            NewSaleUiState(),
        )

    init {
        viewModelScope.launch {
            catalog.collect { load -> if (load is CatalogLoad.Ready) onCatalog(load.catalog) }
        }
    }

    fun onAction(action: NewSaleAction) {
        when (action) {
            is NewSaleAction.QueryChange -> query.value = action.query

            is NewSaleAction.AddProduct -> addProduct(action.productId)

            is NewSaleAction.PickVariantValue -> pickVariantValue(action.axis, action.value)

            is NewSaleAction.AddScanned -> addScanned(action.productId, action.unitId)

            // Lo resuelve la ruta, que abre el escáner.
            NewSaleAction.Scan -> Unit

            NewSaleAction.SubmitSearch -> submitSearch()

            is NewSaleAction.Increment -> add(action.productId, SaleCart.STEP)

            is NewSaleAction.Decrement -> editLines { SaleCart.decrement(it, action.productId) }

            is NewSaleAction.EditQuantity -> openQuantity(action.productId, adding = false)

            is NewSaleAction.SelectMethod -> selectMethod(action.methodId)

            NewSaleAction.OpenPaymentMethods -> showSheet(SaleSheet.PaymentMethods)

            NewSaleAction.OpenCart -> showSheet(SaleSheet.Cart)

            is NewSaleAction.OpenLine -> openLine(action.productId, focusPrice = false)

            NewSaleAction.CloseSheet -> showSheet(null)

            is NewSaleAction.LineQuantityChange -> lineQuantity(action.text)

            is NewSaleAction.LinePriceChange -> linePrice(action.text)

            is NewSaleAction.LineDiscountModeChange -> lineDiscount(action.mode, null)

            is NewSaleAction.LineDiscountChange -> lineDiscount(null, action.text)

            is NewSaleAction.LineUnitChange -> lineUnit(action.unitId)

            is NewSaleAction.RemoveLine -> removeLine(action.productId)

            is NewSaleAction.QuantityTextChange -> quantityText(action.text)

            NewSaleAction.QuantityConfirm -> confirmQuantity(null)

            is NewSaleAction.QuantityShortcut -> confirmQuantity(action.quantity)

            is NewSaleAction.DateChange -> changeDraft { draftDate(it, action) }

            is NewSaleAction.NoteChange -> changeDraft { it.copy(note = action.note) }

            is NewSaleAction.SelectPriceList ->
                changeDraft { it.copy(priceListId = action.listId) }

            NewSaleAction.ClearSale -> askClear()

            NewSaleAction.ConfirmClear -> clear()

            NewSaleAction.Close -> close()

            NewSaleAction.ConfirmLeave -> leave()

            NewSaleAction.DismissDialog -> overlay.update { it.copy(dialog = null) }

            NewSaleAction.Register -> register(zeroConfirmed = false)

            NewSaleAction.ConfirmZeroTotal -> register(zeroConfirmed = true)

            is NewSaleAction.CreateProduct -> awaitCreatedProduct()

            is NewSaleAction.Reactivate -> reactivateProduct(action.productId)

            NewSaleAction.ConfirmCurrency -> showSheet(null)

            NewSaleAction.RetryRead -> attempts.update { it + 1 }

            NewSaleAction.RegisteredShown -> overlay.update { it.copy(registered = null) }

            NewSaleAction.NoticeShown -> overlay.update { it.copy(notice = null) }

            // El cobro es otra pantalla: la ruta deja la venta con checkoutDraft y lo abre.
            NewSaleAction.OpenCheckout, NewSaleAction.ShowQr -> Unit

            NewSaleAction.OpenHold -> openHold()

            is NewSaleAction.HoldLabelChange -> holdLabel(action.text)

            NewSaleAction.ConfirmHold -> confirmHold()

            is NewSaleAction.ResumeHeld -> resumeHeld(action.heldSaleId)

            NewSaleAction.HeldNoticeShown -> overlay.update { it.copy(heldNotice = null) }

            // Abrir la lista de ventas en espera es navegación: la resuelve la ruta.
            NewSaleAction.OpenHeldSales -> Unit
        }
    }

    /**
     * La venta como está, con el método que se cobraría, para abrir el cobro; o `null` si todavía no
     * se puede cobrar: sin líneas, o con algo que corregir. El cobro trabaja sobre esta copia, así
     * que la venta queda intacta si la persona vuelve atrás.
     */
    fun checkoutDraft(): SaleDraft? {
        val catalog = readyCatalog() ?: return null
        val current = draft.value
        if (!previewOf(catalog, current).canRegister || current.note.length > MAX_NOTE_LENGTH) {
            return null
        }
        return current.copy(methodId = selectedMethodId(catalog, current))
    }

    // ------------------------------------------------------------------ armar la venta

    private fun addProduct(productId: Long) {
        val catalog = readyCatalog() ?: return
        val product = catalog.products.firstOrNull { it.id == productId } ?: return
        // El principal de unas variantes no se vende: se elige una de ellas.
        if (VariantChoices.variantsOf(catalog, productId).isNotEmpty()) {
            showSheet(VariantChoices.sheet(catalog, product, emptyList()))
            return
        }
        if (allowsFraction(catalog, product)) {
            openQuantity(productId, adding = true)
            return
        }
        val first = SaleCart.quantityOf(draft.value.lines, productId) == QuantityMilli.ZERO
        val list = catalog.salePriceList(draft.value.priceListId)
        val listed = if (list ==
            null
        ) {
            product.salePrice
        } else {
            list.priceOf(product).price ?: Money.ZERO
        }
        if (add(productId, SaleCart.STEP) && first && listed == Money.ZERO) {
            // Sin precio, la línea se abre con el precio enfocado; dejarlo en cero también vale.
            openLine(productId, focusPrice = true)
        }
    }

    /**
     * Elige [value] en el eje [axis] del selector abierto. Con todos los ejes elegidos, cierra la
     * hoja y agrega la variante como si se hubiera tocado su tarjeta.
     */
    private fun pickVariantValue(axis: Int, value: String) {
        val catalog = readyCatalog() ?: return
        val sheet = overlay.value.sheet as? SaleSheet.VariantPick ?: return
        val parent = catalog.products.firstOrNull { it.id == sheet.parentId } ?: return
        val chosen = sheet.chosen.take(axis) + value
        val variant = VariantChoices.match(catalog, parent.id, chosen, sheet.axes.size)
        if (variant == null) {
            showSheet(VariantChoices.sheet(catalog, parent, chosen))
            return
        }
        overlay.update { it.copy(sheet = null) }
        addProduct(variant.id)
    }

    /**
     * Agrega el producto cuyo código quedó en la búsqueda, como lo deja un lector externo al
     * terminar con Enter, y vacía la búsqueda para el siguiente. Un texto que no es un código no
     * hace nada: la búsqueda sigue mostrando lo que encontró.
     */
    private fun submitSearch() {
        val catalog = readyCatalog() ?: return
        val hit =
            CatalogCodeLookup.find(catalog.products, catalog.conversions, query.value) ?: return
        query.value = ""
        addScanned(hit.productId, hit.unitId)
    }

    /**
     * Agrega un producto que trajo el escáner. Una línea nueva toma la unidad del código leído, si
     * era el de una presentación; una que ya estaba suma uno en la unidad que tiene.
     */
    private fun addScanned(productId: Long, unitId: Long?) {
        val catalog = readyCatalog() ?: return
        val product = catalog.products.firstOrNull { it.id == productId } ?: return
        val isNew = SaleCart.quantityOf(draft.value.lines, productId) == QuantityMilli.ZERO
        if (unitId == null || unitId == product.details.baseUnitId || !isNew) {
            addProduct(productId)
            return
        }
        if (add(productId, SaleCart.STEP)) setLine(productId) { it.copy(unitId = unitId) }
    }

    /** Agrega [quantity] de [productId]. Devuelve si la venta cambió. */
    private fun add(productId: Long, quantity: QuantityMilli): Boolean =
        when (val change = SaleCart.add(draft.value.lines, productId, quantity)) {
            is CartChange.Changed -> {
                editLines { change.lines }
                true
            }

            CartChange.LinesFull -> {
                overlay.update { it.copy(notice = SaleNotice.LINES_FULL) }
                false
            }

            // En el tope de una línea, el más ya está apagado: no hay nada que avisar.
            CartChange.InvalidQuantity -> false
        }

    private fun selectMethod(methodId: Long) {
        changeDraft { it.copy(methodId = methodId) }
        overlay.update { if (it.sheet == SaleSheet.PaymentMethods) it.copy(sheet = null) else it }
    }

    private fun removeLine(productId: Long) {
        editLines { SaleCart.remove(it, productId) }
        showSheet(null)
    }

    private fun askClear() {
        val count = currentPreview()?.itemCount ?: return
        overlay.update { it.copy(dialog = SaleDialog.Clear(count)) }
    }

    private fun clear() {
        // Vaciar una venta retomada la suelta de su espera, que queda como se guardó.
        changeDraft { SaleDraft(methodId = it.methodId, prefilled = it.prefilled) }
        overlay.update { it.copy(dialog = null, sheet = null, status = RegisterStatus.IDLE) }
    }

    private fun close() {
        // Salir de un cambio siempre pregunta: la devolución elegida tampoco se registra.
        if (exchange != null) {
            overlay.update { it.copy(dialog = SaleDialog.LeaveExchange) }
            return
        }
        if (draft.value.lines.isEmpty()) {
            leave()
            return
        }
        val count = currentPreview()?.itemCount ?: draft.value.lines.size.toLong()
        overlay.update { it.copy(dialog = SaleDialog.Leave(count)) }
    }

    private fun leave() {
        changeDraft { SaleDraft() }
        overlay.update { Overlay(closed = true) }
    }

    private fun draftDate(current: SaleDraft, action: NewSaleAction.DateChange): SaleDraft {
        val today = readyCatalog()?.today
        return current.copy(date = action.date.takeIf { it != today })
    }

    // ------------------------------------------------------------------ hoja de la línea

    private fun openLine(productId: Long, focusPrice: Boolean) {
        val line = draft.value.lines.firstOrNull { it.productId == productId } ?: return
        val product = readyCatalog()?.products?.firstOrNull { it.id == productId }
        // El precio de la unidad de la línea, como lo calculan las cuentas de la venta.
        val price = line.unitPrice
            ?: currentPreview()?.lines?.firstOrNull { it.request.productId == productId }?.unitPrice
            ?: product?.salePrice
            ?: Money.ZERO
        val (mode, discountText) = when (val discount = line.discount) {
            LineDiscount.None -> DiscountMode.AMOUNT to ""
            is LineDiscount.Amount -> DiscountMode.AMOUNT to moneyText(discount.amount)
            is LineDiscount.Percent -> DiscountMode.PERCENT to percentText(discount.rate)
        }
        val editor = LineEditor(
            productId = productId,
            name = product?.details?.name.orEmpty(),
            unit = product?.let { lineUnitOf(requireNotNull(readyCatalog()), it) },
            quantityText = quantityText(line.quantity),
            // Sin precio, el campo empieza vacío para escribir el de esta venta.
            priceText = if (price == Money.ZERO) "" else moneyText(price),
            discountMode = mode,
            discountText = discountText,
            focusPrice = focusPrice,
            unitId = line.unitId ?: product?.details?.baseUnitId,
            saleUnits = product?.let { saleUnitsOf(requireNotNull(readyCatalog()), it) }.orEmpty(),
        )
        showSheet(SaleSheet.Line(editor))
    }

    /**
     * Cambia la unidad en que se vende la línea. El precio escrito era por la unidad anterior, así
     * que la línea vuelve al precio de la nueva; una cantidad con decimales en una unidad que se
     * cuenta entera pasa a una.
     */
    private fun lineUnit(unitId: Long) {
        val catalog = readyCatalog() ?: return
        val sheet = overlay.value.sheet as? SaleSheet.Line ?: return
        val product = catalog.products.firstOrNull { it.id == sheet.editor.productId } ?: return
        val whole = catalog.units[unitId]?.allowsFraction == false
        setLine(product.id) { line ->
            val fractional = line.quantity.milli % SaleCart.STEP.milli != 0L
            line.copy(
                unitId = unitId.takeIf { it != product.details.baseUnitId },
                unitPrice = null,
                quantity = if (whole && fractional) SaleCart.STEP else line.quantity,
            )
        }
        openLine(product.id, focusPrice = false)
    }

    private fun lineQuantity(text: String) = editLine { editor ->
        val updated = editor.copy(quantityText = text, quantityError = null)
        val product = readyCatalog()?.products?.firstOrNull { it.id == editor.productId }
        val fraction = product?.let { allowsFraction(requireNotNull(readyCatalog()), it) } ?: false
        when (val read = NumberInputParser.quantity(text, numbers.quantitySymbols, LINE_LIMIT)) {
            // Mientras el campo está vacío, la línea conserva su cantidad.
            NumberInput.Blank -> updated

            is NumberInput.Value -> when (UnitQuantityRule.check(read.value, fraction)) {
                UnitQuantityCheck.NotPositive ->
                    updated.copy(quantityError = SaleFieldError.ZERO_QUANTITY)

                UnitQuantityCheck.FractionNotAllowed ->
                    updated.copy(quantityError = SaleFieldError.FRACTION_NOT_ALLOWED)

                UnitQuantityCheck.Valid -> {
                    editLines { lines ->
                        val change = SaleCart.setQuantity(lines, editor.productId, read.value)
                        (change as? CartChange.Changed)?.lines ?: lines
                    }
                    updated
                }
            }

            else -> updated.copy(quantityError = numberError(read))
        }
    }

    private fun linePrice(text: String) = editLine { editor ->
        val updated = editor.copy(priceText = text, priceError = null)
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        )
        when (read) {
            // Sin precio escrito, la línea vuelve al precio del producto.
            NumberInput.Blank -> {
                setLine(editor.productId) { it.copy(unitPrice = null) }
                updated
            }

            is NumberInput.Value -> if (read.value < Money.ZERO) {
                updated.copy(priceError = SaleFieldError.NOT_A_NUMBER)
            } else {
                setLine(editor.productId) { it.copy(unitPrice = read.value) }
                updated
            }

            else -> updated.copy(priceError = numberError(read))
        }
    }

    /** Cambia el modo del descuento, lo escrito, o los dos, y lo vuelve a leer. */
    private fun lineDiscount(mode: DiscountMode?, text: String?) = editLine { editor ->
        val updated = editor.copy(
            discountMode = mode ?: editor.discountMode,
            discountText = text ?: editor.discountText,
            discountError = null,
        )
        val read: NumberInput<LineDiscount> = when (updated.discountMode) {
            DiscountMode.AMOUNT -> NumberInputParser.money(
                updated.discountText,
                numbers.moneySymbols,
                numbers.currencyDecimals,
                Limit.DOCUMENT_TOTAL,
            ).mapValue { LineDiscount.Amount(it) }

            DiscountMode.PERCENT -> NumberInputParser.percent(
                updated.discountText,
                numbers.percentSymbols,
                BasisPoints.ONE_HUNDRED_PERCENT,
            ).mapValue { LineDiscount.Percent(it) }
        }
        when (read) {
            NumberInput.Blank -> {
                setLine(editor.productId) { it.copy(discount = LineDiscount.None) }
                updated
            }

            is NumberInput.Value -> {
                // Un descuento mayor que la línea se guarda igual y se marca: la venta no se
                // confirma hasta corregirlo, y lo escrito no se pierde.
                setLine(editor.productId) { it.copy(discount = read.value) }
                updated
            }

            // Un porcentaje sobre 100 % es un descuento mayor que la línea.
            is NumberInput.TooLarge -> updated.copy(
                discountError = if (updated.discountMode == DiscountMode.PERCENT) {
                    SaleFieldError.DISCOUNT_OVER_LINE
                } else {
                    SaleFieldError.TOO_HIGH
                },
            )

            else -> updated.copy(discountError = numberError(read))
        }
    }

    // ------------------------------------------------------------------ hoja de cantidad

    private fun openQuantity(productId: Long, adding: Boolean) {
        val catalog = readyCatalog() ?: return
        val product = catalog.products.firstOrNull { it.id == productId } ?: return
        val current = SaleCart.quantityOf(draft.value.lines, productId)
        val adds = adding || current == QuantityMilli.ZERO
        showSheet(
            SaleSheet.Quantity(
                productId = productId,
                name = product.details.name,
                unit = lineUnitOf(catalog, product),
                text = if (adds) "" else quantityText(current),
                adding = adds,
            ),
        )
    }

    private fun quantityText(text: String) = overlay.update { shown ->
        val sheet = shown.sheet as? SaleSheet.Quantity ?: return@update shown
        shown.copy(sheet = sheet.copy(text = text, error = null))
    }

    /** Lee la hoja de cantidad, o usa [shortcut], y agrega o cambia la línea. */
    private fun confirmQuantity(shortcut: QuantityMilli?) {
        val sheet = overlay.value.sheet as? SaleSheet.Quantity ?: return
        val quantity = shortcut ?: when (
            val read = NumberInputParser.quantity(sheet.text, numbers.quantitySymbols, LINE_LIMIT)
        ) {
            is NumberInput.Value -> read.value
            NumberInput.Blank -> QuantityMilli.ZERO
            else -> return quantityError(sheet, numberError(read))
        }
        if (UnitQuantityRule.check(quantity, true) != UnitQuantityCheck.Valid) {
            return quantityError(sheet, SaleFieldError.ZERO_QUANTITY)
        }
        val change = if (sheet.adding) {
            SaleCart.add(draft.value.lines, sheet.productId, quantity)
        } else {
            SaleCart.setQuantity(draft.value.lines, sheet.productId, quantity)
        }
        when (change) {
            is CartChange.Changed -> {
                editLines { change.lines }
                showSheet(null)
            }

            CartChange.LinesFull -> overlay.update {
                it.copy(sheet = null, notice = SaleNotice.LINES_FULL)
            }

            CartChange.InvalidQuantity -> quantityError(sheet, SaleFieldError.TOO_HIGH)
        }
    }

    private fun quantityError(sheet: SaleSheet.Quantity, error: SaleFieldError) =
        overlay.update { it.copy(sheet = sheet.copy(error = error)) }

    // ------------------------------------------------------------------ confirmar

    private fun register(zeroConfirmed: Boolean) {
        if (overlay.value.status == RegisterStatus.WORKING) return
        val catalog = readyCatalog() ?: return
        val current = draft.value
        val preview = previewOf(catalog, current)
        if (current.lines.isEmpty()) return
        val total = preview.total
        if (total == null || current.note.length > MAX_NOTE_LENGTH) {
            // Lo que impide confirmar ya está marcado; una línea con problema se abre para verlo.
            preview.lines.firstOrNull { it.issue != null }?.let { line ->
                if (line.issue != CartLineIssue.PRODUCT_UNAVAILABLE) {
                    openLine(line.request.productId, focusPrice = false)
                }
            }
            return
        }
        if (exchange != null) {
            registerExchange(exchange, catalog, current, total)
            return
        }
        if (total == Money.ZERO && !zeroConfirmed) {
            overlay.update { it.copy(dialog = SaleDialog.ZeroTotal) }
            return
        }
        // Lo que se cobra, con el redondeo del efectivo: es lo que dice el mensaje al registrar.
        val charged = preview.amountDue ?: total
        val methodId = selectedMethodId(catalog, current)
        if (methodId == null) {
            overlay.update { it.copy(dialog = null, status = RegisterStatus.FAILED) }
            return
        }
        overlay.update { it.copy(dialog = null, status = RegisterStatus.WORKING) }
        viewModelScope.launch {
            val request = SaleRequest(
                lines = current.lines,
                paymentMethodId = methodId,
                date = current.date,
                note = current.note.takeIf { it.isNotBlank() },
                heldSaleId = current.heldSaleId,
                // La misma lista con que se calcularon los precios que se ven.
                priceListId = catalog.salePriceList(current.priceListId)?.listId,
            )
            when (val result = registerSale(request)) {
                is RegisterSaleResult.Registered -> {
                    changeDraft { SaleDraft(prefilled = true) }
                    overlay.update {
                        Overlay(
                            registered = RegisteredSale(
                                result.saleId,
                                charged,
                                moneyAccountSkipped = result.moneyAccountSkipped,
                            ),
                        )
                    }
                }

                RegisterSaleResult.CurrencyNotConfirmed -> overlay.update {
                    it.copy(status = RegisterStatus.IDLE, sheet = SaleSheet.CurrencyRequired)
                }

                // Cada rechazo se nombra, sin rama por defecto, para que un resultado nuevo del
                // caso de uso obligue a decidir aquí cómo se muestra. Hoy todos dejan la venta
                // armada y avisan que no se pudo registrar.
                RegisterSaleResult.EmptySale,
                RegisterSaleResult.TooManyLines,
                RegisterSaleResult.NoteTooLong,
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
                is RegisterSaleResult.PaymentsDoNotMatch,
                RegisterSaleResult.OrderDiscountTooLarge,
                RegisterSaleResult.TipNotAccepted,
                // La venta nueva no lleva cliente ni canjea puntos: no llegan aquí.
                RegisterSaleResult.PointsNotAvailable,
                is RegisterSaleResult.NotEnoughPoints,
                RegisterSaleResult.PointsAboveSale,
                RegisterSaleResult.PriceListUnavailable,
                -> overlay.update { it.copy(status = RegisterStatus.FAILED) }
            }
        }
    }

    /**
     * Registra el cambio: la venta nueva y la devolución que la paga, juntas o ninguna.
     *
     * El crédito paga primero con el método con que se cobró la venta de origen, cuyo dinero entra en
     * la misma cuenta de la que sale el reembolso: así en la cuenta queda solo la diferencia. Lo que
     * falta se cobra con el método elegido; si sobra crédito, la cuenta devuelve la diferencia. Sin
     * ese método a la mano, el crédito paga con el elegido. En un cambio el total no lleva recargo de
     * método ni redondeo del efectivo: es el que muestra la banda.
     */
    private fun registerExchange(
        exchange: SaleExchange,
        catalog: SaleCatalog,
        current: SaleDraft,
        total: Money,
    ) {
        val chosenId = selectedMethodId(catalog, current)
        if (chosenId == null) {
            overlay.update { it.copy(dialog = null, status = RegisterStatus.FAILED) }
            return
        }
        val creditMethod = exchange.creditMethodId
            ?.let { id -> catalog.methods.firstOrNull { it.id == id } }
            ?: catalog.methods.first { it.id == chosenId }
        val creditPart = minOf(Money(exchange.credit), total)
        val rest = total - creditPart
        val payments = if (creditMethod.id == chosenId || rest == Money.ZERO) {
            listOf(SalePaymentRequest(creditMethod.id, total))
        } else {
            listOf(
                SalePaymentRequest(creditMethod.id, creditPart),
                SalePaymentRequest(chosenId, rest),
            )
        }
        overlay.update { it.copy(dialog = null, status = RegisterStatus.WORKING) }
        viewModelScope.launch {
            val request = ExchangeRequest(
                saleId = exchange.saleId,
                lines = exchange.lines.map {
                    ReturnLineRequest(it.saleLineId, QuantityMilli(it.quantityMilli), it.restock)
                },
                replacement = SaleRequest(
                    lines = current.lines,
                    paymentMethodId = chosenId,
                    note = current.note.takeIf { it.isNotBlank() },
                    payments = payments,
                    priceListId = catalog.salePriceList(current.priceListId)?.listId,
                ),
                refundAccountId = creditMethod.details.moneyAccountId,
                note = exchangeNote(),
            )
            when (val result = exchangeSale(request)) {
                is ExchangeResult.Exchanged -> {
                    changeDraft { SaleDraft(prefilled = true) }
                    overlay.update {
                        Overlay(
                            registered = RegisteredSale(
                                result.replacementSaleId,
                                total,
                                exchanged = true,
                            ),
                        )
                    }
                }

                is ExchangeResult.SaleRejected -> overlay.update {
                    if (result.rejection == RegisterSaleResult.CurrencyNotConfirmed) {
                        it.copy(status = RegisterStatus.IDLE, sheet = SaleSheet.CurrencyRequired)
                    } else {
                        it.copy(status = RegisterStatus.FAILED)
                    }
                }

                // La devolución ya no cabe, la cuenta no sirve o la base falló: nada se guardó, y la
                // venta sigue armada para reintentar.
                is ExchangeResult.ReturnRejected, is ExchangeResult.StorageFailed ->
                    overlay.update { it.copy(status = RegisterStatus.FAILED) }
            }
        }
    }

    /** La banda del cambio: el crédito y lo que queda con [total], el de la venta nueva. */
    private fun bandOf(exchange: SaleExchange, total: Money?, hasLines: Boolean): ExchangeBand {
        val credit = Money(exchange.credit)
        val difference = total?.takeIf { hasLines }?.let {
            when {
                it > credit -> ExchangeDifference.ToCollect(it - credit)
                it < credit -> ExchangeDifference.ToRefund(credit - it)
                else -> ExchangeDifference.None
            }
        }
        return ExchangeBand(exchange.saleId, credit, difference)
    }

    // ------------------------------------------------------------------ ventas en espera

    private fun openHold() {
        val current = draft.value
        if (current.lines.isEmpty()) return
        val label = current.heldLabel.orEmpty()
        showSheet(SaleSheet.Hold(label = label, tooLong = !fits(label)))
    }

    private fun holdLabel(text: String) = overlay.update { shown ->
        val sheet = shown.sheet as? SaleSheet.Hold ?: return@update shown
        shown.copy(sheet = sheet.copy(label = text, tooLong = !fits(text)))
    }

    /** Pone la venta en espera y deja la pantalla lista para atender a otra persona. */
    private fun confirmHold() {
        val sheet = overlay.value.sheet as? SaleSheet.Hold ?: return
        if (sheet.tooLong || sheet.working) return
        overlay.update { it.copy(sheet = sheet.copy(working = true)) }
        viewModelScope.launch {
            if (holdCurrent(sheet.label)) {
                changeDraft { SaleDraft(methodId = it.methodId, prefilled = true) }
                overlay.update {
                    it.copy(
                        sheet = null,
                        status = RegisterStatus.IDLE,
                        heldNotice = HeldNotice.Held,
                    )
                }
            } else {
                // La venta sigue aquí, intacta, con la hoja abierta para intentarlo de nuevo.
                overlay.update {
                    it.copy(sheet = sheet.copy(working = false), heldNotice = HeldNotice.HoldFailed)
                }
            }
        }
    }

    /** Guarda en espera la venta en edición, encima de la suya si venía de la espera. */
    private suspend fun holdCurrent(label: String?): Boolean {
        val current = draft.value
        val request =
            HoldSaleRequest(current.lines, label, current.heldSaleId, current.priceListId)
        return when (holdSale(request)) {
            is HoldSaleResult.Held -> true

            HoldSaleResult.EmptySale,
            HoldSaleResult.TooManyLines,
            HoldSaleResult.LabelTooLong,
            is HoldSaleResult.LineRejected,
            is HoldSaleResult.StorageFailed,
            -> false
        }
    }

    /**
     * Retoma [heldSaleId]. Si hay otra venta en edición, la persona ya confirmó cambiarla: primero
     * pasa a espera, y solo si quedó guardada se retoma la elegida. Así nunca se pierde una.
     */
    private fun resumeHeld(heldSaleId: Long) {
        if (draft.value.heldSaleId == heldSaleId) return
        viewModelScope.launch {
            val current = draft.value
            if (current.lines.isNotEmpty()) {
                if (!holdCurrent(current.heldLabel)) {
                    overlay.update { it.copy(heldNotice = HeldNotice.HoldFailed) }
                    return@launch
                }
                changeDraft { SaleDraft(methodId = it.methodId, prefilled = true) }
            }
            // Una falla de lectura se dice como una venta que no se pudo abrir: la actual ya quedó
            // en espera, y la pantalla no se cierra.
            val result = readOrNull { resumeHeldSale(heldSaleId) } ?: ResumeHeldSaleResult.NotFound
            when (result) {
                is ResumeHeldSaleResult.Resumed -> {
                    val sale = result.sale
                    changeDraft {
                        SaleDraft(
                            lines = sale.lines,
                            methodId = it.methodId,
                            prefilled = true,
                            heldSaleId = sale.heldSaleId,
                            heldLabel = sale.label,
                            priceListId = sale.priceListId,
                        )
                    }
                    val notice = HeldNotice.Resumed(sale.changedPrices, sale.removedProducts)
                    val worth = notice.changedPrices + notice.removed > 0
                    overlay.update { Overlay(heldNotice = notice.takeIf { worth }) }
                }

                ResumeHeldSaleResult.NotFound ->
                    overlay.update { it.copy(heldNotice = HeldNotice.ResumeFailed) }
            }
        }
    }

    private fun fits(label: String): Boolean = HeldSaleRules.fits(HeldSaleRules.labelOf(label))

    // ------------------------------------------------------------------ catálogo

    private fun onCatalog(catalog: SaleCatalog) {
        val current = draft.value
        if (prefillFromSaleId != null && !current.prefilled) prefillCorrection(prefillFromSaleId)
        if (prefillProductId != null && !current.prefilled) {
            changeDraft { it.copy(prefilled = true) }
            addProduct(prefillProductId)
        }
        val awaiting = current.awaitingProductAfter ?: return
        val created = catalog.products.filter { it.id > awaiting }.minByOrNull { it.id } ?: return
        changeDraft { it.copy(awaitingProductAfter = null) }
        query.value = ""
        addProduct(created.id)
    }

    /**
     * Abre la venta con los datos de la que corrige: sus líneas con precio y descuento, el método,
     * la fecha y la nota. Se hace una sola vez, también si Android recrea la pantalla, porque la
     * marca queda en el estado guardado junto con lo traído. Si alguna línea quedó por fuera
     * porque su producto está archivado, lo dice.
     */
    private fun prefillCorrection(saleId: Long) {
        if (correctionLoading) return
        correctionLoading = true
        viewModelScope.launch {
            val read = readOutcome { loadCorrection(saleId) }
            val correction = (read as? ReadOutcome.Read)?.value
            if (read == ReadOutcome.Failed) {
                overlay.update { it.copy(notice = SaleNotice.CORRECTION_FAILED) }
            }
            val today = readyCatalog()?.today
            changeDraft { current ->
                if (correction == null) {
                    current.copy(prefilled = true)
                } else {
                    current.copy(
                        lines = correction.lines,
                        methodId = correction.paymentMethodId,
                        date = correction.date.takeIf { it != today },
                        note = correction.note.orEmpty(),
                        prefilled = true,
                    )
                }
            }
            val leftOut = correction?.leftOut ?: 0
            if (leftOut > 0) {
                overlay.update { it.copy(notice = SaleNotice.LINES_LEFT_OUT, leftOut = leftOut) }
            }
        }
    }

    /**
     * La persona sale a crear un producto que no encontró. El primero que aparezca después se
     * agrega a la venta al volver: los ids nunca se reutilizan, así que es el que creó.
     */
    private fun awaitCreatedProduct() {
        val catalog = readyCatalog() ?: return
        val newest = (catalog.products + catalog.archived).maxOfOrNull { it.id } ?: 0L
        changeDraft { it.copy(awaitingProductAfter = newest) }
    }

    private fun reactivateProduct(productId: Long) {
        viewModelScope.launch {
            if (readOrNull { reactivate(productId) } != true) {
                overlay.update { it.copy(notice = SaleNotice.REACTIVATE_FAILED) }
            }
        }
    }

    // ------------------------------------------------------------------ estado

    private fun build(
        load: CatalogLoad,
        current: SaleDraft,
        typed: String,
        searched: String,
        shown: Overlay,
    ): NewSaleUiState {
        val content: SaleContent
        val cart: SaleCartState
        val payment: SalePaymentState
        when (load) {
            CatalogLoad.Loading -> {
                content = SaleContent.Loading
                cart = SaleCartState(note = current.note)
                payment = SalePaymentState()
            }

            CatalogLoad.Failed -> {
                content = SaleContent.ReadError
                cart = SaleCartState(note = current.note)
                payment = SalePaymentState()
            }

            is CatalogLoad.Ready -> {
                val catalog = load.catalog
                content = SaleContentBuilder.content(
                    catalog,
                    current.lines,
                    searched,
                    catalog.salePriceList(current.priceListId),
                )
                cart = SaleContentBuilder.cart(catalog, current, previewOf(catalog, current))
                    .copy(noteTooLong = current.note.length > MAX_NOTE_LENGTH)
                payment = SalePaymentState(
                    methods = catalog.methods.map {
                        SaleMethod(
                            id = it.id,
                            systemKey = it.systemKey,
                            name = it.details.name,
                            hasQr = it.details.instructionsText != null ||
                                it.details.instructionsMediaId != null,
                        )
                    },
                    selectedId = selectedMethodId(catalog, current),
                )
            }
        }
        return NewSaleUiState(
            content = content,
            query = typed,
            cart = cart,
            payment = payment,
            sheet = shown.sheet?.let { withLineIssue(it, cart) },
            dialog = shown.dialog,
            status = shown.status,
            notice = shown.notice,
            leftOut = shown.leftOut,
            registered = shown.registered,
            closed = shown.closed,
            heldSaleId = current.heldSaleId,
            heldNotice = shown.heldNotice,
            exchange = exchange?.let { bandOf(it, cart.total, !cart.isEmpty) },
            canScan = (load as? CatalogLoad.Ready)?.catalog?.hasCodes() == true,
        )
    }

    /** Si algún producto o presentación tiene código: sin ninguno, el escáner no se ofrece. */
    private fun SaleCatalog.hasCodes(): Boolean = products.any { it.details.barcode != null } ||
        conversions.values.any { units -> units.values.any { it.barcode != null } }

    /** Un descuento mayor que la línea se marca en su campo, aunque se haya leído bien. */
    private fun withLineIssue(sheet: SaleSheet, cart: SaleCartState): SaleSheet {
        if (sheet !is SaleSheet.Line) return sheet
        val editor = sheet.editor
        val issue = cart.lines.firstOrNull { it.productId == editor.productId }?.issue
        if (issue != CartLineIssue.INVALID_DISCOUNT || editor.discountError != null) return sheet
        return SaleSheet.Line(editor.copy(discountError = SaleFieldError.DISCOUNT_OVER_LINE))
    }

    private fun selectedMethodId(catalog: SaleCatalog, current: SaleDraft): Long? {
        val chosen = current.methodId?.takeIf { id -> catalog.methods.any { it.id == id } }
        // Sin elección, o si el elegido se archivó, se cobra con el primero que se ofrece.
        return chosen ?: catalog.methods.firstOrNull()?.id
    }

    /**
     * Las cuentas de la venta con el impuesto del negocio y el recargo del método elegido: el total
     * que se ve es el que se guarda al confirmar.
     */
    private fun previewOf(catalog: SaleCatalog, current: SaleDraft): CartPreview {
        // Un cambio paga con dos métodos a la vez: su total va sin recargo ni redondeo de ninguno.
        val methodId = selectedMethodId(catalog, current).takeIf { exchange == null }
        val method = catalog.methods.firstOrNull { it.id == methodId }
        val pricing = CartPricing(
            taxMode = catalog.taxes.mode,
            rates = catalog.taxRates,
            defaultRateId = catalog.taxes.defaultTaxRateId,
            methodAdjustment = method?.details?.priceAdjustment ?: BasisPoints.ZERO,
            cashMethod = method?.details?.kind == PaymentMethodKind.CASH,
            cashStep = catalog.cashRounding,
            priceList = catalog.salePriceList(current.priceListId),
        )
        return SaleCart.preview(
            current.lines,
            catalog.products.associateBy { it.id },
            SaleContentBuilder.fractionUnits(catalog),
            pricing,
            catalog.conversions,
        )
    }

    private fun currentPreview(): CartPreview? = readyCatalog()?.let { previewOf(it, draft.value) }

    private fun readyCatalog(): SaleCatalog? = (catalog.value as? CatalogLoad.Ready)?.catalog

    private fun allowsFraction(catalog: SaleCatalog, product: Product): Boolean =
        lineUnitOf(catalog, product)?.allowsFraction ?: false

    /** La unidad en que va [product] en la venta: la de su línea, o su unidad base. */
    private fun lineUnitOf(catalog: SaleCatalog, product: Product): MeasureUnit? {
        val line = draft.value.lines.firstOrNull { it.productId == product.id }
        return catalog.units[line?.unitId ?: product.details.baseUnitId]
    }

    /** Las unidades en que se vende [product]: la base primero, y luego sus conversiones de venta. */
    private fun saleUnitsOf(catalog: SaleCatalog, product: Product): List<MeasureUnit> {
        val others = catalog.conversions[product.id].orEmpty().keys
        return (listOf(product.details.baseUnitId) + others).mapNotNull { catalog.units[it] }
    }

    private fun showSheet(sheet: SaleSheet?) = overlay.update {
        it.copy(sheet = sheet, status = if (sheet == null) it.status else RegisterStatus.IDLE)
    }

    private fun editLine(change: (LineEditor) -> LineEditor) = overlay.update { shown ->
        val sheet = shown.sheet as? SaleSheet.Line ?: return@update shown
        shown.copy(sheet = SaleSheet.Line(change(sheet.editor)))
    }

    private fun setLine(productId: Long, change: (SaleLineRequest) -> SaleLineRequest) =
        editLines { SaleCart.update(it, productId, change) }

    private fun editLines(change: (List<SaleLineRequest>) -> List<SaleLineRequest>) {
        changeDraft { it.copy(lines = change(it.lines)) }
        // Cambiar la venta después de una falla la deja lista para volver a intentar.
        overlay.update {
            if (it.status == RegisterStatus.FAILED) it.copy(status = RegisterStatus.IDLE) else it
        }
    }

    private fun changeDraft(change: (SaleDraft) -> SaleDraft) {
        draft.update(change)
        SaleDraftStore.write(savedState, draft.value)
    }

    private fun quantityText(quantity: QuantityMilli): String =
        plainNumberText(quantity.milli, QUANTITY_DECIMALS, numbers.quantitySymbols)

    private fun moneyText(amount: Money): String =
        plainNumberText(amount.minor, numbers.currencyDecimals, numbers.moneySymbols)

    private fun percentText(rate: BasisPoints): String =
        plainNumberText(rate.bp, PERCENT_DECIMALS, numbers.percentSymbols)

    private fun numberError(read: NumberInput<*>): SaleFieldError = when (read) {
        is NumberInput.TooLarge -> SaleFieldError.TOO_HIGH

        // Un valor leído o un campo vacío no llegan aquí: quien llama ya los resolvió.
        NumberInput.Malformed,
        is NumberInput.TooManyDecimals,
        is NumberInput.Value,
        NumberInput.Blank,
        -> SaleFieldError.NOT_A_NUMBER
    }

    /** Lo que está abierto encima de la venta, y lo que pasa una sola vez. */
    private data class Overlay(
        val sheet: SaleSheet? = null,
        val dialog: SaleDialog? = null,
        val status: RegisterStatus = RegisterStatus.IDLE,
        val notice: SaleNotice? = null,
        val leftOut: Int = 0,
        val registered: RegisteredSale? = null,
        val closed: Boolean = false,
        val heldNotice: HeldNotice? = null,
    )

    companion object {
        /** La marca de que el cliente con que se abrió la venta ya se puso. */
        private const val CUSTOMER_PREFILLED = "sale.customerPrefilled"

        /** La pausa al escribir tras la que se busca. */
        const val SEARCH_PAUSE_MILLIS = 100L

        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer el catálogo. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Largo máximo de la nota de una venta. */
        const val MAX_NOTE_LENGTH = 200

        private const val QUANTITY_DECIMALS = 3
        private const val PERCENT_DECIMALS = 2
        private val LINE_LIMIT = Limit.LINE_QUANTITY_MILLI
    }
}

/** El valor leído, transformado; los demás resultados quedan igual. */
private inline fun <T, R> NumberInput<T>.mapValue(wrap: (T) -> R): NumberInput<R> = when (this) {
    is NumberInput.Value -> NumberInput.Value(wrap(value))
    NumberInput.Blank -> NumberInput.Blank
    NumberInput.Malformed -> NumberInput.Malformed
    is NumberInput.TooManyDecimals -> this
    is NumberInput.TooLarge -> NumberInput.TooLarge(wrap(maximum))
}
