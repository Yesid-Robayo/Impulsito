package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPricePreview
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceResult
import kotlin.math.abs
import kotlin.uuid.Uuid
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

    /**
     * Aplica [request] a [productIds], con lo que fija el negocio para el margen objetivo en
     * [context]. Las listas y los precios propios se vuelven a leer al aplicar.
     */
    suspend fun apply(
        productIds: Collection<Long>,
        request: BulkPriceRequest,
        context: BulkPriceContext,
    ): ApplyBulkPriceResult

    /** Las listas, los precios propios y lo del margen objetivo, para la vista previa. */
    fun listChoices(): Flow<BulkListChoices> = flowOf(BulkListChoices())

    /** Los productos de `Revisar` en el inventario: costo nuevo, margen bajo o costo viejo. */
    fun reviewIds(): Flow<Set<Long>> = flowOf(emptySet())

    /** Lo que haría deshacer la última actualización masiva, o `null` si no hay ninguna. */
    fun undoOffer(): Flow<BulkUndoPlan?>

    suspend fun undo(batchId: Uuid): UndoBulkPriceResult
}

/**
 * Lo que la masiva lee además del catálogo: todas las listas, también las archivadas que una
 * relativa puede tener de base; los precios propios por producto y lista; y lo que fija el negocio
 * para el margen objetivo, o `null` mientras no llega.
 */
@Immutable
data class BulkListChoices(
    val lists: List<PriceList> = emptyList(),
    val ownPrices: Map<Long, Map<Long, Money>> = emptyMap(),
    val pricing: PricingContext? = null,
) {
    /** Las que se pueden actualizar: vigentes y no relativas, que se calculan. */
    val writable: List<PriceList>
        get() = lists.filter { !it.isArchived && it.pricing !is PriceListPricing.Relative }

    /** Lo que la vista previa y el aplicar necesitan. */
    val context: BulkPriceContext
        get() = BulkPriceContext(pricing, lists.associateBy { it.id }, ownPrices)
}

/** Qué productos: los chips de `Qué productos`. */
enum class BulkScopeKind {
    ALL,
    CATEGORY,
    SUPPLIER,

    /** `Costo nuevo sin revisar`: solo aparece si hay alguno en la cola. */
    PENDING_REVIEW,
    PICK,

    /**
     * `Revisar`: los mismos productos de esa lista del inventario. Aparece si hay alguno, o si se
     * abrió con `Revisar todo`.
     */
    REVIEW,
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

/** Un aviso tras aplicar o deshacer. */
sealed interface BulkMessage {
    /** Cambiaron [changed] precios con el lote [batchId], que el aviso ofrece deshacer. */
    data class Applied(val batchId: Uuid, val changed: Int) : BulkMessage

    /** Ninguno cambia ya: otra pantalla los cambió mientras tanto. */
    data object NothingChanged : BulkMessage

    /** Volvieron [reverted] precios y se dejaron [kept], cambiados después. */
    data class Undone(val reverted: Int, val kept: Int) : BulkMessage

    /** El lote ya no se puede deshacer: se deshizo o llegó otro después. */
    data object UndoGone : BulkMessage

    data class Failed(val outOfSpace: Boolean) : BulkMessage

