package io.github.yesidrobayo.shopledger.ui.combo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLineProblem
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboFigures
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.rule.catalog.RecipeCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.RecipeRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RecipeEditorData
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** En qué va la lectura de la receta. */
private sealed interface RecipeLoad {
    data object Loading : RecipeLoad

    data class Ready(val data: RecipeEditorData) : RecipeLoad

    /** El combo ya no existe, o dejó de armarse con componentes. */
    data object Missing : RecipeLoad

    data object Failed : RecipeLoad
}

/** Lo que la pantalla muestra encima de la receta, que no se guarda en el borrador. */
private data class RecipeOverlay(
    val pickerOpen: Boolean = false,
    val query: String = "",
    val discardAsked: Boolean = false,
    val notice: RecipeNotice? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val closed: Boolean = false,
)

/** Cómo se leyó la cantidad de una fila. */
private sealed interface QuantityRead {
    data class Valid(val quantity: QuantityMilli) : QuantityRead

    data object Empty : QuantityRead

    data class Invalid(val error: RecipeRowError) : QuantityRead
}

/**
 * La receta de un combo: la arma en memoria y la guarda entera de una sola vez.
 *
 * - **Nada se escribe hasta guardar.** Lo armado vive en el estado guardado de la pantalla, y
 *   empieza como la receta guardada.
 * - **Lo que se ve es lo que se vendería.** El costo de cada fila, el de una unidad, el margen y
 *   lo que alcanza salen de las mismas reglas que costean la venta, con el stock y el costo de
 *   hoy.
 * - **Los niveles y los ciclos se marcan al agregar**, en la fila, con la misma regla que revisa
 *   al guardar: no hace falta intentar guardar para enterarse.
 * - **Un error nunca borra lo armado.** Una cantidad vacía solo se marca después de intentar
 *   guardar.
 *
 * @param editorSource lo que la receta lee; se vuelve a pedir al reintentar.
 * @param save guarda la receta entera, en una sola transacción.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeViewModel(
    private val comboId: Long,
    private val editorSource: () -> Flow<RecipeEditorData?>,
    private val save: suspend (List<RecipeLine>) -> SaveRecipeResult,
    private val savedState: SavedStateHandle,
    private val quantitySymbols: NumberSymbols,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val draft = MutableStateFlow(RecipeDraftStore.read(savedState))
    private val overlay = MutableStateFlow(RecipeOverlay())
    private val attempts = MutableStateFlow(0)

    private val load: StateFlow<RecipeLoad> = attempts.flatMapLatest {
        editorSource()
            .map { data ->
                if (data == null || !RecipeRules.allowsRecipe(data.combo.details.stockMode)) {
                    RecipeLoad.Missing
                } else {
                    RecipeLoad.Ready(data)
                }
            }
            // Una falla de lectura no cierra la receta: muestra el error y deja reintentar.
            .catch { emit(RecipeLoad.Failed) }
    }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecipeLoad.Loading)

    val uiState: StateFlow<RecipeUiState> = combine(load, draft, overlay, ::build)
        .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            RecipeUiState(),
        )

    /** Si la pantalla terminó y la navegación vuelve. */
    val closed: StateFlow<Boolean> = overlay.map { it.closed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            // La primera lectura arma el borrador con la receta guardada, si no venía uno.
            load.collect { current ->
                if (current is RecipeLoad.Ready && draft.value == null) {
                    changeDraft { storedDraft(current.data) }
                }
            }
        }
    }

    fun onAction(action: RecipeAction) {
        when (action) {
            RecipeAction.OpenPicker -> overlay.update { it.copy(pickerOpen = true, query = "") }

            is RecipeAction.QueryChange -> overlay.update { it.copy(query = action.query) }

            RecipeAction.ClosePicker -> overlay.update { it.copy(pickerOpen = false, query = "") }

            is RecipeAction.AddComponent -> {
                changeDraft { current ->
                    current.copy(
                        lines = current.lines +
                            RecipeLineDraft(current.nextKey, action.productId, ONE_UNIT_TEXT),
                        nextKey = current.nextKey + 1,
                    )
                }
                overlay.update { it.copy(pickerOpen = false, query = "", notice = null) }
            }

            is RecipeAction.QuantityChange -> changeDraft { current ->
                current.copy(
                    lines = current.lines.map {
                        if (it.key == action.key) it.copy(quantityText = action.text) else it
                    },
                )
            }

            is RecipeAction.RemoveComponent -> changeDraft { current ->
                current.copy(lines = current.lines.filterNot { it.key == action.key })
            }

            RecipeAction.Save -> saveNow()

            // Reintentar vuelve a leer si la lectura falló, y si no, vuelve a guardar lo mismo.
            RecipeAction.Retry ->
                if (load.value == RecipeLoad.Failed) attempts.update { it + 1 } else saveNow()

            // La pantalla abre el formulario del producto o el de producción; aquí no cambia nada.
            RecipeAction.EditProduct, RecipeAction.Produce -> Unit

            RecipeAction.Close -> overlay.update {
                if (uiState.value.dirty) it.copy(discardAsked = true) else it.copy(closed = true)
            }

            RecipeAction.ConfirmLeave -> overlay.update {
                it.copy(discardAsked = false, closed = true)
            }

            RecipeAction.DismissDialog -> overlay.update { it.copy(discardAsked = false) }

            RecipeAction.SavedShown -> overlay.update { it.copy(closed = true) }
        }
    }

    private fun changeDraft(change: (RecipeDraft) -> RecipeDraft) {
        val next = change(draft.value ?: RecipeDraft())
        draft.value = next
        RecipeDraftStore.write(savedState, next)
    }

    private fun saveNow() {
        val state = uiState.value
        if (state.saving || state.saved || state.loading || state.missing) return
        changeDraft { it.copy(showMissing = true) }
        val current = draft.value ?: return
        val reads = current.lines.map { readQuantity(it.quantityText, showMissing = true) }
        val lines = current.lines.zip(reads).mapNotNull { (line, read) ->
            (read as? QuantityRead.Valid)?.let { RecipeLine(line.productId, it.quantity) }
        }
        // Una fila sin cantidad válida o con un error de estructura no deja guardar.
        if (lines.size != current.lines.size || uiState.value.rows.any { it.error != null }) return
        overlay.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            val result = save(lines)
            overlay.update { it.copy(saving = false).after(result) }
        }
    }

    private fun RecipeOverlay.after(result: SaveRecipeResult): RecipeOverlay = when (result) {
        SaveRecipeResult.Saved -> copy(saved = true)

        // El combo desapareció o dejó de serlo mientras se armaba: la lectura lo mostrará.
        SaveRecipeResult.NotFound, SaveRecipeResult.NotCombo -> this

        // Los errores de cada fila ya se ven en pantalla, con la misma regla.
        is SaveRecipeResult.Invalid -> this

        is SaveRecipeResult.StorageFailed ->
            copy(notice = RecipeNotice.SaveFailed(result.failure.isOutOfSpace))
    }

    private fun storedDraft(data: RecipeEditorData): RecipeDraft {
        val lines = data.recipe.mapIndexed { index, row ->
            RecipeLineDraft(index + 1L, row.componentProductId, textOf(row.quantity))
        }
        return RecipeDraft(lines = lines, nextKey = lines.size + 1L)
    }

    private fun textOf(quantity: QuantityMilli): String =
        fixedPointInputText(quantity.milli, QUANTITY_DECIMALS, quantitySymbols)

    private fun build(
        current: RecipeLoad,
        shownDraft: RecipeDraft?,
        shown: RecipeOverlay,
    ): RecipeUiState = when (current) {
        RecipeLoad.Loading -> RecipeUiState()

        RecipeLoad.Failed -> RecipeUiState(loading = false, loadFailed = true)

        RecipeLoad.Missing -> RecipeUiState(loading = false, missing = true)

        is RecipeLoad.Ready ->
            if (shownDraft == null) RecipeUiState() else ready(current.data, shownDraft, shown)
    }

    private fun ready(
        data: RecipeEditorData,
        current: RecipeDraft,
        shown: RecipeOverlay,
    ): RecipeUiState {
        val products = data.products
        val reads = current.lines.map { readQuantity(it.quantityText, current.showMissing) }
        // La estructura se revisa con cualquier cantidad: un ciclo se marca antes de escribirla.
        val forCheck = current.lines.zip(reads).map { (line, read) ->
            RecipeLine(line.productId, (read as? QuantityRead.Valid)?.quantity ?: ONE_UNIT)
        }
        val structure = structureErrors(data, forCheck)
        val previewLines = current.lines.indices
            .filter { structure[it] == null }
            .mapNotNull { index ->
                (reads[index] as? QuantityRead.Valid)?.let {
                    RecipeLine(current.lines[index].productId, it.quantity)
                }
            }
        val preview = ComboFigures.preview(data.combo.id, previewLines, data.stored, products)
        val rows = current.lines.mapIndexed { index, line ->
            val product = products[line.productId]
            val read = reads[index]
            RecipeRowUi(
                key = line.key,
                productId = line.productId,
                name = product?.details?.name.orEmpty(),
                unit = product?.let { data.units[it.details.baseUnitId] },
                quantityText = line.quantityText,
                lineCost = preview.rowCosts[line.productId],
                tracked = product?.details?.stockMode != ProductStockMode.UNTRACKED,
                archived = product?.isArchived == true,
                error = structure[index] ?: (read as? QuantityRead.Invalid)?.error,
            )
        }
        val price = data.combo.salePrice
        val cost = preview.unitCost
        val stored = data.recipe.map { it.componentProductId to textOf(it.quantity) }
        return RecipeUiState(
            loading = false,
            comboName = data.combo.details.name,
            produced = data.combo.details.stockMode == ProductStockMode.TRACKED,
            salePrice = price,
            rows = rows,
            unitCost = cost,
            profit = cost?.let { profitOf(price, it) },
            margin = cost?.let { MarginCalculator.margin(price, it) } ?: Margin.NoData,
            available = preview.availability?.units,
            limitingName = preview.availability?.limitingProductId
                ?.let { products[it]?.details?.name },
            hasUncosted = preview.hasUncostedLeaf,
            canAdd = current.lines.size < Limit.DOCUMENT_LINES.max,
            picker = if (shown.pickerOpen) pickerOf(data, current, shown.query) else null,
            discardAsked = shown.discardAsked,
            notice = shown.notice,
            saving = shown.saving,
            saved = shown.saved,
            dirty = current.lines.map { it.productId to it.quantityText } != stored,
        )
    }

    /** El precio menos el costo, o `null` si la resta no cabe: la tarjeta muestra un guion. */
    private fun profitOf(price: Money, cost: Money): Money? = try {
        price - cost
    } catch (overflow: ArithmeticException) {
        null
    }

    /** El error de estructura de cada fila, por su posición: ciclo, tercer nivel o no elegible. */
    private fun structureErrors(
        data: RecipeEditorData,
        lines: List<RecipeLine>,
    ): Map<Int, RecipeRowError> {
        val check = RecipeRules.check(data.combo.id, lines, data.products, data.stored)
        val issues = (check as? RecipeCheck.Invalid)?.issues.orEmpty()
        val errors = HashMap<Int, RecipeRowError>()
        for (issue in issues) {
            val line = issue as? RecipeIssue.Line ?: continue
            val error = when (line.problem) {
                RecipeLineProblem.CYCLE -> RecipeRowError.Cycle(data.combo.details.name)

                RecipeLineProblem.TOO_DEEP -> RecipeRowError.TooDeep

                RecipeLineProblem.SAME_PRODUCT,
                RecipeLineProblem.REPEATED,
                RecipeLineProblem.UNAVAILABLE,
                -> RecipeRowError.Unavailable

                // La cantidad la revisa la lectura de la fila, con el formato de la región.
                RecipeLineProblem.QUANTITY_OUT_OF_RANGE -> null
            }
            if (error != null) errors.putIfAbsent(line.index, error)
        }
        return errors
    }

    /**
     * Los productos que se pueden agregar: vigentes, sin el propio combo ni los que ya están en la
     * receta, en el orden del catálogo.
     */
    private fun pickerOf(
        data: RecipeEditorData,
        current: RecipeDraft,
        query: String,
    ): RecipePicker {
        val taken = current.lines.mapTo(HashSet()) { it.productId } + data.combo.id
        val choices = data.products.values
            .filter { !it.isArchived && it.id !in taken }
            .sortedWith(compareBy({ it.details.normalizedName }, { it.id }))
        val options = ProductSearch.filter(choices, query)
        return RecipePicker(query, options.map { RecipeOption(it.id, it.details.name) })
    }

    private fun readQuantity(text: String, showMissing: Boolean): QuantityRead {
        val read = NumberInputParser.quantity(text, quantitySymbols, Limit.LINE_QUANTITY_MILLI)
        return when (read) {
            NumberInput.Blank ->
                if (showMissing) {
                    QuantityRead.Invalid(RecipeRowError.NotANumber)
                } else {
                    QuantityRead.Empty
                }

            is NumberInput.Value ->
                if (read.value.milli > 0L) {
                    QuantityRead.Valid(read.value)
                } else {
                    QuantityRead.Invalid(RecipeRowError.NotANumber)
                }

            NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                QuantityRead.Invalid(RecipeRowError.NotANumber)

            is NumberInput.TooLarge -> QuantityRead.Invalid(RecipeRowError.TooHigh)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Las milésimas que caben en una cantidad. */
        const val QUANTITY_DECIMALS = 3

        /** Un componente nuevo empieza con una unidad: lo más común en un kit. */
        val ONE_UNIT = QuantityMilli(Scale.MILLI)
        const val ONE_UNIT_TEXT = "1"
    }
}
