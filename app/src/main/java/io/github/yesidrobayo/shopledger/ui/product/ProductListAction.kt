package io.github.yesidrobayo.shopledger.ui.product

/** Lo que la persona puede hacer en la lista de productos. */
sealed interface ProductListAction {
    /** Escribió en la búsqueda. */
    data class QueryChange(val query: String) : ProductListAction

    /** Tocó un producto: se abre su detalle. */
    data class OpenProduct(val productId: Long) : ProductListAction

    /**
     * Quiere crear un producto. Desde una búsqueda sin resultados, [name] lleva lo que escribió,
     * para no tener que escribirlo dos veces.
     */
    data class CreateProduct(val name: String? = null) : ProductListAction

    /** Reintenta leer el catálogo tras un error. */
    data object Retry : ProductListAction

    /** Abre la actualización masiva de precios, desde el menú. */
    data object UpdatePrices : ProductListAction

    /** Abre `Etiquetas imprimibles`, desde el menú. */
    data object OpenLabels : ProductListAction

    /** Abre `Importar datos`, desde el menú, también con el catálogo vacío. */
    data object OpenImport : ProductListAction

    /** Abre la hoja `Filtrar y ordenar`. */
    data object OpenFilters : ProductListAction

    data object CloseFilters : ProductListAction

    /** Filtra por [categoryId]; `null` quita el filtro. */
    data class FilterCategory(val categoryId: Long?) : ProductListAction

    /** Filtra por proveedor habitual [supplierId]; `null` quita el filtro. */
    data class FilterSupplier(val supplierId: Long?) : ProductListAction

    /** Muestra los archivados en vez de los vigentes. */
    data class FilterArchived(val archived: Boolean) : ProductListAction

    data class SortBy(val sort: ProductSort) : ProductListAction

    /** Quita los filtros y deja el orden. */
    data object ClearFilters : ProductListAction
}
