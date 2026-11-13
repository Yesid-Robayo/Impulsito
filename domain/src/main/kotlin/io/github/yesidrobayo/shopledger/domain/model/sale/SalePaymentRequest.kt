package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Un pago de la venta tal como lo escribió la persona al cobrar con varios métodos: con qué método
 * y cuánto cubre de lo que la venta cobra.
 *
 * Una venta tiene un pago por método, al menos uno, y sus montos suman lo que la venta cobra. Si no
 * suman, la venta no se confirma: la pantalla muestra la diferencia y la persona la corrige. Cada
 * monto va de cero al tope de un documento.
 *
 * [feeAmount] es la comisión que la persona escribió en la venta, por ejemplo la que de verdad le
 * cobraron, de cero al tope de un documento. Sin ella vale la comisión de su método, calculada con
 * lo que cubre el pago. Un pago de cero no paga comisión.
 *
 * [tenderedAmount] es lo que se recibió en efectivo, de cero al tope de un documento, solo en un
 * método de efectivo. El cambio es lo recibido menos lo que cubre el pago ya redondeado, y no puede
 * ser negativo: con menos de lo que se cobra, la venta no se confirma. Sin lo recibido no hay
 * cambio: es solo una ayuda.
 *
 * [foreign] es lo recibido en otra moneda, con su cotización. El pago sigue en la moneda del
 * negocio: en efectivo, lo recibido es [foreign] pasado a ella, y el cambio se da en la moneda del
 * negocio; con otro método, lo que el pago cubre ya con el recargo o descuento de su método debe
 * ser exactamente [foreign] pasado a ella. [amount] sigue siendo lo de antes del recargo, como en
 * cualquier pago escrito.
 */
data class SalePaymentRequest(
    val paymentMethodId: Long,
    val amount: Money,
    val feeAmount: Money? = null,
    val tenderedAmount: Money? = null,
    val foreign: ForeignTender? = null,
)
