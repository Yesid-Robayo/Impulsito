package io.github.yesidrobayo.shopledger.ui.location

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockTransferRules
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferResult
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

/** En qué va la lectura del formulario. */
private sealed interface TransferLoad {
    data object Loading : TransferLoad

    /** Las ubicaciones vigentes y el inventario visto desde el origen, si ya hay uno. */
    data class Ready(val locations: List<Location>, val origin: InventoryOverview?) : TransferLoad

    data object Failed : TransferLoad
}

/** Lo que la pantalla muestra encima del borrador, que no se guarda con él. */
private data class TransferOverlay(
    val pickerOpen: Boolean = false,
    val query: String = "",
    val choosing: TransferEnd? = null,
    val discardAsked: Boolean = false,
    val notice: TransferNotice? = null,
    val endError: TransferEnd? = null,
    val rejected: Map<Long, TransferLineError> = emptyMap(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val closed: Boolean = false,
)

/** Cómo se leyó la cantidad de una línea. */
private sealed interface LineRead {
    data class Valid(val quantity: QuantityMilli) : LineRead

    data object Empty : LineRead

    data class Invalid(val error: TransferLineError) : LineRead
}

/**
 * El formulario de una transferencia: desde, hacia, las líneas y una nota, que se registran juntas
 * en una sola transacción.
 *
 * - **Nada se escribe hasta registrar.** Lo escrito vive en el estado guardado de la pantalla.
 * - **El origen puede quedar en negativo:** cada línea lo avisa con lo que quedaría, sin bloquear.
 * - **Solo productos con libro propio y sin lotes:** un servicio o uno hecho de componentes no
 *   tiene stock que mover, y esta versión todavía no transfiere por lote.
 * - **Un error nunca borra lo escrito.** Una cantidad vacía solo se marca al intentar registrar.
 *
 * @param initialFrom la ubicación de origen propuesta, como la abierta en la pantalla anterior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewTransferViewModel(
    private val initialFrom: Long?,
    private val locationsSource: () -> Flow<List<Location>>,
    private val stockAt: (Long) -> Flow<InventoryOverview>,
    private val register: suspend (StockTransferRequest) -> RegisterStockTransferResult,
    private val savedState: SavedStateHandle,
    private val quantitySymbols: NumberSymbols,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val draft = MutableStateFlow(TransferDraftStore.read(savedState))
    private val overlay = MutableStateFlow(TransferOverlay())
    private val attempts = MutableStateFlow(0)

    private val load: StateFlow<TransferLoad> = attempts.flatMapLatest {
        val origin = draft.map { it?.fromId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(null) else stockAt(id)
        }
        combine(locationsSource(), origin) { locations, stock ->
            TransferLoad.Ready(locations, stock) as TransferLoad
        }.catch { emit(TransferLoad.Failed) }
    }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransferLoad.Loading)

    val uiState: StateFlow<NewTransferUiState> = combine(load, draft, overlay, ::build)
        .flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            NewTransferUiState(),
        )

    /** Si el formulario terminó y la navegación vuelve. */
    val closed: StateFlow<Boolean> = overlay.map { it.closed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            // La primera lectura propone los extremos, si no venía un borrador.
            load.collect { current ->
                if (current is TransferLoad.Ready && draft.value == null) {
                    changeDraft { proposed(current.locations) }
                }
            }
        }
    }

    fun onAction(action: NewTransferAction) {
        when (action) {
            is NewTransferAction.OpenEnd -> overlay.update { it.copy(choosing = action.end) }

            is NewTransferAction.ChooseEnd -> chooseEnd(action.locationId)

            NewTransferAction.CloseEnd -> overlay.update { it.copy(choosing = null) }

            NewTransferAction.OpenPicker ->
                overlay.update { it.copy(pickerOpen = true, query = "") }

            is NewTransferAction.QueryChange -> overlay.update { it.copy(query = action.query) }

            NewTransferAction.ClosePicker -> overlay.update { it.copy(pickerOpen = false) }

            is NewTransferAction.AddProduct -> addProduct(action.productId)

            is NewTransferAction.QuantityChange -> {
                changeDraft { current ->
                    current.copy(
                        lines = current.lines.map {
                            if (it.key == action.key) it.copy(quantityText = action.text) else it
                        },
                    )
                }
                overlay.update { it.copy(rejected = it.rejected - action.key, notice = null) }
            }

            is NewTransferAction.RemoveLine -> changeDraft { current ->
                current.copy(lines = current.lines.filterNot { it.key == action.key })
            }

            is NewTransferAction.Note -> changeDraft { it.copy(note = action.text) }

            NewTransferAction.Register -> registerTransfer()

            // Si falló la lectura, se lee otra vez; si falló el registro, se registra otra vez.
            NewTransferAction.Retry ->
                if (load.value == TransferLoad.Failed) {
                    attempts.update { it + 1 }
                } else {
                    registerTransfer()
                }

            // La navegación abre los ajustes de la región: aquí no cambia nada.
            NewTransferAction.ChooseZone -> Unit

            NewTransferAction.Close -> close()

            NewTransferAction.ConfirmLeave ->
                overlay.update { it.copy(discardAsked = false, closed = true) }

            NewTransferAction.DismissDialog -> overlay.update { it.copy(discardAsked = false) }

            NewTransferAction.SavedShown -> overlay.update { it.copy(closed = true) }
        }
    }

    /** El origen propuesto, o la primera vigente, y la primera otra como destino. */
    private fun proposed(locations: List<Location>): TransferDraft {
        val from = locations.firstOrNull { it.id == initialFrom } ?: locations.firstOrNull()
        val to = locations.firstOrNull { it.id != from?.id }
        return TransferDraft(fromId = from?.id, toId = to?.id)
    }

    private fun chooseEnd(locationId: Long) {
        val end = overlay.value.choosing ?: return
        changeDraft {
            when (end) {
                TransferEnd.FROM -> it.copy(fromId = locationId)
                TransferEnd.TO -> it.copy(toId = locationId)
            }
        }
        overlay.update { it.copy(choosing = null, endError = null, notice = null) }
    }

    private fun addProduct(productId: Long) {
        changeDraft { current ->
            if (current.lines.any { it.productId == productId }) {
                current
            } else {
                current.copy(
                    lines = current.lines + TransferLineDraft(current.nextKey, productId, ""),
                    nextKey = current.nextKey + 1,
                )
            }
        }
        overlay.update { it.copy(pickerOpen = false, query = "", notice = null) }
    }

    private fun close() {
        if (snapshot().dirty) {
            overlay.update { it.copy(discardAsked = true) }
        } else {
            overlay.update { it.copy(closed = true) }
        }
    }

    private fun registerTransfer() {
        if (overlay.value.saving) return
        changeDraft { it.copy(showMissing = true) }
        val current = snapshot()
        val from = current.fromId
        val to = current.toId
        val blocked = current.lines.isEmpty() ||
            current.endError != null ||
            current.noteTooLong ||
            current.lines.any { it.error != null }
        if (blocked || from == null || to == null) return
        val lines = current.lines.map { line ->
            val read = readQuantity(line.quantityText, line.unit?.allowsFraction ?: false, true)
            StockTransferLineRequest(line.productId, (read as LineRead.Valid).quantity)
        }
        val keys = current.lines.map { it.key }
        overlay.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            val result = register(StockTransferRequest(from, to, lines, current.note))
            overlay.update { it.copy(saving = false) }
            when (result) {
                is RegisterStockTransferResult.Registered ->
                    overlay.update { it.copy(saved = true) }

                RegisterStockTransferResult.SameLocation ->
                    overlay.update { it.copy(endError = TransferEnd.TO) }

                RegisterStockTransferResult.LocationUnavailable -> {
                    val active = current.locations.map { it.id }
                    val end = if (from !in active) TransferEnd.FROM else TransferEnd.TO
                    overlay.update { it.copy(endError = end) }
                }

                is RegisterStockTransferResult.LineRejected -> {
                    val key = keys.getOrNull(result.index) ?: return@launch
                    overlay.update {
                        it.copy(rejected = it.rejected + (key to rejectionError(result.reason)))
                    }
                }

                RegisterStockTransferResult.UnknownTimeZone ->
                    overlay.update { it.copy(notice = TransferNotice.UnknownZone) }

                is RegisterStockTransferResult.StorageFailed -> overlay.update {
                    it.copy(notice = TransferNotice.SaveFailed(result.failure.isOutOfSpace))
                }

                // El formulario ya revisa líneas, tope y nota: si llega, no se guardó nada.
                RegisterStockTransferResult.EmptyTransfer,
                RegisterStockTransferResult.TooManyLines,
                RegisterStockTransferResult.NoteTooLong,
                -> overlay.update { it.copy(notice = TransferNotice.SaveFailed(false)) }
            }
        }
    }

    /** El estado de ahora, armado en el acto: no espera a que la pantalla lo recoja. */
    private fun snapshot(): NewTransferUiState = build(load.value, draft.value, overlay.value)

    private fun build(
        load: TransferLoad,
        draft: TransferDraft?,
        overlay: TransferOverlay,
    ): NewTransferUiState {
        val ready = load as? TransferLoad.Ready
        val current = draft ?: TransferDraft()
        val movable = ready?.origin?.let(::movableProducts).orEmpty().associateBy { it.id }
        val units = ready?.origin?.units.orEmpty()
        val lines = current.lines.map { line ->
            val product = movable[line.productId]
            val unit = product?.let { units[it.details.baseUnitId] }
            val read = readQuantity(
                line.quantityText,
                unit?.allowsFraction ?: false,
                current.showMissing,
            )
            val error = when {
                ready?.origin != null && product == null -> TransferLineError.UNAVAILABLE
                read is LineRead.Invalid -> read.error
                else -> overlay.rejected[line.key]
            }
            val left = (read as? LineRead.Valid)?.let { valid ->
                product?.let { it.onHand - valid.quantity }?.takeIf { it < QuantityMilli.ZERO }
            }
            TransferLineUi(
                key = line.key,
                productId = line.productId,
                name = product?.details?.name.orEmpty(),
                unit = unit,
                quantityText = line.quantityText,
                error = error,
                leftAtOrigin = left,
            )
        }
        val sameEnds = current.fromId != null && current.fromId == current.toId
        return NewTransferUiState(
            loading = load == TransferLoad.Loading,
            loadFailed = load == TransferLoad.Failed,
            locations = ready?.locations.orEmpty(),
            fromId = current.fromId,
            toId = current.toId,
            endError = overlay.endError ?: TransferEnd.TO.takeIf { sameEnds },
            lines = lines,
            note = current.note,
            noteTooLong = current.note.trim().length > StockTransferRules.NOTE_MAX,
            showNoLines = current.showMissing && current.lines.isEmpty(),
            canAdd = current.lines.size < Limit.DOCUMENT_LINES.max,
            picker = if (overlay.pickerOpen) picker(movable.values, current, overlay) else null,
            choosing = overlay.choosing,
            notice = overlay.notice,
            saving = overlay.saving,
            saved = overlay.saved,
            discardAsked = overlay.discardAsked,
            dirty = current.lines.isNotEmpty() || current.note.isNotBlank(),
        )
    }

    /** Lo que se puede mover: con libro propio, sin lotes y vigente, por nombre. */
    private fun movableProducts(origin: InventoryOverview): List<Product> =
        origin.tracked.filterNot { it.details.tracksLots }

    private fun picker(
        movable: Collection<Product>,
        current: TransferDraft,
        overlay: TransferOverlay,
    ): TransferPicker {
        val added = current.lines.map { it.productId }.toSet()
        val wanted = TextNormalizer.normalize(overlay.query.trim())
        val units = (load.value as? TransferLoad.Ready)?.origin?.units.orEmpty()
        val options = movable
            .filter { it.id !in added }
            .filter { wanted.isEmpty() || wanted in it.details.normalizedName }
            .map {
                TransferOption(it.id, it.details.name, it.onHand, units[it.details.baseUnitId])
            }
        return TransferPicker(overlay.query, options)
    }

    private fun readQuantity(
        text: String,
        allowsFraction: Boolean,
        showMissing: Boolean,
    ): LineRead =
        when (
            val read = NumberInputParser.quantity(text, quantitySymbols, Limit.LINE_QUANTITY_MILLI)
        ) {
            NumberInput.Blank -> if (showMissing) {
                LineRead.Invalid(TransferLineError.NOT_A_NUMBER)
            } else {
                LineRead.Empty
            }

            is NumberInput.Value -> when {
                read.value.milli <= 0L -> LineRead.Invalid(TransferLineError.ZERO)
                !allowsFraction && read.value.milli % MILLI_PER_UNIT != 0L ->
                    LineRead.Invalid(TransferLineError.DECIMALS)
                else -> LineRead.Valid(read.value)
            }

            NumberInput.Malformed -> LineRead.Invalid(TransferLineError.NOT_A_NUMBER)

            is NumberInput.TooManyDecimals -> LineRead.Invalid(TransferLineError.DECIMALS)

            is NumberInput.TooLarge -> LineRead.Invalid(TransferLineError.TOO_HIGH)
        }

    private fun changeDraft(change: (TransferDraft) -> TransferDraft) {
        val next = change(draft.value ?: TransferDraft())
        TransferDraftStore.write(savedState, next)
        draft.value = next
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MILLI_PER_UNIT = 1_000L

        fun rejectionError(reason: StockTransferLineRejection): TransferLineError = when (reason) {
            StockTransferLineRejection.INVALID_QUANTITY -> TransferLineError.NOT_A_NUMBER
            StockTransferLineRejection.QUANTITY_TOO_LARGE -> TransferLineError.TOO_HIGH
            StockTransferLineRejection.PRODUCT_NOT_FOUND,
            StockTransferLineRejection.PRODUCT_ARCHIVED,
            StockTransferLineRejection.NOT_TRACKED,
            StockTransferLineRejection.LOTS_NOT_SUPPORTED,
            -> TransferLineError.UNAVAILABLE
        }
    }
}
