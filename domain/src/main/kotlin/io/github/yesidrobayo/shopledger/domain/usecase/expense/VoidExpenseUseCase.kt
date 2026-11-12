package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseRules
import io.github.yesidrobayo.shopledger.domain.rule.expense.VoidNoteCheck
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/**
 * Resultado de [VoidExpenseUseCase]. Cualquier rechazo deshace la transacción entera: el gasto
 * sigue activo.
 */
sealed interface VoidExpenseResult {
    /** El gasto quedó anulado y dejó de contar en su fecha original. */
    data object Voided : VoidExpenseResult

    /** El gasto no existe. */
    data object ExpenseNotFound : VoidExpenseResult

    /** El gasto ya estaba anulado: un gasto se anula una sola vez, y no se reactiva. */
    data object AlreadyVoided : VoidExpenseResult

    /** El motivo no vale para un gasto, como una devolución: un gasto no se devuelve. */
    data object ReasonNotAllowed : VoidExpenseResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : VoidExpenseResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidExpenseResult
}

/**
 * Anula un gasto activo, en una sola transacción. El gasto no se edita ni se borra: pasa a anulado,
 * con el instante, el motivo y la nota, y se conserva para la historia.
 *
 * - **Deja de contar en su fecha original.** Anular un gasto del mes pasado sube la ganancia del mes
 *   pasado; el mes de la anulación no cambia. Los reportes son la mejor verdad conocida hoy, y la
 *   app no cierra períodos.
 * - **Una sola vez.** Un gasto anulado no se anula de nuevo ni se reactiva: corregir es registrar
 *   otro.
 * - **No toca el inventario.** Un gasto nunca movió stock.
 *
 * Por ahora ningún gasto sale de una cuenta de dinero, así que no hay movimiento que compensar:
 * cuando lleguen las cuentas, su compensatorio irá en esta misma transacción.
 */
class VoidExpenseUseCase(
    private val transactions: TransactionRunner,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        expenseId: Long,
        reason: VoidReason,
        note: String?,
    ): VoidExpenseResult = storageSafe(VoidExpenseResult::StorageFailed) {
        transactions.write { void(expenseId, reason, note) }
    }

    private suspend fun void(
        expenseId: Long,
        reason: VoidReason,
        note: String?,
    ): TxResult<VoidExpenseResult> {
        if (!VoidableDocument.EXPENSE.allows(reason)) {
            return TxResult.Rollback(VoidExpenseResult.ReasonNotAllowed)
        }
        val validNote = when (val check = ExpenseRules.voidNote(note)) {
            VoidNoteCheck.TooLong -> return TxResult.Rollback(VoidExpenseResult.NoteTooLong)
            is VoidNoteCheck.Valid -> check.note
        }
        val expense = expenses.findById(expenseId)
            ?: return TxResult.Rollback(VoidExpenseResult.ExpenseNotFound)
        when (expense.status) {
            DocumentStatus.VOIDED -> return TxResult.Rollback(VoidExpenseResult.AlreadyVoided)
            DocumentStatus.ACTIVE -> Unit
        }
        expenses.markVoided(expenseId, Voiding(clock.now(), reason, validNote))
        return TxResult.Commit(VoidExpenseResult.Voided)
    }
}
