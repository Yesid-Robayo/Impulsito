package io.github.yesidrobayo.shopledger.ui.production

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import kotlinx.datetime.DayOfWeek

/**
 * Lo que muestra el formulario de una producción.
 *
 * @param loading el catálogo todavía no llega: la pantalla dibuja un esqueleto si tarda.
 * @param loadFailed la lectura falló: se dice, con `Reintentar`.
 * @param noRecipes ningún producto se puede producir: la pantalla ofrece crear una receta.
 * @param product lo que se produce, si ya se eligió.
 * @param inputs los insumos de la receta para la cantidad escrita, editables para esta tanda.
 * @param figures lo que cuesta la tanda y cómo deja el costo, si todo se puede leer.
 * @param picker la hoja para elegir el producto, si está abierta.
 * @param outputLot el lote al que entra lo producido, solo si el producto se controla por lotes.
 * @param today hoy y [weekStartDay] para elegir el vencimiento; faltan si la zona no se reconoce.
 */
@Immutable
data class ProductionFormUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val noRecipes: Boolean = false,
    val product: ProductionProductUi? = null,
    val quantityText: String = "",
    val quantityError: ProductionFieldError? = null,
    val inputs: List<ProductionInputUi> = emptyList(),
    val figures: ProductionFiguresUi? = null,
    val noteText: String = "",
    val noteTooLong: Boolean = false,
    val picker: ProductionPicker? = null,
    val saving: Boolean = false,
    val notice: ProductionNotice? = null,
    val discardAsked: Boolean = false,
    val registered: Boolean = false,
    val outputLot: ProductionLotUi? = null,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
) {
    /** Si la persona escribió algo que se perdería al salir. */
    val dirty: Boolean
        get() = product != null || quantityText.isNotEmpty() || noteText.isNotEmpty()
}

/** Lo que se produce: su nombre y la unidad en que se escribe la cantidad. */
@Immutable
data class ProductionProductUi(val id: Long, val name: String, val unit: MeasureUnit?)

/**
 * Un insumo de la tanda: su nombre, su unidad, la cantidad escrita, lo que hay y, si no alcanza,
 * cuánto quedaría. [tracked] es `false` en un servicio, que no tiene stock que mostrar.
 *
 * En un insumo con lotes, [lots] son los que se pueden elegir, en el orden de salida, y
 * [chosenLotId] el elegido, o `null` para que salga primero el que vence antes.
 */
@Immutable
data class ProductionInputUi(
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val quantityText: String,
    val tracked: Boolean,
    val available: QuantityMilli,
    val leftAfter: QuantityMilli?,
    val error: ProductionFieldError?,
    val lots: LotChoices? = null,
    val chosenLotId: Long? = null,
)

/**
 * El lote al que entra lo producido, tal como se escribió.
 *
 * @param code el código, vacío sin lote: entonces lo producido entra al lote sin asignar.
 * @param expiry el vencimiento escrito para un lote nuevo, o `null` sin vencimiento.
 * @param existing si el código es el de un lote que el producto ya tiene: lo producido suma a ese
 *   lote, y su vencimiento, [existingExpiry], se muestra para leer.
 * @param tooLong el código pasa del largo máximo.
 */
@Immutable
data class ProductionLotUi(
    val code: String,
    val expiry: BusinessDate?,
    val existing: Boolean,
    val existingExpiry: BusinessDate?,
    val tooLong: Boolean,
)

/**
 * Las cifras de la tanda: el costo total, el de cada unidad y el costo promedio antes y después.
 * [hasUncosted] avisa que algún insumo cuesta cero.
 */
@Immutable
data class ProductionFiguresUi(
    val totalCost: Money,
    val unitCost: Money,
    val averageBefore: Money,
    val averageAfter: Money,
    val hasUncosted: Boolean,
)

/** Por qué una cantidad no se puede usar. */
sealed interface ProductionFieldError {
    data object Missing : ProductionFieldError

    data object NotANumber : ProductionFieldError

    data object TooHigh : ProductionFieldError

    data object FractionNotAllowed : ProductionFieldError
}

/** La hoja para elegir el producto: lo buscado y los productos que coinciden. */
@Immutable
data class ProductionPicker(val query: String, val options: List<ProductionOption>)

/** Un producto con receta que se puede producir. */
@Immutable
data class ProductionOption(val id: Long, val name: String)

/** Un aviso sobre el botón principal. */
sealed interface ProductionNotice {
    /** No se pudo registrar: lo escrito se conserva, y se puede reintentar. */
    data class RegisterFailed(val outOfSpace: Boolean) : ProductionNotice

    /** Lo producido pasaría del máximo de stock de [productName]. */
    data class StockCap(val productName: String) : ProductionNotice
}

/** Lo que la persona hace en el formulario de una producción. */
sealed interface ProductionFormAction {
    data object OpenPicker : ProductionFormAction

    data class QueryChange(val query: String) : ProductionFormAction

    data object ClosePicker : ProductionFormAction

    data class ChooseProduct(val productId: Long) : ProductionFormAction

    data class QuantityChange(val text: String) : ProductionFormAction

    data class InputChange(val productId: Long, val text: String) : ProductionFormAction

    /** Eligió el lote del que sale el insumo [productId], o `null` para volver a lo automático. */
    data class ChooseInputLot(val productId: Long, val lotId: Long?) : ProductionFormAction

    /** Escribió el código del lote al que entra lo producido. */
    data class LotCodeChange(val text: String) : ProductionFormAction

    /** Eligió el vencimiento del lote nuevo, o `null` para ninguno. */
    data class LotExpiryChange(val date: BusinessDate?) : ProductionFormAction

    data class NoteChange(val text: String) : ProductionFormAction

    data object Register : ProductionFormAction

    data object Retry : ProductionFormAction

    data object CreateRecipe : ProductionFormAction

    data object Close : ProductionFormAction

    data object ConfirmLeave : ProductionFormAction

    data object DismissDialog : ProductionFormAction

    data object RegisteredShown : ProductionFormAction
}
