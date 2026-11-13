package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryCheck
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerLedgerRules

/** Cómo termina agregar una fila al libro del cliente. */
sealed interface CustomerEntryOutcome {
    /** La fila quedó con el id [entryId], y el cliente con [balanceAfter]. */
    data class Appended(val entryId: Long, val balanceAfter: Money) : CustomerEntryOutcome

    /** La fila no entra, por [issue]. No se escribió nada. */
    data class Rejected(val issue: CustomerEntryIssue) : CustomerEntryOutcome

    /** La fila que se quería compensar no existe, o es de otro cliente. */
    data object EntryNotFound : CustomerEntryOutcome

    /** La fila ya tiene su compensatorio: una fila se compensa una sola vez. */
    data object AlreadyCompensated : CustomerEntryOutcome

    /** La fila es un compensatorio: una anulación no se anula. */
    data object NotReversible : CustomerEntryOutcome
}

/**
 * Escribe en el libro del cliente desde cualquier caso de uso: una venta a crédito, un abono, una
 * devolución, un ajuste o una anulación. Siempre corre dentro de la transacción del caso de uso
 * que lo llama, que es la que confirma o deshace todo junto.
 *
 * El saldo que deja cada fila sale de la última fila del cliente más su monto, nunca de lo que diga
 * su cabecera, y la cabecera queda con ese mismo valor: así el saldo siempre es la suma del libro.
 */
class CustomerLedgerWriter(private val ledger: CustomerLedgerRepository) {
    /** Agrega [draft] con el instante y la fecha de [time]. */
    suspend fun append(draft: CustomerEntryDraft, time: RecordTime): CustomerEntryOutcome {
        val clean = draft.copy(note = CustomerLedgerRules.note(draft.note))
        val previous = ledger.lastBalanceAfter(clean.customerId) ?: Money.ZERO
        return when (val check = CustomerLedgerRules.next(previous, clean)) {
            is CustomerEntryCheck.Invalid -> CustomerEntryOutcome.Rejected(check.issue)

            is CustomerEntryCheck.Valid -> {
                val id = ledger.append(
                    NewCustomerLedgerEntry(
                        draft = clean,
                        balanceAfter = check.balanceAfter,
                        occurredAt = time.occurredAt,
                        businessDate = time.businessDate,
                    ),
                )
                CustomerEntryOutcome.Appended(id, check.balanceAfter)
            }
        }
    }

    /**
     * Compensa la fila [entryId] de [customerId] con su monto opuesto. El saldo puede quedar
     * negativo: lo ya abonado de una venta anulada queda como saldo a favor.
     */
    suspend fun reverse(
        customerId: Long,
        entryId: Long,
        note: String?,
        time: RecordTime,
    ): CustomerEntryOutcome {
        val original = ledger.findById(entryId)
            ?.takeIf { it.customerId == customerId }
            ?: return CustomerEntryOutcome.EntryNotFound
        val reversal = CustomerLedgerRules.reversalOf(original, note)
            ?: return CustomerEntryOutcome.NotReversible
        if (ledger.isCompensated(entryId)) return CustomerEntryOutcome.AlreadyCompensated
        return append(reversal, time)
    }
}
