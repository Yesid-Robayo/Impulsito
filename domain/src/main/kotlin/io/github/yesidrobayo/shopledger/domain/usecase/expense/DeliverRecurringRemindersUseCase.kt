package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.RecurringReminder
import io.github.yesidrobayo.shopledger.domain.port.RecurringReminderNotifier
import kotlinx.coroutines.flow.first

/**
 * Avisa los gastos recurrentes que esperan confirmación y tienen el recordatorio encendido.
 *
 * Solo avisa: no escribe nada, ni el gasto ni una marca de aviso. Quien lo llama recuerda qué
 * vencimientos ya avisó mientras viva el proceso, para no repetirlos. Si el sistema no deja
 * avisar, no cuenta nada como avisado. La lista `Por confirmar` no depende de esto.
 */
class DeliverRecurringRemindersUseCase(
    private val observe: ObserveRecurringExpensesUseCase,
    private val categories: ExpenseCategoryRepository,
    private val notifier: RecurringReminderNotifier,
) {
    /** Avisa lo pendiente que no está en [delivered], y devuelve los vencimientos que avisó. */
    suspend operator fun invoke(delivered: Set<RecurringOccurrence>): Set<RecurringOccurrence> {
        val pending = observe().first().due
            .filter { it.details.reminderEnabled && occurrenceOf(it) !in delivered }
        if (pending.isEmpty()) return emptySet()
        val reminders = pending.map {
            RecurringReminder(it, categories.findById(it.details.expenseCategoryId))
        }
        if (!notifier.show(reminders)) return emptySet()
        return pending.mapTo(mutableSetOf(), ::occurrenceOf)
    }

    /** Los vencimientos de [overview] que piden recordatorio. */
    fun remindable(overview: RecurringExpenseOverview): Set<RecurringOccurrence> =
        overview.due.filter { it.details.reminderEnabled }.mapTo(mutableSetOf(), ::occurrenceOf)

    private fun occurrenceOf(recurring: RecurringExpense): RecurringOccurrence =
        RecurringOccurrence(recurring.id, recurring.details.nextDueDate)
}
