package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPricePreview
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import kotlin.math.abs
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que la pantalla de actualización masiva le pide al dominio. Es una interfaz para que las
 * pruebas del ViewModel den respuestas fijas sin una base.
 */
interface BulkPriceOperations {
    /** Todo el catálogo, vigente y archivado: un archivado del alcance se muestra excluido. */
    fun catalog(): Flow<List<Product>>

    fun categories(): Flow<List<Category>>

    /** Los proveedores vigentes, que se pueden elegir como alcance. */
    fun suppliers(): Flow<List<Supplier>>

    /** El paso de redondeo guardado en los ajustes: cero es sin redondeo. */
    fun defaultStep(): Flow<Money>

    suspend fun apply(
        productIds: Collection<Long>,
        percent: BasisPoints,
        step: Money,
    ): ApplyBulkPriceResult
}

/** Qué productos: los chips de `Qué productos`. */
enum class BulkScopeKind {
    ALL,
    CATEGORY,
    SUPPLIER,
    PICK,
}

/** Cómo se redondea: los chips de `Redondear`. */
enum class BulkRounding {
    NONE,
    SAVED_STEP,
    OTHER,
}

/** La hoja de elegir abierta, si hay una. */
enum class BulkSheet {
    CATEGORY,
    SUPPLIER,
    PRODUCTS,
}

/** Lo que muestra la vista previa. */
@Immutable
sealed interface BulkPreviewContent {
    /** Todavía no llega el catálogo. */
    data object Loading : BulkPreviewContent

    /** No hay ningún producto con precio: no hay nada que actualizar. */
    data object NoPrices : BulkPreviewContent

    /** Falta un porcentaje válido o un alcance elegido. */
    data object NeedsInput : BulkPreviewContent

    data class Ready(val preview: BulkPricePreview) : BulkPreviewContent

    data object ReadError : BulkPreviewContent
}

/** Un aviso tras aplicar. */
sealed interface BulkMessage {
    data class Applied(val changed: Int) : BulkMessage

    /** Ninguno cambia ya: otra pantalla los cambió mientras tanto. */
    data object NothingChanged : BulkMessage

    data class Failed(val outOfSpace: Boolean) : BulkMessage
}

/**
 * Lo que la persona eligió y escribió. [percentText] y [stepText] van como texto, para no perder lo
 * escrito; [percentProblem] dice por qué el porcentaje no se puede usar.
 */
@Immutable
data class BulkPriceInput(
    val scope: BulkScopeKind = BulkScopeKind.ALL,
    val categoryId: Long? = null,
    val supplierId: Long? = null,
    val picked: Set<Long> = emptySet(),
    val percentText: String = "",
    val percentProblem: FieldProblem? = null,
    val rounding: BulkRounding = BulkRounding.NONE,
    val stepText: String = "",
    val removed: Set<Long> = emptySet(),
)

/** El estado de la pantalla de actualización masiva. */
@Immutable
data class BulkPriceUiState(
    val input: BulkPriceInput = BulkPriceInput(),
    val content: BulkPreviewContent = BulkPreviewContent.Loading,
    val categories: List<Category> = emptyList(),
    val suppliers: List<Supplier> = emptyList(),
    val products: List<Product> = emptyList(),
    val savedStep: Money = Money.ZERO,
    val sheet: BulkSheet? = null,
    val applying: Boolean = false,
    val message: BulkMessage? = null,
    val percent: BasisPoints? = null,
) {
    /** Cuántos precios cambian al aplicar. */
    val changing: Int get() = (content as? BulkPreviewContent.Ready)?.preview?.changes?.size ?: 0

    /** Si el porcentaje pasa del 50 %: protege de escribir `80` por `8`. */
    val highPercent: Boolean get() = percent?.let { it.bp > HIGH_PERCENT_BP } == true

    private companion object {
        const val HIGH_PERCENT_BP = 5_000L
    }
}

/** Lo que la persona hace en la pantalla. */
sealed interface BulkPriceAction {
    data object Back : BulkPriceAction

    data class SelectScope(val kind: BulkScopeKind) : BulkPriceAction

    data class PickCategory(val categoryId: Long) : BulkPriceAction

    data class PickSupplier(val supplierId: Long) : BulkPriceAction

    data class TogglePicked(val productId: Long) : BulkPriceAction

    data object CloseSheet : BulkPriceAction

    data class PercentText(val text: String) : BulkPriceAction

    /** Un atajo como `+5 %`: [percent] en puntos básicos. */
    data class PercentShortcut(val percent: BasisPoints) : BulkPriceAction

    data class SelectRounding(val rounding: BulkRounding) : BulkPriceAction

