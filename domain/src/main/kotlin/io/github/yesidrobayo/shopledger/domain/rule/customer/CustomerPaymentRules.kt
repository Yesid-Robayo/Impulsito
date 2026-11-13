package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money

/** Lo que impide registrar un abono antes de tocar el libro. */
enum class CustomerPaymentIssue {
    /** El monto es cero o negativo: un abono siempre trae dinero. */
    AMOUNT_NOT_POSITIVE,

    /** El monto pasa del tope de un documento: no cabría en las sumas que vienen después. */
    AMOUNT_TOO_LARGE,
}

/**
 * Cómo queda la cuenta del cliente después de un abono. La hoja del abono lo muestra en vivo, antes
 * de registrarlo.
 */
sealed interface PaymentPreview {
    /** Sigue debiendo [balance], que puede ser cero: la cuenta queda al día. */
    data class RemainingDebt(val balance: Money) : PaymentPreview

    /** El abono pasa de la deuda, y queda [credit] a favor, siempre mayor que cero. */
    data class CreditLeft(val credit: Money) : PaymentPreview

    /** El saldo que dejaría no cabe en un entero de 64 bits: el abono no se registra. */
    data object Overflow : PaymentPreview
}

/** Lo que un abono cubre de un cargo pendiente, [covered], con el vencimiento del cargo. */
data class CoveredCharge(val entryId: Long, val covered: Money, val dueDate: BusinessDate?)

/**
 * Cómo se reparte un abono: los cargos que cubre, del más antiguo al más nuevo, y lo que sobra,
 * [creditLeft], que queda a favor del cliente.
 */
data class PaymentAllocation(val covered: List<CoveredCharge>, val creditLeft: Money)

/**
 * Las reglas de un abono del cliente: lo que paga después por lo que se llevó a crédito, o lo que
 * deja por adelantado.
 *
 * Un abono es una fila `PAYMENT` del libro, con el monto en negativo. Nunca edita un cargo: el
 * reparto sobre los cargos es solo una lectura, igual que el vencimiento, y cubre primero lo más
 * antiguo. Lo que pasa de la deuda no se rechaza: queda a favor, y la próxima venta a crédito lo
 * consume.
 */
object CustomerPaymentRules {
    /** Por qué [amount], lo que el cliente entrega, no sirve como abono, o `null` si sirve. */
    fun check(amount: Money): CustomerPaymentIssue? = when {
        amount <= Money.ZERO -> CustomerPaymentIssue.AMOUNT_NOT_POSITIVE
        !Limit.DOCUMENT_TOTAL.allows(amount.minor) -> CustomerPaymentIssue.AMOUNT_TOO_LARGE
        else -> null
    }

    /** Cómo queda un cliente con saldo [balance] después de abonar [amount], ya comprobado. */
    fun preview(balance: Money, amount: Money): PaymentPreview {
        val after = try {
            balance - amount
        } catch (overflow: ArithmeticException) {
            return PaymentPreview.Overflow
        }
        return if (after < Money.ZERO) {
            PaymentPreview.CreditLeft(-after)
        } else {
            PaymentPreview.RemainingDebt(after)
        }
    }

    /**
     * Reparte [amount] sobre los cargos pendientes de [dues], que ya vienen del más antiguo al más
     * nuevo. Cada cargo se cubre entero antes de pasar al siguiente, y lo que sobra queda a favor.
     */
    fun allocate(dues: CustomerDues, amount: Money): PaymentAllocation {
        var left = amount
        val covered = mutableListOf<CoveredCharge>()
        for (charge in dues.openCharges) {
            if (left <= Money.ZERO) break
            val part = minOf(left, charge.remaining)
            covered += CoveredCharge(charge.entryId, part, charge.dueDate)
            left -= part
        }
        return PaymentAllocation(covered, left)
    }
}
