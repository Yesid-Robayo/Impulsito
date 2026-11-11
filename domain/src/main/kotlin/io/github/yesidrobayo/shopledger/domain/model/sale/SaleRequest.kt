package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate

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
 */
data class SaleRequest(
    val lines: List<SaleLineRequest>,
    val paymentMethodId: Long,
    val date: BusinessDate? = null,
    val note: String? = null,
    val payments: List<SalePaymentRequest> = emptyList(),
)
