package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/** El orden de la lista de productos. */
enum class ProductSort {
    /** Por nombre, con encabezados por inicial si son muchos. */
    NAME,

    /** Primero lo que menos stock tiene; lo que no lleva stock va al final. */
    STOCK,

    /** Primero lo que menos deja; lo que no tiene margen definido va al final. */
    MARGIN,
}

/**
 * Los filtros y el orden de la lista de productos. [categoryId] y [supplierId] en `null` no
 * filtran. [archived] muestra solo los archivados, que de otro modo no aparecen. [inputsOnly]
 * muestra solo los insumos, los que no se venden y los que también van en recetas: así se ven
 * aparte, sin una sección propia.
 */
@Immutable
data class ProductListFilters(
    val categoryId: Long? = null,
    val supplierId: Long? = null,
    val archived: Boolean = false,
    val sort: ProductSort = ProductSort.NAME,
    val inputsOnly: Boolean = false,
) {
    /** Cuántos filtros están puestos, para el botón. El orden no cuenta: no quita productos. */
    val activeCount: Int
        get() = listOf(categoryId != null, supplierId != null, archived, inputsOnly).count { it }

    /** Si [product] pasa los filtros. */
    fun matches(product: Product): Boolean {
        val details = product.details
        return product.isArchived == archived &&
            (categoryId == null || details.categoryId == categoryId) &&
            (supplierId == null || details.defaultSupplierId == supplierId) &&
            (!inputsOnly || details.usage.isInput)
    }
}
