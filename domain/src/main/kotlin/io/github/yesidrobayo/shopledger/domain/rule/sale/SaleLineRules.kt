package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import io.github.yesidrobayo.shopledger.domain.rule.timesRate

/**
 * Las cuentas de una línea de venta que no dependen del stock: el bruto, el descuento y el costo de
 * reposición. Cada resultado se guarda tal cual en la línea, y los reportes suman lo guardado sin
 * volver a calcular nada desde el precio.
 */
object SaleLineRules {
    /**
     * `line_gross`: [quantity] milésimas a [unitPrice] por unidad entera, redondeado. No se guarda,
     * pero de él salen el descuento y el total. Se rechaza si pasa del tope de una línea.
     */
    fun gross(quantity: QuantityMilli, unitPrice: Money): Checked<Money> =
        unitPrice.timesQuantity(quantity, Limit.DOCUMENT_TOTAL)

    /**
     * `line_discount`: el monto que descuenta [discount] de una línea con bruto [gross], o `null`
     * si no vale.
     *
     * Un monto vale de cero hasta el bruto: descontar más que el bruto daría una línea negativa. Un
     * porcentaje vale de 0 a 100 %, y se convierte sobre el bruto con redondeo al entero más
     * cercano: el 12,50 % de 12345 es 1543. El porcentaje no se guarda.
     */
    fun discountOf(gross: Money, discount: LineDiscount): Money? = when (discount) {
        LineDiscount.None -> Money.ZERO

        is LineDiscount.Amount ->
            discount.amount.takeIf { it >= Money.ZERO && it <= gross }

        is LineDiscount.Percent ->
            // Con la tasa entre 0 y 100 %, el resultado nunca pasa del bruto y siempre cabe.
            if (discount.rate >= BasisPoints.ZERO &&
                discount.rate <= BasisPoints.ONE_HUNDRED_PERCENT
            ) {
                gross.timesRate(discount.rate).valueOrNull()
            } else {
                null
            }
    }

    /**
     * `unit_replacement_cost`: lo que costaría reponer hoy una unidad entera. Es el costo de
     * reposición del producto si lo tiene, o el costo que congela la línea si vale cero. Es solo
     * una vista aparte: la ganancia sigue saliendo del costo de la línea.
     */
    fun unitReplacementCost(replacementCost: Money, lineUnitCost: Money): Money =
        if (replacementCost > Money.ZERO) replacementCost else lineUnitCost

    /** `line_replacement_cost`: [quantity] milésimas a [unitReplacementCost], redondeado. */
    fun lineReplacementCost(quantity: QuantityMilli, unitReplacementCost: Money): Checked<Money> =
        unitReplacementCost.timesQuantity(quantity, Limit.DOCUMENT_TOTAL)
}
