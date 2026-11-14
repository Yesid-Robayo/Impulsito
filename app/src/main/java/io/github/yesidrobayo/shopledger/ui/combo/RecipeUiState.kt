package io.github.yesidrobayo.shopledger.ui.combo

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit

/**
 * Lo que muestra la receta de un combo. Todo sale del ViewModel: la pantalla solo dibuja.
 *
 * @param loading la receta todavía no llega: la pantalla dibuja un esqueleto si tarda.
 * @param loadFailed la lectura falló: se dice, con `Reintentar`.
 * @param missing el combo ya no existe, o dejó de armarse con componentes.
 * @param rows las filas de la receta, en el orden en que se armó.
 * @param unitCost lo que cuesta hoy una unidad del combo, o `null` si no se puede calcular.
 * @param profit el precio menos [unitCost], o `null` sin uno de los dos.
 * @param available cuántos combos enteros alcanzan, o `null` si ningún componente lleva stock.
 * @param limitingName el componente que alcanza para menos, para nombrarlo.
 * @param hasUncosted alguna hoja cuesta cero: el margen puede verse mejor de lo que es.
 * @param canAdd todavía cabe otro componente.
 */
@Immutable
data class RecipeUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val missing: Boolean = false,
    val comboName: String = "",
    val salePrice: Money? = null,
    val rows: List<RecipeRowUi> = emptyList(),
    val unitCost: Money? = null,
    val profit: Money? = null,
    val margin: Margin = Margin.NoData,
    val available: Long? = null,
    val limitingName: String? = null,
    val hasUncosted: Boolean = false,
    val canAdd: Boolean = true,
    val picker: RecipePicker? = null,
    val discardAsked: Boolean = false,
    val notice: RecipeNotice? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val dirty: Boolean = false,
)

/**
 * Una fila de la receta en pantalla.
 *
 * @param key la identidad estable de la fila en la lista.
 * @param unit la unidad base del componente, con la que se escribe la cantidad.
 * @param lineCost lo que cuesta la fila en una unidad del combo, o `null` si no se puede calcular.
 * @param tracked el componente descuenta stock; un servicio no.
 * @param archived el componente está archivado: sigue en la receta, pero no se elige otra vez.
 */
@Immutable
data class RecipeRowUi(
    val key: Long,
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val quantityText: String,
    val lineCost: Money?,
    val tracked: Boolean,
    val archived: Boolean,
    val error: RecipeRowError? = null,
)

/** Por qué una fila no se puede guardar. La pantalla elige el texto. */
sealed interface RecipeRowError {
    /** Lo escrito no es un número, o es cero. */
    data object NotANumber : RecipeRowError

    /** La cantidad pasa de lo que la app puede guardar. */
    data object TooHigh : RecipeRowError

    /** El componente ya contiene a este combo: se nombra el combo. */
    data class Cycle(val comboName: String) : RecipeRowError

    /** Con este componente habría un tercer nivel de combos. */
    data object TooDeep : RecipeRowError

    /** El componente ya no se puede elegir: se quita de la receta. */
    data object Unavailable : RecipeRowError
}

/** La hoja para agregar un componente: lo buscado y los productos que coinciden. */
@Immutable
data class RecipePicker(val query: String, val options: List<RecipeOption>)

/** Un producto que se puede agregar a la receta. */
data class RecipeOption(val id: Long, val name: String)

/** Los avisos de la receta, que no borran nada de lo armado. */
sealed interface RecipeNotice {
    /** No se pudo guardar: se reintenta con lo mismo. [outOfSpace] dice si falta espacio. */
    data class SaveFailed(val outOfSpace: Boolean) : RecipeNotice
}

/** Lo que la persona hace en la receta. */
sealed interface RecipeAction {
    data object OpenPicker : RecipeAction

    data class QueryChange(val query: String) : RecipeAction

    data object ClosePicker : RecipeAction

    data class AddComponent(val productId: Long) : RecipeAction

    data class QuantityChange(val key: Long, val text: String) : RecipeAction

    data class RemoveComponent(val key: Long) : RecipeAction

    data object Save : RecipeAction

    data object Retry : RecipeAction

    /** Ir al formulario del producto: lo resuelve la navegación. */
    data object EditProduct : RecipeAction

    data object Close : RecipeAction

    data object ConfirmLeave : RecipeAction

    data object DismissDialog : RecipeAction

    /** El mensaje de guardado ya se mostró: la pantalla termina. */
    data object SavedShown : RecipeAction
}
