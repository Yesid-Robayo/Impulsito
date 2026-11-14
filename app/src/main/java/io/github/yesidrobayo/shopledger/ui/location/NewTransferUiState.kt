package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.Location

/** Por qué una línea no se puede registrar. La pantalla elige el texto. */
enum class TransferLineError {
    /** Lo escrito no es un número, o falta. */
    NOT_A_NUMBER,

    /** Es cero o menos. */
    ZERO,

    /** Tiene decimales en una unidad que no los admite. */
    DECIMALS,

    /** Pasa de lo que la app puede guardar en una línea. */
    TOO_HIGH,

    /** El producto ya no se puede transferir: se archivó o dejó de llevar stock. */
    UNAVAILABLE,
}

/**
 * Una línea del formulario: el producto, cuánto se mueve tal como se escribió, y [leftAtOrigin],
 * lo
 * que quedaría en el origen si es menos que cero, para avisarlo sin bloquear.
 */
@Immutable
data class TransferLineUi(
    val key: Long,
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val quantityText: String,
    val error: TransferLineError? = null,
    val leftAtOrigin: QuantityMilli? = null,
)

/** Un producto que se puede agregar, con lo que hay de él en el origen. */
@Immutable
data class TransferOption(
    val id: Long,
    val name: String,
    val onHand: QuantityMilli,
    val unit: MeasureUnit?,
)

/** La hoja para agregar un producto: lo buscado y lo que coincide. */
@Immutable
data class TransferPicker(val query: String, val options: List<TransferOption>)

/** Cuál de los dos extremos se está eligiendo. */
enum class TransferEnd {
    FROM,
    TO,
}

/** Los avisos del formulario, que no borran nada de lo escrito. */
@Immutable
sealed interface TransferNotice {
    /** No se pudo registrar: se reintenta con lo mismo. */
    data class SaveFailed(val outOfSpace: Boolean) : TransferNotice

    /** Este teléfono no conoce la zona del negocio: no hay fecha que darle. */
    data object UnknownZone : TransferNotice
}

/**
 * El formulario de una transferencia. Todo sale del ViewModel: la pantalla solo dibuja.
 *
 * @param fromId y [toId] los extremos elegidos; [endError] marca el que hay que cambiar, porque
 * es el
 *   mismo que el otro o porque ya no se puede elegir.
 * @param showNoLines se tocó registrar sin líneas: se dice qué falta.
 * @param dirty hay algo escrito: salir pregunta antes de descartarlo.
 */
@Immutable
data class NewTransferUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val locations: List<Location> = emptyList(),
    val fromId: Long? = null,
    val toId: Long? = null,
    val endError: TransferEnd? = null,
    val lines: List<TransferLineUi> = emptyList(),
    val note: String = "",
    val noteTooLong: Boolean = false,
    val showNoLines: Boolean = false,
    val canAdd: Boolean = true,
    val picker: TransferPicker? = null,
    val choosing: TransferEnd? = null,
    val notice: TransferNotice? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val discardAsked: Boolean = false,
    val dirty: Boolean = false,
)

/** Lo que la persona hace en el formulario. */
sealed interface NewTransferAction {
    data class OpenEnd(val end: TransferEnd) : NewTransferAction

    data class ChooseEnd(val locationId: Long) : NewTransferAction

    data object CloseEnd : NewTransferAction

    data object OpenPicker : NewTransferAction

    data class QueryChange(val query: String) : NewTransferAction

    data object ClosePicker : NewTransferAction

    data class AddProduct(val productId: Long) : NewTransferAction

    data class QuantityChange(val key: Long, val text: String) : NewTransferAction

    data class RemoveLine(val key: Long) : NewTransferAction

    data class Note(val text: String) : NewTransferAction

    data object Register : NewTransferAction

    data object Retry : NewTransferAction

    data object ChooseZone : NewTransferAction

    data object Close : NewTransferAction

    data object ConfirmLeave : NewTransferAction

    data object DismissDialog : NewTransferAction

    /** El aviso de registrada ya se mostró: la pantalla termina. */
    data object SavedShown : NewTransferAction
}

/**
 * La transferencia a medio escribir, tal cual. Nada se escribe en la base hasta registrarla: vive
 * en
 * el estado guardado de la pantalla, que sobrevive a una llamada y a que Android cierre el
 * proceso.
 */
data class TransferDraft(
    val fromId: Long? = null,
    val toId: Long? = null,
    val lines: List<TransferLineDraft> = emptyList(),
    val note: String = "",
    val nextKey: Long = 1L,
    val showMissing: Boolean = false,
)

/** Una línea tal como se escribió. */
data class TransferLineDraft(val key: Long, val productId: Long, val quantityText: String)

/**
 * Guarda el borrador en el estado guardado, con tipos que caben en un `Bundle`. Si las partes no
 * cuadran entre sí, se descarta y se empieza de nuevo.
 */
internal object TransferDraftStore {
    private const val ENDS = "transfer.ends"
    private const val LINES = "transfer.lines"
    private const val TEXTS = "transfer.texts"
    private const val NOTE = "transfer.note"
    private const val NEXT_KEY = "transfer.nextKey"
    private const val SHOW_MISSING = "transfer.showMissing"

    /** Clave y producto de cada línea. */
    private const val FIELDS = 2

    /** Un extremo sin elegir. Los id de la base empiezan en 1. */
    private const val NONE = 0L

    fun read(state: SavedStateHandle): TransferDraft? {
        val ends = state.get<LongArray>(ENDS) ?: return null
        val numbers = state.get<LongArray>(LINES) ?: return null
        val texts = state.get<ArrayList<String>>(TEXTS) ?: return null
        val count = numbers.size / FIELDS
        if (ends.size != 2 || numbers.size % FIELDS != 0 || texts.size != count) return null
        return TransferDraft(
            fromId = ends[0].takeIf { it != NONE },
            toId = ends[1].takeIf { it != NONE },
            lines = (0 until count).map { index ->
                TransferLineDraft(
                    key = numbers[index * FIELDS],
                    productId = numbers[index * FIELDS + 1],
                    quantityText = texts[index],
                )
            },
            note = state.get<String>(NOTE).orEmpty(),
            nextKey = state.get<Long>(NEXT_KEY) ?: (count + 1L),
            showMissing = state.get<Boolean>(SHOW_MISSING) ?: false,
        )
    }

    fun write(state: SavedStateHandle, draft: TransferDraft) {
        val numbers = LongArray(draft.lines.size * FIELDS)
        draft.lines.forEachIndexed { index, line ->
            numbers[index * FIELDS] = line.key
            numbers[index * FIELDS + 1] = line.productId
        }
        state[ENDS] = longArrayOf(draft.fromId ?: NONE, draft.toId ?: NONE)
        state[LINES] = numbers
        state[TEXTS] = ArrayList(draft.lines.map { it.quantityText })
        state[NOTE] = draft.note
        state[NEXT_KEY] = draft.nextKey
        state[SHOW_MISSING] = draft.showMissing
    }
}
