package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Busca productos por lo que escribe la persona.
 *
 * Compara la forma normalizada del nombre, sin mayúsculas ni tildes, con la del texto buscado, igual
 * que la unicidad de los nombres: la `ñ` es una letra propia, así que `pina` no encuentra `Piña`. Un
 * código de barras escrito completo también encuentra su producto. Un texto en blanco no filtra.
 */
object ProductSearch {
    /** Los productos de [products] que coinciden con [query], en el mismo orden. */
    fun filter(products: List<Product>, query: String): List<Product> {
        val normalized = TextNormalizer.normalize(query)
        if (normalized.isEmpty()) return products
        val code = query.trim()
        return products.filter {
            it.details.normalizedName.contains(normalized) || it.details.barcode == code
        }
    }
}
