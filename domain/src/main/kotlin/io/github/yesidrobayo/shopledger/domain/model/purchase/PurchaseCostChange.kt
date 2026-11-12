package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Cómo dejó una compra el costo de un producto: el costo promedio antes de la compra y después de
 * todas sus líneas.
 */
data class PurchaseCostChange(
    val productId: Long,
    val averageCostBefore: Money,
    val averageCostAfter: Money,
)
