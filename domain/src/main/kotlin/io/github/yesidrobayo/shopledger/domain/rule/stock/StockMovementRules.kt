package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft

/**
 * Qué fila puede entrar al libro de stock, y cómo se anula una.
 *
 * El libro solo crece: ninguna fila se edita ni se borra. Corregir es anular, y anular es agregar
 * un compensatorio con la cantidad opuesta en la misma parte del stock, que apunta a la fila
 * anulada. Cada fila se anula a lo sumo una vez. Las guardas de la base repiten estas mismas
 * condiciones; aquí se revisan antes, para que el caso de uso devuelva un motivo en vez de chocar
 * con la base.
 */
object StockMovementRules {
    /**
     * Revisa [draft] antes de agregarlo al libro.
     *
     * [onHandBefore] es el saldo del producto justo antes de esta fila, ya con las filas anteriores
     * de la misma transacción. [compensated] es la fila que [draft] dice anular, o `null` si no
     * dice anular ninguna o si esa fila no existe. [isAlreadyCompensated] dice si esa fila ya tiene
     * su compensatorio.
     *
     * Nunca rechaza una salida por falta de stock: el saldo puede quedar negativo.
     */
    fun check(
        draft: StockMovementDraft,
        onHandBefore: QuantityMilli,
        compensated: StockMovement?,
        isAlreadyCompensated: Boolean,
    ): StockMovementCheck {
        if (!draft.type.quantitySign.allows(draft.quantity)) {
            return StockMovementCheck.WrongQuantitySign
        }
        val compensation = checkCompensation(draft, compensated, isAlreadyCompensated)
        if (compensation != StockMovementCheck.Valid) return compensation
        return if (exceedsOnHandLimit(draft, onHandBefore)) {
            StockMovementCheck.AboveOnHandLimit
        } else {
            StockMovementCheck.Valid
        }
    }

    /**
     * El compensatorio que anula [original]: el tipo que lo anula, la cantidad opuesta, la misma
     * parte del stock y su id como fila anulada. [isAlreadyCompensated] dice si [original] ya tiene
     * su compensatorio.
     *
     * Lanza [ArithmeticException] si la cantidad de [original] no tiene opuesta en `Long`, algo que
     * una fila escrita con los topes de la app no puede tener.
     */
    fun compensationOf(original: StockMovement, isAlreadyCompensated: Boolean): StockCompensation {
        val voidType = original.type.voidType ?: return StockCompensation.NotCompensable
        if (isAlreadyCompensated) return StockCompensation.AlreadyCompensated
        return StockCompensation.Ready(
            StockMovementDraft(
                key = original.key,
                type = voidType,
                quantity = -original.quantity,
                reversesMovementId = original.id,
            ),
        )
    }

    private fun checkCompensation(
        draft: StockMovementDraft,
        compensated: StockMovement?,
        isAlreadyCompensated: Boolean,
    ): StockMovementCheck {
        val reversesId = draft.reversesMovementId
        if (!draft.type.isCompensating) {
            return if (reversesId == null) {
                StockMovementCheck.Valid
            } else {
                StockMovementCheck.UnexpectedCompensation
            }
        }
        if (reversesId == null || compensated == null) {
            return StockMovementCheck.MissingCompensatedMovement
        }
        require(compensated.id == reversesId) {
            "La fila recibida no es la que el compensatorio dice anular"
        }
        return when {
            !draft.type.compensates(compensated.type) -> StockMovementCheck.CrossCompensation
            draft.key != compensated.key -> StockMovementCheck.DifferentStockKey
            draft.quantity != -compensated.quantity -> StockMovementCheck.QuantityNotOpposite
            isAlreadyCompensated -> StockMovementCheck.AlreadyCompensated
            else -> StockMovementCheck.Valid
        }
    }

    /**
     * Si una entrada dejaría el saldo del producto sobre su tope. Solo cuentan las entradas que la
     * persona escribe y que suben el saldo; una salida o una anulación nunca se rechazan por esto.
     *
     * Compara sin desbordar: con saldo positivo mira cuánto falta para el tope, y con saldo
     * negativo o cero suma, porque una cantidad positiva más un saldo no positivo siempre cabe en
     * `Long`.
     */
    private fun exceedsOnHandLimit(
        draft: StockMovementDraft,
        onHandBefore: QuantityMilli,
    ): Boolean {
        val quantity = draft.quantity.milli
        if (!draft.type.checksOnHandLimit || quantity <= 0L) return false
        val before = onHandBefore.milli
        val limit = Limit.ON_HAND_MILLI.max
        return if (before > 0L) quantity > limit - before else before + quantity > limit
    }
}
