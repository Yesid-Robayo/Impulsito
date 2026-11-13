package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.NewLoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.LoyaltyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyEntryCheck
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyLedgerRules

/** Cómo termina agregar una fila al libro de puntos. */
sealed interface LoyaltyEntryOutcome {
    /** La fila quedó con el id [entryId], y el cliente con [balanceAfter] puntos. */
    data class Appended(val entryId: Long, val balanceAfter: Long) : LoyaltyEntryOutcome

    /** La fila no entra, por [issue]. No se escribió nada. */
    data class Rejected(val issue: LoyaltyEntryIssue) : LoyaltyEntryOutcome

    /** La fila que se quería compensar no existe, o es de otro cliente. */
    data object EntryNotFound : LoyaltyEntryOutcome

    /** La fila ya tiene su compensatorio: una fila se compensa una sola vez. */
    data object AlreadyCompensated : LoyaltyEntryOutcome

    /** La fila es un compensatorio: una anulación no se anula. */
    data object NotReversible : LoyaltyEntryOutcome
}

/**
 * Escribe en el libro de puntos desde cualquier caso de uso: una venta que gana o canjea, o su
 * anulación. Siempre corre dentro de la transacción del caso de uso que lo llama, que es la que
 * confirma o deshace todo junto.
 *
 * El saldo que deja cada fila sale de la última fila del cliente más sus puntos, nunca de lo que
 * diga su cabecera, y la cabecera queda con ese mismo valor: así el saldo de puntos siempre es la
 * suma del libro.
 */
class LoyaltyLedgerWriter(private val ledger: LoyaltyLedgerRepository) {
    /** El saldo de puntos de [customerId] según su libro: cero si no tiene ninguna fila. */
    suspend fun balanceOf(customerId: Long): Long = ledger.lastBalanceAfter(customerId) ?: 0L

    /** Agrega [draft] con el instante y la fecha de [time]. */
    suspend fun append(draft: LoyaltyEntryDraft, time: RecordTime): LoyaltyEntryOutcome {
        val clean = draft.copy(note = LoyaltyLedgerRules.note(draft.note))
        return when (val check = LoyaltyLedgerRules.next(balanceOf(clean.customerId), clean)) {
            is LoyaltyEntryCheck.Invalid -> LoyaltyEntryOutcome.Rejected(check.issue)

            is LoyaltyEntryCheck.Valid -> {
                val id = ledger.append(
                    NewLoyaltyLedgerEntry(
                        draft = clean,
                        balanceAfter = check.balanceAfter,
                        occurredAt = time.occurredAt,
                        businessDate = time.businessDate,
                    ),
                )
                LoyaltyEntryOutcome.Appended(id, check.balanceAfter)
            }
        }
    }

    /**
     * Compensa la fila [entryId] de [customerId] con sus puntos opuestos. El saldo puede quedar
     * negativo: los puntos de una venta anulada se descuentan aunque ya se hayan canjeado.
     */
    suspend fun reverse(
        customerId: Long,
        entryId: Long,
        note: String?,
        time: RecordTime,
    ): LoyaltyEntryOutcome {
        val original = ledger.findById(entryId)
            ?.takeIf { it.customerId == customerId }
            ?: return LoyaltyEntryOutcome.EntryNotFound
        val reversal = LoyaltyLedgerRules.reversalOf(original, note)
            ?: return LoyaltyEntryOutcome.NotReversible
        if (ledger.isCompensated(entryId)) return LoyaltyEntryOutcome.AlreadyCompensated
        return append(reversal, time)
    }
}
