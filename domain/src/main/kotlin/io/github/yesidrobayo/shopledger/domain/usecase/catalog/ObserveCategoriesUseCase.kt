package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Una categoría de producto con cuántos productos, archivados o no, la tienen. */
data class ProductCategoryRow(val category: Category, val products: Int)

/**
 * Una categoría de gasto con cuántos gastos, activos o anulados, la tienen, y lo que se puede hacer
 * con ella. La de otros gastos no se archiva ni se elimina; con gastos, solo se archiva.
 */
data class ExpenseCategoryRow(val category: ExpenseCategory, val expenses: Int) {
    /** Se elimina solo sin gastos, y nunca la de otros gastos. */
    val canDelete: Boolean get() = !category.isSystem && expenses == 0

    /** Se archiva una vigente con gastos que no sea la de otros gastos. */
    val canArchive: Boolean get() = !category.isSystem && !category.isArchived && expenses > 0
}

/**
 * Las categorías de productos y de gastos, con sus conteos. [archivedExpenses] va aparte, al final
 * de la lista de gastos, con `Reactivar`. Las categorías de producto no se archivan.
 */
data class CategoriesOverview(
    val products: List<ProductCategoryRow>,
    val expenses: List<ExpenseCategoryRow>,
    val archivedExpenses: List<ExpenseCategoryRow>,
)

/**
 * Lee las categorías de productos y de gastos con cuántos elementos tiene cada una, de nuevo con cada
 * cambio. El orden por nombre lo pone la pantalla: el nombre visible de una categoría inicial sale
 * del idioma de la interfaz. No escribe nada.
 */
class ObserveCategoriesUseCase(
    private val categories: CategoryRepository,
    private val expenseCategories: ExpenseCategoryRepository,
) {
    operator fun invoke(): Flow<CategoriesOverview> = combine(
        categories.observeAll(),
        categories.observeProductCounts(),
        expenseCategories.observe(ArchiveFilter.ACTIVE),
        expenseCategories.observe(ArchiveFilter.ARCHIVED),
        expenseCategories.observeExpenseCounts(),
    ) { products, productCounts, active, archived, expenseCounts ->
        CategoriesOverview(
            products = products.map { ProductCategoryRow(it, productCounts[it.id] ?: 0) },
            expenses = active.map { ExpenseCategoryRow(it, expenseCounts[it.id] ?: 0) },
            archivedExpenses = archived.map { ExpenseCategoryRow(it, expenseCounts[it.id] ?: 0) },
        )
    }.distinctUntilChanged()
}
