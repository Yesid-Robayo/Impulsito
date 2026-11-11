package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator

/**
 * Lo que dejó una venta: su costo, la ganancia, el margen, las comisiones y lo que llega de verdad.
 * Solo se muestra en el detalle, después de vender: al registrar, quien compra puede estar mirando.
 *
 * - La ganancia es lo vendido sin impuesto menos el costo congelado de sus líneas.
 * - El margen sale de esas dos sumas, nunca del promedio de las líneas.
 * - El neto recibido es lo cobrado menos las comisiones de sus pagos.
 */
data class SaleResults(
    val cost: Money,
    val profit: Money,
    val margin: Margin,
    val fees: Money,
    val netReceived: Money,
) {
    companion object {
        /**
         * Las cifras de [sale] con sus [payments]. Cada monto está dentro del tope de un documento,
         * así que ninguna resta ni suma desborda.
         */
        fun of(sale: Sale, payments: List<SalePayment>): SaleResults {
            val content = sale.content
            val fees = payments.fold(Money.ZERO) { sum, it -> sum + it.content.feeAmount }
            val paid = payments.fold(Money.ZERO) { sum, it -> sum + it.content.amount }
            return SaleResults(
                cost = content.totalCost,
                profit = content.netTotal - content.totalCost,
                margin = MarginCalculator.margin(content.netTotal, content.totalCost),
                fees = fees,
                netReceived = paid - fees,
            )
        }
    }
}
