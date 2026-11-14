package io.github.yesidrobayo.shopledger.ui.variant

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.ui.product.FieldProblem
import io.github.yesidrobayo.shopledger.ui.product.ProductEditField

/** Una combinación de valores, uno por eje, ya recortados: `M` y `Rojo`. */
data class VariantCombo(val first: String, val second: String?) {
    /** Cómo se compara con otra: sin mayúsculas ni tildes, como la base. */
    val key: Pair<String, String?>
        get() = TextNormalizer.normalize(first) to second?.let(TextNormalizer::normalize)

    /** Los valores unidos con el separador de los nombres de variante, para leerlos. */
    val label: String get() = listOfNotNull(first, second).joinToString(" · ")
}

/** Lo que la persona escribe de una variante nueva, tal como lo escribió. */
data class VariantInput(
    val price: String = "",
    val cost: String = "",
    val initialStock: String = "",
    val minStock: String = "",
    val barcode: String = "",
)

/** Un campo de la fila de una variante o de `Aplicar a todas`. */
enum class VariantField { PRICE, COST, INITIAL_STOCK, MIN_STOCK, BARCODE }

/** Uno de los dos ejes. */
enum class VariantAxis { FIRST, SECOND }

/** Por qué un valor o un eje no se puede usar así. */
enum class VariantTextProblem { EMPTY, TOO_LONG, REPEATED }

/** Lo que impide crear una combinación, aparte de sus campos. */
enum class VariantComboProblem { REPEATED, NAME_TOO_LONG }

/** Una variante que ya existe: se muestra para leer, y su combinación no se vuelve a crear. */
data class ExistingVariant(
    val id: Long,
    val name: String,
    val combo: VariantCombo,
    val salePrice: Money,
    val onHand: QuantityMilli,
    val archived: Boolean,
)

/** Lo que se avisa arriba del botón, sin borrar nada de lo escrito. */
sealed interface VariantsNotice {
    data class SaveFailed(val outOfSpace: Boolean) : VariantsNotice

    /** Hay precio o costo y la moneda del negocio sigue sin confirmar. */
    data object CurrencyUnconfirmed : VariantsNotice

    /** El producto tiene stock propio: se ajusta a cero antes de repartirlo en variantes. */
    data object ParentHasStock : VariantsNotice

    /** El producto ya no existe, está archivado, es una variante o es un combo. */
    data object ParentNotAllowed : VariantsNotice

    /** No queda ninguna combinación marcada para crear. */
    data object NothingSelected : VariantsNotice
}

/**
 * La pantalla de variantes de un producto: sus ejes con sus valores, la matriz de combinaciones,
 * lo que se aplica a todas y una fila por variante nueva.
 *
 * Con variantes ya creadas, los ejes quedan fijos y sus combinaciones salen como creadas: solo se
 * agregan valores y combinaciones nuevas. [secondAxis] `null` es que el segundo eje no se abrió.
 */
data class VariantsUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val parentName: String = "",
    val unit: MeasureUnit? = null,
    val tracksStock: Boolean = true,
    val firstAxis: String = "",
    val secondAxis: String? = null,
    val firstValues: List<String> = emptyList(),
    val secondValues: List<String> = emptyList(),
    val newFirstValue: String = "",
    val newSecondValue: String = "",
    val axisProblems: Map<VariantAxis, VariantTextProblem> = emptyMap(),
    val valueProblems: Map<VariantAxis, VariantTextProblem> = emptyMap(),
    val existing: List<ExistingVariant> = emptyList(),
    val unchecked: Set<Pair<String, String?>> = emptySet(),
    val applyAll: VariantInput = VariantInput(),
    val inputs: Map<Pair<String, String?>, VariantInput> = emptyMap(),
    val prices: Map<Pair<String, String?>, Money> = emptyMap(),
    val fieldProblems: Map<Pair<String, String?>, Map<VariantField, FieldProblem>> = emptyMap(),
    val comboProblems: Map<Pair<String, String?>, VariantComboProblem> = emptyMap(),
    val expanded: Pair<String, String?>? = null,
    val saving: Boolean = false,
    val notice: VariantsNotice? = null,
    val confirmingDiscard: Boolean = false,
    val closed: Boolean = false,
    val createdCount: Int? = null,
) {
    /** Con variantes creadas, los ejes ya no cambian: una variante no cambia de identidad. */
    val axesLocked: Boolean get() = existing.isNotEmpty()

    /** Cada combinación de los valores, en el orden de la matriz: primer eje, luego el segundo. */
    val allCombos: List<VariantCombo>
        get() {
            val seconds: List<String?> = if (secondAxis == null) listOf(null) else secondValues
            return firstValues.flatMap { first -> seconds.map { VariantCombo(first, it) } }
        }

    /** Las combinaciones que ya son variantes, por su clave. */
    val existingKeys: Set<Pair<String, String?>> get() = existing.mapTo(mutableSetOf()) {
        it.combo.key
    }

    /** Las combinaciones que se van a crear: las marcadas que todavía no existen. */
    val newCombos: List<VariantCombo>
        get() = allCombos.filter { it.key !in existingKeys && it.key !in unchecked }

    /** Si hay algo escrito que se perdería al cerrar. */
    val dirty: Boolean
        get() = newFirstValue.isNotBlank() || newSecondValue.isNotBlank() ||
            newCombos.isNotEmpty() || applyAll != VariantInput()

    /** El error de [field] en la fila de [combo], o `null`. */
    fun problemOf(combo: VariantCombo, field: VariantField): FieldProblem? =
        fieldProblems[combo.key]?.get(field)

    companion object {
        /** El campo del formulario de producto que da el texto de cada error. */
        fun productFieldOf(field: VariantField): ProductEditField = when (field) {
            VariantField.PRICE -> ProductEditField.SALE_PRICE
            VariantField.COST -> ProductEditField.COST
            VariantField.INITIAL_STOCK -> ProductEditField.INITIAL_STOCK
            VariantField.MIN_STOCK -> ProductEditField.MIN_STOCK
            VariantField.BARCODE -> ProductEditField.BARCODE
        }
    }
}
