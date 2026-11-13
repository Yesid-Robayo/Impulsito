package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin

/**
 * Lo que se deriva del libro de dinero: saldos, la cadena de saldos de cada movimiento y las
 * transferencias completas.
 *
 * El libro es la verdad. El saldo de cada cuenta es una copia que se escribe en la misma transacción
 * que su movimiento, y siempre debe ser igual a la suma de sus movimientos, cada uno en la moneda de
 * su cuenta. Estas funciones son esas sumas, escritas una sola vez: las usan los casos de uso, las
 * pruebas y la revisión de una copia.
 */
object MoneyLedger {
    /**
     * Los movimientos en el orden del libro: por id ascendente, nunca por fecha. Un movimiento con
     * fecha pasada no reordena saldos ya escritos.
     *
     * Lanza [IllegalArgumentException] si dos movimientos comparten id: un libro así está roto.
     */
    fun inOrder(movements: Iterable<MoneyMovement>): List<MoneyMovement> {
        val ordered = movements.sortedBy { it.id }
        for (index in 1 until ordered.size) {
            require(ordered[index].id != ordered[index - 1].id) {
                "Dos movimientos del libro con el mismo id"
            }
        }
        return ordered
    }

    /**
     * El saldo que deja un movimiento de [amount] sobre [balance], o [Checked.Overflow] si no cabe en
     * `Long`. Así un monto absurdo se rechaza con un motivo, sin dejar el libro a medias.
     */
    fun next(balance: Money, amount: Money): Checked<Money> = try {
        Checked.Value(balance + amount)
    } catch (_: ArithmeticException) {
        Checked.Overflow
    }

    /**
     * El saldo de cada cuenta: la suma de todos sus movimientos, anulados y anulaciones incluidos,
     * porque cada par suma cero. Una cuenta sin movimientos no aparece: su saldo es cero.
     *
     * Nunca suma cuentas entre sí: cada saldo queda en la moneda de su cuenta.
     */
    fun balanceByAccount(movements: Iterable<MoneyMovement>): Map<Long, Money> {
        val totals = mutableMapOf<Long, Money>()
        for (movement in inOrder(movements)) {
            totals[movement.accountId] =
                totals.getOrElse(movement.accountId) { Money.ZERO } + movement.amount
        }
        return totals
    }

    /**
     * El saldo de su cuenta justo después de cada movimiento, por id de movimiento: el que debe
     * guardar en su `balance_after`. Se acumula en el orden del libro.
     */
    fun balancesAfter(movements: Iterable<MoneyMovement>): Map<Long, Money> {
        val running = mutableMapOf<Long, Money>()
        val after = mutableMapOf<Long, Money>()
        for (movement in inOrder(movements)) {
            val balance = running.getOrElse(movement.accountId) { Money.ZERO } + movement.amount
            running[movement.accountId] = balance
            after[movement.id] = balance
        }
        return after
    }

    /**
     * Las cuentas que rompen el saldo derivado: las de [balances] cuyo saldo guardado no es la suma de
     * sus movimientos, y las que tienen un movimiento cuyo saldo guardado no sigue la cadena. Una
     * cuenta con movimientos que no está en [balances] también cuenta. Vacío es un libro sano.
     */
    fun brokenAccounts(balances: Map<Long, Money>, movements: Iterable<MoneyMovement>): Set<Long> {
        val ordered = inOrder(movements)
        val sums = balanceByAccount(ordered)
        val after = balancesAfter(ordered)
        val broken = mutableSetOf<Long>()
        for ((accountId, stored) in balances) {
            if (stored != sums.getOrElse(accountId) { Money.ZERO }) broken += accountId
        }
        for (movement in ordered) {
            if (movement.accountId !in balances || movement.balanceAfter != after[movement.id]) {
                broken += movement.accountId
            }
        }
        return broken
    }

    /**
     * Los grupos de transferencia incompletos: los que no tienen exactamente una parte que sale y una
     * que entra. Vacío es un libro sano.
     */
    fun brokenTransfers(movements: Iterable<MoneyMovement>): Set<String> {
        val groups = mutableMapOf<String, MutableList<MoneyMovementType>>()
        for (movement in movements) {
            val origin = movement.draft.origin
            if (origin is MoneyOrigin.Transfer) {
                groups.getOrPut(origin.groupId) { mutableListOf() } += movement.type
            }
        }
        return groups.filterValues { types -> types.sorted() != COMPLETE_TRANSFER }.keys
    }

    /** Las partes de una transferencia completa, en el orden de [MoneyMovementType]. */
    private val COMPLETE_TRANSFER =
        listOf(MoneyMovementType.TRANSFER_OUT, MoneyMovementType.TRANSFER_IN).sorted()
}
