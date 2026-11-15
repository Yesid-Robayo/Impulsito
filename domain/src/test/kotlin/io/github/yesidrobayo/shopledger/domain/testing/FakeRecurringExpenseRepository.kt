package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.port.RecurringExpenseRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [RecurringExpenseRepository] sobre [InMemoryDatabase]. Como la base: la categoría tiene que
 * existir, y nada se borra.
 */
internal class FakeRecurringExpenseRepository(private val database: InMemoryDatabase) :
    RecurringExpenseRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<RecurringExpense>> =
        database.tables.map { tables ->
            tables.recurringExpenses.values
                .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedBy { it.id }
        }

    override suspend fun findById(id: Long): RecurringExpense? =
        database.read().recurringExpenses[id]

    override suspend fun insert(details: RecurringExpenseDetails, createdAt: Instant): Long {
        check(details.expenseCategoryId in database.read().expenseCategories) {
            "El gasto recurrente no tiene categoría"
        }
        val id = database.nextId(TABLE)
        val stored = RecurringExpense(id, details, null, createdAt, createdAt)
        database.write { it.copy(recurringExpenses = it.recurringExpenses + (id to stored)) }
        return id
    }

    override suspend fun update(id: Long, details: RecurringExpenseDetails, updatedAt: Instant) =
        change(id) { it.copy(details = details, updatedAt = updatedAt) }

    override suspend fun advance(id: Long, nextDueDate: BusinessDate, updatedAt: Instant) =
        change(id) {
            it.copy(details = it.details.copy(nextDueDate = nextDueDate), updatedAt = updatedAt)
        }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        change(id) { it.copy(archivedAt = archivedAt, updatedAt = updatedAt) }

    private fun change(id: Long, update: (RecurringExpense) -> RecurringExpense) {
        database.write { tables ->
            val current = checkNotNull(tables.recurringExpenses[id]) { "No existe" }
            tables.copy(recurringExpenses = tables.recurringExpenses + (id to update(current)))
        }
    }

    private companion object {
        const val TABLE = "recurring_expenses"
    }
}
