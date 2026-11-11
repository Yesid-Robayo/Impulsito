package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import kotlin.time.Instant

/**
 * Un pago de una venta que el caso de uso va a guardar, todavía sin id. Una venta tiene uno por
 * método, al menos uno, también con total cero, y sus pagos suman lo que la venta cobra.
 *
 * [methodNameSnapshot] y [methodKind] se copian del método al cobrar, para que renombrarlo después
 * no cambie esta venta. [amount] es lo que cubre este pago, [feeAmount] su comisión, calculada con
 * los datos del método o escrita por la persona en la venta, y
 * [settlementDate] la fecha de negocio en que se acredita. [moneyAccountId] es la cuenta del
 * método, si tiene.
 *
 * [tenderedAmount] es lo que se recibió en efectivo y [changeAmount] el cambio que se devolvió, solo
 * en un pago en efectivo que lo escribió, y siempre juntos: lo recibido menos el cambio es [amount].
 * El cambio no mueve dinero aparte: el pago entra por [amount]. El cobro en otra moneda todavía no se
 * guarda.
 */
data class NewSalePayment(
    val paymentMethodId: Long,
    val methodNameSnapshot: String,
    val methodKind: PaymentMethodKind,
    val amount: Money,
    val feeAmount: Money,
    val settlementDate: BusinessDate,
    val moneyAccountId: Long?,
    val createdAt: Instant,
    val tenderedAmount: Money? = null,
    val changeAmount: Money? = null,
) {
    /**
     * Lo que de verdad llega por este pago: `amount - fee_amount`. La comisión baja la ganancia
     * final, nunca el margen. Puede ser negativo si la comisión fija pasa del pago.
     */
    val netReceived: Money get() = amount - feeAmount
}
