package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Un producto guardado, con su precio y el estado de su stock.
 *
 * [onHand], [averageCost] y [stockValue] son copias derivadas del libro de stock: el saldo es la
 * suma de sus filas, y el costo y el valor son los que dejó su última fila. Solo los cambia el
 * libro, en la misma transacción que agrega cada fila. [onHand] puede ser negativo, porque vender
 * nunca se bloquea por falta de stock, y vale cero en un producto sin stock propio o hecho de
 * componentes. [stockValue] es el valor total del stock, en unidad menor: sin él, un costo por
 * unidad menor que uno se perdería al redondear el promedio.
 *
 * [initialCost] es el costo con que empieza la reproducción del libro. [replacementCost] es lo que
 * costaría reponerlo hoy, cero sin dato. Con [priceReviewSince] el producto espera que la persona
 * revise su precio tras un costo nuevo. Con [archivedAt] está archivado.
 */
data class Product(
    val id: Long,
    val details: ProductDetails,
    val salePrice: Money,
    val initialCost: Money,
    val averageCost: Money,
    val stockValue: Money,
    val onHand: QuantityMilli,
    val replacementCost: Money,
    val replacementCostAt: Instant?,
    val priceReviewSince: Instant?,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Si está archivado: no se ofrece en ventas, compras ni ajustes nuevos. */
    val isArchived: Boolean get() = archivedAt != null
}
