package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Cuánto de la propina lleva cada pago escrito.
 *
 * La propina no es una venta: no entra en lo que cobran las líneas ni en ninguna ganancia. Por eso
 * no paga el recargo o el descuento de un método, que sí se reparte entre las líneas: el recargo de
 * cada pago se calcula sobre lo que cubre sin su parte de la propina. La comisión, en cambio, sí se
 * cobra sobre todo el pago, propina incluida.
 *
 * La propina se reparte entre los pagos en proporción a lo que cubre cada uno, como un ajuste de la
 * venta entre sus líneas: el pago mayor absorbe el residuo del redondeo.
 */
object PaymentTips {
    /**
     * La parte de [tip] de cada uno de [amounts], en el mismo orden. Nunca pasa de lo que cubre el
     * pago: si lo escrito todavía no alcanza para la propina, el pago lleva solo lo que tiene, y el
     * cobro no cuadra hasta corregirlo. Sin propina o sin pagos, cada parte es cero.
     */
    fun shares(tip: Money, amounts: List<Money>): List<Money> {
        require(tip >= Money.ZERO) { "Una propina no es negativa" }
        if (tip == Money.ZERO || amounts.isEmpty()) return amounts.map { Money.ZERO }
        // Un ajuste positivo siempre se reparte: no hay descuento que supere a las bases.
        val split = OrderAdjustmentShares.split(tip, amounts) as AdjustmentSplit.Shares
        return split.shares.zip(amounts) { share, amount -> minOf(share, amount) }
    }
}
