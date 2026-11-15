package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.expense.RecurringExpenseEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.port.RecurringExpenseRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los gastos recurrentes sobre `recurring_expenses`. Las escrituras solo corren dentro de la
 * transacción del caso de uso, y una que no encuentra su fila deshace la transacción entera.
 */
internal class RoomRecurringExpenseRepository(database: AppDatabase) : RecurringExpenseRepository {
    private val recurring = database.recurringExpenseDao()
    private val writes = RepositoryWrites(database)

    override fun observe(filter: ArchiveFilter): Flow<List<RecurringExpense>> = when (filter) {
        ArchiveFilter.ACTIVE -> recurring.observeActive()
        ArchiveFilter.ARCHIVED -> recurring.observeArchived()
    }.map { rows -> rows.map(::toRecurringExpense) }.distinctUntilChanged()

    override suspend fun findById(id: Long): RecurringExpense? =
        recurring.findById(id)?.let(::toRecurringExpense)

    override suspend fun insert(details: RecurringExpenseDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            recurring.insert(
                RecurringExpenseEntity(
                    expenseCategoryId = details.expenseCategoryId,
                    amount = details.amount.minor,
                    description = details.description,
                    recurrence = details.recurrence.name,
                    dayOfPeriod = details.dayOfPeriod.toLong(),
                    nextDueDate = details.nextDueDate.toStored(),
                    moneyAccountId = details.moneyAccountId,
                    reminderEnabled = details.reminderEnabled.toStoredFlag(),
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: RecurringExpenseDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                recurring.update(
                    id = id,
                    expenseCategoryId = details.expenseCategoryId,
                    amount = details.amount.minor,
                    description = details.description,
                    recurrence = details.recurrence.name,
                    dayOfPeriod = details.dayOfPeriod.toLong(),
                    nextDueDate = details.nextDueDate.toStored(),
                    moneyAccountId = details.moneyAccountId,
                    reminderEnabled = details.reminderEnabled.toStoredFlag(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun advance(id: Long, nextDueDate: BusinessDate, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                recurring.updateNextDueDate(
                    id,
                    nextDueDate.toStored(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                recurring.setArchivedAt(
                    id,
                    archivedAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    private fun toRecurringExpense(row: RecurringExpenseEntity): RecurringExpense =
        RecurringExpense(
            id = row.id,
            details = RecurringExpenseDetails(
                expenseCategoryId = row.expenseCategoryId,
                amount = Money(row.amount),
                description = row.description,
                recurrence = storedEnum<Recurrence>(row.recurrence),
                dayOfPeriod = row.dayOfPeriod.toStoredInt(),
                nextDueDate = storedDate(row.nextDueDate),
                moneyAccountId = row.moneyAccountId,
                reminderEnabled = row.reminderEnabled.toFlag(),
            ),
            archivedAt = row.archivedAt?.toInstant(),
            createdAt = row.createdAt.toInstant(),
            updatedAt = row.updatedAt.toInstant(),
        )
}
