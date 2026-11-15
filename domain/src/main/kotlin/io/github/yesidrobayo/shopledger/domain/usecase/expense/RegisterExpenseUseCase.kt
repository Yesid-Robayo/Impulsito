package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.RecurringExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCheck
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseRules
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurrenceRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant

/** Cómo termina registrar un gasto. Salvo [Registered], no se escribió nada. */
sealed interface RegisterExpenseResult {
    /** El gasto quedó guardado, activo, con el id [expenseId]. */
    data class Registered(val expenseId: Long) : RegisterExpenseResult

    /** El valor o la descripción no se pueden guardar: cada error va en su campo. */
    data class Invalid(val errors: Set<ExpenseInputError>) : RegisterExpenseResult

    /** La fecha elegida es posterior a hoy en la zona del negocio. */
    data object FutureDate : RegisterExpenseResult

    /** La categoría ya no existe. */
    data object CategoryNotFound : RegisterExpenseResult

    /** La categoría se archivó: no se ofrece en gastos nuevos. La persona elige otra o la reactiva. */
    data object CategoryArchived : RegisterExpenseResult

    /**
     * Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fechar el
     * gasto. La persona elige una zona que el teléfono conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : RegisterExpenseResult

    /** La moneda sigue sin confirmar: ningún monto se guarda antes. Lo escrito se conserva. */
    data object CurrencyNotConfirmed : RegisterExpenseResult

    /** La cuenta elegida no puede pagar el gasto, por [issue]. La persona elige otra o ninguna. */
    data class AccountUnavailable(val issue: AccountIssue) : RegisterExpenseResult

    /** El saldo de la cuenta no cabría en un entero de 64 bits con este gasto. */
    data object AccountOverflow : RegisterExpenseResult

    /** El gasto recurrente que se confirmaba ya no existe o se archivó: no se propone más. */
    data object RecurringUnavailable : RegisterExpenseResult

    /**
     * Ese vencimiento del gasto recurrente ya se registró u omitió, quizá en otra pantalla:
     * registrarlo de nuevo lo contaría dos veces.
     */
    data object RecurringAlreadyHandled : RegisterExpenseResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RegisterExpenseResult
}

/**
 * Registra un gasto en una sola transacción: el dinero del negocio que no se convierte en mercancía
 * para vender, como el alquiler o los servicios. Reduce la ganancia neta en su propia fecha.
 *
 * - **La mercancía no es un gasto.** Se registra como compra y pesa al venderla: anotarla aquí la
 *   contaría dos veces. Eso lo advierte la pantalla; este caso de uso no puede saberlo.
 * - **La fecha puede ser pasada**, nunca futura. Hoy lleva el instante actual; un día anterior, las
 *   12:00 de ese día en la zona del negocio.
 * - **La categoría tiene que estar vigente.** Una archivada sigue en los reportes, pero no recibe
 *   gastos nuevos.
 * - **El primer gasto bloquea la moneda**, en su misma transacción, igual que una venta o una compra.
 *
 * - **La cuenta es opcional.** Con una cuenta, el dinero sale de ella como un `EXPENSE_PAYMENT`, en
 *   esta misma transacción y con la fecha del gasto. La cuenta tiene que estar vigente y llevar la
 *   moneda del negocio. Sin cuenta no se escribe ningún movimiento ni se lee ninguna cuenta.
 *
 * - **Puede confirmar un gasto recurrente.** Con [ExpenseRequest.recurring], el gasto
 *   lleva `recurring_expense_id`, y en esta misma transacción el gasto recurrente pasa a su fecha
 *   siguiente, contada desde el vencimiento. Ese vencimiento tiene que seguir pendiente: si otra
 *   pantalla ya lo registró u omitió, no se registra nada. Así la app nunca registra un gasto
 *   recurrente sola, y uno confirmado no se cuenta dos veces.
 */
class RegisterExpenseUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val expenses: ExpenseRepository,
    private val categories: ExpenseCategoryRepository,
    private val clock: Clock,
    private val money: DocumentMoney? = null,
    private val recurring: RecurringExpenseRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: ExpenseRequest): RegisterExpenseResult =
        storageSafe(RegisterExpenseResult::StorageFailed) {
            transactions.write { register(request) }
        }

    private suspend fun register(request: ExpenseRequest): TxResult<RegisterExpenseResult> {
        val valid = when (val check = ExpenseRules.check(request)) {
            is ExpenseCheck.Invalid -> return reject(RegisterExpenseResult.Invalid(check.errors))
            is ExpenseCheck.Valid -> check
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(RegisterExpenseResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return reject(RegisterExpenseResult.FutureDate)
        val category = categories.findById(request.categoryId)
            ?: return reject(RegisterExpenseResult.CategoryNotFound)
        if (category.isArchived) return reject(RegisterExpenseResult.CategoryArchived)
        val confirmed = request.recurring?.let { occurrence ->
            val templates = checkNotNull(recurring) { "Confirmar necesita los recurrentes" }
            val template = templates.findById(occurrence.recurringExpenseId)
                ?.takeIf { !it.isArchived }
                ?: return reject(RegisterExpenseResult.RecurringUnavailable)
            if (template.details.nextDueDate != occurrence.dueDate) {
                return reject(RegisterExpenseResult.RecurringAlreadyHandled)
            }
            template
        }

        // Justo antes de escribir: el primer registro con dinero bloquea la moneda en esta misma
        // transacción, y si el gasto se deshace, el bloqueo también.
        val currency = when (val lock = currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> lock.currency

            CurrencyLockResult.Unconfirmed -> return reject(
                RegisterExpenseResult.CurrencyNotConfirmed,
            )
        }
        val accountId = request.moneyAccountId
        val payer = accountId?.let {
            val documentMoney = checkNotNull(money) { "Un gasto con cuenta necesita el dinero" }
            documentMoney.issueOf(it, currency)?.let { issue ->
                return reject(RegisterExpenseResult.AccountUnavailable(issue))
            }
            documentMoney
        }
        val id = expenses.insert(
            NewExpense(
                expenseCategoryId = category.id,
                amount = valid.amount,
                description = valid.description,
                moneyAccountId = accountId,
                recurringExpenseId = confirmed?.id,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        if (confirmed != null) {
            val details = confirmed.details
            val next =
                RecurrenceRules.next(details.recurrence, details.dayOfPeriod, details.nextDueDate)
            checkNotNull(recurring).advance(confirmed.id, next, now)
        }
        if (payer != null) {
            val draft = MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.EXPENSE_PAYMENT,
                amount = -valid.amount,
                origin = MoneyOrigin.Expense(id),
            )
            when (payer.append(draft, time, now)) {
                is MoneyAppendResult.Appended -> Unit

                // Se revisó en esta misma transacción: no puede haber desaparecido.
                MoneyAppendResult.AccountNotFound ->
                    return reject(RegisterExpenseResult.AccountUnavailable(AccountIssue.NOT_FOUND))

                MoneyAppendResult.Overflow -> return reject(RegisterExpenseResult.AccountOverflow)
            }
        }
        return TxResult.Commit(RegisterExpenseResult.Registered(id))
    }

    /**
     * El instante y la fecha del gasto: ahora, si la fecha es hoy o no se eligió; las 12:00 de ese
     * día, si es pasada; o `null` si es futura.
     */
    private fun recordTime(
        calendar: BusinessCalendar,
        chosen: BusinessDate?,
        now: Instant,
    ): RecordTime? {
        val today = calendar.stamp(now)
        return when {
            chosen == null || chosen == today.businessDate -> today
            chosen > today.businessDate -> null
            else -> calendar.stampBackdated(chosen)
        }
    }

    private fun reject(result: RegisterExpenseResult): TxResult<RegisterExpenseResult> =
        TxResult.Rollback(result)
}
