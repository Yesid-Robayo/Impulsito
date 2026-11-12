package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los conteos de la pantalla de categorías sobre Room: los productos de cada categoría, también los
 * archivados, y los gastos de cada una, también los anulados, de nuevo con cada cambio. Datos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CategoryCountsTest : RepositoryTest() {
    private val categories by lazy { RoomCategoryRepository(database) }
    private val expenseCategories by lazy { RoomExpenseCategoryRepository(database) }
    private val expenses by lazy { RoomExpenseRepository(database) }

    @Test
    fun productsAreCountedPerCategoryArchivedOnesToo() = runBlocking {
        val cleaning = commit {
            categories.insert(CategoryDetails("Limpieza", "limpieza", null), AT)
        }
        categories.observeProductCounts().test {
            assertEquals(emptyMap<Long, Int>(), awaitItem())

            commit {
                database.productDao().insert(product("Jabón inventado", categoryId = cleaning))
                database.productDao().insert(
                    product(
                        "Cloro inventado",
                        categoryId = cleaning,
                    ).copy(archivedAt = AT.toEpochMilliseconds()),
                )
            }
            awaitValue(mapOf(cleaning to 2))
        }
    }

    @Test
    fun expensesAreCountedPerCategoryVoidedOnesToo() = runBlocking {
        val id = commit { database.expenseCategoryDao().insert(expenseCategory("Fumigación")) }
        expenseCategories.observeExpenseCounts().test {
            assertEquals(emptyMap<Long, Int>(), awaitItem())

            val first = commit { database.expenseDao().insert(expense(id)) }
            commit { database.expenseDao().insert(expense(id)) }
            awaitValue(mapOf(id to 2))

            commit { expenses.markVoided(first, Voiding(LATER_AT, VoidReason.ENTRY_ERROR, null)) }
            // Anular no borra: sigue contando, y por eso la categoría no se puede eliminar.
            expectNoEvents()
            assertEquals(true, expenseCategories.hasExpenses(id))
        }
    }
}
