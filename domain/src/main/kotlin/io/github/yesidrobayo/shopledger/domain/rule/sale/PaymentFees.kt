package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.timesRate

/**
 * La comisión y la fecha de acreditación de un pago, con los datos que la persona escribió en su
 * método. La app no trae ninguna tarifa: con un método sin comisión, la comisión es cero.
 */
object PaymentFees {
    /**
     * `fee_amount = roundDiv(amount × fee_bp, 10000) + fee_fixed`. La comisión baja la ganancia
     * final, nunca el margen. Se rechaza si pasa del tope de un documento.
     */
    fun feeOf(amount: Money, feeRate: BasisPoints, feeFixed: Money): Checked<Money> {
        require(amount >= Money.ZERO && feeFixed >= Money.ZERO) {
            "Ni el pago ni la comisión fija son negativos"
        }
        return when (val share = amount.timesRate(feeRate, Limit.DOCUMENT_TOTAL)) {
            is Checked.Value -> {
                // Se compara con lo que falta para el tope antes de sumar, así la suma nunca
                // desborda aunque la comisión fija sea absurda.
                val room = Limit.DOCUMENT_TOTAL.max - share.value.minor
                if (feeFixed.minor <= room) {
                    Checked.Value(share.value + feeFixed)
                } else {
                    Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
                }
            }

            else -> share
        }
    }

    /** `settlement_date = business_date + settlement_days`: cuándo llega el dinero del pago. */
    fun settlementDateOf(businessDate: BusinessDate, settlementDays: Int): BusinessDate {
        require(settlementDays >= 0) { "La acreditación no llega antes de la venta" }
        return businessDate.plusDays(settlementDays)
    }
}
