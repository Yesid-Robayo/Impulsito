package io.github.yesidrobayo.shopledger.notification

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppEvent
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.RecurringReminderNotifier
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.usecase.expense.DeliverRecurringRemindersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ObserveRecurringExpensesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lanza los recordatorios de gastos recurrentes por confirmar mientras la base está abierta.
 *
 * Avisa al abrir la app y cada vez que cambia qué vencimientos esperan confirmación con el
 * recordatorio encendido. Cada vencimiento se avisa una vez mientras viva el proceso: no hay marca
 * en la base, así que al volver a abrir la app, uno que sigue pendiente se recuerda de nuevo. Un
 * recordatorio nunca registra el gasto.
 *
 * Una falla al entregar se registra por su clase y no detiene a las siguientes.
 *
 * @param scope el alcance de la aplicación: sigue mientras viva el proceso.
 */
class RecurringReminderDelivery(
    private val scope: CoroutineScope,
    private val notifier: RecurringReminderNotifier,
    private val clock: Clock,
    private val log: EventLog,
) {
    private val started = AtomicBoolean(false)

    /** Los vencimientos ya avisados en este proceso. Solo los toca la corrutina de [follow]. */
    private val delivered = mutableSetOf<RecurringOccurrence>()

    /** Empieza a seguir los gastos recurrentes de [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        val observe = ObserveRecurringExpensesUseCase(
            ports.recurringExpenses,
            BusinessCalendarSource(ports.settings),
            clock,
        )
        val deliver = DeliverRecurringRemindersUseCase(observe, ports.expenseCategories, notifier)
        scope.launch {
            observe()
                .map { deliver.remindable(it) }
                .distinctUntilChanged()
                .collect { pending ->
                    if ((pending - delivered).isNotEmpty()) {
                        log.contain(AppEvent.RECURRING_DELIVERY_FAILED) {
                            delivered += deliver(delivered)
                        }
                    }
                }
        }
    }
}
