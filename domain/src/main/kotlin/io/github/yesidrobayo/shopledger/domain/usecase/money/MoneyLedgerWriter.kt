package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyLedger
import kotlin.time.Instant

/** Cómo termina agregar un movimiento al libro de dinero. Salvo [Appended], no se escribió nada. */
sealed interface MoneyAppendResult {
    /** Quedó en el libro con el id [movementId], y su cuenta con el saldo [balanceAfter]. */
    data class Appended(val movementId: Long, val balanceAfter: Money) : MoneyAppendResult

    /** La cuenta ya no existe. */
    data object AccountNotFound : MoneyAppendResult

    /** El saldo de la cuenta no cabría en `Long` con este monto. */
    data object Overflow : MoneyAppendResult
}

/**
 * La única forma de escribir en el libro de dinero desde un caso de uso: lee la cuenta, calcula el
 * saldo que deja el movimiento y lo agrega con ese saldo, todo dentro de la transacción de quien lo
 * llama. Así cada `balance_after` es el anterior más su monto, y el saldo de la cuenta es la suma de
 * su libro.
 *
 * No abre transacciones: se llama dentro de `TransactionRunner.write`, en la misma del documento que
 * mueve el dinero. Si devuelve algo distinto de [MoneyAppendResult.Appended], quien llama deshace la
 * transacción entera. Tampoco bloquea la moneda: quien escribe un registro con dinero ya lo hizo.
 *
 * Con una caja abierta en la cuenta, el movimiento cae en esa sesión, salvo que el borrador ya
 * traiga la suya: así lo esperado de la caja suma todo lo que movió la cuenta, sin que cada caso de
 * uso lo sepa. Sin caja abierta, el movimiento no lleva sesión.
 *
 * Un movimiento siempre va en la moneda de su cuenta. Escribir en una cuenta de otra moneda un monto
 * en la moneda del negocio sería mezclar unidades: lo convierte antes quien llama, con su cotización.
 */
class MoneyLedgerWriter(
    private val accounts: MoneyAccountRepository,
    private val ledger: MoneyLedgerRepository,
) {
    /** Agrega [draft] al libro, ocurrido en [time] y registrado en [createdAt]. */
    suspend fun append(
        draft: MoneyMovementDraft,
        time: RecordTime,
        createdAt: Instant,
    ): MoneyAppendResult {
        val account = accounts.findById(draft.accountId) ?: return MoneyAppendResult.AccountNotFound
        val balanceAfter = when (val next = MoneyLedger.next(account.balance, draft.amount)) {
            is Checked.Value -> next.value
            Checked.Overflow, is Checked.AboveLimit -> return MoneyAppendResult.Overflow
        }
        val inSession = if (draft.cashSessionId == null) {
            accounts.openCashSessionId(draft.accountId)
                ?.let { draft.copy(cashSessionId = it) } ?: draft
        } else {
            draft
        }
        val id = ledger.append(NewMoneyMovement(inSession, balanceAfter, time, createdAt))
        return MoneyAppendResult.Appended(id, balanceAfter)
    }

    /**
     * Compensa [movement] con un `MOVEMENT_VOID` de monto opuesto en su misma cuenta. Quien llama ya
     * comprobó que se puede anular y que no tiene su anulación.
     */
    suspend fun void(
        movement: MoneyMovement,
        time: RecordTime,
        createdAt: Instant,
        note: String? = null,
    ): MoneyAppendResult = append(
        MoneyMovementDraft(
            accountId = movement.accountId,
            type = MoneyMovementType.MOVEMENT_VOID,
            amount = -movement.amount,
            origin = MoneyOrigin.Void(movement.id),
            note = note,
        ),
        time,
        createdAt,
    )
}
