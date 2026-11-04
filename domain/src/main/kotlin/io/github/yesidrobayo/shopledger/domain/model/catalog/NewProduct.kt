package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Un producto que el caso de uso va a crear, todavía sin id.
 *
 * Nace sin stock: saldo y valor del stock en cero, y costo promedio igual a [initialCost], que es
 * el punto de partida de la reproducción del libro. Si la persona escribe un stock inicial, entra
 * después como la primera fila del libro, en la misma transacción. [replacementCost] cero significa
 * sin dato.
 */
data class NewProduct(
    val details: ProductDetails,
    val salePrice: Money,
    val initialCost: Money,
    val replacementCost: Money,
    val replacementCostAt: Instant?,
    val createdAt: Instant,
)
