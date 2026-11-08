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
 * no cambie esta venta. [amount] es lo que cubre este pago, [feeAmount] su comisión y
 * [settlementDate] la fecha de negocio en que se acredita. [moneyAccountId] es la cuenta del
 * método, si tiene.
 *
 * Lo recibido y el cambio en efectivo, y el cobro en otra moneda, llegan con la pantalla de cobro:
 * aquí todavía no se guardan.
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
)
