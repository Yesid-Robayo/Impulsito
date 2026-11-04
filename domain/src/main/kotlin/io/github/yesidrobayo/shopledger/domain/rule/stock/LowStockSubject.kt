package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode

/**
 * Lo que la alerta de stock bajo necesita saber de un producto al empezar una transacción, antes de
 * su primera fila.
 *
 * [minStock] es el mínimo del producto: llegar a él ya es estar bajo, porque es el punto en que hay
 * que reponer. Cero significa avisar cuando se agote. [hasActiveAlert] dice si el producto tiene
 * una alerta sin resolver; hay a lo sumo una por producto.
 */
data class LowStockSubject(
    val productId: Long,
    val stockMode: ProductStockMode,
    val isArchived: Boolean,
    val minStock: QuantityMilli,
    val onHandBefore: QuantityMilli,
    val hasActiveAlert: Boolean,
)
