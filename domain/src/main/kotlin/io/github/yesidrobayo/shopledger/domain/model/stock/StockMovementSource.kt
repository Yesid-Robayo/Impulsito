package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * De qué documento sale una fila del libro de stock: el id de su línea o de su documento, según el
 * tipo de la fila.
 *
 * Cada tipo exige su origen: una venta y su anulación apuntan a la línea de venta, una compra y su
 * anulación a la línea de compra, y así cada uno. Un compensatorio lleva el mismo origen que la
 * fila que anula. Un inventario inicial, un ajuste suelto y una corrección de costo no tienen
 * documento, y sus campos quedan en `null`. [countSessionId] solo lo lleva un ajuste que sale de un
 * conteo, y [saleLineComponentId] la salida de un componente de un combo vendido.
 */
data class StockMovementSource(
    val saleLineId: Long? = null,
    val saleLineComponentId: Long? = null,
    val purchaseLineId: Long? = null,
    val saleReturnLineId: Long? = null,
    val purchaseReturnLineId: Long? = null,
    val transferLineId: Long? = null,
    val productionBatchId: Long? = null,
    val countSessionId: Long? = null,
) {
    companion object {
        /** Sin documento: un inventario inicial, un ajuste suelto o una corrección de costo. */
        val NONE: StockMovementSource = StockMovementSource()
    }
}
