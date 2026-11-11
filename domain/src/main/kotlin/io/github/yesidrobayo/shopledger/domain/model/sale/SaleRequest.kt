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
)
