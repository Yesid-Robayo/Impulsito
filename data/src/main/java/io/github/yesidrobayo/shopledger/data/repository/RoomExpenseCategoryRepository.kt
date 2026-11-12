package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseCategoryEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las categorías de gasto sobre `expense_categories`.
 *
 * La de otros gastos nunca se archiva ni se borra: la base no la cambia, y aquí eso sale como regla
 * rota, porque el caso de uso no debe pedirlo. Lo mismo con una categoría con gastos que se quiere
 * borrar: su llave `RESTRICT` la protege.
 */
internal class RoomExpenseCategoryRepository(database: AppDatabase) : ExpenseCategoryRepository {
    private val categories = database.expenseCategoryDao()
    private val writes = RepositoryWrites(database)

    override fun observe(filter: ArchiveFilter): Flow<List<ExpenseCategory>> {
        val rows =
            when (filter) {
                ArchiveFilter.ACTIVE -> categories.observeActive()
                ArchiveFilter.ARCHIVED -> categories.observeArchived()
            }
        return rows.map { list -> list.map(::toCategory) }.distinctUntilChanged()
    }

    override suspend fun findById(id: Long): ExpenseCategory? =
        categories.findById(id)?.let(::toCategory)

    override suspend fun findActiveByNormalizedName(normalizedName: String): ExpenseCategory? =
        categories.findActiveByNormalizedName(normalizedName)?.let(::toCategory)

    override suspend fun hasExpenses(id: Long): Boolean = categories.hasExpenses(id)

    override fun observeExpenseCounts(): Flow<Map<Long, Int>> = categories.observeExpenseCounts()
        .map { rows -> rows.associate { it.ownerId to it.count } }
        .distinctUntilChanged()

    override suspend fun insert(details: ExpenseCategoryDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            categories.insert(
                ExpenseCategoryEntity(
                    // Solo las iniciales, que crea la base, llevan clave y marca de sistema.
                    systemKey = null,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    isSystem = false.toStoredFlag(),
                    isFixed = details.isFixed.toStoredFlag(),
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: ExpenseCategoryDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                categories.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    isFixed = details.isFixed.toStoredFlag(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                categories.setArchivedAt(
                    id,
                    archivedAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(categories.delete(id)) }
    }

    private fun toCategory(row: ExpenseCategoryEntity): ExpenseCategory {
        val details =
            ExpenseCategoryDetails(
                name = row.name,
                normalizedName = row.normalizedName,
                isFixed = row.isFixed.toFlag(),
            )
        // Sin nombre ni clave no habría con qué mostrarla: el modelo la rechaza.
        if (details.name == null && row.systemKey == null) brokenRow()
        return ExpenseCategory(
            id = row.id,
            systemKey = row.systemKey,
            details = details,
            isSystem = row.isSystem.toFlag(),
            archivedAt = row.archivedAt?.toInstant(),
            createdAt = row.createdAt.toInstant(),
            updatedAt = row.updatedAt.toInstant(),
        )
    }
}
