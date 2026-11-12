package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeExpenseRepository
import io.github.yesidrobayo.shopledger.domain.testing.seedExpenseCategories
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.CATEGORY_ID
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ArchiveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las categorías de productos y de gastos con sus conteos, y lo que se puede hacer con cada una de
 * gasto. Datos inventados.
 */
class ObserveCategoriesUseCaseTest {
    private val fixture = CatalogFixture()
    private val expenseCategories = FakeExpenseCategoryRepository(fixture.database)
    private val expenses = FakeExpenseRepository(fixture.database)
    private val seeded = fixture.database.seedExpenseCategories(Instant.fromEpochMilliseconds(0))
    private val observe = ObserveCategoriesUseCase(fixture.categories, expenseCategories)

    private suspend fun spend(categoryId: Long): Long {
        val register = RegisterExpenseUseCase(
            fixture.transactions,
            fixture.settings,
            expenses,
            expenseCategories,
            fixture.clock,
        )
        val result = register(ExpenseRequest(Money(1_000), categoryId))
        return (result as RegisterExpenseResult.Registered).expenseId
    }

    @Test
    fun `products are counted per category, archived ones too`() = runTest {
        fixture.seedProduct(1, "Jabón inventado")
        fixture.seedProduct(2, "Cloro inventado", archived = true)
        fixture.seedProduct(3, "Cepillo inventado")
        fixture.database.seed { tables ->
            tables.copy(
                products = tables.products.mapValues { (id, product) ->
                    if (id == 3L) {
                        product
                    } else {
                        product.copy(details = product.details.copy(categoryId = CATEGORY_ID))
                    }
                },
            )
        }

        val overview = observe().first()

        assertEquals(
            listOf(CATEGORY_ID to 2),
            overview.products.map { it.category.id to it.products },
        )
    }

    @Test
    fun `expenses count voided ones, and decide what can be done`() = runTest {
        val rent = seeded.getValue("RENT")
        val other = seeded.getValue("OTHER")
        val utilities = seeded.getValue("UTILITIES")
        spend(rent)
        val voided = spend(utilities)
        VoidExpenseUseCase(fixture.transactions, expenses, fixture.clock)(
            voided,
            VoidReason.ENTRY_ERROR,
            null,
        )
        spend(other)

        val rows = observe().first().expenses.associateBy { it.category.id }

        assertEquals(1, rows.getValue(utilities).expenses)
        // Con gastos, aunque anulados: se archiva y no se elimina.
        assertTrue(rows.getValue(utilities).canArchive)
        assertFalse(rows.getValue(utilities).canDelete)
        // Sin gastos: se elimina y no se archiva.
        val transport = rows.getValue(seeded.getValue("TRANSPORT"))
        assertEquals(0, transport.expenses)
        assertTrue(transport.canDelete)
        assertFalse(transport.canArchive)
        // La de otros gastos: ninguna de las dos.
        assertFalse(rows.getValue(other).canDelete)
        assertFalse(rows.getValue(other).canArchive)
    }

    @Test
    fun `archived expense categories go apart, and cannot be archived again`() = runTest {
        val rent = seeded.getValue("RENT")
        spend(rent)
        ArchiveExpenseCategoryUseCase(fixture.transactions, expenseCategories, fixture.clock)(rent)

        val overview = observe().first()

        assertEquals(8, overview.expenses.size)
        val archived = overview.archivedExpenses.single()
        assertEquals(rent, archived.category.id)
        assertFalse(archived.canArchive)
        assertFalse(archived.canDelete)
    }
}
