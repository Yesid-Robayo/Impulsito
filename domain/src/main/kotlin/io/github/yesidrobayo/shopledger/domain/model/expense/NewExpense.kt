package io.github.yesidrobayo.shopledger.domain.model.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Un gasto que el caso de uso va a registrar, todavía sin id.
 *
 * [amount] es mayor que cero, en unidad menor. [moneyAccountId] es la cuenta de la que salió el
 * dinero, si la persona lleva cuentas, y [recurringExpenseId] el gasto recurrente que lo originó.
 */
data class NewExpense(
    val expenseCategoryId: Long,
    val amount: Money,
    val description: String?,
    val moneyAccountId: Long?,
    val recurringExpenseId: Long?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)
