package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Las sumas de ventas y devoluciones de un período con las que se calcula lo vendido y su ganancia
 * en ventas.
 *
 * Cada suma cuenta solo documentos activos cuya fecha de negocio cae en el período, cada uno con su
 * propia fecha: una devolución resta en el día en que se hizo, no en el de su venta. Una venta
 * anulada no suma, ni siquiera en su número.
 */
data class SalesTotals(
    /** Suma del total sin impuesto de las ventas. */
    val salesNetTotal: Money,
    /** Suma de lo reembolsado sin impuesto en las devoluciones de clientes. */
    val returnsNetRefund: Money,
    /** Suma del costo congelado en las líneas de las ventas. */
    val salesTotalCost: Money,
    /** Suma del costo de lo devuelto por los clientes. */
    val returnsCostReturned: Money,
    /** Cuántas ventas activas hay. Las devoluciones no lo cambian: la venta sí ocurrió. */
    val salesCount: Long,
) {
    companion object {
        /** Un período sin documentos. */
        val ZERO = SalesTotals(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, 0L)
    }
}
