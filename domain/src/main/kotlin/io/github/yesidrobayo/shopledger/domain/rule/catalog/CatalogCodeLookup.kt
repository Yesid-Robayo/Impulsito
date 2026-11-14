package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/** El producto de un código y, si era el de una presentación, la unidad [unitId] de ella. */
data class CodeHit(val productId: Long, val unitId: Long?)

/**
 * Busca un código en un catálogo ya leído, sin ir a la base: lo que escribe un lector externo en
 * la búsqueda de la venta, que termina con Enter.
 *
 * Sigue el orden de siempre: primero el código de los productos y luego el de sus presentaciones,
 * cada uno también en su otra forma de UPC-A. [products] son los que se pueden vender: un
 * archivado no está, y su código no encuentra nada.
 */
object CatalogCodeLookup {
    fun find(
        products: List<Product>,
        conversions: Map<Long, Map<Long, ConversionDetails>>,
        code: String,
    ): CodeHit? {
        val keys = BarcodeKeys.forLookup(code)
        for (key in keys) {
            products.firstOrNull { it.details.barcode == key }?.let { return CodeHit(it.id, null) }
        }
        val sellable = products.associateBy { it.id }
        for (key in keys) {
            for ((productId, units) in conversions) {
                if (productId !in sellable) continue
                val unitId = units.entries.firstOrNull { it.value.barcode == key }?.key
                if (unitId != null) return CodeHit(productId, unitId)
            }
        }
        return null
    }
}
