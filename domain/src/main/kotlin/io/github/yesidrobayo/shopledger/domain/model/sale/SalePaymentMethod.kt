package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind

/**
 * Lo que una venta necesita saber de un método de pago para cobrar con él y congelarlo en su pago.
 *
 * Una venta copia el nombre y el tipo del método, así que renombrarlo o archivarlo después no
 * cambia ninguna venta pasada. Un método inicial no tiene [name] mientras nadie lo renombre:
 * entonces el nombre que se congela es el texto de su [systemKey] en el idioma de la interfaz al
 * confirmar.
 *
 * [feeRate] y [feeFixed] son la comisión que escribió la persona, nunca una tarifa de la app, y
 * [settlementDays] cuántos días tarda en llegar el dinero. [moneyAccountId] es la cuenta a la que
 * entra lo cobrado; sin cuenta no se mueve dinero. Archivado, el método no se ofrece al cobrar.
 */
data class SalePaymentMethod(
    val id: Long,
    val systemKey: String?,
    val name: String?,
    val kind: PaymentMethodKind,
    val feeRate: BasisPoints,
    val feeFixed: Money,
    val settlementDays: Int,
    val moneyAccountId: Long?,
    val isArchived: Boolean,
)
