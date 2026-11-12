package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Cómo dejó una compra el costo de un producto: el costo promedio antes de la compra y después de
 * todas sus líneas. [marginBelowTarget] dice si, con el costo nuevo, el margen del precio de venta
 * quedó por debajo del objetivo del producto: entonces conviene revisar su precio.
 */
data class PurchaseCostChange(
    val productId: Long,
    val averageCostBefore: Money,
    val averageCostAfter: Money,
    val marginBelowTarget: Boolean = false,
) {
    /**
     * Si la compra subió un costo que ya se conocía. De ningún costo a uno no es subir: es saber
     * por fin cuánto cuesta.
     */
    val costRose: Boolean
        get() = averageCostBefore > Money.ZERO && averageCostAfter > averageCostBefore
}
