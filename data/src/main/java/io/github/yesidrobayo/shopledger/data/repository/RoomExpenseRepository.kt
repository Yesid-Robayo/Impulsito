package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseEntity
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los gastos sobre `expenses`. Un gasto nace activo y lo único que cambia después es su anulación,
 * una sola vez.
 */
internal class RoomExpenseRepository(database: AppDatabase) : ExpenseRepository {
    private val expenses = database.expenseDao()
    private val writes = RepositoryWrites(database)

    override fun observeIn(period: ReportPeriod): Flow<List<Expense>> = expenses
        .observeIn(period.storedStart, period.storedEnd)
        .map { rows -> rows.map(::toExpense) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): Expense? = expenses.findById(id)?.let(::toExpense)

    override suspend fun insert(expense: NewExpense): Long = writes.inTransaction {
        expenses.insert(
            ExpenseEntity(
                expenseCategoryId = expense.expenseCategoryId,
                status = DocumentStatus.ACTIVE.name,
                amount = expense.amount.minor,
                description = expense.description,
                moneyAccountId = expense.moneyAccountId,
                recurringExpenseId = expense.recurringExpenseId,
                occurredAt = expense.occurredAt.toStoredMillis(),
                createdAt = expense.createdAt.toStoredMillis(),
                businessDate = expense.businessDate.toStored(),
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    /**
     * Un gasto que no existe o ya estaba anulado no cambia, y la transacción se deshace como regla
     * rota: anularlo dos veces devolvería dos veces su dinero.
     */
    override suspend fun markVoided(id: Long, voiding: Voiding) {
        writes.inTransaction {
            requireOneRow(
                expenses.markVoided(
                    id,
                    voiding.voidedAt.toStoredMillis(),
                    voiding.reason.name,
                    voiding.note,
                ),
            )
        }
    }

    private fun toExpense(row: ExpenseEntity): Expense = Expense(
        id = row.id,
        content = NewExpense(
            expenseCategoryId = row.expenseCategoryId,
            amount = Money(row.amount),
            description = row.description,
            moneyAccountId = row.moneyAccountId,
            recurringExpenseId = row.recurringExpenseId,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )
}