    companion object {
        /**
         * El aviso de cada resultado de deshacer. `null` es una base que no abrió sin riesgo. Cada
         * resultado se nombra, sin rama por defecto, para que uno nuevo obligue a decidir aquí.
         */
        fun ofUndo(result: UndoBulkPriceResult?): BulkMessage = when (result) {
            is UndoBulkPriceResult.Undone -> Undone(result.reverted, result.kept)
            is UndoBulkPriceResult.NothingToRevert -> Undone(reverted = 0, kept = result.kept)
            UndoBulkPriceResult.NotUndoable -> UndoGone
            is UndoBulkPriceResult.StorageFailed -> Failed(result.failure.isOutOfSpace)
            null, UndoBulkPriceResult.UnknownTimeZone -> Failed(outOfSpace = false)
        }
    }
}

/**
 * Lo que la persona eligió y escribió. [percentText], [amountText], [marginText] y [stepText] van
 * como texto, para no perder lo escrito; [percentProblem] dice por qué el porcentaje no se puede
 * usar. [mode] es el cálculo, y [listIds] las listas que cambian: vacío es solo el precio de venta.
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
    val mode: BulkPriceMode = BulkPriceMode.PERCENT,
    val amountText: String = "",
    val marginText: String = "",
    val listIds: Set<Long> = emptySet(),
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
    val undo: BulkUndoPlan? = null,
    val confirmingUndo: Boolean = false,
    val undoing: Boolean = false,
    val lists: List<PriceList> = emptyList(),
    val reviewIds: Set<Long> = emptySet(),
) {
    /** Las listas que se ofrecen: con una sola no hay nada que elegir. */
    val offeredLists: List<PriceList> get() = lists.takeIf { it.size > 1 }.orEmpty()

    /** Si se ofrece `Deshacer la última`: hay un lote y al menos un precio que volvería. */
    val canUndo: Boolean get() = undo?.canUndo == true

    /** Cuántos precios cambian al aplicar. */
    val changing: Int get() = (content as? BulkPreviewContent.Ready)?.preview?.changes?.size ?: 0

    /** Si el porcentaje pasa del 50 %: protege de escribir `80` por `8`. */
    val highPercent: Boolean
        get() = input.mode == BulkPriceMode.PERCENT &&
            percent?.let { it.bp > HIGH_PERCENT_BP } == true

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

    data class SelectMode(val mode: BulkPriceMode) : BulkPriceAction

    /** El monto con signo que se suma a cada precio. */
    data class AmountText(val text: String) : BulkPriceAction

    /** El margen objetivo, en porcentaje. */
    data class MarginText(val text: String) : BulkPriceAction

    /** Agrega o quita una lista de las que cambian. */
    data class ToggleList(val listId: Long) : BulkPriceAction

    data class RemoveRow(val productId: Long) : BulkPriceAction

    data object Apply : BulkPriceAction

    data object MessageShown : BulkPriceAction

    data object Retry : BulkPriceAction

    /** `Deshacer la última`: pregunta antes. */
    data object OpenUndo : BulkPriceAction

    data object DismissUndo : BulkPriceAction

    data object ConfirmUndo : BulkPriceAction
}

/** Los separadores con que se leen el porcentaje y el paso escritos. */
data class BulkNumberFormat(
    val percentSymbols: NumberSymbols,
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
)

