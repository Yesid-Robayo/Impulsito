package io.github.yesidrobayo.shopledger.ui.production

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionRules
import io.github.yesidrobayo.shopledger.domain.rule.production.RecipeInputsResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionCatalog
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionInputRejection
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionLot
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionLots
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que escribió la persona, que sobrevive a la muerte del proceso. */
private data class ProductionDraft(
    val productId: Long? = null,
    val quantityText: String = "",
    val noteText: String = "",
    /** Los insumos que la persona cambió para esta tanda, por producto. */
    val overrides: Map<Long, String> = emptyMap(),
    /** El lote elegido de cada insumo con lotes, por producto. */
    val chosenLots: Map<Long, Long> = emptyMap(),
    /** El código y el vencimiento del lote al que entra lo producido. */
    val lotCode: String = "",
    val lotExpiry: BusinessDate? = null,
)

/** Los lotes que el formulario necesita leer: los de lo producido, si los lleva, y los insumos. */
private data class LotsWanted(val outputId: Long?, val inputIds: Set<Long>)

/** Lo que la pantalla muestra encima del borrador. */
private data class ProductionOverlay(
    val pickerOpen: Boolean = false,
    val query: String = "",
    val showMissing: Boolean = false,
    val saving: Boolean = false,
    val notice: ProductionNotice? = null,
    val rejected: Map<Long, ProductionFieldError> = emptyMap(),
    val discardAsked: Boolean = false,
    val registered: Boolean = false,
    val closed: Boolean = false,
)

/** Cómo se leyó una cantidad. */
private sealed interface QuantityRead {
    data class Valid(val quantity: QuantityMilli) : QuantityRead

    data object Empty : QuantityRead

    data class Invalid(val error: ProductionFieldError) : QuantityRead
}

