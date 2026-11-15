package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowHorizon
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowSums
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowWindow
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El flujo de caja proyectado con el ejemplo de la regla, escrito a mano: saldos de 100000, de los
 * que 15000 se acreditan en 2 días, 20000 por cobrar, 30000 por pagar y 25000 de recurrentes en la
 * semana dan disponible 85000 y proyectado a 7 días 65000. Hoy es el lunes 2026-11-02.
 */
class CashFlowRulesTest {
    private val at = Instant.parse("2026-11-01T12:00:00Z")
    private val today = date("2026-11-02")

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun recurring(
        amount: Long,
        recurrence: Recurrence,
        day: Int,
        next: String,
        archived: Boolean = false,
    ) = RecurringExpense(
        id = amount,
        details = RecurringExpenseDetails(
            expenseCategoryId = 1,
            amount = Money(amount),
            description = null,
            recurrence = recurrence,
            dayOfPeriod = day,
            nextDueDate = date(next),
            moneyAccountId = null,
            reminderEnabled = false,
        ),
        archivedAt = if (archived) at else null,
        createdAt = at,
        updatedAt = at,
    )

    private val example = CashFlowSums(
        accountCount = 2,
        balance = Money(100_000),
        notYetAvailable = Money(15_000),
        short = CashFlowWindow(Money(15_000), Money(20_000), Money(30_000)),
        long = CashFlowWindow(Money(15_000), Money(20_000), Money(30_000)),
    )

    @Test
    fun `the example of the rule gives 85000 today and 65000 in seven days`() {
        val rent = recurring(25_000, Recurrence.MONTHLY, day = 5, next = "2026-11-05")

        val projection = CashFlowRules.project(today, example, listOf(rent))

        assertEquals(
            CashFlowProjection(
                today = today,
                availableToday = Money(85_000),
                horizons = listOf(
                    CashFlowHorizon(
                        days = 7,
                        until = date("2026-11-09"),
                        settlements = Money(15_000),
                        receivables = Money(20_000),
                        payables = Money(30_000),
                        recurring = Money(25_000),
                        projected = Money(65_000),
                    ),
                    // A 30 días el alquiler del 5 de diciembre todavía no vence: 2026-12-02.
                    CashFlowHorizon(
                        days = 30,
                        until = date("2026-12-02"),
                        settlements = Money(15_000),
                        receivables = Money(20_000),
                        payables = Money(30_000),
                        recurring = Money(25_000),
                        projected = Money(65_000),
                    ),
                ),
            ),
            projection,
        )
    }

    @Test
    fun `a weekly expense counts every time it falls due, and an archived one never`() {
        val weekly = recurring(1_000, Recurrence.WEEKLY, day = 1, next = "2026-11-02")
        val late = recurring(3_000, Recurrence.MONTHLY, day = 1, next = "2026-10-01")
        val archived =
            recurring(9_000, Recurrence.MONTHLY, day = 3, next = "2026-11-03", archived = true)

        // Lunes 2, 9, 16, 23 y 30 de noviembre: cinco veces hasta el 2 de diciembre.
        assertEquals(
            Money(5_000),
            CashFlowRules.recurringDue(listOf(weekly, archived), date("2026-12-02")),
        )
        // El de octubre sigue por confirmar: cuenta octubre, noviembre y diciembre.
        assertEquals(Money(9_000), CashFlowRules.recurringDue(listOf(late), date("2026-12-02")))
        assertEquals(Money.ZERO, CashFlowRules.recurringDue(emptyList(), date("2026-12-02")))
    }

    @Test
    fun `a projection can end below zero`() {
        val sums = example.copy(long = CashFlowWindow(Money.ZERO, Money.ZERO, Money(200_000)))

        val long = CashFlowRules.project(today, sums, emptyList()).horizons.last()

        assertEquals(Money(-115_000), long.projected)
    }
}
