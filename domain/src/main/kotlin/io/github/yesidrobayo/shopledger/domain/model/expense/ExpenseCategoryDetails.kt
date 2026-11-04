package io.github.yesidrobayo.shopledger.domain.model.expense

/**
 * Lo que la persona escribe de una categoría de gasto.
 *
 * Una categoría inicial nace sin nombre propio: se muestra con el texto traducido de su clave, en
 * el idioma de la interfaz, hasta que la persona la renombra. Por eso [name] y [normalizedName]
 * pueden faltar, pero solo en una categoría inicial. [isFixed] marca un gasto fijo, que cuenta para
 * el punto de equilibrio.
 */
data class ExpenseCategoryDetails(
    val name: String?,
    val normalizedName: String?,
    val isFixed: Boolean,
)
