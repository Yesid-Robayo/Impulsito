package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que un movimiento de dinero dice, antes de saber en qué lugar del libro queda.
 *
 * - [amount] va en la unidad menor de la moneda **de la cuenta** [accountId], con signo: lo que
 *   entra suma y lo que sale resta.
 * - [origin] es el documento del que sale, de la clase que su tipo exige.
 * - [cashSessionId] es la sesión de caja en la que cae el movimiento, si la hay. Una diferencia de
 *   caja siempre la lleva.
 * - [availableDate] es la fecha en que el dinero estará disponible, si llega después.
 * - [note] va recortada, de 1 a [NOTE_MAX] caracteres, o nula.
 *
 * Lanza [IllegalArgumentException] si el monto es cero, si su signo o su origen no son los de su tipo,
 * o si la nota no cabe: la guarda de la base rechazaría la fila, y es un error de quien la arma.
 */
data class MoneyMovementDraft(
    val accountId: Long,
    val type: MoneyMovementType,
    val amount: Money,
    val origin: MoneyOrigin,
    val cashSessionId: Long? = null,
    val availableDate: BusinessDate? = null,
    val note: String? = null,
) {
    init {
        require(type.allows(amount.minor)) { "El signo del monto no es el de su tipo" }
        require(origin.kind == type.originKind) { "El origen no es el de su tipo" }
        require(type != MoneyMovementType.CASH_DIFFERENCE || cashSessionId != null) {
            "Una diferencia de caja va con su sesión"
        }
        require(note == null || note.length in 1..NOTE_MAX) { "Nota fuera de rango" }
    }

    companion object {
        /** Los caracteres más largos de una nota de movimiento. */
        const val NOTE_MAX: Int = 200
    }
}
