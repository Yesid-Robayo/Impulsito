package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry

/** Lo que queda sin cubrir de un cargo del libro, con su vencimiento si tiene uno. */
data class OpenCharge(val entryId: Long, val remaining: Money, val dueDate: BusinessDate?)

/**
 * Lo que un cliente tiene pendiente, cargo por cargo, del más antiguo al más nuevo. La suma de
 * [openCharges] es lo que debe; con saldo a favor o en cero, no hay ninguno.
 */
data class CustomerDues(val openCharges: List<OpenCharge>) {
    /** El vencimiento más antiguo ya pasado en [today], o `null` si nada está vencido. */
    fun overdueSince(today: BusinessDate): BusinessDate? =
        openCharges.mapNotNull { it.dueDate }.filter { it < today }.minOrNull()

    /** El vencimiento más cercano desde [today] en adelante, o `null` si no hay ninguno. */
    fun nextDue(today: BusinessDate): BusinessDate? =
        openCharges.mapNotNull { it.dueDate }.filter { it >= today }.minOrNull()
}

/**
 * Qué está vencido en el libro de un cliente. Es solo una lectura: no escribe nada en el libro.
 *
 * Una fila compensada y su compensatorio se cancelan entre sí, y salen de la cuenta: una venta
 * anulada no deja un cargo vencido, y un abono anulado no cubre nada. Con lo demás, en el orden del
 * libro, cada abono, saldo a favor o ajuste a la baja cubre primero los cargos más antiguos, y lo
 * que sobra queda a favor para los que vengan. Un día vence al terminar: un cargo que vence hoy
 * todavía no está vencido.
 */
object CustomerDueRules {
    fun duesOf(entries: List<CustomerLedgerEntry>): CustomerDues {
        val reversed = entries.mapNotNull { (it.source as? CustomerEntrySource.Reverses)?.entryId }
            .toSet()
        val open = ArrayDeque<OpenCharge>()
        var credit = Money.ZERO
        for (entry in entries.sortedBy { it.id }) {
            if (entry.id in reversed || entry.source is CustomerEntrySource.Reverses) continue
            if (entry.amount > Money.ZERO) {
                val covered = minOf(credit, entry.amount)
                credit -= covered
                val remaining = entry.amount - covered
                if (remaining > Money.ZERO) {
                    open.addLast(OpenCharge(entry.id, remaining, entry.dueDate))
                }
            } else {
                credit += cover(open, -entry.amount)
            }
        }
        return CustomerDues(open.toList())
    }

    /**
     * Cubre con [amount] los cargos de [open], del más antiguo al más nuevo, y devuelve lo que
     * sobra, que queda a favor.
     */
    private fun cover(open: ArrayDeque<OpenCharge>, amount: Money): Money {
        var left = amount
        while (left > Money.ZERO && open.isNotEmpty()) {
            val oldest = open.removeFirst()
            if (oldest.remaining > left) {
                open.addFirst(oldest.copy(remaining = oldest.remaining - left))
                left = Money.ZERO
            } else {
                left -= oldest.remaining
            }
        }
        return left
    }
}
