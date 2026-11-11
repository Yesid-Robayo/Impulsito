package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest

/**
 * Cómo se reparte entre varios pagos lo que cobra una venta: un pago por método, y la suma de los
 * pagos igual a lo que se cobra, sin una unidad de más ni de menos. Sin esa igualdad la venta no se
 * confirma, porque el reporte por método sumaría otra cifra que la venta.
 */
object PaymentSplit {
    /**
     * El primer pago de [payments] que no se puede guardar, con su posición y su motivo, o `null`
     * si todos se pueden. Se mira sin leer la base: un método que se repite y un monto fuera de
     * rango.
     */
    fun firstRejection(payments: List<SalePaymentRequest>): IndexedValue<PaymentRejection>? {
        val seen = HashSet<Long>()
        for ((index, payment) in payments.withIndex()) {
            val reason = when {
                !isValidAmount(payment.amount) -> PaymentRejection.INVALID_AMOUNT
                !seen.add(payment.paymentMethodId) -> PaymentRejection.DUPLICATE_METHOD
                else -> null
            }
            if (reason != null) return IndexedValue(index, reason)
        }
        return null
    }

    /**
     * Si [amounts] suman exactamente [amountDue]. Cada monto debe estar dentro de su rango, como
     * lo comprueba [firstRejection]. La suma se corta en cuanto pasa del tope de un documento, así
     * que nunca desborda.
     */
    fun check(amountDue: Money, amounts: List<Money>): PaymentSplitCheck {
        require(amounts.all(::isValidAmount)) { "Cada pago está dentro de su rango" }
        var paid = Money.ZERO
        for (amount in amounts) {
            paid += amount
            if (!Limit.DOCUMENT_TOTAL.allows(paid.minor)) return PaymentSplitCheck.AboveLimit
        }
        val matches = paid == amountDue
        return if (matches) PaymentSplitCheck.Matches else PaymentSplitCheck.Mismatch(paid)
    }

    private fun isValidAmount(amount: Money): Boolean =
        amount >= Money.ZERO && Limit.DOCUMENT_TOTAL.allows(amount.minor)
}

/** Si los pagos de una venta suman lo que la venta cobra. */
sealed interface PaymentSplitCheck {
    /** Los pagos suman exactamente lo que se cobra. */
    data object Matches : PaymentSplitCheck

    /** Los pagos suman [paid], que no es lo que se cobra. */
    data class Mismatch(val paid: Money) : PaymentSplitCheck

    /** Los pagos suman más que el tope de un documento: ninguna venta cobra tanto. */
    data object AboveLimit : PaymentSplitCheck
}
