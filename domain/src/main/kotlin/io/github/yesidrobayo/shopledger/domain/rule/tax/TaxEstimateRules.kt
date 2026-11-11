package io.github.yesidrobayo.shopledger.domain.rule.tax

import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals

/** Cómo se arma el impuesto estimado de un período a partir de sus sumas. */
object TaxEstimateRules {
    /**
     * Lo cobrado es el impuesto de las ventas menos el devuelto, y lo pagado el de las compras. Con
     * 52500 cobrados, 2100 devueltos y 21000 pagados, da 50400 y 21000.
     *
     * Lo cobrado puede quedar negativo, si en el período se devolvió más impuesto del que se cobró:
     * una devolución resta en su propia fecha. Las dos sumas son positivas o cero, así que la resta
     * nunca se desborda.
     */
    fun estimate(totals: TaxTotals): TaxEstimate = TaxEstimate(
        collected = totals.salesTax - totals.returnsTaxRefund,
        paid = totals.purchasesTax,
    )
}
