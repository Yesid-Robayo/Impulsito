package io.github.yesidrobayo.shopledger.domain.model

/**
 * Las sumas de un período con las que se calcula su ganancia.
 *
 * Cada suma cuenta solo documentos activos cuya fecha de negocio cae en el período, cada uno con su
 * propia fecha: una devolución resta en el día en que se hizo, no en el de su venta. Una venta o un
 * gasto anulados no suman, y un ajuste anulado deja de contar como merma.
 *
 * Las propinas no están aquí, porque no son de quien vende y no entran en ninguna cifra de
 * ganancia. Los otros ingresos se muestran aparte, los retiros del dueño no son gasto y las compras
 * tampoco: comprar pasa el dinero al inventario, y llega a la ganancia cuando se vende.
 */
data class ProfitTotals(
    /** Suma del total sin impuesto de las ventas. */
    val salesNetTotal: Money,
    /** Suma de lo reembolsado sin impuesto en las devoluciones de clientes. */
    val returnsNetRefund: Money,
    /** Suma del costo congelado en las líneas de las ventas. */
    val salesTotalCost: Money,
    /** Suma del costo de lo devuelto por los clientes. */
    val returnsCostReturned: Money,
    /** Suma de los gastos registrados. */
    val recordedExpenses: Money,
    /**
     * Daños y pérdidas: lo que valen los ajustes de stock, con signo cambiado. Es negativa si los
     * sobrantes superan a las pérdidas, y entonces reduce los gastos del período.
     */
    val netShrinkage: Money,
    /** Suma de las comisiones de los cobros de las ventas. */
    val paymentFees: Money,
    /**
     * Suma del redondeo de efectivo de las ventas, con signo. Redondear hacia abajo cobra menos y
     * cuesta; redondear hacia arriba cobra más y reduce ese costo.
     */
    val cashRoundingTotal: Money,
)
