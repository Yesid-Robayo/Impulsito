package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Un extra que se ofrece al vender [productId]: el producto [modifierProductId], como una porción
 * adicional. Al venderlo es una línea normal enlazada a la del producto.
 *
 * [groupName] agrupa extras en la pantalla, y `null` es sin grupo. [sortOrder] es el orden en que
 * se ofrecen; dos con el mismo orden salen por [id].
 */
data class ProductModifier(
    val id: Long,
    val productId: Long,
    val modifierProductId: Long,
    val groupName: String?,
    val sortOrder: Long,
)

/** Por qué no se pueden guardar los extras elegidos para un producto. */
enum class ModifierIssue {
    /** Un producto no puede ser extra de sí mismo. */
    SAME_PRODUCT,

    /** Un extra ya no existe o está archivado: no se ofrecería al vender. */
    UNAVAILABLE,

    /** Un extra tiene variantes: no se vende directo, sino una de sus variantes. */
    HAS_VARIANTS,

    /** El mismo extra aparece dos veces. */
    DUPLICATE,

    /** Son más extras de los que caben en un producto. */
    TOO_MANY,
}
