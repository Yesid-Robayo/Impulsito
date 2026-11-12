package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.OTHER_EXPENSE_CATEGORY
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [ExpenseRepository] sobre [InMemoryDatabase]. Como la base: un gasto no se borra, y la única
 * escritura después de insertarlo es su anulación, una sola vez.
 */
internal class FakeExpenseRepository(private val database: InMemoryDatabase) : ExpenseRepository {
    override fun observeIn(period: ReportPeriod): Flow<List<Expense>> = database.tables.map {
        it.expenses.values
            .filter { expense -> expense.content.businessDate in period }
            .sortedWith(
                compareByDescending<Expense> { expense -> expense.content.businessDate }
                    .thenByDescending { expense -> expense.id },
            )
    }

    override suspend fun findById(id: Long): Expense? = database.read().expenses[id]

    override suspend fun insert(expense: NewExpense): Long {
        // Como la llave foránea de la base: la categoría tiene que existir.
        check(expense.expenseCategoryId in database.read().expenseCategories) {
            "El gasto no tiene categoría"
        }
        val id = database.nextId(TABLE)
        database.write { it.copy(expenses = it.expenses + (id to Expense(id, expense, null))) }
        return id
    }

    override suspend fun markVoided(id: Long, voiding: Voiding) {
        val expense = checkNotNull(database.read().expenses[id]) { "El gasto no existe" }
        check(expense.voiding == null) { "El gasto ya está anulado" }
        database.write { it.copy(expenses = it.expenses + (id to expense.copy(voiding = voiding))) }
    }

    private companion object {
        const val TABLE = "expenses"
    }
}

/**
 * Un [ExpenseCategoryRepository] sobre [InMemoryDatabase]. Repite lo que la base impide: archivar o
 * borrar la de otros gastos, borrar una con gastos y dos vigentes con el mismo nombre.
 */
internal class FakeExpenseCategoryRepository(private val database: InMemoryDatabase) :
    ExpenseCategoryRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<ExpenseCategory>> =
        database.tables.map { tables ->
            tables.expenseCategories.values
                .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedBy { it.id }
        }

    override suspend fun findById(id: Long): ExpenseCategory? =
        database.read().expenseCategories[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): ExpenseCategory? =
        database.read().expenseCategories.values.firstOrNull {
            !it.isArchived && it.details.normalizedName == normalizedName
        }

    override suspend fun hasExpenses(id: Long): Boolean =
        database.read().expenses.values.any { it.content.expenseCategoryId == id }

    override fun observeExpenseCounts(): Flow<Map<Long, Int>> = database.tables.map { tables ->
        tables.expenses.values.groupingBy { it.content.expenseCategoryId }.eachCount()
    }

    override suspend fun insert(details: ExpenseCategoryDetails, createdAt: Instant): Long {
        details.normalizedName?.let { check(findActiveByNormalizedName(it) == null) }
        val id = database.nextId(TABLE)
        val stored = ExpenseCategory(
            id = id,
            systemKey = null,
            details = details,
            isSystem = false,
            archivedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        database.write { it.copy(expenseCategories = it.expenseCategories + (id to stored)) }
        return id
    }

    override suspend fun update(id: Long, details: ExpenseCategoryDetails, updatedAt: Instant) =
        change(id) { it.copy(details = details, updatedAt = updatedAt) }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        change(id) {
            check(!it.isSystem) { "La de otros gastos nunca se archiva" }
            it.copy(archivedAt = archivedAt, updatedAt = updatedAt)
        }

    override suspend fun delete(id: Long) {
        // Como la base: con gastos, la llave lo impide, y la de otros gastos nunca se borra.
        check(!hasExpenses(id)) { "Una llave impide borrarla" }
        database.write { tables ->
            val category = checkNotNull(tables.expenseCategories[id]) { "No existe la categoría" }
            check(!category.isSystem) { "La de otros gastos nunca se borra" }
            tables.copy(expenseCategories = tables.expenseCategories - id)
        }
    }

    /** Cambia la categoría [id] y comprueba, como la guarda, que no queden dos vigentes iguales. */
    private fun change(id: Long, update: (ExpenseCategory) -> ExpenseCategory) {
        database.write { tables ->
            val category = checkNotNull(tables.expenseCategories[id]) { "No existe la categoría" }
            val changed = update(category)
            val name = changed.details.normalizedName
            check(
                changed.isArchived || name == null ||
                    tables.expenseCategories.values.none {
                        it.id != id && !it.isArchived && it.details.normalizedName == name
                    },
            ) { "Dos vigentes con el mismo nombre" }
            tables.copy(expenseCategories = tables.expenseCategories + (id to changed))
        }
    }

    private companion object {
        const val TABLE = "expense_categories"
    }
}

/**
 * Siembra las nueve categorías iniciales, con su clave y sin nombre, como las crea la base al nacer.
 * La última, la de otros gastos, es la única de sistema. Devuelve el id de cada clave.
 */
internal fun InMemoryDatabase.seedExpenseCategories(at: Instant): Map<String, Long> {
    val keys = SeedTable.EXPENSE_CATEGORIES.systemKeys
    val ids = keys.withIndex().associate { (index, key) -> key to index + 1L }
    seed { tables ->
        tables.copy(
            expenseCategories = keys.associate { key ->
                val id = ids.getValue(key)
                id to ExpenseCategory(
                    id = id,
                    systemKey = key,
                    details = ExpenseCategoryDetails(null, null, isFixed = false),
                    isSystem = key == OTHER_EXPENSE_CATEGORY,
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                )
            },
            lastIds = tables.lastIds + ("expense_categories" to keys.size.toLong()),
        )
    }
    return ids
}
