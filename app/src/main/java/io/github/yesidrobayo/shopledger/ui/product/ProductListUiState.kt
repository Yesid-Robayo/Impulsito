package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing

/**
 * El estado de la lista de productos. [query] es lo que la persona escribió en la búsqueda, tal
 * cual: sobrevive a cualquier cambio del contenido, y el contenido se arma con ella.
 * [showsUsageFilter] dice si la hoja ofrece el filtro `Insumos`: solo si hay alguno, o si ya está
 * puesto, porque lo que no se usa no estorba.
 */
@Immutable
data class ProductListUiState(
    val query: String = "",
    val content: ProductListContent = ProductListContent.Loading,
    val filters: ProductListFilters = ProductListFilters(),
    val filtersOpen: Boolean = false,
    val categories: List<Category> = emptyList(),
    val suppliers: List<Supplier> = emptyList(),
    val showsUsageFilter: Boolean = false,
)

/** Lo que muestra la lista debajo de la búsqueda. */
sealed interface ProductListContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : ProductListContent

    /** No hay ningún producto vigente: el estado vacío invita a crear el primero. */
    data object EmptyCatalog : ProductListContent

    /** Ningún producto pasa los filtros puestos: se ofrece quitarlos. */
    data object FilteredOut : ProductListContent

    /** Hay productos, pero ninguno coincide con [query]: se ofrece crearlo con ese nombre. */
    data class NoResults(val query: String) : ProductListContent

    /**
     * Los productos que coinciden con [query], por nombre. Con muchos, van en [sections] por su
     * inicial, para encontrarlos al desplazar; con pocos, una sola sección sin encabezado. [query]
     * es la búsqueda con que se armó, y [filters], los filtros y el orden: un resultado de otra
     * búsqueda o de otro orden empieza arriba.
     */
    data class Products(
        val sections: List<ProductListSection>,
        val query: String = "",
        val filters: ProductListFilters = ProductListFilters(),
    ) : ProductListContent

    /** No se pudo leer el catálogo. Se puede reintentar. */
    data object ReadError : ProductListContent
}

/** Un grupo de la lista: la inicial de sus nombres, o `null` si la lista no lleva encabezados. */
@Immutable
data class ProductListSection(val initial: String?, val items: List<ProductListItem>)

/**
 * Una fila de la lista, ya calculada: lo que dice su stock, su precio y su margen.
 *
 * La unidad va como la guardó la base, sin texto: su símbolo sale de los recursos del idioma de la
 * interfaz al dibujarla, y así cambiar de idioma no obliga a releer el catálogo. [photoMediaId] es
 * la foto del producto, o `null` sin foto: la fila la busca al dibujarse.
 *
 * [usage] dice si es un insumo: un insumo puro (`INPUT`) no se vende, así que la fila muestra su
 * [cost] en vez de precio y margen, y `INPUT` y `BOTH` llevan su etiqueta. [cost] es el costo
 * promedio, o el costo fijo sin stock propio; `null` si no se conoce.
 */
@Immutable
data class ProductListItem(
    val id: Long,
    val name: String,
    val stock: ProductListStock,
    val unit: MeasureUnit?,
    val trailing: ProductRowTrailing.Catalog,
    val archived: Boolean,
    val photoMediaId: Long? = null,
    val usage: ProductUsage = ProductUsage.SALE,
    val cost: Money? = null,
)

/** Lo que dice la segunda línea de una fila sobre el stock del producto. */
sealed interface ProductListStock {
    data class Normal(val onHand: QuantityMilli) : ProductListStock

    /** En su mínimo o por debajo, pero todavía con unidades. */
    data class Low(val onHand: QuantityMilli) : ProductListStock

    /** Sin unidades. */
    data object Out : ProductListStock

    /** Vendido de más: falta registrar una compra o un conteo. */
    data class Negative(val onHand: QuantityMilli) : ProductListStock

    /** Un servicio o un producto hecho de componentes: no lleva stock propio. */
    data object NoStock : ProductListStock
}
