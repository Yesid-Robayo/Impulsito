package io.github.yesidrobayo.shopledger.ui.category

import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CategoriesOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseCategoryLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * La pantalla de categorías sin base: leer y reintentar, crear y renombrar de los dos tipos con sus
 * errores de campo, y eliminar, archivar y reactivar con sus fallas. Nombres inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val empty = CategoriesOverview(emptyList(), emptyList(), emptyList())
    private val calls = mutableListOf<String>()

    private var productSave: SaveCategoryResult = SaveCategoryResult.Saved(1)
    private var expenseSave: SaveExpenseCategoryResult = SaveExpenseCategoryResult.Saved(2)
    private var lifecycle: ExpenseCategoryLifecycleResult = ExpenseCategoryLifecycleResult.Done
    private var productDelete: DeleteCategoryResult = DeleteCategoryResult.Deleted(3)
    private var fail = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun overview(): Flow<CategoriesOverview> =
        if (fail) flow { error("lectura inventada") } else flowOf(empty)

    private fun TestScope.viewModel(): CategoriesViewModel {
        val model = CategoriesViewModel(
            overview = ::overview,
            operations = CategoryOperations(
                createProduct = {
                    calls += "create $it"
                    productSave
                },
                renameProduct = { id, name ->
                    calls += "rename $id $name"
                    productSave
                },
                deleteProduct = {
                    calls += "delete $it"
                    productDelete
                },
                saveExpense = { id, draft: ExpenseCategoryDraft ->
                    calls += "expense $id ${draft.name} ${draft.isFixed}"
                    expenseSave
                },
                archiveExpense = {
                    calls += "archive $it"
                    lifecycle
                },
                reactivateExpense = {
                    calls += "reactivate $it"
                    lifecycle
                },
                deleteExpense = {
                    calls += "delete-expense $it"
                    lifecycle
                },
            ),
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `it reads, and a read error can be retried`() = runTest(dispatcher) {
        fail = true
        val model = viewModel()
        assertEquals(CategoriesContent.ReadError, model.uiState.value.content)

        fail = false
        model.onAction(CategoriesAction.Retry)
        advanceUntilIdle()
        assertEquals(CategoriesContent.Ready(empty), model.uiState.value.content)
    }

    @Test
    fun `a new category follows the tab, and saving closes the sheet`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(CategoriesAction.TabChange(CategoryTab.EXPENSES))
        model.onAction(CategoriesAction.NewCategory)
        model.onAction(CategoriesAction.NameChange("Fumigación"))
        model.onAction(CategoriesAction.FixedChange(true))
        model.onAction(CategoriesAction.Save)
        advanceUntilIdle()

        assertEquals(listOf("expense null Fumigación true"), calls)
        assertNull(model.uiState.value.sheet)
        assertEquals(CategoryMessage.SAVED, model.uiState.value.message)
        model.onAction(CategoriesAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `renaming a product category keeps its id`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(CategoriesAction.Rename(CategoryTab.PRODUCTS, 7, "Limpieza", false, false))
        model.onAction(CategoriesAction.NameChange("Aseo"))
        model.onAction(CategoriesAction.Save)
        advanceUntilIdle()

        assertEquals(listOf("rename 7 Aseo"), calls)
    }

    @Test
    fun `each save rejection is a field error, and a failure keeps what was written`() =
        runTest(dispatcher) {
            val model = viewModel()
            val product = listOf(
                SaveCategoryResult.Invalid(setOf(CategoryFormIssue.NAME_EMPTY)) to
                    CategoryNameError.EMPTY,
                SaveCategoryResult.Invalid(setOf(CategoryFormIssue.NAME_TOO_LONG)) to
                    CategoryNameError.TOO_LONG,
                SaveCategoryResult.DuplicateName("Limpieza") to CategoryNameError.DUPLICATE,
            )
            for ((result, error) in product) {
                productSave = result
                model.onAction(CategoriesAction.NewCategory)
                model.onAction(CategoriesAction.NameChange("x"))
                model.onAction(CategoriesAction.Save)
                advanceUntilIdle()
                assertEquals(error, model.uiState.value.sheet?.error)
                assertEquals("x", model.uiState.value.sheet?.name)
            }
            productSave = SaveCategoryResult.StorageFailed(StorageFailure.IO_ERROR)
            model.onAction(CategoriesAction.Save)
            advanceUntilIdle()
            assertEquals(true, model.uiState.value.sheet?.saveFailed)

            model.onAction(CategoriesAction.TabChange(CategoryTab.EXPENSES))
            val expense = listOf(
                SaveExpenseCategoryResult.Invalid(setOf(ExpenseCategoryInputError.NAME_EMPTY)) to
                    CategoryNameError.EMPTY,
                SaveExpenseCategoryResult.Invalid(setOf(ExpenseCategoryInputError.NAME_TOO_LONG)) to
                    CategoryNameError.TOO_LONG,
                SaveExpenseCategoryResult.DuplicateName to CategoryNameError.DUPLICATE,
            )
            for ((result, error) in expense) {
                expenseSave = result
                model.onAction(CategoriesAction.NewCategory)
                model.onAction(CategoriesAction.Save)
                advanceUntilIdle()
                assertEquals(error, model.uiState.value.sheet?.error)
            }
            expenseSave = SaveExpenseCategoryResult.NotFound
            model.onAction(CategoriesAction.Save)
            advanceUntilIdle()
            assertEquals(true, model.uiState.value.sheet?.saveFailed)

            // Escribir de nuevo quita el error.
            model.onAction(CategoriesAction.NameChange("y"))
            assertNull(model.uiState.value.sheet?.error)
            model.onAction(CategoriesAction.CloseSheet)
            assertNull(model.uiState.value.sheet)
        }

    @Test
    fun `deleting and archiving ask first, then run`() = runTest(dispatcher) {
        val model = viewModel()
        for (confirm in listOf(
            CategoryConfirm.DeleteProduct(1, "Limpieza", 3),
            CategoryConfirm.DeleteExpense(2, "Fumigación"),
            CategoryConfirm.ArchiveExpense(3, "Alquiler"),
        )) {
            model.onAction(CategoriesAction.Ask(confirm))
            assertEquals(confirm, model.uiState.value.confirm)
            model.onAction(CategoriesAction.Confirm)
            advanceUntilIdle()
            assertNull(model.uiState.value.confirm)
        }
        model.onAction(CategoriesAction.Ask(CategoryConfirm.DeleteExpense(9, "x")))
        model.onAction(CategoriesAction.CloseConfirm)

        assertEquals(listOf("delete 1", "delete-expense 2", "archive 3"), calls)
        assertNull(model.uiState.value.failure)
    }

    @Test
    fun `reactivating with the name taken and failures are told once`() = runTest(dispatcher) {
        val model = viewModel()

        lifecycle = ExpenseCategoryLifecycleResult.NameTaken
        model.onAction(CategoriesAction.Reactivate(4))
        advanceUntilIdle()
        assertEquals(CategoryFailure.NAME_TAKEN, model.uiState.value.failure)
        model.onAction(CategoriesAction.FailureShown)
        assertNull(model.uiState.value.failure)

        lifecycle = ExpenseCategoryLifecycleResult.StorageFailed(StorageFailure.IO_ERROR)
        model.onAction(CategoriesAction.Reactivate(4))
        advanceUntilIdle()
        assertEquals(CategoryFailure.NOT_SAVED, model.uiState.value.failure)

        model.onAction(CategoriesAction.FailureShown)
        productDelete = DeleteCategoryResult.NotFound
        model.onAction(CategoriesAction.Ask(CategoryConfirm.DeleteProduct(1, "Limpieza", 0)))
        model.onAction(CategoriesAction.Confirm)
        advanceUntilIdle()
        assertEquals(CategoryFailure.NOT_SAVED, model.uiState.value.failure)

        // Ya estaba como se pedía: sin error.
        model.onAction(CategoriesAction.FailureShown)
        lifecycle = ExpenseCategoryLifecycleResult.Unchanged
        model.onAction(CategoriesAction.Reactivate(4))
        advanceUntilIdle()
        assertNull(model.uiState.value.failure)
    }
}
