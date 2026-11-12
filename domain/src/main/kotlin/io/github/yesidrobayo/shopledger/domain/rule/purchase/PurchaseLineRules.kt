package io.github.yesidrobayo.shopledger.domain.rule.purchase

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * Las cuentas de una línea de compra antes del impuesto.
 *
 * La persona escribe el costo unitario o el total de la línea, y el otro valor se deduce: el
 * documento del proveedor puede traer cualquiera de los dos. Los dos se guardan, y el costo
 * promedio usa siempre el total: tres kilogramos por 25000 son 8333 por kilogramo, pero entran
 * 25000 al valor del stock, no 24999.
 */
object PurchaseLineRules {
    /**
     * El costo unitario y el total de [quantity] milésimas, según cómo se escribió [amount].
     *
     * - Con costo unitario, el total es `mulDivRound(cantidad, costo, 1000)`.
     * - Con total, el costo unitario es `roundDiv(total × 1000, cantidad)`.
     *
     * Rechaza con [Checked.AboveLimit] un costo unitario sobre [Limit.UNIT_MONEY] o un total sobre
     * [Limit.DOCUMENT_TOTAL]: el total de una línea mínima por un monto enorme daría un costo
     * unitario que ninguna cuenta posterior soporta. [quantity] debe ser positiva y el monto no
     * negativo: lo revisa quien llama, que tiene el motivo para la pantalla.
     */
    fun amounts(quantity: QuantityMilli, amount: PurchaseLineAmount): Checked<PurchaseLineAmounts> {
        require(quantity.milli > 0L) { "La cantidad de una línea de compra es positiva" }
        return when (amount) {
            is PurchaseLineAmount.UnitCost -> {
                require(amount.cost >= Money.ZERO) { "El costo de una compra no es negativo" }
                if (!Limit.UNIT_MONEY.allows(amount.cost.minor)) {
                    Checked.AboveLimit(Limit.UNIT_MONEY)
                } else {
                    amount.cost.timesQuantity(quantity, Limit.DOCUMENT_TOTAL)
                        .map { total -> PurchaseLineAmounts(amount.cost, total) }
                }
            }

            is PurchaseLineAmount.LineTotal -> {
                costPerUnit(quantity, amount.total)
                    .map { cost -> PurchaseLineAmounts(cost, amount.total) }
            }
        }
    }

    /**
     * Lo mismo que [amounts], con el rechazo ya dicho como lo entiende la persona: el costo
     * unitario o el total de la línea pasan de su tope. Una cuenta que no cabe en `Long` es un total
     * demasiado grande.
     */
    fun check(quantity: QuantityMilli, amount: PurchaseLineAmount): PurchaseLineCheck =
        when (val checked = amounts(quantity, amount)) {
            is Checked.Value -> PurchaseLineCheck.Ready(checked.value)

            is Checked.AboveLimit -> if (checked.limit == Limit.UNIT_MONEY) {
                PurchaseLineCheck.UnitCostTooLarge
            } else {
                PurchaseLineCheck.LineTotalTooLarge
            }

            Checked.Overflow -> PurchaseLineCheck.LineTotalTooLarge
        }

    /**
     * El costo de una unidad entera cuando [quantity] milésimas valen [total]: `roundDiv(total ×
     * 1000, cantidad)`. Con la cantidad en la unidad base y lo que entró al valor del stock, es el
     * costo que congela la fila del libro. Rechaza un total sobre [Limit.DOCUMENT_TOTAL] o un costo
     * sobre [Limit.UNIT_MONEY].
     */
    fun costPerUnit(quantity: QuantityMilli, total: Money): Checked<Money> {
        require(quantity.milli > 0L) { "La cantidad de una línea de compra es positiva" }
        require(total >= Money.ZERO) { "El costo de una compra no es negativo" }
        if (!Limit.DOCUMENT_TOTAL.allows(total.minor)) {
            return Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
        }
        // Un total del documento por mil todavía cabe en Long: así lo garantiza su tope.
        val cost = roundDiv(total.minor * Scale.MILLI, quantity.milli)
        return if (Limit.UNIT_MONEY.allows(cost)) {
            Checked.Value(Money(cost))
        } else {
            Checked.AboveLimit(Limit.UNIT_MONEY)
        }
    }
}
