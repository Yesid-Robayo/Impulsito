package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Los ejes de las variantes de un producto, como talla y color, tal como los escribió la persona.
 *
 * Hay uno o dos: la base solo tiene dos columnas de eje, y un tercero haría una matriz que nadie
 * recorre al vender. [second] `null` es un solo eje.
 */
data class VariantAxes(val first: String, val second: String?)

/**
 * Los valores de una combinación, uno por eje, tal como los escribió la persona: `M` y `Rojo`.
 * [second] es `null` si y solo si los ejes tienen uno solo.
 */
data class VariantValues(val first: String, val second: String?)

/**
 * Una variante que la persona quiere crear: su combinación de valores y lo propio de ella.
 *
 * Cada variante es un producto con su libro, así que lleva su precio, su costo, su stock inicial,
 * su mínimo y su código, igual que un producto suelto. Lo demás, como la categoría, la unidad o el
 * modo de stock, lo hereda del producto principal. [cost] es el costo con que empieza su libro, o
 * el costo fijo si el principal no lleva stock. [barcode] en blanco es sin código.
 */
data class VariantDraft(
    val values: VariantValues,
    val salePrice: Money = Money.ZERO,
    val cost: Money = Money.ZERO,
    val initialStock: QuantityMilli = QuantityMilli.ZERO,
    val minStock: QuantityMilli = QuantityMilli.ZERO,
    val barcode: String? = null,
)

/**
 * Lo que impide crear unas variantes tal como se escribieron. Los del eje marcan el campo del eje;
 * los de una variante, su fila.
 */
enum class VariantIssue {
    /** El nombre del eje queda vacío tras recortar espacios. */
    AXIS_EMPTY,

    /** El nombre del eje pasa de 40 caracteres. */
    AXIS_TOO_LONG,

    /** Los ejes no son los de las variantes que el producto ya tiene: no se mezclan matrices. */
    AXES_CHANGED,

    /** No hay ninguna variante que crear. */
    NO_VARIANTS,

    /** Un valor queda vacío tras recortar espacios. */
    VALUE_EMPTY,

    /** Un valor pasa de 40 caracteres. */
    VALUE_TOO_LONG,

    /** La combinación tiene un valor por eje de más o de menos. */
    VALUES_DO_NOT_MATCH_AXES,

    /** La combinación ya existe en este producto, o se repite entre las que se van a crear. */
    DUPLICATE_COMBINATION,

    /** El nombre del producto más sus valores pasa del largo máximo de un nombre. */
    NAME_TOO_LONG,

    /** Dos de las variantes que se van a crear llevan el mismo código de barras. */
    BARCODE_REPEATED,
}
