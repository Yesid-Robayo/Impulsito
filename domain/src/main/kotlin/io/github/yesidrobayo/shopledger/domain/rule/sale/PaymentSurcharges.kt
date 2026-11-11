package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.rule.timesRate

/**
 * El recargo o descuento que cada método de pago suma a la venta, con signo, y cuánto termina
 * cubriendo cada pago.
 *
 * El recargo de un pago es `roundDiv(monto × price_adjustment_bp, 10000)`, sobre el monto de ese
 * método antes del recargo. Los recargos de todos los pagos suman el ajuste de la venta, que se
 * reparte entre sus líneas como cualquier ajuste: entra en lo que cada línea cobra, y por eso
 * también paga impuesto en proporción.
 *
 * Como el impuesto del recargo puede subir el total más que el recargo mismo, cada pago cubre lo
 * que su propio recargo sube el total. Los pagos se recorren en orden: el pago `i` cubre su monto
 * antes del recargo, más el total con los recargos de los pagos `1..i`, menos el total con los de
 * `1..i-1`. Así cada pago lleva su recargo, el impuesto que ese recargo provoca y su redondeo, y la
 * suma de los pagos es exactamente lo que la venta cobra, también con descuentos.
 */
object PaymentSurcharges {
    /**
     * El recargo de un pago de [amount] con el ajuste [rate] de su método, de −100 % a +100 %:
     * negativo si es un descuento. Nunca pasa del pago en valor absoluto, así que siempre cabe.
     */
    fun surchargeOf(amount: Money, rate: BasisPoints): Money {
        require(amount >= Money.ZERO) { "Un pago no es negativo" }
        require(rate.bp in -Scale.BASIS_POINTS..Scale.BASIS_POINTS) {
            "El ajuste de un método va de −100 % a +100 %"
        }
        // Con la tasa dentro de ±100 %, el resultado no pasa del pago: no hay rechazo que mirar.
        return (amount.timesRate(rate) as Checked.Value).value
    }

    /**
     * Lo que cubre cada pago, en orden, con [amounts] antes del recargo y [rates] el ajuste del
     * método de cada uno. [totalWith] da el total de la venta con un ajuste dado, o `null` si la
     * venta no lo admite: un descuento mayor que lo vendido o un total sobre el tope.
     */
    fun settle(
        amounts: List<Money>,
        rates: List<BasisPoints>,
        totalWith: (adjustment: Money) -> Money?,
    ): SurchargeSettlement {
        require(amounts.size == rates.size) { "Cada pago tiene el ajuste de su método" }
        var adjustment = Money.ZERO
        var totalBefore = totalWith(adjustment) ?: return SurchargeSettlement.NotAllowed
        val covered = ArrayList<Money>(amounts.size)
        for ((index, amount) in amounts.withIndex()) {
            adjustment += surchargeOf(amount, rates[index])
            val totalAfter = totalWith(adjustment) ?: return SurchargeSettlement.NotAllowed
            val payment = amount + totalAfter - totalBefore
            if (payment < Money.ZERO) return SurchargeSettlement.BelowZero(index)
            covered += payment
            totalBefore = totalAfter
        }
        return SurchargeSettlement.Settled(adjustment, covered)
    }
}

/** Cómo quedaron los pagos después de sumar el recargo o descuento de cada método. */
sealed interface SurchargeSettlement {
    /**
     * [adjustment] es la suma de los recargos, el ajuste de la venta, y [amounts] lo que cubre cada
     * pago, en el orden en que llegaron. Suman el total de la venta con ese ajuste.
     */
    data class Settled(val adjustment: Money, val amounts: List<Money>) : SurchargeSettlement

    /** La venta no admite el ajuste que suman los recargos. */
    data object NotAllowed : SurchargeSettlement

    /**
     * El pago número [index] quedaría negativo: su descuento, con el impuesto que deja de
     * cobrarse, baja el total más que lo que el pago cubría.
     */
    data class BelowZero(val index: Int) : SurchargeSettlement
}
