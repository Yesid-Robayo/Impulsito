package io.github.yesidrobayo.shopledger.domain.rule.cash

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin

/**
 * Las cifras de una caja abierta, para su tarjeta: lo que había al abrir, [sales] lo cobrado en
 * ventas, [cashIn] lo demás que entró, [cashOut] lo que salió, con su signo negativo, y [expected],
 * que es la suma de todo y lo que debería haber al contar.
 */
data class CashSessionFigures(
    val opening: Money,
    val sales: Money,
    val cashIn: Money,
    val cashOut: Money,
    val expected: Money,
)

/**
 * Reparte lo que movió la cuenta durante una sesión de caja, con la misma suma que usa el cierre:
 *
 * ```text
 * expected = opening + sales + cashIn + cashOut
 *          = opening + suma de los movimientos de la sesión, sin CASH_DIFFERENCE
 * ```
 *
 * Una diferencia de caja no cuenta: es la corrección de un conteo, no dinero que entró o salió. La
 * anulación de un cobro de venta resta de las ventas, no de las salidas, para que `Ventas en
 * efectivo` diga lo vendido de verdad. Es solo una lectura: no escribe nada.
 */
object CashSessionSummary {
    /**
     * Las cifras de [session] con [movements], los de su cuenta en el orden del libro. Devuelve
     * `null` si una suma no cabe en un monto: solo sale de cifras absurdas o de una copia dañada.
     */
    fun of(session: CashSession, movements: List<MoneyMovement>): CashSessionFigures? {
        val types = movements.associate { it.id to it.type }
        var sales = Money.ZERO
        var cashIn = Money.ZERO
        var cashOut = Money.ZERO
        return try {
            for (movement in movements) {
                if (movement.draft.cashSessionId != session.id) continue
                if (movement.type == MoneyMovementType.CASH_DIFFERENCE) continue
                when {
                    kindOf(movement, types) == MoneyMovementType.SALE_RECEIPT ->
                        sales += movement.amount

                    movement.amount > Money.ZERO -> cashIn += movement.amount

                    else -> cashOut += movement.amount
                }
            }
            val opening = session.opening.openingAmount
            CashSessionFigures(opening, sales, cashIn, cashOut, opening + sales + cashIn + cashOut)
        } catch (overflow: ArithmeticException) {
            null
        }
    }

    /** El tipo que cuenta: el propio, o el del movimiento que anula, si está en el libro. */
    private fun kindOf(
        movement: MoneyMovement,
        types: Map<Long, MoneyMovementType>,
    ): MoneyMovementType {
        val origin = movement.draft.origin
        if (origin !is MoneyOrigin.Void) return movement.type
        return types[origin.reversesMovementId] ?: movement.type
    }
}
