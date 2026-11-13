package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseRules
import io.github.yesidrobayo.shopledger.domain.rule.expense.VoidNoteCheck
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney

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

    /**
     * El gasto salió de una cuenta y este teléfono no conoce la zona del negocio [storedZoneId], así
     * que no hay cómo fechar la devolución del dinero. La persona elige una zona que conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : VoidExpenseResult

    /** El saldo de la cuenta del gasto no cabría al devolverle el dinero: no se anula nada. */
    data object AccountOverflow : VoidExpenseResult

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
 * - **El dinero vuelve a su cuenta.** Si el gasto salió de una cuenta, su `EXPENSE_PAYMENT` recibe
 *   un `MOVEMENT_VOID` con la fecha de la anulación, en esta misma transacción. Un gasto sin cuenta
 *   no lee nada más.
 *
 * [settings] y [money] solo hacen falta para anular un gasto que salió de una cuenta.
 */
class VoidExpenseUseCase(
    private val transactions: TransactionRunner,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
    private val settings: AppSettingsRepository? = null,
    private val money: DocumentMoney? = null,
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
        val now = clock.now()
        expenses.markVoided(expenseId, Voiding(now, reason, validNote))
        if (expense.content.moneyAccountId != null) {
            val regional = checkNotNull(settings) {
                "Anular dinero pide los ajustes"
            }.get().regional
            val calendar = when (val result = BusinessCalendar.of(regional)) {
                is BusinessCalendarResult.Ready -> result.calendar

                is BusinessCalendarResult.UnknownZone -> return TxResult.Rollback(
                    VoidExpenseResult.UnknownTimeZone(result.storedZoneId),
                )
            }
            val documentMoney = checkNotNull(money) { "Anular dinero pide sus cuentas" }
            if (!documentMoney.reverseExpense(expenseId, calendar.stamp(now), now)) {
                return TxResult.Rollback(VoidExpenseResult.AccountOverflow)
            }
        }
        return TxResult.Commit(VoidExpenseResult.Voided)
    }
}
