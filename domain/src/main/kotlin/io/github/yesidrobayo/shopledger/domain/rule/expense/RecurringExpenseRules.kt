package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/**
 * Revisa un gasto recurrente antes de guardarlo y arma lo que trae registrarlo. No escribe nada.
 *
 * Los topes son los de la guarda de `recurring_expenses`, que queda como última defensa.
 * Además, la próxima fecha tiene que caer en el día elegido: si no, la primera propuesta no
 * coincidiría con lo que dice la fila, `Cada mes, día 5`.
 */
object RecurringExpenseRules {
    /** Largo máximo de la descripción, el mismo de un gasto. */
    const val DESCRIPTION_MAX: Int = ExpenseRules.DESCRIPTION_MAX

    /** Revisa [draft] y devuelve todos los errores a la vez, o lo que se guarda. */
    fun check(draft: RecurringExpenseDraft): RecurringExpenseCheck {
        val errors = mutableSetOf<RecurringExpenseInputError>()
        val amount = draft.amount
        if (amount <= Money.ZERO) errors += RecurringExpenseInputError.AMOUNT_NOT_POSITIVE
        if (!Limit.DOCUMENT_TOTAL.allows(amount.minor)) {
            errors += RecurringExpenseInputError.AMOUNT_TOO_LARGE
        }
        val description = draft.description?.trim()?.ifEmpty { null }
        if (description != null && CatalogText.length(description) > DESCRIPTION_MAX) {
            errors += RecurringExpenseInputError.DESCRIPTION_TOO_LONG
        }
        val recurrence = draft.recurrence
        val day = draft.dayOfPeriod
        if (!RecurrenceRules.isValidDay(recurrence, day)) {
            errors += RecurringExpenseInputError.DAY_OUT_OF_RANGE
        } else if (!RecurrenceRules.matches(recurrence, day, draft.nextDueDate)) {
            errors += RecurringExpenseInputError.NEXT_DATE_MISMATCH
        }
        if (errors.isNotEmpty()) return RecurringExpenseCheck.Invalid(errors)
        return RecurringExpenseCheck.Valid(
            RecurringExpenseDetails(
                expenseCategoryId = draft.categoryId,
                amount = amount,
                description = description,
                recurrence = recurrence,
                dayOfPeriod = day,
                nextDueDate = draft.nextDueDate,
                moneyAccountId = draft.moneyAccountId,
                reminderEnabled = draft.reminderEnabled,
            ),
        )
    }

    /**
     * Lo que trae registrar el vencimiento actual de [recurring], cuya categoría de hoy es
     * [category]: el gasto nuevo con sus datos puestos. Una categoría archivada o que ya no existe
     * no se trae, y la persona elige otra, como al corregir un gasto.
     */
    fun registrationOf(
        recurring: RecurringExpense,
        category: ExpenseCategory?,
    ): RecurringRegistration {
        val details = recurring.details
        val usable = category?.takeIf { !it.isArchived }
        return RecurringRegistration(
            occurrence = RecurringOccurrence(recurring.id, details.nextDueDate),
            amount = details.amount,
            categoryId = usable?.id,
            description = details.description,
            moneyAccountId = details.moneyAccountId,
            categoryLeftOut = usable == null,
        )
    }
}

/** Resultado de [RecurringExpenseRules.check]. */
sealed interface RecurringExpenseCheck {
    /** Se puede guardar: [details] ya va con la descripción recortada. */
    data class Valid(val details: RecurringExpenseDetails) : RecurringExpenseCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<RecurringExpenseInputError>) : RecurringExpenseCheck
}

/**
 * Lo que trae el gasto nuevo que confirma [occurrence]. La fecha que propone es la del
 * vencimiento. [categoryId] es `null` si la categoría hoy está archivada o ya no existe, y
 * [categoryLeftOut] lo dice para que la persona elija otra.
 */
data class RecurringRegistration(
    val occurrence: RecurringOccurrence,
    val amount: Money,
    val categoryId: Long?,
    val description: String?,
    val moneyAccountId: Long?,
    val categoryLeftOut: Boolean,
)
