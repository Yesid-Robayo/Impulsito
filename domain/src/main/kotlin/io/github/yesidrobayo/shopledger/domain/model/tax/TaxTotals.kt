package io.github.yesidrobayo.shopledger.domain.model.tax

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Las sumas de impuesto de un período, tal como quedaron guardadas en cada documento.
 *
 * Cada suma cuenta solo documentos activos cuya fecha de negocio cae en el período, cada uno con su
 * propia fecha: una devolución resta en el día en que se hizo, no en el de su venta. Una venta,
 * devolución o compra anulada no suma.
 */
data class TaxTotals(
    /** Suma del impuesto de las ventas. */
    val salesTax: Money,
    /** Suma del impuesto devuelto en las devoluciones de clientes. */
    val returnsTaxRefund: Money,
    /** Suma del impuesto de las compras. */
    val purchasesTax: Money,
) {
    companion object {
        /** Un período sin documentos con impuesto. */
        val ZERO = TaxTotals(Money.ZERO, Money.ZERO, Money.ZERO)
    }
}
