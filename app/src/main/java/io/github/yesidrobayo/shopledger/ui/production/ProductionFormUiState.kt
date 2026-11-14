package io.github.yesidrobayo.shopledger.ui.production

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit

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

    data class NoteChange(val text: String) : ProductionFormAction

    data object Register : ProductionFormAction

    data object Retry : ProductionFormAction

    data object CreateRecipe : ProductionFormAction

    data object Close : ProductionFormAction

    data object ConfirmLeave : ProductionFormAction

    data object DismissDialog : ProductionFormAction

    data object RegisteredShown : ProductionFormAction
}
