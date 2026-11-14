package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog

/**
 * El selector de variantes de la venta, sin dibujar: qué valores ofrece cada eje, cuáles están sin
 * stock y qué variante corresponde a lo elegido. Es puro, así que se prueba sin pantalla.
 *
 * Solo cuentan las variantes vigentes del catálogo. Un valor del segundo eje se ofrece si existe
 * su combinación con el valor elegido del primero; sin primer valor elegido, se ofrecen todos.
 */
internal object VariantChoices {
    /** Las variantes vigentes de [parentId], en el orden en que se crearon. */
    fun variantsOf(catalog: SaleCatalog, parentId: Long): List<Product> =
        catalog.products.filter { it.details.parentProductId == parentId }.sortedBy { it.id }

    /** La hoja del selector de [parent], con [chosen] ya elegido, eje por eje. */
    fun sheet(
        catalog: SaleCatalog,
        parent: Product,
        chosen: List<String?>,
    ): SaleSheet.VariantPick {
        val variants = variantsOf(catalog, parent.id)
        val first = variants.firstOrNull()?.details
        val axes = buildList {
            add(axisOf(first?.variantAxis1.orEmpty(), variants) { it.details.variantValue1 })
            val second = first?.variantAxis2
            if (second != null) {
                val firstValue = chosen.firstOrNull()
                val matching = variants.filter {
                    firstValue == null || it.details.variantValue1 == firstValue
                }
                add(axisOf(second, matching) { it.details.variantValue2 })
            }
        }
        return SaleSheet.VariantPick(parent.id, parent.details.name, axes, chosen)
    }

    /** La variante de [parentId] con los valores [chosen], si ya hay uno por cada eje. */
    fun match(
        catalog: SaleCatalog,
        parentId: Long,
        chosen: List<String?>,
        axisCount: Int,
    ): Product? {
        if (chosen.size < axisCount || chosen.any { it == null }) return null
        return variantsOf(catalog, parentId).firstOrNull {
            it.details.variantValue1 == chosen[0] &&
                (axisCount < 2 || it.details.variantValue2 == chosen[1])
        }
    }

    private fun axisOf(
        name: String,
        variants: List<Product>,
        valueOf: (Product) -> String?,
    ): VariantAxisChoice {
        val byValue = variants.groupBy { valueOf(it).orEmpty() }
        return VariantAxisChoice(
            name = name,
            values = byValue.keys.toList(),
            outOfStock = byValue.filterValues { family -> family.all(::isOut) }.keys,
        )
    }

    /** Sin stock: lleva libro y no le queda nada. Uno sin libro nunca se agota. */
    private fun isOut(product: Product): Boolean =
        product.details.stockMode == ProductStockMode.TRACKED &&
            product.onHand <= QuantityMilli.ZERO
}