/**
 * El formulario de una producción: elige el producto, escribe la cantidad, revisa los insumos que
 * pide la receta y registra.
 *
 * - **Lo que se ve es lo que se registra.** Los insumos, el costo total y el costo promedio que
 *   deja salen de las mismas reglas que usa el registro, con el stock y el costo de este momento.
 * - **Cambiar el producto o la cantidad vuelve a la receta.** Lo que la persona cambió en un
 * insumo   vale para la tanda que está viendo; con otra cantidad, los insumos se recalculan.
 * - **Faltar insumos no bloquea:** la fila dice cuánto quedaría, y se puede registrar igual.
 * - **Un error nunca borra lo escrito.**
 * - **Los lotes solo aparecen si se usan.** Un insumo con lotes ofrece elegir el lote, y sin
 *   elegir sale el que vence antes; lo producido con lotes pide el código y el vencimiento de su
 *   lote. Quien no usa lotes ve el formulario de siempre.
 *
 * @param catalogSource los productos, las recetas y las unidades; se vuelve a pedir al reintentar.
 * @param register registra la producción, en una sola transacción.
 * @param initialProductId el producto que llega elegido, desde la receta.
 * @param lotsSource lee los lotes de lo producido, si su id no es `null`, y de los insumos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionFormViewModel(
    private val catalogSource: () -> Flow<ProductionCatalog>,
    private val register: suspend (ProductionRequest) -> RegisterProductionResult,
    private val savedState: SavedStateHandle,
    private val quantitySymbols: NumberSymbols,
    initialProductId: Long?,
    computation: CoroutineDispatcher,
    private val lotsSource: suspend (Long?, Set<Long>) -> ProductionLots =
        { _, _ -> ProductionLots() },
) : ViewModel() {
    private val draft = MutableStateFlow(readDraft(initialProductId))
    private val overlay = MutableStateFlow(ProductionOverlay())
    private val attempts = MutableStateFlow(0)

    private val catalog: StateFlow<CatalogLoad> = attempts.flatMapLatest {
        catalogSource()
            .map<ProductionCatalog, CatalogLoad> { CatalogLoad.Ready(it) }
            // Una falla de lectura no cierra el formulario: muestra el error y deja reintentar.
            .catch { emit(CatalogLoad.Failed) }
    }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogLoad.Loading)

    // Los lotes de lo que se produce y de sus insumos. Se releen al cambiar el producto; una falla
    // solo quita el selector y deja salir por vencimiento.
    private val lotInfo: Flow<ProductionLots> = combine(catalog, draft) { load, current ->
        val data = (load as? CatalogLoad.Ready)?.catalog ?: return@combine null
        val product = current.productId?.let { data.products[it] } ?: return@combine null
        LotsWanted(
            outputId = product.id.takeIf { product.details.tracksLots },
            inputIds = lottedInputsOf(product, data),
        )
    }.distinctUntilChanged().flatMapLatest { wanted ->
        if (wanted == null || (wanted.outputId == null && wanted.inputIds.isEmpty())) {
            flowOf(ProductionLots())
        } else {
            flow { emit(lotsSource(wanted.outputId, wanted.inputIds)) }
                .catch { emit(ProductionLots()) }
        }
    }.flowOn(computation)

    val uiState: StateFlow<ProductionFormUiState> =
        combine(catalog, draft, overlay, lotInfo, ::build)
            .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ProductionFormUiState(),
        )

    /** Si el formulario terminó y la navegación vuelve. */
    val closed: StateFlow<Boolean> = overlay.map { it.closed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onAction(action: ProductionFormAction) {
        when (action) {
            ProductionFormAction.OpenPicker ->
                overlay.update { it.copy(pickerOpen = true, query = "") }

            is ProductionFormAction.QueryChange -> overlay.update { it.copy(query = action.query) }

            ProductionFormAction.ClosePicker ->
                overlay.update { it.copy(pickerOpen = false, query = "") }

            is ProductionFormAction.ChooseProduct -> {
                changeDraft {
                    it.copy(
                        productId = action.productId,
                        overrides = emptyMap(),
                        chosenLots = emptyMap(),
                        lotCode = "",
                        lotExpiry = null,
                    )
                }
                overlay.update {
                    it.copy(pickerOpen = false, query = "", notice = null, rejected = emptyMap())
                }
            }

            is ProductionFormAction.QuantityChange -> {
                changeDraft { it.copy(quantityText = action.text, overrides = emptyMap()) }
                overlay.update { it.copy(notice = null, rejected = emptyMap()) }
            }

            is ProductionFormAction.InputChange -> {
                changeDraft {
                    it.copy(overrides = it.overrides + (action.productId to action.text))
                }
                overlay.update { it.copy(rejected = it.rejected - action.productId) }
            }

            is ProductionFormAction.ChooseInputLot -> changeDraft {
                val chosen = action.lotId
                it.copy(
                    chosenLots = if (chosen == null) {
                        it.chosenLots - action.productId
                    } else {
                        it.chosenLots + (action.productId to chosen)
                    },
                )
            }

            is ProductionFormAction.LotCodeChange -> changeDraft { it.copy(lotCode = action.text) }

            is ProductionFormAction.LotExpiryChange ->
                changeDraft { it.copy(lotExpiry = action.date) }

            is ProductionFormAction.NoteChange -> changeDraft { it.copy(noteText = action.text) }

            ProductionFormAction.Register -> registerNow()

            // Reintentar vuelve a leer si la lectura falló, y si no, vuelve a registrar lo mismo.
            ProductionFormAction.Retry ->
                if (catalog.value == CatalogLoad.Failed) {
                    attempts.update { it + 1 }
                } else {
                    registerNow()
                }

            // Crear una receta lo resuelve la navegación.
            ProductionFormAction.CreateRecipe -> Unit

            ProductionFormAction.Close -> overlay.update {
                if (uiState.value.dirty) it.copy(discardAsked = true) else it.copy(closed = true)
            }

            ProductionFormAction.ConfirmLeave ->
                overlay.update { it.copy(discardAsked = false, closed = true) }

            ProductionFormAction.DismissDialog -> overlay.update { it.copy(discardAsked = false) }

            ProductionFormAction.RegisteredShown -> overlay.update { it.copy(closed = true) }
        }
    }

    private fun build(
        load: CatalogLoad,
        current: ProductionDraft,
        shown: ProductionOverlay,
        lots: ProductionLots,
    ): ProductionFormUiState {
        val data = when (load) {
            CatalogLoad.Loading -> return ProductionFormUiState()
            CatalogLoad.Failed -> return ProductionFormUiState(loading = false, loadFailed = true)
            is CatalogLoad.Ready -> load.catalog
        }
        val producible = data.producible
        val product = current.productId?.let { id -> producible.firstOrNull { it.id == id } }
        val quantity = product?.let {
            readOutput(current.quantityText, it, data, shown.showMissing)
        }
        val inputs = if (product != null && quantity is QuantityRead.Valid) {
            inputsOf(product, quantity.quantity, data, current, shown, lots)
        } else {
            emptyList()
        }
        val figures = if (product != null && quantity is QuantityRead.Valid) {
            figuresOf(product, quantity.quantity, inputs, data, lots)
        } else {
            null
        }
        return ProductionFormUiState(
            loading = false,
            noRecipes = producible.isEmpty(),
            product = product?.let {
                ProductionProductUi(it.id, it.details.name, data.units[it.details.baseUnitId])
            },
            quantityText = current.quantityText,
            quantityError = (quantity as? QuantityRead.Invalid)?.error,
            inputs = inputs.map { row ->
                val left = figures?.statesAfter?.get(row.product.id)?.onHand
                row.ui.copy(leftAfter = left?.takeIf { it.milli < 0L })
            },
            figures = figures?.ui,
            noteText = current.noteText,
            noteTooLong = current.noteText.trim().length > MAX_NOTE,
            picker = if (shown.pickerOpen) pickerOf(producible, shown.query) else null,
            saving = shown.saving,
            notice = shown.notice,
            discardAsked = shown.discardAsked,
            registered = shown.registered,
            outputLot = product?.takeIf { it.details.tracksLots }?.let { lotUiOf(current, lots) },
            today = lots.today,
            weekStartDay = lots.weekStartDay,
        ).let { state ->
            // Un rechazo del registro se marca en su fila hasta que la persona la cambie.
            state.copy(
                inputs = state.inputs.map { row ->
                    shown.rejected[row.productId]?.let { row.copy(error = it) } ?: row
                },
            )
        }
    }

    /**
     * Los insumos con lotes de la receta de [product], también los que llegan por un combo de la
     * receta: los que el formulario ofrece elegir por lote.
     */
    private fun lottedInputsOf(product: Product, data: ProductionCatalog): Set<Long> {
        val first = data.recipes[product.id].orEmpty().map { it.componentProductId }
        val second = first.flatMap { id ->
            data.recipes[id].orEmpty().map { it.componentProductId }
        }
        return (first + second)
            .filter { data.products[it]?.details?.tracksLots == true }
            .toSet()
    }

    /**
     * El lote de lo producido en pantalla: si el código es el de un lote que el producto ya tiene,
     * lo producido suma a él y se muestra su vencimiento.
     */
    private fun lotUiOf(current: ProductionDraft, lots: ProductionLots): ProductionLotUi {
        val code = current.lotCode.trim()
        val match = lots.outputLots.firstOrNull { code.isNotEmpty() && it.code == code }
        return ProductionLotUi(
            code = current.lotCode,
            expiry = current.lotExpiry,
            existing = match != null,
            existingExpiry = match?.expiryDate,
            tooLong = code.length > LotRules.MAX_CODE_LENGTH,
        )
    }

    /** Lo que tiene cada lote ofrecido, con el sin asignar al final, como lo lee el registro. */
    private fun stocksOf(choices: LotChoices): List<LotStock> =
        choices.lots.map { it.stock } + listOfNotNull(choices.unassigned)

    /** Los insumos de la receta para [quantity], con lo que la persona cambió para esta tanda. */
    private fun inputsOf(
        product: Product,
        quantity: QuantityMilli,
        data: ProductionCatalog,
        current: ProductionDraft,
        shown: ProductionOverlay,
        lots: ProductionLots,
    ): List<InputRow> {
        val fromRecipe = when (
            val result =
                ProductionRules.recipeInputs(product.id, quantity, data.recipes, data.modes)
        ) {
            is RecipeInputsResult.Inputs -> result.inputs

            // Una receta que no se puede expandir con esta cantidad no ofrece insumos: la
            // cantidad se marca como demasiado alta o el registro lo rechaza.
            is RecipeInputsResult.InputRoundsToZero,
            RecipeInputsResult.QuantityOutOfRange,
            RecipeInputsResult.TooDeep,
            -> emptyList()
        }
        return fromRecipe.mapNotNull { input ->
            val component = data.products[input.productId] ?: return@mapNotNull null
            val text = current.overrides[input.productId] ?: quantityText(input.quantity)
            val read = readInput(text, shown.showMissing)
            val choices = lots.inputs[component.id]
            // Un lote elegido que ya no se ofrece, porque se agotó, vuelve a lo automático.
            val chosen = current.chosenLots[component.id]?.takeIf { id ->
                choices?.lots.orEmpty().any { it.stock.lotId == id }
            }
            InputRow(
                product = component,
                read = read,
                ui = ProductionInputUi(
                    productId = component.id,
                    name = component.details.name,
                    unit = data.units[component.details.baseUnitId],
                    quantityText = text,
                    tracked = component.details.stockMode == ProductStockMode.TRACKED,
                    available = component.onHand,
                    leftAfter = null,
                    error = (read as? QuantityRead.Invalid)?.error,
                    lots = choices,
                    chosenLotId = chosen,
                ),
            )
        }
    }

    /** El costo de la tanda y lo que deja en cada insumo, si todas las cantidades se leen. */
    private fun figuresOf(
        product: Product,
        quantity: QuantityMilli,
        rows: List<InputRow>,
        data: ProductionCatalog,
        lots: ProductionLots,
    ): Figures? {
        if (rows.isEmpty()) return null
        val inputs = rows.map { row ->
            val read = row.read as? QuantityRead.Valid ?: return null
            ProductionInput(row.product.id, read.quantity, row.ui.chosenLotId)
        }
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val stocks = lots.inputs.mapValues { (_, choices) -> stocksOf(choices) }
        val planned = ProductionRules.plan(before, quantity, inputs, data.products, stocks)
        val plan = when (val result = planned) {
            is Checked.Value -> result.value
            Checked.Overflow, is Checked.AboveLimit -> return null
        }
        return Figures(
            statesAfter = plan.inputStatesAfter,
            ui = ProductionFiguresUi(
                totalCost = plan.totalCost,
                unitCost = plan.unitCost,
                averageBefore = plan.outputBefore.averageCost,
                averageAfter = plan.outputAfter.averageCost,
                hasUncosted = plan.inputs.any { it.lineCost == Money.ZERO },
            ),
        )
    }

    private fun registerNow() {
        val state = uiState.value
        if (state.saving) return
        val current = draft.value
        val product = state.product
        val catalogData = (catalog.value as? CatalogLoad.Ready)?.catalog ?: return
        val stored = product?.let { catalogData.products[it.id] }
        val quantity = stored?.let { readOutput(current.quantityText, it, catalogData, true) }
        val reads = state.inputs.map { readInput(it.quantityText, showMissing = true) }
        val valid = quantity is QuantityRead.Valid &&
            reads.isNotEmpty() &&
            reads.all { it is QuantityRead.Valid } &&
            !state.noteTooLong &&
            state.outputLot?.tooLong != true
        if (product == null) {
            // Sin producto no hay receta: se abre la hoja para elegirlo.
            overlay.update { it.copy(showMissing = true, pickerOpen = true, query = "") }
            return
        }
        if (!valid) {
            overlay.update { it.copy(showMissing = true) }
            return
        }
        val request = ProductionRequest(
            productId = product.id,
            quantity = (quantity as QuantityRead.Valid).quantity,
            inputs = state.inputs.zip(reads).map { (row, read) ->
                val milli = (read as QuantityRead.Valid).quantity
                ProductionInput(row.productId, milli, row.chosenLotId)
            },
            note = current.noteText,
            // Un código que ya existe suma a ese lote: el vencimiento escrito no lo cambia.
            lot = state.outputLot?.let { lot ->
                ProductionLot(lot.code, lot.expiry.takeIf { !lot.existing })
            },
        )
        overlay.update { it.copy(saving = true, notice = null, showMissing = true) }
        viewModelScope.launch {
            val result = register(request)
            overlay.update { it.copy(saving = false) }
            handle(result, state)
        }
    }

    private fun handle(result: RegisterProductionResult, state: ProductionFormUiState) {
        when (result) {
            is RegisterProductionResult.Registered ->
                overlay.update { it.copy(registered = true) }

            is RegisterProductionResult.InputRejected -> {
                val row = state.inputs.getOrNull(result.index) ?: return failed(false)
                val error = when (result.reason) {
                    ProductionInputRejection.INVALID_QUANTITY -> ProductionFieldError.TooHigh

                    ProductionInputRejection.NOT_FOUND,
                    ProductionInputRejection.IS_COMBO,
                    ProductionInputRejection.SAME_PRODUCT,
                    ProductionInputRejection.REPEATED,
                    ProductionInputRejection.LOTS_NOT_SUPPORTED,
                    ProductionInputRejection.LOT_NOT_FOUND,
                    -> return failed(false)
                }
                overlay.update { it.copy(rejected = it.rejected + (row.productId to error)) }
            }

            is RegisterProductionResult.AboveLimit -> when (result.limit) {
                Limit.ON_HAND_MILLI -> overlay.update {
                    it.copy(notice = ProductionNotice.StockCap(state.product?.name.orEmpty()))
                }

                Limit.UNIT_MONEY, Limit.DOCUMENT_TOTAL, Limit.STOCK_VALUE, Limit.POINTS,
                Limit.LINE_QUANTITY_MILLI, Limit.DOCUMENT_LINES,
                -> failed(false)
            }

            is RegisterProductionResult.StorageFailed -> failed(result.failure.isOutOfSpace)

            // El catálogo cambió entre leer y registrar, o la región no se puede usar: no se
            // escribió nada, y lo escrito se conserva para reintentar.
            RegisterProductionResult.NotFound,
            RegisterProductionResult.ProductArchived,
            RegisterProductionResult.NotStocked,
            RegisterProductionResult.LotsNotSupported,
            RegisterProductionResult.LotNotTracked,
            is RegisterProductionResult.LotCodeTooLong,
            RegisterProductionResult.NoRecipe,
            is RegisterProductionResult.InvalidQuantity,
            RegisterProductionResult.NoInputs,
            RegisterProductionResult.TooManyInputs,
            RegisterProductionResult.NoteTooLong,
            RegisterProductionResult.UnknownTimeZone,
            RegisterProductionResult.CurrencyUnconfirmed,
            -> failed(false)
        }
    }

    private fun failed(outOfSpace: Boolean) {
        overlay.update { it.copy(notice = ProductionNotice.RegisterFailed(outOfSpace)) }
    }

    private fun pickerOf(producible: List<Product>, query: String): ProductionPicker =
        ProductionPicker(
            query,
            ProductSearch.filter(producible, query).map {
                ProductionOption(it.id, it.details.name)
            },
        )

    /** La cantidad a producir, en la unidad del producto. */
    private fun readOutput(
        text: String,
        product: Product,
        data: ProductionCatalog,
        showMissing: Boolean,
    ): QuantityRead {
        val read = readInput(text, showMissing)
        if (read !is QuantityRead.Valid) return read
        val wholeOnly = data.units[product.details.baseUnitId]?.allowsFraction == false
        return if (wholeOnly && !read.quantity.isWholeUnits) {
            QuantityRead.Invalid(ProductionFieldError.FractionNotAllowed)
        } else {
            read
        }
    }

    /** Una cantidad en milésimas: los insumos admiten fracciones en cualquier unidad. */
    private fun readInput(text: String, showMissing: Boolean): QuantityRead = when (
        val read =
            NumberInputParser.quantity(text, quantitySymbols, Limit.LINE_QUANTITY_MILLI)
    ) {
        NumberInput.Blank ->
            if (showMissing) {
                QuantityRead.Invalid(ProductionFieldError.Missing)
            } else {
                QuantityRead.Empty
            }

        is NumberInput.Value ->
            if (read.value.milli > 0L) {
                QuantityRead.Valid(read.value)
            } else {
                QuantityRead.Invalid(ProductionFieldError.NotANumber)
            }

        NumberInput.Malformed, is NumberInput.TooManyDecimals ->
            QuantityRead.Invalid(ProductionFieldError.NotANumber)

        is NumberInput.TooLarge -> QuantityRead.Invalid(ProductionFieldError.TooHigh)
    }

    private fun quantityText(quantity: QuantityMilli): String =
        fixedPointInputText(quantity.milli, QUANTITY_DECIMALS, quantitySymbols)

    private fun changeDraft(change: (ProductionDraft) -> ProductionDraft) {
        val next = change(draft.value)
        draft.value = next
        savedState[KEY_PRODUCT] = next.productId
        savedState[KEY_QUANTITY] = next.quantityText
        savedState[KEY_NOTE] = next.noteText
        savedState[KEY_OVERRIDE_IDS] = next.overrides.keys.toLongArray()
        savedState[KEY_OVERRIDE_TEXTS] = ArrayList(next.overrides.values)
        savedState[KEY_LOT_INPUTS] = next.chosenLots.keys.toLongArray()
        savedState[KEY_LOT_IDS] = next.chosenLots.values.toLongArray()
        savedState[KEY_LOT_CODE] = next.lotCode
        savedState[KEY_LOT_EXPIRY] = next.lotExpiry?.toString()
    }

    private fun readDraft(initialProductId: Long?): ProductionDraft {
        val ids = savedState.get<LongArray>(KEY_OVERRIDE_IDS) ?: LongArray(0)
        val texts = savedState.get<ArrayList<String>>(KEY_OVERRIDE_TEXTS).orEmpty()
        val lotInputs = savedState.get<LongArray>(KEY_LOT_INPUTS) ?: LongArray(0)
        val lotIds = savedState.get<LongArray>(KEY_LOT_IDS) ?: LongArray(0)
        return ProductionDraft(
            productId = savedState.get<Long>(KEY_PRODUCT) ?: initialProductId,
            quantityText = savedState.get<String>(KEY_QUANTITY).orEmpty(),
            noteText = savedState.get<String>(KEY_NOTE).orEmpty(),
            overrides = ids.toList().zip(texts).toMap(),
            chosenLots = lotInputs.toList().zip(lotIds.toList()).toMap(),
            lotCode = savedState.get<String>(KEY_LOT_CODE).orEmpty(),
            lotExpiry = savedState.get<String>(KEY_LOT_EXPIRY)?.let { BusinessDate.parse(it) },
        )
    }

    /** En qué va la lectura del catálogo. */
    private sealed interface CatalogLoad {
        data object Loading : CatalogLoad

        data class Ready(val catalog: ProductionCatalog) : CatalogLoad

        data object Failed : CatalogLoad
    }

    /** Un insumo leído, con su producto y su fila para la pantalla. */
    private class InputRow(val product: Product, val read: QuantityRead, val ui: ProductionInputUi)

    private class Figures(val statesAfter: Map<Long, CostState>, val ui: ProductionFiguresUi)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val QUANTITY_DECIMALS = 3
        const val MAX_NOTE = RegisterProductionUseCase.MAX_NOTE_LENGTH
        const val KEY_PRODUCT = "production.product"
        const val KEY_QUANTITY = "production.quantity"
        const val KEY_NOTE = "production.note"
        const val KEY_OVERRIDE_IDS = "production.overrideIds"
        const val KEY_OVERRIDE_TEXTS = "production.overrideTexts"
        const val KEY_LOT_INPUTS = "production.lotInputs"
        const val KEY_LOT_IDS = "production.lotIds"
        const val KEY_LOT_CODE = "production.lotCode"
        const val KEY_LOT_EXPIRY = "production.lotExpiry"
    }
}
