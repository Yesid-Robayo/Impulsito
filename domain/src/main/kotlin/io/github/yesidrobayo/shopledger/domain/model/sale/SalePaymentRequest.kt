package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Un pago de la venta tal como lo escribió la persona al cobrar con varios métodos: con qué método
 * y cuánto cubre de lo que la venta cobra.
 *
 * Una venta tiene un pago por método, al menos uno, y sus montos suman lo que la venta cobra. Si no
 * suman, la venta no se confirma: la pantalla muestra la diferencia y la persona la corrige. Cada
 * monto va de cero al tope de un documento.
 */
data class SalePaymentRequest(val paymentMethodId: Long, val amount: Money)
