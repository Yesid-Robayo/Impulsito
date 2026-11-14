package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Un catálogo para compartir con clientes: productos con su precio de venta en la lista elegida,
 * agrupados por categoría si se pidió. Es un modelo aparte a propósito: no tiene costo, margen,
 * ganancia, proveedor ni stock, así que ningún catálogo puede llevarlos. La leyenda de que no es
 * un comprobante ni una factura la pone siempre quien dibuja.
 *
 * - [title] es el que escribió la persona, o `null` para el título por defecto.
 * - [pricesOn] es la fecha de negocio de los precios: cambian, y el papel lo dice.
 * - [sections] van en orden; una sin categoría lleva `null`.
 */
data class CatalogDocument(
    val title: String?,
    val header: ReceiptHeader,
    val pricesOn: BusinessDate,
    val sections: List<CatalogSection>,
) {
    /** Cuántos productos lleva, contando cada variante. */
    val productCount: Int get() = sections.sumOf { section -> section.items.sumOf { it.count } }
}

/** Los productos de una categoría, o de todas si [categoryName] es `null`. */
data class CatalogSection(val categoryName: String?, val items: List<CatalogItem>)

/**
 * Un producto del catálogo: su nombre, la unidad en que se vende y su precio. Un producto con
 * variantes lleva [variants] y no tiene precio propio: cada variante tiene el suyo. [photoMediaId]
 * es su foto, si tiene y si se pidieron fotos.
 */
data class CatalogItem(
    val name: String,
    val unitId: Long,
    val price: Money?,
    val photoMediaId: Long?,
    val variants: List<CatalogVariant>,
) {
    /** Cuántos productos cuenta: sus variantes, o él solo si no tiene. */
    val count: Int get() = variants.size.coerceAtLeast(1)
}

/** Una variante bajo su producto, con lo que la distingue y su precio. */
data class CatalogVariant(val label: String, val price: Money)
