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
}
