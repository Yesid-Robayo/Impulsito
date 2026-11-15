package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense

/**
 * El aviso de un gasto recurrente que espera confirmación, con su categoría para nombrarlo cuando
 * no tiene descripción. [category] es `null` si ya no existe.
 */
data class RecurringReminder(val recurring: RecurringExpense, val category: ExpenseCategory?)

/**
 * Lanza los recordatorios de gastos recurrentes como notificaciones locales del teléfono, sin
 * servidor. Lo implementa `:app`. Un recordatorio solo avisa: nunca registra el gasto.
 */
fun interface RecurringReminderNotifier {
    /**
     * Muestra [reminders], todos de una misma tanda. Devuelve `false` si el sistema no deja
     * avisar: el permiso, las notificaciones de la app o su canal están apagados.
     */
    suspend fun show(reminders: List<RecurringReminder>): Boolean
}
