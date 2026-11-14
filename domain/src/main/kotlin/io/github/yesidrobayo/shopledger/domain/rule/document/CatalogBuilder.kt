package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogDocument
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogItem
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogSection
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogVariant
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList

/** Qué productos lleva el catálogo. */
sealed interface CatalogFilter {
    /** Todos los que tienen precio. */
    data object All : CatalogFilter

    /** Solo los de la categoría [categoryId]. */
    data class InCategory(val categoryId: Long) : CatalogFilter
}

/**
 * Lo que la persona elige para un catálogo. No se guarda: se elige cada vez. [prices] es la lista
 * de precios elegida, con todas las listas y los precios propios de cada producto.
 */
data class CatalogOptions(
    val filter: CatalogFilter,
    val prices: SalePriceList,
    val showPhotos: Boolean,
    val groupByCategory: Boolean,
    val includeOutOfStock: Boolean,
    val title: String,
)

/**
 * Arma el catálogo con los productos vigentes y lo que eligió la persona.
 *
 * Quedan fuera los archivados y los que no tienen precio en la lista elegida, o un precio que no
 * se puede calcular. Sin [CatalogOptions.includeOutOfStock], quedan fuera también los agotados: un
 * producto que lleva stock y no tiene nada. Uno que no lleva stock, como un servicio, nunca está
 * agotado. Las variantes van bajo su producto, con su propio precio; un producto cuyas variantes
 * quedan todas fuera no aparece. Todo va por nombre, y por categoría si se pidió, con los que no
 * tienen categoría al final.
 */
object CatalogBuilder {
    fun of(
        products: List<Product>,
        categories: List<Category>,
        options: CatalogOptions,
        header: ReceiptHeader,
        pricesOn: BusinessDate,
    ): CatalogDocument {
        val active = products.filterNot { it.isArchived }
        // Con las archivadas: un producto con variantes lo sigue siendo aunque todas lo estén.
        val children = products.filter { it.details.parentProductId != null }
            .groupBy { it.details.parentProductId }
        val items = active
            .filter { it.details.parentProductId == null }
            .filter { matches(it, options.filter) }
            .sortedBy { it.details.normalizedName }
            .mapNotNull { product ->
                val variants = children[product.id]
                val item = if (variants != null) {
                    variantItem(product, variants.filterNot { it.isArchived }, options)
                } else {
                    plainItem(product, options)
                }
                item?.let { product to it }
            }
        val sections = if (options.groupByCategory) {
            val known = categories.map { it.id }.toSet()
            val byCategory = items.groupBy { (product, _) ->
                product.details.categoryId?.takeIf { it in known }
            }
            val named = categories.sortedBy { it.details.normalizedName }.mapNotNull { category ->
                byCategory[category.id]?.let { grouped ->
                    CatalogSection(category.details.name, grouped.map { it.second })
                }
            }
            val loose = byCategory[null]?.let { grouped ->
                listOf(CatalogSection(null, grouped.map { it.second }))
            }.orEmpty()
            named + loose
        } else {
            listOf(CatalogSection(null, items.map { it.second })).filter { it.items.isNotEmpty() }
        }
        return CatalogDocument(
            title = options.title.trim().takeIf { it.isNotEmpty() },
            header = header,
            pricesOn = pricesOn,
            sections = sections,
        )
    }

    private fun matches(product: Product, filter: CatalogFilter): Boolean = when (filter) {
        CatalogFilter.All -> true
        is CatalogFilter.InCategory -> product.details.categoryId == filter.categoryId
    }

    private fun plainItem(product: Product, options: CatalogOptions): CatalogItem? {
        val price = priceOf(product, options) ?: return null
        if (!options.includeOutOfStock && isOutOfStock(product)) return null
        return CatalogItem(
            name = product.details.name,
            unitId = product.details.baseUnitId,
            price = price,
            photoMediaId = product.details.photoMediaId.takeIf { options.showPhotos },
            variants = emptyList(),
        )
    }

    private fun variantItem(
        parent: Product,
        variants: List<Product>,
        options: CatalogOptions,
    ): CatalogItem? {
        val shown = variants
            .filter { options.includeOutOfStock || !isOutOfStock(it) }
            .sortedBy { it.details.normalizedName }
            .mapNotNull { variant ->
                priceOf(variant, options)?.let { CatalogVariant(labelOf(variant), it) }
            }
        if (shown.isEmpty()) return null
        return CatalogItem(
            name = parent.details.name,
            unitId = parent.details.baseUnitId,
            price = null,
            photoMediaId = parent.details.photoMediaId.takeIf { options.showPhotos },
            variants = shown,
        )
    }

    /** El precio en la lista elegida, o `null` si no tiene o no se puede calcular. */
    private fun priceOf(product: Product, options: CatalogOptions): Money? =
        options.prices.priceOf(product).price?.takeIf { it > Money.ZERO }

    private fun isOutOfStock(product: Product): Boolean =
        product.details.stockMode == ProductStockMode.TRACKED &&
            product.onHand <= QuantityMilli.ZERO

    /** Lo que distingue a una variante: sus valores, o su nombre si no tiene ninguno. */
    private fun labelOf(variant: Product): String {
        val values = listOfNotNull(variant.details.variantValue1, variant.details.variantValue2)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return values.joinToString(VALUE_SEPARATOR).ifEmpty { variant.details.name }
    }

    /** Entre los dos valores de una variante, como `Rojo / M`. Es puntuación, no idioma. */
    private const val VALUE_SEPARATOR = " / "
}
