package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/** Lo que impide agregar una fila al libro del cliente. */
enum class CustomerEntryIssue {
    /** El monto es cero: una fila siempre mueve el saldo. */
    ZERO_AMOUNT,

    /** El signo no es el de su tipo: un cargo a crédito suma, un abono y una devolución restan. */
    WRONG_SIGN,

    /** El origen no es el de su tipo, como un cargo a crédito sin su venta. */
    WRONG_SOURCE,

    /** Un ajuste sin nota: una corrección a mano siempre dice por qué. */
    NOTE_REQUIRED,

    /** La nota pasa de 200 caracteres. */
    NOTE_TOO_LONG,

    /** El saldo que dejaría no cabe en un entero de 64 bits. */
    BALANCE_OVERFLOW,
}

/** Resultado de [CustomerLedgerRules.next]. */
sealed interface CustomerEntryCheck {
    /** Se puede agregar, y deja al cliente con [balanceAfter]. */
    data class Valid(val balanceAfter: Money) : CustomerEntryCheck

    data class Invalid(val issue: CustomerEntryIssue) : CustomerEntryCheck
}

/**
 * Las reglas del libro del cliente, que es de solo agregar como el de stock.
 *
 * El saldo del cliente es la suma de sus filas, y cada fila guarda el saldo que deja: el de la fila
 * anterior más su monto. Así el saldo nunca se escribe a mano y la cadena se comprueba fila por
 * fila. Positivo debe; negativo es saldo a favor, y puede quedar así tras anular una venta ya
 * abonada.
 */
object CustomerLedgerRules {
    /** Largo máximo de la nota de una fila. */
    const val NOTE_MAX: Int = 200

    /** El saldo que deja [draft] sobre [previous], el de la última fila, o por qué no entra. */
    fun next(previous: Money, draft: CustomerEntryDraft): CustomerEntryCheck {
        val issue = issueOf(draft)
        if (issue != null) return CustomerEntryCheck.Invalid(issue)
        return try {
            CustomerEntryCheck.Valid(previous + draft.amount)
        } catch (overflow: ArithmeticException) {
            CustomerEntryCheck.Invalid(CustomerEntryIssue.BALANCE_OVERFLOW)
        }
    }

    /**
     * La fila que compensa [original]: su monto opuesto, que apunta a ella, sin vencimiento. `null`
     * si [original] ya es un compensatorio: una anulación no se anula, se registra la operación
     * otra vez.
     */
    fun reversalOf(original: CustomerLedgerEntry, note: String?): CustomerEntryDraft? =
        if (original.type == CustomerLedgerEntryType.ENTRY_VOID) {
            null
        } else {
            CustomerEntryDraft(
                customerId = original.customerId,
                type = CustomerLedgerEntryType.ENTRY_VOID,
                amount = -original.amount,
                source = CustomerEntrySource.Reverses(original.id),
                dueDate = null,
                note = note,
            )
        }

    /**
     * El saldo a favor que tenía el cliente justo antes de una venta, leído de [charges], los
     * cargos a crédito de esa venta en el orden del libro: el saldo que dejó el primero menos su
     * monto. Cero si la venta no cargó nada a crédito o si el cliente debía. Solo lee: el saldo de
     * entonces ya está en la fila, y no cambia aunque después se anule la venta.
     */
    fun creditBefore(charges: List<CustomerLedgerEntry>): Money {
        val charge = charges.firstOrNull() ?: return Money.ZERO
        val before = charge.balanceAfter - charge.amount
        return if (before < Money.ZERO) -before else Money.ZERO
    }

    /** [raw] recortada, o `null` si queda en blanco: una nota vacía no se guarda. */
    fun note(raw: String?): String? = raw?.let { it.trim().ifEmpty { null } }

    private fun issueOf(draft: CustomerEntryDraft): CustomerEntryIssue? {
        val amount = draft.amount
        val note = draft.note
        return when {
            amount == Money.ZERO -> CustomerEntryIssue.ZERO_AMOUNT

            !draft.type.admits(positive = amount > Money.ZERO) -> CustomerEntryIssue.WRONG_SIGN

            !sourceFits(draft.type, draft.source) -> CustomerEntryIssue.WRONG_SOURCE

            note != null && CatalogText.length(note) > NOTE_MAX -> CustomerEntryIssue.NOTE_TOO_LONG

            draft.type == CustomerLedgerEntryType.ADJUSTMENT && note(note) == null ->
                CustomerEntryIssue.NOTE_REQUIRED

            else -> null
        }
    }

    private fun sourceFits(type: CustomerLedgerEntryType, source: CustomerEntrySource): Boolean =
        when (type) {
            CustomerLedgerEntryType.CREDIT_SALE -> source is CustomerEntrySource.Sale

            CustomerLedgerEntryType.RETURN_CREDIT -> source is CustomerEntrySource.SaleReturn

            CustomerLedgerEntryType.ENTRY_VOID -> source is CustomerEntrySource.Reverses

            CustomerLedgerEntryType.OPENING_BALANCE,
            CustomerLedgerEntryType.PAYMENT,
            CustomerLedgerEntryType.ADJUSTMENT,
            -> source == CustomerEntrySource.None
        }
}