    data class StepText(val text: String) : BulkPriceAction

    data class RemoveRow(val productId: Long) : BulkPriceAction

    data object Apply : BulkPriceAction

    data object MessageShown : BulkPriceAction

    data object Retry : BulkPriceAction
}

/** Los separadores con que se leen el porcentaje y el paso escritos. */
data class BulkNumberFormat(
    val percentSymbols: NumberSymbols,
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
)

/**
 * La actualización masiva de precios por porcentaje: elegir qué productos, cuánto y cómo redondear,
 * ver el resultado y aplicarlo todo de una vez.
 *
 * La vista previa se rearma fuera del hilo principal con cada cambio, tras una pausa breve al
 * escribir, y nunca bloquea lo que se escribe. Quitar una fila la deja fuera al aplicar. Aplicar
 * corre una sola vez a la vez.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class BulkPriceViewModel(
    private val operations: BulkPriceOperations,
    private val numbers: BulkNumberFormat,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val input = MutableStateFlow(BulkPriceInput())
    private val extra = MutableStateFlow(Extra())
    private val attempts = MutableStateFlow(0)
    private var lastRequest: BulkPriceRequest? = null
    private var lastPreview: BulkPricePreview? = null

    /** El catálogo, o `null` mientras no llega. Reintentar vuelve a leerlo. */
    private val catalog: Flow<List<Product>?> = attempts.flatMapLatest {
        operations.catalog().map<List<Product>, List<Product>?> { it }.onStart { emit(null) }
    }

    private val content: Flow<BulkPreviewContent> =
        combine(
            catalog,
            input.debounce(TYPING_PAUSE_MILLIS),
            operations.defaultStep(),
        ) { products, typed, saved ->
            val request = requestOf(typed, saved)
            lastRequest = request
            when {
                products == null -> BulkPreviewContent.Loading

                products.none { !it.isArchived && it.salePrice > Money.ZERO } ->
                    BulkPreviewContent.NoPrices

                request == null -> BulkPreviewContent.NeedsInput

                else ->
                    BulkPreviewContent.Ready(BulkPricePreviewer.preview(products, request))
            }
        }
            .flowOn(computation)
            .onEach { shown -> lastPreview = (shown as? BulkPreviewContent.Ready)?.preview }
            .catch { emit(BulkPreviewContent.ReadError) }

    val uiState: StateFlow<BulkPriceUiState> =
        combine(
            input,
            content,
            combine(
                operations.categories(),
                operations.suppliers(),
                catalog.map { it.orEmpty().filterNot(Product::isArchived) },
                operations.defaultStep(),
            ) { categories, suppliers, products, step ->
                Lists(categories, suppliers, products, step)
            },
            extra,
        ) { typed, shown, lists, more ->
            BulkPriceUiState(
                input = typed,
                content = shown,
                categories = lists.categories,
                suppliers = lists.suppliers,
                products = lists.products,
                savedStep = lists.step,
                sheet = more.sheet,
                applying = more.applying,
                message = more.message,
                percent = percentOf(typed),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = BulkPriceUiState(),
        )

    fun onAction(action: BulkPriceAction) {
        when (action) {
            is BulkPriceAction.SelectScope -> {
                input.update { it.copy(scope = action.kind, removed = emptySet()) }
                val sheet = when (action.kind) {
                    BulkScopeKind.ALL -> null
                    BulkScopeKind.CATEGORY -> BulkSheet.CATEGORY
                    BulkScopeKind.SUPPLIER -> BulkSheet.SUPPLIER
                    BulkScopeKind.PICK -> BulkSheet.PRODUCTS
                }
                extra.update { it.copy(sheet = sheet) }
            }

            is BulkPriceAction.PickCategory -> {
                input.update { it.copy(categoryId = action.categoryId, removed = emptySet()) }
                extra.update { it.copy(sheet = null) }
            }

            is BulkPriceAction.PickSupplier -> {
                input.update { it.copy(supplierId = action.supplierId, removed = emptySet()) }
                extra.update { it.copy(sheet = null) }
            }

            is BulkPriceAction.TogglePicked ->
                input.update {
                    val picked =
                        if (action.productId in it.picked) {
                            it.picked - action.productId
                        } else {
                            it.picked + action.productId
                        }
                    it.copy(picked = picked)
                }

            BulkPriceAction.CloseSheet -> extra.update { it.copy(sheet = null) }

            is BulkPriceAction.PercentText ->
                input.update { it.copy(percentText = action.text, percentProblem = null) }

            is BulkPriceAction.PercentShortcut ->
                input.update {
                    it.copy(percentText = percentText(action.percent), percentProblem = null)
                }

            is BulkPriceAction.SelectRounding -> input.update {
                it.copy(rounding = action.rounding)
            }

            is BulkPriceAction.StepText -> input.update { it.copy(stepText = action.text) }

            is BulkPriceAction.RemoveRow ->
                input.update { it.copy(removed = it.removed + action.productId) }

            BulkPriceAction.Apply -> apply()

            BulkPriceAction.MessageShown -> extra.update { it.copy(message = null) }

            BulkPriceAction.Retry -> attempts.update { it + 1 }

            // La resuelve la pantalla con la navegación.
            BulkPriceAction.Back -> Unit
        }
    }

    private fun apply() {
        if (extra.value.applying) return
        val request = lastRequest ?: return
        val preview = lastPreview ?: return
        if (preview.changes.isEmpty()) return
        val ids = preview.changes.map { it.productId }
        extra.update { it.copy(applying = true) }
        viewModelScope.launch {
            val message = when (
                val result = operations.apply(ids, request.percent, request.roundingStep)
            ) {
                is ApplyBulkPriceResult.Applied -> BulkMessage.Applied(result.changed)

                ApplyBulkPriceResult.NothingToChange -> BulkMessage.NothingChanged

                ApplyBulkPriceResult.InvalidRequest, ApplyBulkPriceResult.UnknownTimeZone,
                ApplyBulkPriceResult.CurrencyUnconfirmed,
                -> BulkMessage.Failed(outOfSpace = false)

                is ApplyBulkPriceResult.StorageFailed ->
                    BulkMessage.Failed(result.failure.isOutOfSpace)
            }
            if (message is BulkMessage.Applied) {
                input.update { it.copy(percentText = "", removed = emptySet()) }
            }
            extra.update { it.copy(applying = false, message = message) }
        }
    }

    /** La solicitud que pide lo escrito, o `null` si falta algo o no se puede leer. */
    private fun requestOf(typed: BulkPriceInput, saved: Money): BulkPriceRequest? {
        val percent = percentOf(typed) ?: return null
        val scope = when (typed.scope) {
            BulkScopeKind.ALL -> BulkPriceScope.All

            BulkScopeKind.CATEGORY -> BulkPriceScope.Category(typed.categoryId ?: return null)

            BulkScopeKind.SUPPLIER -> BulkPriceScope.Supplier(typed.supplierId ?: return null)

            BulkScopeKind.PICK ->
                if (typed.picked.isEmpty()) return null else BulkPriceScope.Picked(typed.picked)
        }
        val step = when (typed.rounding) {
            BulkRounding.NONE -> Money.ZERO
            BulkRounding.SAVED_STEP -> saved
            BulkRounding.OTHER -> stepOf(typed.stepText) ?: return null
        }
        return BulkPriceRequest(scope, percent, step, typed.removed)
    }

    /** El porcentaje escrito, si es válido: con signo, distinto de cero y dentro de los topes. */
    private fun percentOf(typed: BulkPriceInput): BasisPoints? {
        val read = NumberInputParser.percent(
            typed.percentText,
            numbers.percentSymbols,
            BasisPoints.MAX_BULK_PERCENT,
        )
        val value = (read as? NumberInput.Value)?.value ?: return null
        return value.takeIf { BulkPriceRules.isValidPercent(it) }
    }

    private fun stepOf(text: String): Money? = when (
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        )
    ) {
        is NumberInput.Value -> read.value.takeIf { it > Money.ZERO }

        NumberInput.Blank, NumberInput.Malformed, is NumberInput.TooManyDecimals,
        is NumberInput.TooLarge,
        -> null
    }

    /** Un atajo como texto editable: `5`, `-10`, con el separador de la región. */
    private fun percentText(percent: BasisPoints): String {
        val whole = percent.bp / PERCENT_SCALE
        val fraction = (abs(percent.bp) % PERCENT_SCALE).toString().padStart(2, '0').trimEnd('0')
        val separator = numbers.percentSymbols.decimalSeparator
        return if (fraction.isEmpty()) whole.toString() else "$whole$separator$fraction"
    }

    private data class Extra(
        val sheet: BulkSheet? = null,
        val applying: Boolean = false,
        val message: BulkMessage? = null,
    )

    private data class Lists(
        val categories: List<Category>,
        val suppliers: List<Supplier>,
        val products: List<Product>,
        val step: Money,
    )

    companion object {
        /** La pausa al escribir tras la que se rearma la vista previa. */
        const val TYPING_PAUSE_MILLIS = 150L

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val PERCENT_SCALE = 100L
    }
}
