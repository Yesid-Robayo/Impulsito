package io.github.yesidrobayo.shopledger.domain.model.returns

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Cuánto devuelve la persona de una línea de la venta: [quantity] en milésimas de la unidad base del
 * producto, y si la mercancía vuelve al stock. Sin reingreso, se perdió: no deja fila en el libro y
 * su costo sigue en el costo de lo vendido.
 */
data class ReturnLineRequest(
    val saleLineId: Long,
    val quantity: QuantityMilli,
    val restock: Boolean = true,
)

/** Cómo quiere la persona resolver lo que devuelve. */
sealed interface ReturnSettlement {
    /**
     * Devuelve el dinero. Con [moneyAccountId], sale de esa cuenta; sin ella, la persona lo entrega
     * por fuera de las cuentas y no se escribe ningún movimiento.
     */
    data class Refund(val moneyAccountId: Long?) : ReturnSettlement

    /**
     * Deja lo devuelto como saldo a favor de un cliente. Si la venta tiene cliente, es el suyo, y
     * [customerId] puede ir vacío; si no lo tiene, la persona elige uno, porque un saldo a favor
     * siempre es de alguien.
     */
    data class StoreCredit(val customerId: Long?) : ReturnSettlement
}

/**
 * Lo que la persona pide al devolver parte de la venta [saleId]: una o más de sus líneas, cada una
 * una sola vez, cómo se resuelve y una nota opcional de hasta 200 caracteres.
 */
data class SaleReturnRequest(
    val saleId: Long,
    val lines: List<ReturnLineRequest>,
    val settlement: ReturnSettlement,
    val note: String? = null,
)
