package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Busca productos por lo que escribe la persona.
 *
 * Compara la forma normalizada del nombre, sin mayúsculas ni tildes, con la del texto buscado, igual
 * que la unicidad de los nombres: la `ñ` es una letra propia, así que `pina` no encuentra `Piña`. Un
 * código de barras escrito completo también encuentra su producto, y también lo encuentra el
 * nombre de una de sus etiquetas. Un texto en blanco no filtra.
 */
object ProductSearch {
    /**
     * Los productos de [products] que coinciden con [query], en el mismo orden. [tags] trae, por id
     * de producto, la forma normalizada del nombre de cada etiqueta que lleva.
     */
    fun filter(
        products: List<Product>,
        query: String,
        tags: Map<Long, Collection<String>> = emptyMap(),
    ): List<Product> {
        val normalized = TextNormalizer.normalize(query)
        if (normalized.isEmpty()) return products
        val code = query.trim()
        return products.filter { product ->
            product.details.normalizedName.contains(normalized) ||
                product.details.barcode == code ||
                tags[product.id].orEmpty().any { it.contains(normalized) }
        }
    }
}
