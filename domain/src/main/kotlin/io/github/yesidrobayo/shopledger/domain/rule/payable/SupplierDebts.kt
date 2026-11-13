package io.github.yesidrobayo.shopledger.domain.rule.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType

/**
 * Qué parte de lo que se le debe a un proveedor sigue abierta, y cuándo vence. Es solo una lectura:
 * no escribe nada en el libro.
 *
 * Como con los clientes, lo que resta (pagos, créditos por devolución y ajustes a la baja) cubre
 * los cargos más antiguos primero, en el orden del libro. Una fila compensada y su compensación no
 * cuentan: es como si no hubieran existido. Lo que resta de más queda como saldo a favor.
 *
 * Con cargos de 5000 que vence el 2026-11-10 y de 3000 que vence el 2026-11-20, y un pago de
 * 6000, quedan 2000 del segundo cargo, y nada está vencido hasta pasar el 2026-11-20.
 */
object SupplierDebts {
    /**
     * Lo abierto de [entries], las filas de un solo proveedor. Devuelve `null` si las sumas no caben
     * en un monto: un libro así solo sale de cifras absurdas o de una copia dañada.
     */
    fun of(entries: List<SupplierLedgerEntry>): SupplierDebt? = try {
        open(entries)
    } catch (overflow: ArithmeticException) {
        null
    }

    private fun open(entries: List<SupplierLedgerEntry>): SupplierDebt {
        val reversed = entries.mapNotNullTo(HashSet()) { it.content.reversesEntryId }
        val live = entries.sortedBy { it.id }.filter {
            it.content.type != SupplierLedgerEntryType.ENTRY_VOID && it.id !in reversed
        }
        var credit = Money.ZERO
        for (entry in live) {
            if (entry.content.amount < Money.ZERO) credit -= entry.content.amount
        }
        val open = ArrayList<OpenCharge>()
        for (charge in live.filter { it.content.amount > Money.ZERO }) {
            val amount = charge.content.amount
            val covered = minOf(credit, amount)
            credit -= covered
            if (covered < amount) {
                open += OpenCharge(
                    entryId = charge.id,
                    purchaseId = charge.content.purchaseId,
                    dueDate = charge.content.dueDate,
                    remaining = amount - covered,
                )
            }
        }
        return SupplierDebt(open, credit)
    }
}

/**
 * Lo que falta pagar de un cargo: [remaining] de la fila [entryId], de la compra [purchaseId] si
 * salió de una, con su vencimiento [dueDate], si tiene.
 */
data class OpenCharge(
    val entryId: Long,
    val purchaseId: Long?,
    val dueDate: BusinessDate?,
    val remaining: Money,
)

/**
 * Lo abierto con un proveedor: los cargos con algo por pagar, del más antiguo al más nuevo, y el
 * saldo a favor del negocio, que solo existe si no queda ningún cargo abierto.
 */
data class SupplierDebt(val openCharges: List<OpenCharge>, val creditInFavor: Money) {
    /** Lo que se le debe: la suma de lo abierto. */
    val owed: Money get() = openCharges.fold(Money.ZERO) { sum, charge -> sum + charge.remaining }

    /**
     * Los cargos vencidos a la fecha de negocio [today]: los que vencían antes de hoy. Uno que
     * vence hoy todavía no está vencido.
     */
    fun overdue(today: BusinessDate): List<OpenCharge> = openCharges.filter { charge ->
        val due = charge.dueDate
        due != null && due < today
    }

    /** Desde cuándo está vencido: el vencimiento más antiguo de lo vencido, o `null`. */
    fun overdueSince(today: BusinessDate): BusinessDate? =
        openCharges.mapNotNull { it.dueDate }.filter { it < today }.minOrNull()

    /** Lo vencido a [today]. */
    fun overdueAmount(today: BusinessDate): Money =
        overdue(today).fold(Money.ZERO) { sum, charge -> sum + charge.remaining }

    /** El próximo vencimiento desde [today], incluido hoy, o `null` si no queda ninguno. */
    fun nextDue(today: BusinessDate): BusinessDate? =
        openCharges.mapNotNull { it.dueDate }.filter { it >= today }.minOrNull()

    /** Lo que vence entre [today] y [until], los dos incluidos. */
    fun dueBetween(today: BusinessDate, until: BusinessDate): Money = openCharges
        .filter { charge ->
            val due = charge.dueDate
            due != null && due in today..until
        }
        .fold(Money.ZERO) { sum, charge -> sum + charge.remaining }
}
