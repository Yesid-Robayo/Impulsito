package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo vendido y lo cobrado de un día, separados: la venta a crédito cuenta como venta al venderse, y
 * el dinero cuenta al cobrarse.
 *
 * - [sold] suma `amount_due` de las ventas activas del día: lo que cada venta pidió cobrar.
 * - [collectedAtSale] suma los pagos de esas ventas que no quedaron a crédito.
 * - [collectedOnAccount] suma los abonos de clientes con fecha de ese día, sin los anulados.
 */
data class DayCollection(
    val date: BusinessDate,
    val sold: Money,
    val collectedAtSale: Money,
    val collectedOnAccount: Money,
)

/**
 * Lo vendido y lo cobrado de un período, aparte. Nunca se suman: lo vendido a
 * crédito queda como la diferencia, y se cobra después con abonos, quizá en otro período.
 */
data class SoldAndCollected(
    val sold: Money,
    val collectedAtSale: Money,
    val collectedOnAccount: Money,
) {
    /** Todo lo cobrado en el período: al vender y en abonos. */
    val collected: Money get() = collectedAtSale + collectedOnAccount

    companion object {
        val ZERO = SoldAndCollected(Money.ZERO, Money.ZERO, Money.ZERO)
    }
}
