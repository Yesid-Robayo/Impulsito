package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import kotlin.time.Instant

/**
 * Un movimiento listo para agregarse al libro de dinero.
 *
 * [balanceAfter] es el saldo de la cuenta al terminar este movimiento: el que tenía más
 * [MoneyMovementDraft.amount]. Lo calcula el caso de uso con la cuenta que leyó dentro de la misma
 * transacción, y el repositorio lo comprueba antes de escribir.
 */
data class NewMoneyMovement(
    val draft: MoneyMovementDraft,
    val balanceAfter: Money,
    val time: RecordTime,
    val createdAt: Instant,
)

/**
 * Un movimiento guardado en el libro de dinero. El libro solo crece: este movimiento no cambia nunca,
 * y se anula con otro que lo compensa.
 *
 * [id] fija su lugar en el orden del libro, que nunca es el de la fecha.
 */
data class MoneyMovement(
    val id: Long,
    val draft: MoneyMovementDraft,
    val balanceAfter: Money,
    val time: RecordTime,
    val createdAt: Instant,
) {
    val accountId: Long get() = draft.accountId
    val type: MoneyMovementType get() = draft.type
    val amount: Money get() = draft.amount
}