/**
 * La actualización masiva de precios: elegir qué productos y qué listas, el cálculo y cuánto, cómo
 * redondear, ver el resultado y aplicarlo todo de una vez.
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
    initialListId: Long? = null,
    pendingReview: Boolean = false,
    review: Boolean = false,
) : ViewModel() {
    private val input = MutableStateFlow(
        BulkPriceInput(
            scope = when {
                review -> BulkScopeKind.REVIEW
                pendingReview -> BulkScopeKind.PENDING_REVIEW
                else -> BulkScopeKind.ALL
            },
            listIds = setOfNotNull(initialListId),
        ),
    )

    /** Los de `Revisar`: si no se pueden leer, el chip no aparece y su alcance queda vacío. */
    private val reviewIds: Flow<Set<Long>> = operations.reviewIds().catch { emit(emptySet()) }
    private val extra = MutableStateFlow(Extra())
    private val attempts = MutableStateFlow(0)
    private var lastRequest: BulkPriceRequest? = null
    private var lastPreview: BulkPricePreview? = null
    private var lastContext = BulkPriceContext()

    /** Las listas y los precios propios: sin ellas, solo el precio de venta. */
    private val choices: Flow<BulkListChoices> =
        operations.listChoices().catch { emit(BulkListChoices()) }

    /** El catálogo, o `null` mientras no llega. Reintentar vuelve a leerlo. */
    private val catalog: Flow<List<Product>?> = attempts.flatMapLatest {
        operations.catalog().map<List<Product>, List<Product>?> { it }.onStart { emit(null) }
    }

    private val content: Flow<BulkPreviewContent> =
        combine(
            catalog,
            input.debounce(TYPING_PAUSE_MILLIS),
            operations.defaultStep(),
            choices,
            reviewIds,
        ) { products, typed, saved, lists, reviewed ->
            val request = requestOf(typed, saved, reviewed)
            lastRequest = request
            lastContext = lists.context
            when {
                products == null -> BulkPreviewContent.Loading

                products.none { !it.isArchived && it.salePrice > Money.ZERO } ->
                    BulkPreviewContent.NoPrices

                request == null -> BulkPreviewContent.NeedsInput

                else ->
                    BulkPreviewContent.Ready(
                        BulkPricePreviewer.preview(products, request, lists.context),
                    )
            }
        }
            .flowOn(computation)
            .onEach { shown -> lastPreview = (shown as? BulkPreviewContent.Ready)?.preview }
            .catch { emit(BulkPreviewContent.ReadError) }

    val uiState: StateFlow<BulkPriceUiState> =
        combine(
            input,
            content,
            // Las listas de los filtros no cierran la pantalla si fallan: el contenido ya muestra el
            // error de lectura con `Reintentar`, y los filtros quedan vacíos mientras tanto.
            combine(
                operations.categories().catch { emit(emptyList()) },
                operations.suppliers().catch { emit(emptyList()) },
                catalog.map {
                    it.orEmpty().filterNot(Product::isArchived)
                }.catch { emit(emptyList()) },
                operations.defaultStep().catch { emit(Money.ZERO) },
                reviewIds,
            ) { categories, suppliers, products, step, reviewed ->
                Lists(categories, suppliers, products, step, reviewed)
            },
            combine(
                extra,
                operations.undoOffer().catch {
                    emit(null)
                },
                choices,
            ) { more, offer, listed ->
                Triple(more, offer, listed)
            },
        ) { typed, shown, lists, (more, offer, listed) ->
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
                undo = offer,
                confirmingUndo = more.confirmingUndo && offer?.canUndo == true,
                undoing = more.undoing,
                lists = listed.writable,
                reviewIds = lists.reviewIds,
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
                    BulkScopeKind.PENDING_REVIEW, BulkScopeKind.REVIEW -> null
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

            is BulkPriceAction.SelectMode -> input.update { it.copy(mode = action.mode) }

            is BulkPriceAction.AmountText -> input.update { it.copy(amountText = action.text) }

            is BulkPriceAction.MarginText -> input.update { it.copy(marginText = action.text) }

            is BulkPriceAction.ToggleList ->
                input.update {
                    val ids = if (action.listId in it.listIds) {
                        it.listIds - action.listId
                    } else {
                        it.listIds + action.listId
                    }
                    it.copy(listIds = ids, removed = emptySet())
                }

            is BulkPriceAction.RemoveRow ->
                input.update { it.copy(removed = it.removed + action.productId) }

            BulkPriceAction.Apply -> apply()

            BulkPriceAction.MessageShown -> extra.update { it.copy(message = null) }

            BulkPriceAction.Retry -> attempts.update { it + 1 }

            BulkPriceAction.OpenUndo ->
                if (uiState.value.canUndo) extra.update { it.copy(confirmingUndo = true) }

            BulkPriceAction.DismissUndo -> extra.update { it.copy(confirmingUndo = false) }

            BulkPriceAction.ConfirmUndo -> undo()

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
            val message = when (val result = operations.apply(ids, request, lastContext)) {
                is ApplyBulkPriceResult.Applied -> BulkMessage.Applied(
                    result.batchId,
                    result.changed,
                )

                ApplyBulkPriceResult.NothingToChange -> BulkMessage.NothingChanged

                ApplyBulkPriceResult.InvalidRequest, ApplyBulkPriceResult.UnknownTimeZone,
                ApplyBulkPriceResult.CurrencyUnconfirmed,
                -> BulkMessage.Failed(outOfSpace = false)

                is ApplyBulkPriceResult.StorageFailed ->
                    BulkMessage.Failed(result.failure.isOutOfSpace)
            }
            if (message is BulkMessage.Applied) {
                input.update {
                    it.copy(
                        percentText = "",
                        amountText = "",
                        marginText = "",
                        removed = emptySet(),
                    )
                }
            }
            extra.update { it.copy(applying = false, message = message) }
        }
    }

    /** Deshace el lote ofrecido, una sola vez a la vez, y se queda en la pantalla con su aviso. */
    private fun undo() {
        val batchId = uiState.value.undo?.batchId
        if (extra.value.undoing || batchId == null) return
        extra.update { it.copy(confirmingUndo = false, undoing = true) }
        viewModelScope.launch {
            val message = BulkMessage.ofUndo(operations.undo(batchId))
            extra.update { it.copy(undoing = false, message = message) }
        }
    }

    /** La solicitud que pide lo escrito, o `null` si falta algo o no se puede leer. */
    private fun requestOf(
        typed: BulkPriceInput,
        saved: Money,
        reviewed: Set<Long>,
    ): BulkPriceRequest? {
        val adjustment = adjustmentOf(typed) ?: return null
        val scope = when (typed.scope) {
            BulkScopeKind.ALL -> BulkPriceScope.All

            BulkScopeKind.CATEGORY -> BulkPriceScope.Category(typed.categoryId ?: return null)

            BulkScopeKind.SUPPLIER -> BulkPriceScope.Supplier(typed.supplierId ?: return null)

            BulkScopeKind.PENDING_REVIEW -> BulkPriceScope.PendingReview

            BulkScopeKind.PICK ->
                if (typed.picked.isEmpty()) return null else BulkPriceScope.Picked(typed.picked)

            BulkScopeKind.REVIEW -> BulkPriceScope.Review(reviewed)
        }
        val step = when (typed.rounding) {
            BulkRounding.NONE -> Money.ZERO
            BulkRounding.SAVED_STEP -> saved
            BulkRounding.OTHER -> stepOf(typed.stepText) ?: return null
        }
        return BulkPriceRequest(scope, adjustment, step, typed.removed, typed.listIds)
    }

    /** El cálculo escrito para el modo elegido, si se puede usar. */
    private fun adjustmentOf(typed: BulkPriceInput): BulkPriceAdjustment? {
        val adjustment = when (typed.mode) {
            BulkPriceMode.PERCENT -> percentOf(typed)?.let { BulkPriceAdjustment.Percent(it) }

            BulkPriceMode.AMOUNT -> signedMoneyOf(typed.amountText)?.let {
                BulkPriceAdjustment.Amount(it)
            }

            BulkPriceMode.TARGET_MARGIN -> (
                NumberInputParser.percent(
                    typed.marginText,
                    numbers.percentSymbols,
                    BasisPoints.MAX_TARGET_MARGIN,
                ) as? NumberInput.Value
                )?.value?.let { BulkPriceAdjustment.TargetMargin(it) }

            // La cotización llega con la moneda de referencia: aquí no se ofrece todavía.
            BulkPriceMode.REFERENCE_RATE -> null
        }
        return adjustment?.takeIf { BulkPriceRules.isValid(it) }
    }

    /** Un monto con signo, distinto de cero y dentro del tope por unidad. */
    private fun signedMoneyOf(text: String): Money? = (
        NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        ) as? NumberInput.Value
        )?.value?.takeIf { it != Money.ZERO }

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
        val confirmingUndo: Boolean = false,
        val undoing: Boolean = false,
    )

    private data class Lists(
        val categories: List<Category>,
        val suppliers: List<Supplier>,
        val products: List<Product>,
        val step: Money,
        val reviewIds: Set<Long>,
    )

    companion object {
        /** La pausa al escribir tras la que se rearma la vista previa. */
        const val TYPING_PAUSE_MILLIS = 150L

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val PERCENT_SCALE = 100L
    }
}
