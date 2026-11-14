package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentSubject

/** Cómo ajusta la persona: con lo que contó, con una salida por motivo o con el stock inicial. */
enum class AdjustmentMode {
    /** Escribe la cantidad real, y la diferencia con el libro es el ajuste. */
    COUNT,

    /** Escribe lo que sale, con su motivo; con otro motivo también puede ser una entrada. */
    CHANGE,

    /** Escribe el stock con que empezó y su costo. Solo sin movimientos vigentes. */
    OPENING,
}

/** Los campos del formulario que pueden mostrar un error. */
enum class AdjustmentField { QUANTITY, UNIT_COST, NOTE }

/** Por qué un campo no se puede guardar, dicho de forma que la persona lo arregle. */
sealed interface AdjustmentProblem {
    data object NotANumber : AdjustmentProblem

    data object TooHigh : AdjustmentProblem

    data object Zero : AdjustmentProblem

    /** La unidad no admite fracciones. */
    data object WholeUnitsOnly : AdjustmentProblem

    /** Tiene más decimales que [allowed]. */
    data class TooManyDecimals(val allowed: Int) : AdjustmentProblem

    /** El motivo pide una nota que diga lo que pasó. */
    data object NoteRequired : AdjustmentProblem

    data class TooLong(val maxLength: Int) : AdjustmentProblem
}

/**
 * Lo que dejaría el ajuste escrito, en vivo: [left] queda en stock, [difference] es lo que cambia y
 * [value] lo que vale, con su signo; `null` si no se puede calcular.
 */
@Immutable
data class AdjustmentPreview(
    val left: QuantityMilli,
    val difference: QuantityMilli,
    val value: Money?,
)

/** Lo que muestra la pantalla debajo de su barra. */
sealed interface StockAdjustmentContent {
    /** Leyendo el producto. El esqueleto solo aparece si tarda. */
    data object Loading : StockAdjustmentContent

    /**
     * El producto no existe, está archivado o no lleva stock: no hay nada que ajustar. Casi nunca
     * se ve, porque ninguna pantalla ofrece ajustar esos productos.
     */
    data object Unavailable : StockAdjustmentContent

    /** La lectura falló: se dice, con `Reintentar`. */
    data object ReadFailed : StockAdjustmentContent

    /** El producto, listo para ajustar. */
    data class Ready(val subject: StockAdjustmentSubject) : StockAdjustmentContent
}

/** Cómo terminó guardar: la ruta lo dice con un mensaje y cierra. */
sealed interface AdjustmentOutcome {
    /** El ajuste quedó guardado y el producto quedó en [onHandAfter]. */
    data class Saved(val onHandAfter: QuantityMilli) : AdjustmentOutcome

    /** Lo contado era lo que decía el libro: no se guardó nada. */
    data object NoDifference : AdjustmentOutcome
}

/**
 * El estado del ajuste de stock. Los campos guardan lo escrito tal cual, y un error, del campo o de
 * la base, nunca lo borra.
 */
@Immutable
data class StockAdjustmentUiState(
    val content: StockAdjustmentContent = StockAdjustmentContent.Loading,
    val mode: AdjustmentMode = AdjustmentMode.COUNT,
    val quantityText: String = "",
    val reason: StockAdjustmentReason = StockAdjustmentReason.DAMAGED,
    val increase: Boolean = false,
    val unitCostText: String = "",
    val noteText: String = "",
    val preview: AdjustmentPreview? = null,
    val errors: Map<AdjustmentField, AdjustmentProblem> = emptyMap(),
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val outcome: AdjustmentOutcome? = null,
    val confirmingDiscard: Boolean = false,
    val closed: Boolean = false,
    val locations: List<Location> = emptyList(),
    val locationId: Long? = null,
) {
    /** Si la persona ya escribió algo: cerrar pregunta antes de descartarlo. */
    val dirty: Boolean
        get() = quantityText.isNotBlank() || unitCostText.isNotBlank() || noteText.isNotBlank()

    /** Si el motivo elegido admite una entrada: entonces se elige el sentido. */
    val showsDirection: Boolean
        get() = mode == AdjustmentMode.CHANGE && reason.allowsIncrease
}

/** Lo que la persona puede hacer en el ajuste de stock. */
sealed interface StockAdjustmentAction {
    /** Vuelve a leer el producto tras una falla de lectura. */
    data object Retry : StockAdjustmentAction

    /** Eligió la ubicación que se ajusta. */
    data class LocationChanged(val locationId: Long) : StockAdjustmentAction

    data class ModeChanged(val mode: AdjustmentMode) : StockAdjustmentAction

    data class QuantityChanged(val text: String) : StockAdjustmentAction

    data class ReasonChanged(val reason: StockAdjustmentReason) : StockAdjustmentAction

    /** Eligió entrada o salida, con un motivo que admite las dos. */
    data class DirectionChanged(val increase: Boolean) : StockAdjustmentAction

    data class UnitCostChanged(val text: String) : StockAdjustmentAction

    data class NoteChanged(val text: String) : StockAdjustmentAction

    data object Save : StockAdjustmentAction

    /** Cerrar: con algo escrito, pregunta antes de descartarlo. */
    data object Close : StockAdjustmentAction

    /** Siguió escribiendo en vez de descartar. */
    data object KeepEditing : StockAdjustmentAction

    /** Confirmó que descarta lo escrito. */
    data object Discard : StockAdjustmentAction

    /** Quiere corregir el costo desde el aviso sin costo: lo resuelve la navegación. */
    data object FixCost : StockAdjustmentAction
}
