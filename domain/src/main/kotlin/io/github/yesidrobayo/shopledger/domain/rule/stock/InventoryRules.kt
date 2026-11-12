package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull

/** Cómo está el stock de un producto que lleva libro, de lo más urgente a lo normal. */
enum class InventoryStatus {
    /** Vendido de más: falta registrar una compra o un conteo. */
    NEGATIVE,

    /** Sin unidades. */
    OUT,

    /** Con unidades, pero en su mínimo o por debajo. */
    LOW,

    /** Por encima de su mínimo. */
    NORMAL,
}

/**
 * La vista del inventario: qué se está acabando, en qué orden se muestra y cuánto vale lo que hay.
 *
 * Todo sale de los productos, no de las alertas: así la lista funciona aunque las notificaciones
 * estén negadas, y una alerta perdida nunca esconde un producto que se acaba.
 */
object InventoryRules {
    /**
     * El estado de [product], o `null` si no lleva libro propio: un servicio o un producto hecho de
     * componentes no se agota. Un producto archivado no se vigila, pero su estado se puede leer.
     */
    fun statusOf(product: Product): InventoryStatus? {
        if (product.details.stockMode != ProductStockMode.TRACKED) return null
        val onHand = product.onHand
        return when {
            onHand < QuantityMilli.ZERO -> InventoryStatus.NEGATIVE
            onHand == QuantityMilli.ZERO -> InventoryStatus.OUT
            onHand <= product.details.minStock -> InventoryStatus.LOW
            else -> InventoryStatus.NORMAL
        }
    }

    /**
     * Los productos por agotarse: con libro, sin archivar y en su mínimo o por debajo. Primero el
     * stock negativo, después los agotados y después el resto, y dentro de cada grupo por nombre,
     * para que lo más urgente quede arriba y el orden no salte entre aperturas.
     */
    fun lowStock(products: Iterable<Product>): List<Product> = products
        .filter {
            LowStockEvaluator.isLow(
                it.details.stockMode,
                it.isArchived,
                it.onHand,
                it.details.minStock,
            )
        }
        .sortedWith(compareBy({ urgency(it) }, { it.details.normalizedName }, { it.id }))

    /** Los productos sin archivar con stock negativo, por nombre. */
    fun negative(products: Iterable<Product>): List<Product> = products
        .filter { !it.isArchived && statusOf(it) == InventoryStatus.NEGATIVE }
        .sortedWith(compareBy({ it.details.normalizedName }, { it.id }))

    /**
     * El valor del inventario: la suma del valor del stock de cada producto, también de los
     * archivados, que siguen teniendo sus unidades. Un producto en cero o en negativo vale cero: no
     * hay unidades que valorar. Es `null` si la suma no cabe en `Long`, y entonces se muestra una
     * raya, nunca una cifra cortada.
     */
    fun value(products: Iterable<Product>): Money? = products.map { it.stockValue }.sumOrNull()

    /**
     * Si algún producto sin archivar tiene unidades sin costo conocido: entonces el valor real del
     * inventario puede ser mayor que el que se muestra.
     */
    fun hasStockWithoutCost(products: Iterable<Product>): Boolean = products.any {
        !it.isArchived && statusOf(it) != null && it.onHand > QuantityMilli.ZERO &&
            it.stockValue == Money.ZERO && it.averageCost == Money.ZERO
    }

    /** El grupo de [product] en la lista por agotarse: 0 el negativo, 1 el agotado, 2 el resto. */
    internal fun urgency(product: Product): Int {
        val status = statusOf(product) ?: return LAST_GROUP
        return when (status) {
            InventoryStatus.NEGATIVE -> 0
            InventoryStatus.OUT -> 1
            InventoryStatus.LOW, InventoryStatus.NORMAL -> LAST_GROUP
        }
    }

    private const val LAST_GROUP = 2
}
