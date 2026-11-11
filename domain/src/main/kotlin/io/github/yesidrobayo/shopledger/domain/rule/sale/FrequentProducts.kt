package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/**
 * Los productos que la venta ofrece sin buscar, en `Más vendidos`.
 *
 * Primero los que tienen líneas en ventas activas de los últimos treinta días, de más a menos
 * líneas, y después, para completar, los demás en orden alfabético. Se cuentan líneas, no cantidad
 * ni dinero: lo que ahorra toques es cuántas veces se vende un producto, y así no se suman kilos con
 * unidades. Un producto con variantes cuenta las líneas de todas ellas, y sus variantes no aparecen
 * sueltas: se eligen al tocarlo. Los empates van por nombre normalizado. Sin historial, el orden es
 * alfabético. Lo que no aparezca se encuentra con el buscador.
 */
object FrequentProducts {
    /** Cuántos productos se ofrecen como máximo. */
    const val LIMIT: Int = 50

    /** Cuántos días de ventas cuentan, hoy incluido. */
    const val WINDOW_DAYS: Int = 30

    /** La primera fecha de negocio que cuenta, si hoy es [today]. */
    fun since(today: BusinessDate): BusinessDate = today.plusDays(1 - WINDOW_DAYS)

    /**
     * Los productos de `Más vendidos`, de [products], con [lineCounts] líneas por id de producto.
     * Los archivados y las variantes no se ofrecen.
     */
    fun rank(
        products: List<Product>,
        lineCounts: Map<Long, Int>,
        limit: Int = LIMIT,
    ): List<Product> {
        val counts = HashMap<Long, Int>()
        for (product in products) {
            val count = lineCounts[product.id] ?: continue
            // Las líneas de una variante cuentan para su producto principal.
            val owner = product.details.parentProductId ?: product.id
            counts[owner] = (counts[owner] ?: 0) + count
        }
        val offered = products.filter { !it.isArchived && it.details.parentProductId == null }
        val (sold, rest) = offered.partition { (counts[it.id] ?: 0) > 0 }
        val byName = compareBy<Product> { it.details.normalizedName }.thenBy { it.id }
        val ranked = sold.sortedWith(
            compareByDescending<Product> { counts.getValue(it.id) }.then(byName),
        )
        return (ranked + rest.sortedWith(byName)).take(limit)
    }
}
