package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Una venta tal como la confirma la persona: sus líneas, con qué método se cobró y, si es atrasada,
 * en qué fecha ocurrió.
 *
 * Sin [payments], la venta tiene un solo pago con [paymentMethodId], que cubre todo lo que se cobra:
 * es la venta común, que se confirma sin pasar por la pantalla de cobro. Con [payments], la venta se
 * cobra con varios métodos, uno por pago, y entonces esos pagos mandan y [paymentMethodId] no se
 * usa.
 *
 * [date] es la fecha de negocio elegida: `null` es hoy, y nunca puede ser futura. Una fecha pasada
 * guarda el hecho a las 12:00 de ese día en la zona del negocio, y el registro igual toma su lugar
 * en el libro por orden de llegada. [note] la escribe la persona, hasta 200 caracteres; en blanco
 * no se guarda.
 *
 * [orderAdjustment] es el descuento o recargo que la persona escribe para toda la venta, con signo:
 * negativo es descuento y positivo recargo. Se reparte entre las líneas y paga impuesto con ellas.
 * Los pagos escritos cubren lo que se cobra con él, antes de los recargos de sus métodos.
 *
 * [heldSaleId] es la venta en espera de la que se retomó esta, si se retomó: al confirmar, sale de
 * la espera en la misma transacción.
 *
 * [tipAmount] es la propina, que solo existe con las propinas encendidas. Los pagos la cubren junto
 * con la venta, pero no entra en ninguna ganancia ni paga el recargo de un método.
 *
 * [customerId] es el cliente de la venta, si la persona eligió uno. Es obligatorio con un pago a
 * crédito, que carga lo pagado así en su libro, con el vencimiento [creditDueDate] si la persona
 * eligió uno.
 *
 * [pointsRedeemed] son los puntos del cliente que se canjean en esta venta. Los puntos no son
 * dinero: su descuento entra en el ajuste de toda la venta, junto con [orderAdjustment], y nunca
 * como un pago. Exige cliente y el programa de puntos activo; cero es sin canje.
 *
 * [priceListId] es la lista de precios de la venta: la del cliente o la que eligió la persona. Nula
 * es la inicial. El precio de cada línea sin precio escrito sale de esa lista, y la venta y cada
 * línea la guardan, así que cambiar la lista después no toca la venta.
 *
 * [locationId] es la ubicación de donde sale el stock, que la venta guarda. Nula es la inicial:
 * mientras haya una sola, nadie pregunta dónde.
 */
data class SaleRequest(
    val lines: List<SaleLineRequest>,
    val paymentMethodId: Long,
    val date: BusinessDate? = null,
    val note: String? = null,
    val payments: List<SalePaymentRequest> = emptyList(),
    val orderAdjustment: Money = Money.ZERO,
    val heldSaleId: Long? = null,
    val tipAmount: Money = Money.ZERO,
    val customerId: Long? = null,
    val creditDueDate: BusinessDate? = null,
    val pointsRedeemed: Long = 0L,
    val priceListId: Long? = null,
    val locationId: Long? = null,
)
