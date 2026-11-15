package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowHorizon
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowSums
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowWindow
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurrenceRules

/**
 * El flujo de caja proyectado a 7 y 30 días, solo en la moneda del negocio:
 *
 * ```text
 * disponible hoy = suma de saldos - lo que se acredita después de hoy
 * proyectado(h)  = disponible hoy + lo que se acredita hasta h + lo que vence por cobrar
 *                - lo que vence por pagar - los gastos recurrentes que vencen hasta h
 * ```
 *
 * Saldos de 100000, de los que 15000 se acreditan en 2 días, 20000 por cobrar, 30000 por pagar y
 * 25000 de recurrentes en la semana: disponible hoy 85000 y proyectado a 7 días 65000.
 *
 * Los gastos recurrentes cuentan cada vencimiento del horizonte, también los atrasados que esperan
 * confirmación: uno semanal pesa cuatro o cinco veces en 30 días. Uno archivado no cuenta. Es una
 * estimación: la pantalla lo dice.
 */
object CashFlowRules {
    /** El horizonte corto, en días desde hoy. */
    const val SHORT_DAYS: Int = 7

    /** El horizonte largo, en días desde hoy. */
    const val LONG_DAYS: Int = 30

    /** El proyectado de hoy con [sums] y los gastos recurrentes [recurring]. */
    fun project(
        today: BusinessDate,
        sums: CashFlowSums,
        recurring: List<RecurringExpense>,
    ): CashFlowProjection {
        val available = sums.balance - sums.notYetAvailable
        return CashFlowProjection(
            today = today,
            availableToday = available,
            horizons = listOf(
                horizon(available, today, SHORT_DAYS, sums.short, recurring),
                horizon(available, today, LONG_DAYS, sums.long, recurring),
            ),
        )
    }

    /** Lo que suman los vencimientos de [recurring] hasta [until], incluido. */
    fun recurringDue(recurring: List<RecurringExpense>, until: BusinessDate): Money =
        recurring.filter { !it.isArchived }.fold(Money.ZERO) { total, item ->
            val details = item.details
            val times = RecurrenceRules.occurrencesThrough(
                details.recurrence,
                details.dayOfPeriod,
                details.nextDueDate,
                until,
            )
            total + Money(Math.multiplyExact(details.amount.minor, times.toLong()))
        }

    private fun horizon(
        available: Money,
        today: BusinessDate,
        days: Int,
        window: CashFlowWindow,
        recurring: List<RecurringExpense>,
    ): CashFlowHorizon {
        val until = today.plusDays(days)
        val due = recurringDue(recurring, until)
        return CashFlowHorizon(
            days = days,
            until = until,
            settlements = window.settlements,
            receivables = window.receivables,
            payables = window.payables,
            recurring = due,
            projected = available + window.settlements + window.receivables -
                window.payables - due,
        )
    }
}
